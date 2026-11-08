package dev.tvapkpush.play

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AtomicFile
import android.util.Log
import java.io.File

internal object ApkThumbnail {
    fun write(context: Context, files: List<File>, destination: File) {
        if (destination.isFile) return
        runCatching {
            @Suppress("DEPRECATION")
            val app = context.packageManager.getPackageArchiveInfo(files.first().path, 0)?.applicationInfo
                ?: return
            app.sourceDir = files.first().path
            app.publicSourceDir = app.sourceDir
            app.splitSourceDirs = files.drop(1).map { it.path }.toTypedArray()
            app.splitPublicSourceDirs = app.splitSourceDirs
            val drawable = app.loadIcon(context.packageManager)
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                val file = AtomicFile(destination)
                val output = file.startWrite()
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Icon encoding failed" }
                    file.finishWrite(output)
                } catch (error: Exception) { file.failWrite(output); throw error }
            } finally { bitmap.recycle() }
        }.onFailure { Log.w("APKPush", "Download icon unavailable: type=${it.javaClass.simpleName}") }
    }
}
