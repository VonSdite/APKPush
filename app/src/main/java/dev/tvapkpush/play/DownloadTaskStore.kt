package dev.tvapkpush.play

import android.content.Context
import android.util.AtomicFile
import com.aurora.gplayapi.device.DeviceProfile
import com.aurora.gplayapi.device.DeviceRegistry
import dev.tvapkpush.core.InputRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.Properties
import java.util.UUID

enum class DownloadPhase {
    QUEUED, RUNNING, WAITING, PAUSING, PAUSED, FAILED, CANCELLED;

    val canPause get() = this == QUEUED || this == RUNNING
    val canRetry get() = this == PAUSED || this == FAILED || this == WAITING
    val inFlight get() = this == RUNNING || this == PAUSING
    val needsService get() = this == QUEUED || inFlight || this == CANCELLED
}

enum class DownloadAction { PAUSE, RESUME, CANCEL }

data class DownloadTask(
    val id: String,
    val packageName: String,
    val label: String,
    val profile: DeviceProfile,
    val phase: DownloadPhase = DownloadPhase.QUEUED,
    val message: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val iconUrl: String = "",
) {
    fun restored(): DownloadTask = when (phase) {
        DownloadPhase.QUEUED, DownloadPhase.RUNNING, DownloadPhase.WAITING ->
            copy(phase = DownloadPhase.FAILED, message = "下载已中断")
        DownloadPhase.PAUSING -> copy(phase = DownloadPhase.PAUSED, message = "已暂停")
        else -> this
    }
}

class DownloadTaskStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "play-download-task.json"))

    fun readAll(): List<DownloadTask> {
        val input = try { file.openRead() } catch (_: FileNotFoundException) { return emptyList() }
        val data = input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val tasks = if (data.has("tasks")) data.getJSONArray("tasks") else JSONArray().put(data)
        return (0 until tasks.length()).map { decode(tasks.getJSONObject(it)) }.also { result ->
            require(result.map { it.id }.distinct().size == result.size)
        }
    }

    private fun decode(data: JSONObject): DownloadTask {
        val properties = data.getJSONObject("properties")
        return DownloadTask(
            id = data.getString("id").also { require(UUID.fromString(it).toString() == it) },
            packageName = InputRules.packageName(data.getString("packageName")),
            label = data.getString("label"),
            profile = DeviceRegistry.parse(data.getString("profileId"), Properties().apply {
                properties.keys().forEach { key -> setProperty(key, properties.getString(key)) }
            }),
            phase = DownloadPhase.valueOf(data.getString("phase")),
            message = data.optString("message"),
            bytes = data.optLong("bytes"),
            total = data.optLong("total"),
            iconUrl = data.optString("iconUrl"),
        )
    }

    fun saveAll(tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) { clear(); return }
        require(tasks.map { it.id }.distinct().size == tasks.size)
        writeDownloadJson(file, JSONObject().put("tasks", JSONArray().apply { tasks.forEach { put(encode(it)) } }))
    }

    private fun encode(task: DownloadTask): JSONObject =
        JSONObject().put("id", task.id).put("packageName", task.packageName).put("label", task.label)
            .put("profileId", task.profile.id).put("properties", JSONObject(task.profile.properties))
            .put("phase", task.phase.name)
            .put("message", task.message).put("bytes", task.bytes).put("total", task.total)
            .put("iconUrl", task.iconUrl)

    fun clear() = file.delete()
}

internal fun writeDownloadJson(file: AtomicFile, data: JSONObject) {
    val output = file.startWrite()
    try {
        output.write((data.toString() + "\n").toByteArray(Charsets.UTF_8))
        file.finishWrite(output)
    } catch (error: Exception) {
        file.failWrite(output)
        throw error
    }
}
