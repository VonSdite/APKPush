package dev.tvapkpush

import android.app.Instrumentation
import android.content.ContextWrapper
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import dev.tvapkpush.install.LocalInstaller
import dev.tvapkpush.play.DownloadLibrary
import dev.tvapkpush.play.VerifiedDownload
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException

object LocalInstallSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "local-install-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val library = DownloadLibrary(context)
        val installer = context.packageManager.packageInstaller
        val sessionsBefore = installer.mySessions.map { it.sessionId }.toSet()
        val source = File(base.applicationInfo.sourceDir)
        val version = base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode
        val control = JobControl()
        val hash = VerifiedDownload.digest(source, control)
        val id = UUID.randomUUID().toString()
        val sessions = mutableListOf<Int>()
        try {
            val folder = library.staging(id)
            source.copyTo(File(folder, "base.apk"))
            source.copyTo(File(folder, "split-1.apk"))
            library.save(id, folder, "Local install fixture", base.packageName, version, "Local install test", listOf(hash, hash), control)
            val selected = library.open(id, control)
            report("Checking complete split staging, content integrity and isolation from the selected APK cache.")
            var temporary: File? = null
            val sessionId = library.withInstallFiles(id, control) { apk ->
                temporary = apk.file.parentFile
                check(apk.files.size == 2 && apk.file != selected.file)
                LocalInstaller.stage(context, apk, control)
            }.also { sessions.add(it) }
            check(temporary?.exists() == false)
            check(selected.files.all { VerifiedDownload.digest(it, control) == hash })
            installer.openSession(sessionId).use { session ->
                check(session.names.toSet() == setOf("base.apk", "split-1.apk"))
                session.names.forEach { name ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    session.openRead(name).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash)
                }
            }
            installer.abandonSession(sessionId)

            report("Checking cancellation, incompatible architecture and partial-session cleanup.")
            val cancelled = JobControl().apply { cancel() }
            check(runCatching { library.withInstallFiles(id, cancelled) { error("Cancelled preparation continued") } }
                .exceptionOrNull() is CancellationException)
            library.withInstallFiles(id, control) { apk ->
                check(runCatching { LocalInstaller.stage(context, apk.copy(abis = setOf("unsupported-test-abi")), control) }
                    .exceptionOrNull() is PushFailure)
                check(apk.splits.single().delete())
                check(runCatching { LocalInstaller.stage(context, apk, control) }.isFailure)
            }
            check(installer.mySessions.map { it.sessionId }.toSet() == sessionsBefore) { "Unfinished install session remains" }
            check(File(context.cacheDir, "play-apks").listFiles().orEmpty().none { it.name.startsWith(".install-") })
            check(library.contains(id) && selected.files.all { VerifiedDownload.digest(it, control) == hash })
            return "Local split-session staging, verified content, cache isolation, cancellation, compatibility and failure cleanup passed.\n"
        } finally {
            sessions.forEach { runCatching { installer.abandonSession(it) } }
            if (library.contains(id)) library.delete(id) else library.discard(id)
            root.deleteRecursively()
        }
    }
}
