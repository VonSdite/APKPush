package dev.tvapkpush

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.tvapkpush.data.IdentityStore
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature

class DeviceSmokeRunner : Instrumentation() {
    private var checkDiscovery = false
    private var checkPlay = false
    private var checkNavigation = false
    private var checkRecovery = false
    private var checkBackground = false
    private var checkBackgroundPlay = false
    private var checkPublicDownloads = false
    private var checkIcons = false
    private var checkBrowsing = false
    private var checkQueue = false
    private var checkLocalInstall = false
    private var checkSwipe = false
    private var checkUpdates = false
    private var checkBatchDelete = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkDiscovery = arguments?.getString("discovery") == "true"
        checkPlay = arguments?.getString("play") == "true"
        checkNavigation = arguments?.getString("navigation") == "true"
        checkRecovery = arguments?.getString("recovery") == "true"
        checkBackground = arguments?.getString("background") == "true"
        checkBackgroundPlay = arguments?.getString("backgroundPlay") == "true"
        checkPublicDownloads = arguments?.getString("publicDownloads") == "true"
        checkIcons = arguments?.getString("icons") == "true"
        checkBrowsing = arguments?.getString("browsing") == "true"
        checkQueue = arguments?.getString("queue") == "true"
        checkLocalInstall = arguments?.getString("localInstall") == "true"
        checkSwipe = arguments?.getString("swipe") == "true"
        checkUpdates = arguments?.getString("updates") == "true"
        checkBatchDelete = arguments?.getString("batchDelete") == "true"
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            report("Checking Android Keystore identity and ADB signature.")
            val first = IdentityStore()
            val second = IdentityStore()
            check(first.publicKey().contentEquals(second.publicKey())) { "ADB identity changed between instances" }
            val source = "Device ADB authentication verification".toByteArray()
            val signature = first.sign(MessageDigest.getInstance("SHA-1").digest(source))
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val certificate = store.getCertificate("tvapkpush.adb.identity.v1")
            check(Signature.getInstance("SHA1withRSA").run {
                initVerify(certificate)
                update(source)
                verify(signature)
            }) { "Android Keystore ADB signature verification failed" }
            results.putString("stream", "Android Keystore identity reuse and ADB SHA-1 signature verification passed.\n")
            report("Android Keystore checks passed.")
            if (checkDiscovery) {
                report("Checking service discovery.")
                DiscoverySmokeCheck.run(this)
                results.putString("stream", results.getString("stream") + "NSD discovery, custom ADB port, TLS classification, and discovery cancellation passed.\n")
            }
            if (checkPlay) results.putString("stream", results.getString("stream") + PlaySmokeCheck.run(this, ::report))
            if (checkNavigation) results.putString("stream", results.getString("stream") + NavigationSmokeCheck.run(this, ::report))
            if (checkRecovery) results.putString("stream", results.getString("stream") + DownloadRecoverySmokeCheck.run(this, ::report))
            if (checkBackground) results.putString("stream", results.getString("stream") + BackgroundDownloadSmokeCheck.run(this, ::report))
            if (checkBackgroundPlay) results.putString("stream", results.getString("stream") + BackgroundDownloadSmokeCheck.runLive(this, ::report))
            if (checkPublicDownloads) results.putString("stream", results.getString("stream") + PublicDownloadsSmokeCheck.run(this, ::report))
            if (checkIcons) results.putString("stream", results.getString("stream") + AppIconsSmokeCheck.run(this, ::report))
            if (checkBrowsing) results.putString("stream", results.getString("stream") + ConcurrentBrowsingSmokeCheck.run(this, ::report))
            if (checkQueue) results.putString("stream", results.getString("stream") + DownloadQueueSmokeCheck.run(this, ::report))
            if (checkLocalInstall) results.putString("stream", results.getString("stream") + LocalInstallSmokeCheck.run(this, ::report))
            if (checkSwipe) results.putString("stream", results.getString("stream") + DownloadSwipeSmokeCheck.run(this, ::report))
            if (checkUpdates) results.putString("stream", results.getString("stream") + PlayUpdatesSmokeCheck.run(this, ::report))
            if (checkBatchDelete) results.putString("stream", results.getString("stream") + BatchDeleteSmokeCheck.run(this, ::report))
            finish(Activity.RESULT_OK, results)
        } catch (error: Exception) {
            results.putString("stream", "Device smoke test failed:\n${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun report(message: String) {
        sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }
}
