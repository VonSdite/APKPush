package dev.tvapkpush.play

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.AtomicFile
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.PlayException
import com.aurora.gplayapi.android.NativeDeviceProperties
import com.aurora.gplayapi.device.DeviceProfile
import com.aurora.gplayapi.device.DeviceRegistry
import com.aurora.gplayapi.engine.OkHttpEngine
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.AppFile
import com.aurora.gplayapi.model.BundleCursor
import com.aurora.gplayapi.model.Restriction
import com.aurora.gplayapi.model.StreamBundle
import com.aurora.gplayapi.model.StreamCluster
import com.aurora.gplayapi.session.DefaultPlaySessions
import com.aurora.gplayapi.web.WebPlayClient
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.core.InputRules
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

open class PlayRepository(private val context: Context, val library: DownloadLibrary) {
    val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    private val engine = OkHttpEngine(http)
    private val web = WebPlayClient(engine, locale = Locale.US)
    private val clients = mutableMapOf<String, PlayClient>()
    val profiles: List<DeviceProfile> by lazy {
        listOf(DeviceRegistry.parse("native", NativeDeviceProperties.read(context))) + DeviceRegistry.available()
    }

    open fun search(query: String, next: BundleCursor?, control: JobControl): StreamBundle = request(control) {
        if (next == null && runCatching { InputRules.packageName(query) }.isSuccess) {
            val app = web.apps.details(query)
            StreamBundle(query, clusters = listOf(StreamCluster(query, apps = listOf(app))))
        } else if (next == null) web.search.results(query) else web.search.next(query, next)
    }

    private fun client(profile: DeviceProfile): PlayClient = synchronized(clients) {
        clients.getOrPut(profile.fingerprint) {
            PlayClient(engine, DefaultPlaySessions(engine, profile, Locale.US, additionalLocales = listOf(Locale.SIMPLIFIED_CHINESE)))
        }
    }

    open fun details(packageName: String, profile: DeviceProfile, control: JobControl): App {
        InputRules.packageName(packageName)
        return request(control) { client(profile).apps.details(packageName) }
    }

    fun download(packageName: String, profile: DeviceProfile, control: JobControl,
                 progress: (String, Long, Long) -> Unit): ApkFile {
        val task = DownloadTask(UUID.randomUUID().toString(), packageName, packageName, profile)
        try { return download(task, control, progress) } finally { library.discard(task.id) }
    }

