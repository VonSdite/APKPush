package dev.tvapkpush.data

import android.content.Context
import dev.tvapkpush.core.InputRules
import dev.tvapkpush.core.PendingInstall
import dev.tvapkpush.core.PushFailure
import dev.tvapkpush.core.TvDevice
import org.json.JSONArray
import org.json.JSONObject

interface TaskJournal {
    fun pending(deviceId: String): List<PendingInstall>
    fun put(task: PendingInstall)
    fun remove(id: String)
}

class DeviceStore(context: Context) : TaskJournal {
    private val preferences = context.getSharedPreferences("devices_and_tasks", Context.MODE_PRIVATE)

    @Synchronized fun devices(): List<TvDevice> = readArray("devices").map {
        TvDevice(it.getString("id"), it.getString("name"), InputRules.host(it.getString("host")), it.getInt("port"))
    }

    @Synchronized fun save(device: TvDevice) {
        val devices = devices().toMutableList()
        val index = devices.indexOfFirst { it.id == device.id }
        if (index >= 0) devices[index] = device else devices.add(device)
        persist("devices", JSONArray().apply {
            devices.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port)) }
        })
        select(device.id)
    }

    @Synchronized fun delete(id: String) {
        if (pending(id).isNotEmpty()) throw PushFailure("PENDING_TASK", "此设备有待核对或清理的任务，请先连接处理")
        persist("devices", JSONArray().apply {
            readArray("devices").filter { it.getString("id") != id }.forEach { put(it) }
        })
        if (selectedId() == id) select(devices().firstOrNull()?.id.orEmpty())
    }

    @Synchronized fun selectedId(): String = preferences.getString("selected", "").orEmpty()

    @Synchronized fun select(id: String) {
        if (!preferences.edit().putString("selected", id).commit()) throw PushFailure("STATE_WRITE", "无法保存设备设置，请检查手机存储空间")
    }

    @Synchronized override fun pending(deviceId: String): List<PendingInstall> = readArray("tasks")
        .filter { it.getString("deviceId") == deviceId }
        .map {
            PendingInstall(
                it.getString("id"), it.getString("deviceId"), it.getString("serial"),
                InputRules.packageName(it.getString("packageName")), it.getLong("versionCode"),
                it.getString("label"), it.getInt("userId"), it.getBoolean("submitted"),
                it.optInt("fileCount", 1), if (it.has("sessionId")) it.getInt("sessionId") else null,
            ).also { task -> task.remotePath }
        }

    @Synchronized override fun put(task: PendingInstall) {
        val items = readArray("tasks").filter { it.getString("id") != task.id }
        persist("tasks", JSONArray().apply {
            items.forEach { put(it) }
            put(JSONObject().put("id", task.id).put("deviceId", task.deviceId).put("serial", task.serial)
                .put("packageName", task.packageName).put("versionCode", task.versionCode)
                .put("label", task.label).put("userId", task.userId).put("submitted", task.submitted)
                .put("fileCount", task.fileCount).put("sessionId", task.sessionId))
        })
    }

    @Synchronized override fun remove(id: String) {
        persist("tasks", JSONArray().apply {
            readArray("tasks").filter { it.getString("id") != id }.forEach { put(it) }
        })
    }

    private fun readArray(key: String): List<JSONObject> = try {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }
    } catch (error: Exception) {
        throw PushFailure("STATE_READ", "无法读取保存的设备或任务记录，请保留应用数据并检查手机存储", error)
    }

    private fun persist(key: String, value: JSONArray) {
        if (!preferences.edit().putString(key, value.toString()).commit()) {
            throw PushFailure("STATE_WRITE", "无法保存任务记录，请检查手机存储空间")
        }
    }
}
