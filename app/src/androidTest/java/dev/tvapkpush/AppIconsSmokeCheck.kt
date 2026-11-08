package dev.tvapkpush

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AppIconsSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val redRequests = AtomicInteger()
        val greenRequests = AtomicInteger()
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }
        val red = png(Color.RED)
        val green = png(Color.GREEN)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val first = chain.request().url.encodedPath == "/red"
            if (first) {
                redRequests.incrementAndGet()
                requested.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Icon response was not released" }
            } else greenRequests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (first) red else green).toResponseBody()).build()
        }.build()
        val icons = AppIcons(instrumentation.targetContext, client)
        lateinit var first: ImageView
        lateinit var second: ImageView
        fun color(view: ImageView) = (view.drawable as? BitmapDrawable)?.bitmap?.getPixel(0, 0)
        fun await(check: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync { ready = check() }
                if (ready) return
                SystemClock.sleep(30)
            }
            error("Icon state timed out")
        }
        try {
            report("Checking merged image requests and stale view bindings.")
            instrumentation.runOnMainSync {
                first = ImageView(instrumentation.targetContext)
                second = ImageView(instrumentation.targetContext)
                icons.load("https://icons.example/red", first)
                icons.load("https://icons.example/red", second)
            }
            check(requested.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { icons.load("https://icons.example/green", first) }
            await { color(first) == Color.GREEN }
            release.countDown()
            await { color(second) == Color.RED }
            instrumentation.runOnMainSync { check(color(first) == Color.GREEN) { "Stale response replaced the current icon" } }
            check(redRequests.get() == 1) { "Duplicate icon request was not merged" }
            report("Checking cached images and clearing obsolete targets.")
            instrumentation.runOnMainSync {
                icons.unbind(first)
                icons.load("https://icons.example/red", first)
                check(color(first) == Color.RED) { "Cached image was not immediately available" }
                icons.load("", first)
                check(color(first) == null) { "Empty binding retained a previous application icon" }
            }
            check(redRequests.get() == 1 && greenRequests.get() == 1)
            return "Icon request merging, stale response isolation, memory reuse and placeholder reset passed.\n"
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { icons.unbind(first); icons.unbind(second) }
        }
    }
}