    open fun download(task: DownloadTask, control: JobControl, progress: (String, Long, Long) -> Unit): ApkFile {
        if (library.contains(task.id)) return library.open(task.id, control)
        val packageName = task.packageName
        val profile = task.profile
        InputRules.packageName(packageName)
        val client = client(profile)
        progress("正在获取安装包", 0, 0)
        val app = request(control) { client.apps.details(packageName) }
        validateApp(app)
        val files = request(control) { client.delivery.resolve(app.packageName, app.versionCode, app.offerType) }
        if (files.count { it.type == AppFile.Type.BASE } != 1 || files.size !in 1..128) {
            throw PushFailure("PLAY_FILES", "Google Play 未返回完整的安装文件")
        }
        if (files.any { it.type != AppFile.Type.BASE && it.type != AppFile.Type.SPLIT }) {
            throw PushFailure("PLAY_EXPANSION", "此应用需要 OBB 资源包，当前暂不支持")
        }
        val ordered = files.sortedWith(compareBy<AppFile> { if (it.type == AppFile.Type.BASE) 0 else 1 }.thenBy { it.name })
        val total = ordered.sumOf { it.size }
        if (ordered.any { it.size <= 0 || it.size > VerifiedDownload.MAX_SIZE } || total > VerifiedDownload.MAX_SIZE) {
            throw PushFailure("DOWNLOAD_SIZE", "安装包大小无效或超过 2 GiB 上限")
        }
        val id = task.id
        var folder = library.staging(id)
        val plan = JSONObject().put("version", app.versionCode).put("profile", profile.fingerprint)
            .put("files", JSONArray().apply {
                ordered.forEach { file -> put(JSONObject().put("name", file.name).put("size", file.size)
                    .put("sha256", file.sha256.lowercase(Locale.ROOT)).put("sha1", file.sha1.lowercase(Locale.ROOT))) }
            })
        val planFile = AtomicFile(File(folder, "plan.json"))
        val previous = runCatching { planFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText().trim() } }.getOrNull()
        if (previous != plan.toString()) {
            library.discard(id)
            folder = library.staging(id)
        }
        writeDownloadJson(planFile, plan)
        control.check()
        val retained = ordered.indices.sumOf { index -> File(folder, DownloadLibrary.fileName(index)).length().coerceAtMost(ordered[index].size) }
        val storage = context.getSystemService(StorageManager::class.java)
        val volume = storage.getUuidForPath(folder)
        val required = total - retained + VerifiedDownload.SPACE_RESERVE
        if (storage.getAllocatableBytes(volume) < required) throw PushFailure("LOCAL_STORAGE", "本机空间不足，请清理后重试")
        storage.allocateBytes(volume, required)
        var completed = 0L
        val downloader = VerifiedDownload(http)
        val hashes = ordered.mapIndexed { index, file ->
            control.check()
            val name = DownloadLibrary.fileName(index)
            downloader.fetch(file.url, File(folder, name), file.size, file.sha256, file.sha1,
                VerifiedDownload.MAX_SIZE - completed, control, resume = true) { bytes ->
                if (StatFs(folder.path).availableBytes < VerifiedDownload.SPACE_RESERVE) throw PushFailure("LOCAL_STORAGE", "本机空间不足，请清理后重试")
                progress("正在下载 ${app.displayName}", completed + bytes, total)
            }.also { completed += File(folder, name).length() }
        }
        progress("正在校验安装包", total, total)
        return library.save(id, folder, app.displayName.ifBlank { packageName }, packageName, app.versionCode,
            profileLabel(profile), hashes, control)
    }

    private fun <T> request(control: JobControl, action: suspend () -> T): T {
        val job = Job()
        val cancellation = Closeable { job.cancel() }
        control.attach(cancellation)
        try {
            return runBlocking(job) { action() }
        } finally {
            control.detach(cancellation)
            job.cancel()
        }
    }

    companion object {
        internal fun validateApp(app: App) {
            if (!app.isFree) throw PushFailure("PLAY_PAID", "当前支持免费应用，付费应用请使用 Google Play")
            if (app.restriction != Restriction.AVAILABLE) throw PushFailure("PLAY_RESTRICTED", "此应用对当前下载机型或地区不可用")
            if (app.versionCode <= 0) throw PushFailure("PLAY_VERSION", "Google Play 未提供可下载版本")
            if (app.assetPacks.isNotEmpty() || app.dependencies.assetModuleNames.isNotEmpty()) {
                throw PushFailure("PLAY_ASSETS", "此应用需要 Play 游戏资源包，当前暂不支持")
            }
            // 运行依赖由目标设备提供，配套共享库需要独立安装包。
            val libraries = (app.dependencies.dependentLibraries.map { it.packageName } +
                app.dependencies.libraries.map { it.packageName }).distinct()
            if (libraries.isNotEmpty()) {
                val names = libraries.joinToString().take(240)
                throw PushFailure("PLAY_DEPENDENCY", "此应用需要配套共享库（$names），当前暂不支持自动下载和安装")
            }
        }

        fun profileLabel(profile: DeviceProfile): String =
            "${if (profile.id == "native") "本机 · " else ""}${profile.name} · API ${profile.properties["Build.VERSION.SDK_INT"]} · ${profile.properties["Platforms"]?.substringBefore(',')}"

        fun errorMessage(error: Exception): String = when (error) {
            is PushFailure -> error.userMessage
            is PlayException.RateLimited -> "Google Play 请求过于频繁，请稍后重试"
            is PlayException.AppNotSupported -> "此应用对当前下载机型或地区不可用"
            is PlayException.AppNotPurchased -> "此应用需要购买或不对当前地区开放"
            is PlayException.RequiresAccount, is PlayException.Unauthorized -> "Google Play 要求账号授权，当前匿名下载不可用"
            is PlayException.NotFound, is PlayException.AppRemoved -> "Google Play 中未找到此应用"
            is PlayException.NoFilesAvailable -> "Google Play 暂未提供可下载的安装包"
            is PlayException -> "Google Play 接口暂不可用，请稍后重试"
            is IOException -> "无法访问 Google Play，请检查本机能否连接 Google 服务后重试"
            else -> "操作未完成，请稍后重试"
        }
    }
}
