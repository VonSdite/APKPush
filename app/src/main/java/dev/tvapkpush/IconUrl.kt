package dev.tvapkpush

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object IconUrl {
    fun thumbnail(value: String): String? {
        val url = value.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        if (!url.host.endsWith(".googleusercontent.com") || url.query != null || url.fragment != null) return url.toString()
        val path = url.encodedPath.replace(Regex("=[swh]\\d+[^/]*$"), "")
        return url.newBuilder().encodedPath("$path=s192").build().toString()
    }
}
