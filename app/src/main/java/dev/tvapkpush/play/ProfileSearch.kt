package dev.tvapkpush.play

import com.aurora.gplayapi.device.DeviceProfile

fun filterProfiles(profiles: List<DeviceProfile>, query: String): List<DeviceProfile> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return profiles
    return profiles.filter { profile ->
        val properties = profile.properties
        val searchable = listOfNotNull(
            profile.name, profile.id,
            if (profile.id == "native") "本机 当前手机" else null,
            properties["Build.BRAND"], properties["Build.MANUFACTURER"], properties["Build.MODEL"],
            "API ${properties["Build.VERSION.SDK_INT"]} API${properties["Build.VERSION.SDK_INT"]}",
            "Android ${properties["Build.VERSION.RELEASE"]} Android${properties["Build.VERSION.RELEASE"]}",
            properties["Platforms"],
            if (properties["Features"].orEmpty().contains("android.software.leanback")) "电视 TV" else null,
        ).joinToString(" ")
        terms.all { searchable.contains(it, ignoreCase = true) }
    }
}
