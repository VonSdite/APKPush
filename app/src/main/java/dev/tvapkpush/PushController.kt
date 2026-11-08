package dev.tvapkpush

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.tvapkpush.adb.AdbSession
import dev.tvapkpush.core.*
import dev.tvapkpush.data.ApkImporter
import dev.tvapkpush.data.FileStamp
import dev.tvapkpush.data.DeviceStore
import dev.tvapkpush.data.IdentityStore
import dev.tvapkpush.play.DownloadLibrary
import java.util.UUID
import java.util.concurrent.Executors

data class ScreenState(
    val devices: List<TvDevice> = emptyList(),
    val selectedId: String = "",
    val apk: ApkFile? = null,
    val progress: Progress = Progress(),
    val busy: Boolean = false,
    val tvInfo: TvInfo? = null,
    val installed: InstalledTarget? = null,
    val decision: PendingInstall? = null,
    val hasPending: Boolean = false,
) {
    val device: TvDevice? get() = devices.firstOrNull { it.id == selectedId }
}

class PushController(context: Context, private val downloads: DownloadLibrary) {
    private val store = DeviceStore(context)
    private val importer = ApkImporter(context)
    private val installer = Installer(store)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "apk-push-worker") }
    private val cancellationWorker = Executors.newSingleThreadExecutor { Thread(it, "apk-push-cancel") }
    private val main = Handler(Looper.getMainLooper())
    private var job: JobControl? = null
    private var lastProgressTime = 0L
    private var lastStage = Stage.IDLE
    private var preparedFiles = emptyList<FileStamp?>()
    var listener: ((ScreenState) -> Unit)? = null
    var state = ScreenState()
        private set

    init {
        importer.discardStale()
        runCatching { refreshDevices() }.onFailure { failure(it as Exception) }
    }

    fun saveDevice(name: String, host: String, port: String, existingId: String? = null) = edit {
        val address = InputRules.host(host)
        val number = InputRules.port(port)
        if (existingId != null && store.pending(existingId).isNotEmpty()) throw PushFailure("PENDING_TASK", "请先连接此设备，处理待核对或清理的任务后再编辑")
        val duplicate = store.devices().firstOrNull { it.host == address && it.port == number && it.id != existingId }
        val device = TvDevice(existingId ?: duplicate?.id ?: UUID.randomUUID().toString(), name.trim().take(60).ifBlank { "Android 设备" }, address, number)
        store.save(device)
        state = state.copy(tvInfo = null, installed = null, decision = null, progress = Progress())
        refreshDevices()
    }

    fun select(id: String) = edit {
        store.select(id)
        state = state.copy(tvInfo = null, installed = null, decision = null, progress = Progress())
        refreshDevices()
    }

    fun deleteDevice(id: String) = edit {
        store.delete(id)
        state = state.copy(tvInfo = null, installed = null, decision = null, progress = Progress())
        refreshDevices()
    }

    fun import(uri: Uri) {
        if (state.busy) return
        val old = state.apk
        start(Progress(Stage.PREPARING, "正在读取 APK")) { control ->
            val apk = importer.import(uri, control, old) { bytes, total -> updateProgress(Progress(Stage.PREPARING, "正在读取 APK", bytes, total)) }
            if (control.cancelled) {
                if (apk !== old) discardImport(apk)
                control.check()
            }
            if (apk !== old) discardImport(old)
            selected(apk)
            Outcome(Progress(Stage.IDLE, "已读取 ${apk.label}，请选择目标设备并安装"))
        }
    }

    fun useDownload(id: String) {
        if (state.busy) return
        val old = state.apk
        if (old?.libraryId == id) {
            val unchanged = if (old.source != null) runCatching { old.source.checkUnchanged() }.isSuccess else
                preparedFiles.isNotEmpty() && preparedFiles.none { it == null } && old.files.map(FileStamp::read) == preparedFiles
            if (unchanged) return
        }
        start(Progress(Stage.PREPARING, "正在准备安装包")) { control ->
            val apk = downloads.openForPush(id, control)
            if (control.cancelled) { discardImport(apk); control.check() }
            discardImport(old, id)
            selected(apk)
            Outcome(Progress(Stage.IDLE, "已选择 ${apk.label}"))
        }
    }

    fun clearDownload(id: String) {
        if (state.apk?.libraryId == id) clearApk()
    }

    fun clearApk() {
        if (state.busy) return
        discardImport(state.apk)
        preparedFiles = emptyList()
        state = state.copy(apk = null, installed = null, decision = null, progress = Progress())
        emit()
    }

    private fun selected(apk: ApkFile) {
        val stamps = apk.files.map(FileStamp::read)
        main.post { preparedFiles = stamps; state = state.copy(apk = apk) }
    }

    private fun discardImport(apk: ApkFile?, keepLibraryId: String? = null) {
        if (apk?.source != null) apk.source.close()
        else if (apk?.libraryId == null) apk?.file?.delete()
        if (apk?.libraryId != null && apk.libraryId != keepLibraryId) downloads.release(apk.libraryId)
    }

    fun connect() = withDevice { device, _, session, info ->
        installer.recover(device, info, TvRemote(session)) ?: Outcome(Progress(Stage.IDLE,
            "${device.name} 可连接 · ${info.model} · API ${info.sdk}\n调试授权有效"))
    }

    fun install() {
        val apk = state.apk ?: return
        withDevice { device, control, session, info -> installer.install(device, apk, info, session, control, ::updateProgress) }
    }

    fun acknowledge() {
        val decision = state.decision ?: return
        withDevice { device, _, session, info -> installer.acknowledge(decision.id, device, info, TvRemote(session)) }
    }

    fun launch() {
        val target = state.installed ?: return
        if (target.device.id != state.selectedId) return
        withDevice { _, _, session, info ->
            val remote = TvRemote(session)
            if (remote.installedVersion(target.packageName, target.userId) != target.versionCode) {
                throw PushFailure("LAUNCH_VERSION", "设备上的应用版本发生变化，请重新核对")
            }
            remote.launch(target.packageName, target.userId, info.isTelevision)
            Outcome(Progress(Stage.SUCCESS, "已请求设备打开应用"), target)
        }
    }

    fun cancel() {
        val active = job ?: return
        active.requestCancellation()
        cancellationWorker.execute { active.cancel() }
        state = state.copy(progress = state.progress.copy(message = "正在停止等待；设备上已经开始的安装可能继续执行"))
        emit()
    }

    fun report(message: String) {
        if (!state.busy) {
            state = state.copy(progress = Progress(Stage.FAILED, message))
            emit()
        }
    }

    private fun withDevice(operation: (TvDevice, JobControl, AdbSession, TvInfo) -> Outcome) {
        val device = state.device ?: return
        start(Progress(Stage.CONNECTING, "正在连接 ${device.name} · ${device.address}")) { control ->
            control.check()
            val session = AdbSession(IdentityStore())
            control.attach(session)
            session.use {
                try {
                    session.connect(device.host, device.port) { updateProgress(Progress(Stage.AUTHORIZING, "请在设备上允许 APKPush 的调试授权，可选择始终允许")) }
                    control.check()
                    updateProgress(Progress(Stage.CHECKING, "正在读取设备系统与存储信息"))
                    val info = TvRemote(session).info()
                    main.post { state = state.copy(tvInfo = info) }
                    control.check()
                    operation(device, control, session, info)
                } finally {
                    control.detach(session)
                }
            }
        }
    }

    private fun start(initial: Progress, action: (JobControl) -> Outcome) {
        if (state.busy) return
        val control = JobControl()
        job = control
        state = state.copy(busy = true, progress = initial, installed = null, decision = null)
        emit()
        worker.execute {
            val outcome = try {
                action(control)
            } catch (error: Exception) {
                if (!control.cancelled) Log.w("APKPush", "Task failed: stage=${state.progress.stage}, type=${error.javaClass.simpleName}")
                Outcome(Progress(if (control.cancelled) Stage.CANCELLED else Stage.FAILED, Installer.errorMessage(error, control.cancelled)))
            }
            main.post {
                job = null
                state = state.copy(busy = false, progress = outcome.progress, installed = outcome.installed, decision = outcome.decision)
                runCatching { refreshDevices() }.onFailure { failure(it as Exception) }
                emit()
            }
        }
    }

    private fun updateProgress(progress: Progress) {
        val now = System.nanoTime()
        if (progress.stage == lastStage && now - lastProgressTime < 100_000_000 && progress.bytes != progress.total) return
        lastStage = progress.stage
        lastProgressTime = now
        main.post {
            state = state.copy(progress = progress)
            emit()
        }
    }

    private fun edit(operation: () -> Unit) {
        if (state.busy) return
        try { operation() } catch (error: Exception) { failure(error) }
        emit()
    }

    private fun refreshDevices() {
        val devices = store.devices()
        val selected = store.selectedId().takeIf { id -> devices.any { it.id == id } } ?: devices.firstOrNull()?.id.orEmpty()
        state = state.copy(devices = devices, selectedId = selected, hasPending = store.pending(selected).isNotEmpty())
    }

    private fun failure(error: Exception) {
        state = state.copy(progress = Progress(Stage.FAILED, Installer.errorMessage(error)))
    }

    private fun emit() { listener?.invoke(state) }
}
