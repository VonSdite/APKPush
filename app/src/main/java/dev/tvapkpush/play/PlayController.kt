package dev.tvapkpush.play

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aurora.gplayapi.device.DeviceProfile
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.BundleCursor
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.core.JobControl
import java.util.UUID
import java.util.concurrent.Executors

data class PlayState(
    val profiles: List<DeviceProfile> = emptyList(),
    val profileId: String = "native",
    val query: String = "",
    val apps: List<App> = emptyList(),
    val next: BundleCursor? = null,
    val downloads: List<ApkFile> = emptyList(),
    val tasks: List<DownloadTask> = emptyList(),
    val busy: Boolean = false,
    val searched: Boolean = false,
    val message: String = "",
    val error: Boolean = false,
    val searching: Boolean = false,
    val searchError: String = "",
    val libraryBusy: Boolean = false,
    val libraryMessage: String = "",
    val latest: Map<PlayAppKey, App> = emptyMap(),
    val checkingUpdates: Boolean = false,
    val updateError: String = "",
) {
    val profile: DeviceProfile? get() = profiles.firstOrNull { it.id == profileId }
    val backgroundDownload: Boolean get() = tasks.any { it.phase.needsService }
    val downloading: Boolean get() = tasks.any { it.phase.inFlight }
}

class PlayController(
    private val context: Context,
    val library: DownloadLibrary,
    val repository: PlayRepository = PlayRepository(context, library),
    private val isOnline: () -> Boolean = {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    },
) {
    private val preferences = context.getSharedPreferences("play_settings", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "play-worker") }
    private val downloadWorker = Executors.newFixedThreadPool(MAX_DOWNLOADS) { Thread(it, "play-download") }
    private val searchWorker = Executors.newSingleThreadExecutor { Thread(it, "play-search") }
    private val libraryWorker = Executors.newSingleThreadExecutor { Thread(it, "play-library") }
    private val updateWorker = Executors.newSingleThreadExecutor { Thread(it, "play-updates") }
    private val cancellationWorker = Executors.newCachedThreadPool { Thread(it, "play-cancel") }
    private var job: JobControl? = null
    private var searchJob: JobControl? = null
    private var updateJob: JobControl? = null
    private var foreground = false
    private val updateChecks = mutableMapOf<PlayAppKey, Long>()
    private val taskStore = DownloadTaskStore(context)
    private val active = mutableMapOf<String, ActiveDownload>()
    private val discarding = mutableSetOf<String>()
    private val serviceActions = mutableListOf<Pair<String, DownloadAction>>()
    private var initializing = true
    private var serviceListener: ((PlayState) -> Unit)? = null
    private data class ActiveDownload(val control: JobControl, var interruption: String = "", var savedAt: Long = 0)
    var listener: ((PlayState) -> Unit)? = null
    var state = PlayState(profileId = preferences.getString("profile", "native").orEmpty())
        private set

    init {
        start("正在读取本地应用", after = {
            initializing = false
            state.tasks.filter { it.phase == DownloadPhase.CANCELLED }.forEach { discardTask(it.id) }
            serviceActions.toList().forEach { (id, action) -> performAction(id, action) }
            serviceActions.clear()
            pumpDownloads()
            if (foreground) checkUpdates()
        }) { control ->
            val tasks = taskStore.readAll().map { it.restored() }
            library.discardPartial(tasks.map { it.id }.toSet())
            val profiles = repository.profiles
            val migration = runCatching { library.migrate(control) }
            val downloads = library.list()
            val restored = tasks.filter { task -> task.phase == DownloadPhase.CANCELLED || downloads.none { it.libraryId == task.id } }
                .map { task -> library.partialProgress(task.id)?.let { task.copy(bytes = it.first, total = it.second) } ?: task }
            control.check()
            main.post {
                setTasks(restored)
                state = state.copy(profiles = profiles, downloads = downloads,
                    profileId = state.profileId.takeIf { id -> profiles.any { it.id == id } } ?: "native")
            }
            migration.getOrThrow()
            ""
        }
    }

    fun selectProfile(id: String) {
        if (state.busy || state.profiles.none { it.id == id }) return
        preferences.edit().putString("profile", id).apply()
        state = state.copy(profileId = id)
        emit()
    }

    fun search(value: String, more: Boolean = false) {
        val query = value.trim().take(200)
        if (initializing || query.isEmpty() || (more && state.searching)) return
        val next = if (more && query == state.query) state.next ?: return else null
        cancelSearch()
        val control = JobControl()
        searchJob = control
        if (next == null) state = state.copy(query = query, apps = emptyList(), next = null, searched = false)
        state = state.copy(searching = true, searchError = "")
        emit()
        searchWorker.execute {
            val result = runCatching { repository.search(query, next, control).also { control.check() } }
            main.post {
                if (searchJob !== control) return@post
                searchJob = null
                val bundle = result.getOrNull()
                state = if (bundle != null) {
                    val apps = state.copy(apps = bundle.clusters.flatMap { it.apps }).orderedApps()
                    state.copy(query = query, apps = ((if (next != null) state.apps else emptyList()) + apps)
                        .distinctBy { it.packageName }, next = bundle.next, searched = true, searching = false, searchError = "")
                } else {
                    val error = result.exceptionOrNull()!!
                    if (error !is Exception) throw error
                    Log.w("APKPush", "Play search failed: type=${error.javaClass.simpleName}")
                    state.copy(searching = false, searchError = PlayRepository.errorMessage(error))
                }
                emit()
            }
        }
    }

    fun cancelSearch() {
        val control = searchJob ?: return
        searchJob = null
        interrupt(control)
        state = state.copy(searching = false)
        emit()
    }

    fun clearSearch() {
        cancelSearch()
        state = state.copy(query = "", apps = emptyList(), next = null, searched = false, searching = false, searchError = "")
        emit()
    }

    fun download(app: App) {
        enqueueDownload(app, state.profile ?: return)
    }

    fun updateDownload(id: String) {
        val apk = state.downloads.firstOrNull { it.libraryId == id } ?: return
        val update = state.updateFor(apk) ?: return
        enqueueDownload(update.app, update.profile)
    }

    private fun enqueueDownload(app: App, profile: DeviceProfile) {
        if (state.busy) return
        if (state.tasks.any { it.packageName == app.packageName && it.profile.fingerprint == profile.fingerprint }) return
        val task = DownloadTask(UUID.randomUUID().toString(), app.packageName, app.displayName.ifBlank { app.packageName }, profile,
            message = "等待下载", iconUrl = app.iconArtwork.url)
        if (setTasks(state.tasks + task)) launchDownloadService()
        else { replaceTask(task.copy(phase = DownloadPhase.FAILED, message = state.message)); emit() }
    }

    fun delete(id: String) = delete(listOf(id))

    fun delete(ids: Collection<String>) {
        if (initializing || state.libraryBusy) return
        val requested = ids.toSet()
        val targets = state.downloads.mapNotNull { it.libraryId }.filter { id ->
            id in requested && state.tasks.none { it.id == id }
        }.distinct()
        if (targets.isEmpty()) return
        state = state.copy(libraryBusy = true, libraryMessage = "")
        emit()
        libraryWorker.execute {
            val deleted = mutableSetOf<String>()
            var failure: Exception? = null
            targets.forEach { id ->
                try {
                    library.delete(id)
                    deleted.add(id)
                } catch (error: Exception) {
                    failure = error
                    Log.w("APKPush", "Download deletion failed: id=$id, type=${error.javaClass.simpleName}")
                }
            }
            main.post {
                state = state.copy(libraryBusy = false,
                    downloads = state.downloads.filterNot { it.libraryId in deleted },
                    libraryMessage = when {
                        failure == null -> if (targets.size == 1) "已删除" else "已删除 ${deleted.size} 项"
                        targets.size == 1 -> PlayRepository.errorMessage(checkNotNull(failure))
                        else -> "已删除 ${deleted.size} 项，${targets.size - deleted.size} 项未能删除，请重试"
                    })
                emit()
            }
        }
    }

    fun export(id: String, uri: Uri) = start("正在导出") { control ->
        library.export(id, uri, control)
        "已导出到所选位置"
    }

    fun cancel(id: String) {
        val task = findTask(id) ?: return
        if (task.phase == DownloadPhase.CANCELLED) return
        replaceTask(task.copy(phase = DownloadPhase.CANCELLED, message = "正在取消…"))
        if (id in active) interruptDownload(id, "user") else if (!initializing) discardTask(id)
        pumpDownloads()
    }

    fun cancelOperation() { job?.let { interrupt(it) } }
    fun enterForeground() { foreground = true; emit(); checkUpdates() }

    fun leaveForeground() {
        foreground = false
        updateJob?.let { updateJob = null; interrupt(it) }
        state = state.copy(checkingUpdates = false)
        cancelSearch()
        if (!initializing) cancelOperation()
    }

    fun checkUpdates(force: Boolean = false): Boolean {
        if (initializing || updateJob != null) return false
        if (!isOnline()) {
            if (force) { state = state.copy(updateError = "无网络，请连接后检查更新"); emit() }
            return false
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val targets = state.downloads.mapNotNull { apk ->
            state.profileFor(apk)?.let { PlayAppKey(apk.packageName, it.fingerprint) to it }
        }.distinctBy { it.first }.filter { (key, _) -> force || updateChecks[key]?.let { now - it >= 15 * 60_000 } != false }
        if (targets.isEmpty()) return false
        val control = JobControl()
        updateJob = control
        state = state.copy(checkingUpdates = true, updateError = "")
        emit()
        updateWorker.execute {
            targets.forEach { (key, profile) ->
                if (control.cancelled) return@execute
                val result = runCatching {
                    repository.details(key.packageName, profile, control).also {
                        control.check()
                        check(it.packageName == key.packageName) { "Update metadata package mismatch" }
                        PlayRepository.validateApp(it)
                    }
                }
                if (control.cancelled) return@execute
                main.post {
                    if (updateJob !== control) return@post
                    updateChecks[key] = android.os.SystemClock.elapsedRealtime()
                    val app = result.getOrNull()
                    if (app != null) state = state.copy(latest = state.latest + (key to app))
                    else {
                        val error = result.exceptionOrNull()!!
                        if (error !is Exception) throw error
                        Log.w("APKPush", "Play update check failed: package=${key.packageName}, type=${error.javaClass.simpleName}")
                        state = state.copy(updateError = PlayRepository.errorMessage(error))
                    }
                    emit()
                }
            }
            main.post {
                if (updateJob !== control) return@post
                updateJob = null
                state = state.copy(checkingUpdates = false)
                emit()
            }
        }
        return true
    }

    fun retryDownload(id: String) {
        if (initializing || !queueRetry(id)) return
        launchDownloadService()
    }

    private fun queueRetry(id: String): Boolean {
        val task = findTask(id) ?: return false
        if (!task.phase.canRetry || id in active) return false
        return setTasks(state.tasks.filterNot { it.id == id } + task.copy(phase = DownloadPhase.QUEUED, message = "等待下载"))
    }

    fun pauseDownload(id: String) {
        val task = findTask(id) ?: return
        if (!task.phase.canPause) return
        if (id in active) {
            replaceTask(task.copy(phase = DownloadPhase.PAUSING, message = "正在暂停…"))
            interruptDownload(id, "pause")
        } else replaceTask(task.copy(phase = DownloadPhase.PAUSED, message = "已暂停"))
        pumpDownloads()
    }

    fun serviceStarted(observer: (PlayState) -> Unit, taskId: String? = null, action: DownloadAction? = null) {
        serviceListener = observer
        if (taskId != null && action != null) {
            if (initializing) serviceActions.add(taskId to action) else performAction(taskId, action)
        }
        pumpDownloads()
    }

    private fun performAction(id: String, action: DownloadAction) {
        when (action) {
            DownloadAction.PAUSE -> pauseDownload(id)
            DownloadAction.RESUME -> queueRetry(id)
            DownloadAction.CANCEL -> cancel(id)
        }
    }

    fun serviceStopped(observer: ((PlayState) -> Unit)? = serviceListener) {
        if (serviceListener !== observer) return
        serviceListener = null
        if (initializing) return
        active.keys.toList().forEach { interruptDownload(it, "service") }
        setTasks(state.tasks.map { if (it.phase == DownloadPhase.QUEUED) it.copy(phase = DownloadPhase.FAILED, message = "下载已中断") else it })
        emit()
    }

    fun networkChanged() {
        if (serviceListener == null || isOnline()) return
        active.keys.toList().forEach { interruptDownload(it, "network") }
        pumpDownloads()
    }

    private fun launchDownloadService() {
        emit()
        try {
            context.startForegroundService(Intent(context, PlayDownloadService::class.java))
        } catch (error: Exception) {
            Log.w("APKPush", "Download service start failed: type=${error.javaClass.simpleName}")
            setTasks(state.tasks.map { if (it.phase == DownloadPhase.QUEUED) it.copy(phase = DownloadPhase.FAILED,
                message = "后台下载未能启动，请点击重试") else it })
            emit()
        }
    }

    private fun pumpDownloads() {
        if (!initializing && serviceListener != null) {
            if (!isOnline()) {
                if (state.tasks.any { it.phase == DownloadPhase.QUEUED }) setTasks(state.tasks.map { if (it.phase == DownloadPhase.QUEUED)
                    it.copy(phase = DownloadPhase.FAILED, message = "无网络，请点击重试") else it })
            } else {
                state.tasks.filter { it.phase == DownloadPhase.QUEUED }.take((MAX_DOWNLOADS - active.size).coerceAtLeast(0)).forEach(::runDownload)
            }
        }
        emit()
    }

    private fun runDownload(task: DownloadTask) {
        if (!replaceTask(task.copy(phase = DownloadPhase.RUNNING, message = "正在获取安装包"))) {
            replaceTask(task.copy(phase = DownloadPhase.FAILED, message = state.message))
            return
        }
        val download = ActiveDownload(JobControl())
        active[task.id] = download
        downloadWorker.execute {
            var lastProgress = 0L
            val outcome = runCatching {
                repository.download(task, download.control) { message, bytes, total ->
                    val now = System.nanoTime()
                    if (now - lastProgress >= 120_000_000 || bytes == total) {
                        lastProgress = now
                        main.post { progress(task.id, download, message, bytes, total) }
                    }
                }
            }
            val retained = if (outcome.isFailure) library.partialProgress(task.id) else null
            main.post {
                if (active[task.id] !== download) return@post
                active.remove(task.id)
                val current = findTask(task.id) ?: return@post
                outcome.getOrNull()?.let { saved ->
                    state = state.copy(downloads = listOf(saved) + state.downloads.filterNot { it.libraryId == saved.libraryId })
                }
                if (current.phase == DownloadPhase.CANCELLED) {
                    discardTask(task.id)
                } else if (outcome.isSuccess) {
                    setTasks(state.tasks.filterNot { it.id == task.id })
                    state = state.copy(message = "${task.label} 已保存，可选中推送")
                } else {
                    val error = outcome.exceptionOrNull()!!
                    if (error !is Exception) throw error
                    Log.w("APKPush", "Background download interrupted: task=${task.id}, type=${error.javaClass.simpleName}, reason=${download.interruption}")
                    val paused = download.interruption == "pause" || current.phase == DownloadPhase.PAUSING
                    val message = when {
                        paused -> "已暂停"
                        download.interruption == "service" -> "下载已中断"
                        download.interruption == "storage" -> "无法保存下载进度，请检查存储空间后重试"
                        download.interruption == "network" || !isOnline() -> "网络已断开，请点击重试"
                        else -> PlayRepository.errorMessage(error)
                    }
                    replaceTask(current.copy(phase = if (paused) DownloadPhase.PAUSED else DownloadPhase.FAILED,
                        message = message, bytes = retained?.first ?: current.bytes, total = retained?.second ?: current.total))
                }
                pumpDownloads()
            }
        }
    }

    private fun progress(id: String, download: ActiveDownload, message: String, bytes: Long, total: Long) {
        if (active[id] !== download || download.control.cancelled) return
        val task = findTask(id) ?: return
        val updated = if (total > 0) task.copy(message = message, bytes = bytes, total = total) else task.copy(message = message)
        val now = android.os.SystemClock.elapsedRealtime()
        state = state.copy(tasks = state.tasks.map { if (it.id == id) updated else it })
        if (now - download.savedAt >= 2000) {
            download.savedAt = now
            if (!setTasks(state.tasks)) interruptDownload(id, "storage")
        }
        emit()
    }

    private fun findTask(id: String) = state.tasks.firstOrNull { it.id == id }
    private fun replaceTask(task: DownloadTask) = setTasks(state.tasks.map { if (it.id == task.id) task else it })

    private fun setTasks(tasks: List<DownloadTask>): Boolean {
        state = state.copy(tasks = tasks)
        return try { taskStore.saveAll(tasks); true } catch (error: Exception) {
            Log.w("APKPush", "Download queue persistence failed: type=${error.javaClass.simpleName}")
            state = state.copy(error = true, message = "无法保存下载进度，请检查存储空间后重试")
            false
        }
    }

    private fun interrupt(control: JobControl) {
        control.requestCancellation()
        cancellationWorker.execute { control.cancel() }
    }

    private fun interruptDownload(id: String, reason: String) {
        val download = active[id] ?: return
        if (download.interruption == "user" || (download.interruption == "pause" && reason != "user")) return
        download.interruption = reason
        interrupt(download.control)
    }

    private fun discardTask(id: String) {
        if (!discarding.add(id)) return
        libraryWorker.execute {
            val result = runCatching { library.discard(id) }
            main.post {
                discarding.remove(id)
                setTasks(state.tasks.filterNot { it.id == id })
                context.getSystemService(NotificationManager::class.java).cancel(id, PlayDownloadService.RESULT)
                if (result.isFailure) state = state.copy(error = true, message = "下载已取消，未完成文件将在下次启动时清理")
                pumpDownloads()
            }
        }
    }

    private fun start(message: String, after: () -> Unit = {}, operation: (JobControl) -> String) {
        if (state.busy) return
        val control = JobControl()
        job = control
        state = state.copy(busy = true, message = message, error = false)
        emit()
        worker.execute {
            var failed = false
            val result = try { operation(control) } catch (error: Exception) {
                failed = !control.cancelled
                if (failed) Log.w("APKPush", "Play operation failed: type=${error.javaClass.simpleName}")
                if (control.cancelled) "已取消" else PlayRepository.errorMessage(error)
            }
            main.post {
                job = null
                state = state.copy(busy = false, message = result, error = failed)
                after()
                emit()
            }
        }
    }

    private fun emit() { listener?.invoke(state); serviceListener?.invoke(state) }

    companion object { const val MAX_DOWNLOADS = 3 }
}
