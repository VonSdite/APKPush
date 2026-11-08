package dev.tvapkpush

import android.app.Application

class PushApplication : Application() {
    val icons by lazy { AppIcons(this) }
    val downloads by lazy { dev.tvapkpush.play.DownloadLibrary(this).apply { discardCache() } }
    val controller by lazy { PushController(this, downloads) }
    val play by lazy { dev.tvapkpush.play.PlayController(this, downloads) }
}
