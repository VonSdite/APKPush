package dev.tvapkpush.install

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.tvapkpush.AppPalette
import dev.tvapkpush.PushApplication
import dev.tvapkpush.R
import dev.tvapkpush.UiIcon
import dev.tvapkpush.core.JobControl
import java.util.concurrent.Executors

class LocalInstallActivity : Activity() {
    private val installer get() = packageManager.packageInstaller
    private val control = JobControl()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "local-install") }
    private var libraryId = ""
    private var appName = ""
    private var sessionId = -1
    private var submitted = false
    private var preparing = false
    private var resumed = false
    private var failure: String? = null
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var action: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libraryId = savedInstanceState?.getString("library") ?: intent.getStringExtra("library").orEmpty()
        appName = savedInstanceState?.getString("label") ?: intent.getStringExtra("label").orEmpty()
        sessionId = savedInstanceState?.getInt("session", -1) ?: -1
        submitted = savedInstanceState?.getBoolean("submitted") ?: false
        failure = savedInstanceState?.getString("failure")
        buildScreen()
        if (savedInstanceState == null && intent.action != RESULT_ACTION) {
            installer.mySessions.filter { !it.isCommitted }.forEach { abandon(it.sessionId) }
        }
        if (savedInstanceState == null && intent.action == RESULT_ACTION) handleResult(intent)
        else if (failure != null) showFailure(failure!!)
        else if (libraryId.isEmpty()) showFailure("请从已下载列表选择安装包。")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (failure != null || submitted || preparing || isFinishing) return
        if (sessionId >= 0) commit()
        else if (packageManager.canRequestPackageInstalls()) prepare()
        else {
            progress.visibility = View.GONE
            message.setText(R.string.local_install_permission)
            action.text = "打开设置"
            action.visibility = View.VISIBLE
            action.setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                catch (error: Exception) { showFailure("无法打开安装权限设置，请在系统设置中允许 APK Push 安装应用。") }
            }
        }
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == RESULT_ACTION) handleResult(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("library", libraryId)
        outState.putString("label", appName)
        outState.putInt("session", if (submitted) sessionId else -1)
        outState.putBoolean("submitted", submitted)
        outState.putString("failure", failure)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        control.requestCancellation()
        worker.shutdown()
        if (!submitted && sessionId >= 0) abandon(sessionId)
        super.onDestroy()
    }

    private fun prepare() {
        preparing = true
        progress.visibility = View.VISIBLE
        action.visibility = View.GONE
        message.text = "正在准备安装包"
        val context = applicationContext
        val library = (application as PushApplication).downloads
        worker.execute {
            val result = runCatching { library.withInstallFiles(libraryId, control) { LocalInstaller.stage(context, it, control) } }
            runOnUiThread {
                if (isFinishing || isDestroyed || control.cancelled) {
                    result.getOrNull()?.let(::abandon)
                    return@runOnUiThread
                }
                preparing = false
                result.fold(onSuccess = { id -> sessionId = id; if (resumed) commit() }, onFailure = { error ->
                    if (error !is Exception) throw error
                    Log.w("APKPush", "Local install preparation failed: type=${error.javaClass.simpleName}")
                    showFailure(LocalInstaller.preparationMessage(error))
                })
            }
        }
    }

    private fun commit() {
        try {
            val callback = Intent(this, LocalInstallActivity::class.java).apply {
                this.action = RESULT_ACTION
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("library", libraryId)
                putExtra("label", appName)
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getActivity(this, sessionId, callback, flags)
            installer.openSession(sessionId).use { it.commit(result.intentSender) }
            submitted = true
            message.text = "等待系统安装"
        } catch (error: Exception) {
            abandon(sessionId)
            sessionId = -1
            Log.w("APKPush", "Local install submission failed: type=${error.javaClass.simpleName}")
            showFailure("无法提交安装，请检查安装权限后重试。")
        }
    }

    private fun handleResult(intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || (sessionId >= 0 && id != sessionId)) return
        sessionId = id
        submitted = true
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                try {
                    checkNotNull(confirmation)
                    message.text = "请在系统界面确认安装"
                    startActivity(confirmation)
                } catch (error: Exception) {
                    abandon(id)
                    showFailure("无法打开系统安装界面，请返回后重试。")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "${appName.ifBlank { "应用" }} 已安装", Toast.LENGTH_SHORT).show()
                finish()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Toast.makeText(this, "已取消安装", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> {
                Log.w("APKPush", "Local installation failed: status=$status")
                showFailure(LocalInstaller.failureMessage(status))
            }
        }
    }

    private fun abandon(id: Int) { runCatching { installer.abandonSession(id) } }

    private fun showFailure(value: String) {
        failure = value
        progress.visibility = View.GONE
        message.text = value
        action.text = "返回下载库"
        action.visibility = View.VISIBLE
        action.setOnClickListener { finish() }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("安装到本机", 18f, AppPalette.INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageButton(this).apply {
            setImageDrawable(UiIcon(UiIcon.Kind.CLOSE, AppPalette.SECONDARY))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = touchBackground()
            contentDescription = "关闭本机安装"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        body.addView(label(appName, 22f, AppPalette.INK, true).apply { gravity = Gravity.CENTER })
        progress = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(AppPalette.BLUE) }
        body.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(24) })
        message = label(if (submitted) "等待系统安装" else "正在准备安装包", 15f, AppPalette.SECONDARY).apply { gravity = Gravity.CENTER }
        body.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        action = Button(this).apply {
            textSize = 16f; isAllCaps = false; setTextColor(AppPalette.BLUE)
            background = touchBackground(); stateListAnimator = null
            minHeight = dp(48); minimumHeight = dp(48)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            visibility = View.GONE
        }
        body.addView(action, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun label(text: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun touchBackground() = RippleDrawable(ColorStateList.valueOf(0x120071E3), null,
        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() })
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val RESULT_ACTION = "dev.tvapkpush.LOCAL_INSTALL_RESULT" }
}
