package dev.tvapkpush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class AppIcons(context: Context, http: OkHttpClient? = null) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(6) { Thread(it, "app-icons") }
    private val client = http ?: OkHttpClient.Builder()
        .cache(Cache(File(context.cacheDir, "play-icons-http"), 12L * 1024 * 1024))
        .connectTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val images = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private class RequestGroup {
        val targets = mutableSetOf<ImageView>()
        @Volatile var call: Call? = null
        var future: Future<*>? = null
    }
    private inner class Binding(val key: String) : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {}
        override fun onViewDetachedFromWindow(view: View) { unbind(view) }
    }
    private val pending = mutableMapOf<String, RequestGroup>()

    fun load(url: String, view: ImageView) {
        val key = IconUrl.thumbnail(url)
        bind(key, view) { group ->
            val call = client.newCall(Request.Builder().url(checkNotNull(key)).build())
            group.call = call
            if (Thread.currentThread().isInterrupted) { call.cancel(); return@bind null }
            call.execute().use { response ->
                if (!response.isSuccessful) null else response.body.byteStream().use(::decode)
            }
        }
    }

    fun load(file: File?, view: ImageView) {
        val key = file?.takeIf { it.isFile }?.let { "file:${it.path}:${it.lastModified()}:${it.length()}" }
        bind(key, view) { file?.inputStream()?.use(::decode) }
    }

    private fun bind(key: String?, view: ImageView, fetch: (RequestGroup) -> Bitmap?) {
        if (key != null && (view.tag as? Binding)?.key == key) return
        unbind(view)
        view.setImageDrawable(UiIcon(UiIcon.Kind.FILE, AppPalette.BLUE))
        if (key == null) return
        val binding = Binding(key)
        view.tag = binding
        view.addOnAttachStateChangeListener(binding)
        images.get(key)?.let { view.setImageBitmap(it); return }
        pending[key]?.let { it.targets.add(view); return }
        val group = RequestGroup().apply { targets.add(view) }
        pending[key] = group
        group.future = worker.submit {
            val bitmap = runCatching { fetch(group) }.getOrNull()
            main.post {
                if (pending[key] !== group) return@post
                pending.remove(key)
                if (bitmap != null) images.put(key, bitmap)
                group.targets.forEach { target ->
                    if ((target.tag as? Binding)?.key == key && bitmap != null) target.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun unbind(view: View) {
        if (view is ViewGroup) for (index in 0 until view.childCount) unbind(view.getChildAt(index))
        val binding = view.tag as? Binding ?: return
        view.removeOnAttachStateChangeListener(binding)
        view.tag = null
        val group = pending[binding.key] ?: return
        group.targets.remove(view)
        if (group.targets.isEmpty()) {
            main.post {
                if (pending[binding.key] === group && group.targets.isEmpty()) {
                    pending.remove(binding.key)
                    group.call?.cancel()
                    group.future?.cancel(true)
                }
            }
        }
    }

    private fun decode(input: InputStream): Bitmap? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 1024 * 1024) return null
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize.coerceAtLeast(1) * 2) >= 192) {
                inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
