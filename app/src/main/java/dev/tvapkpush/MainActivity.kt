package dev.tvapkpush

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.tvapkpush.core.InputRules
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.core.Stage
import dev.tvapkpush.core.TvDevice
import dev.tvapkpush.discovery.DeviceDiscovery
import dev.tvapkpush.discovery.DiscoveredDevice
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val controller get() = (application as PushApplication).controller
    private val ink = AppPalette.INK
    private val secondary = AppPalette.SECONDARY
    private val blue = AppPalette.BLUE
    private val pageColor = AppPalette.BACKGROUND
    private val separator = AppPalette.LINE
    private val green = Color.rgb(36, 138, 61)
    private val warning = Color.rgb(173, 85, 0)

    private lateinit var deviceRow: LinearLayout
    private lateinit var deviceName: TextView
    private lateinit var deviceAddress: TextView
    private lateinit var fileRow: LinearLayout
    private lateinit var fileIcon: ImageView
    private val iconWorker = Executors.newSingleThreadExecutor { Thread(it, "apk-icon") }
    private var iconKey: String? = null
    private lateinit var fileTitle: TextView
    private lateinit var fileSubtitle: TextView
    private lateinit var fileActions: LinearLayout
    private lateinit var replaceApk: Button
    private lateinit var clearApk: Button
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusDetails: Button
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var primary: Button
    private var moreMenu: PopupWindow? = null
    private var devicePicker: Dialog? = null
    private lateinit var pages: FrameLayout
    private lateinit var pushPage: LinearLayout
    private lateinit var pushScroll: ScrollView
    private lateinit var pushTab: LinearLayout
    private lateinit var appsTab: LinearLayout
    private var playPage: PlayPage? = null
    private var playSavedState: Bundle? = null
    private var appsSelected = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playSavedState = savedInstanceState?.getBundle("play_page")
        buildScreen()
        showPage(savedInstanceState?.getBoolean("apps_selected") == true)
        pushScroll.post { pushScroll.scrollTo(0, savedInstanceState?.getInt("push_scroll") ?: 0) }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        started = true
        controller.listener = ::render
        render(controller.state)
        playPage?.start()
        (application as PushApplication).play.enterForeground()
    }

    override fun onStop() {
        moreMenu?.dismiss()
        devicePicker?.dismiss()
        started = false
        controller.listener = null
        if (!isChangingConfigurations && controller.state.busy) controller.cancel()
        playPage?.stop(isChangingConfigurations)
        if (!isChangingConfigurations) (application as PushApplication).play.leaveForeground()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        iconWorker.shutdownNow()
        playPage?.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("apps_selected", appsSelected)
        outState.putInt("push_scroll", pushScroll.scrollY)
        outState.putBundle("play_page", playPage?.saveState() ?: playSavedState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("兼容 Android 10 及以上系统的返回键")
    override fun onBackPressed() {
        if (appsSelected && playPage?.cancelSelection() == true) return
        if (appsSelected) showPage(false) else super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    @Deprecated("兼容所有受支持系统版本的文件选择回调")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_APK && resultCode == RESULT_OK) data?.data?.let { controller.import(it) }
    }

    private fun handleIncoming(intent: Intent) {
        if (intent.getBooleanExtra("show_downloads", false)) {
            showPage(true)
            playPage?.showLibrary()
            intent.removeExtra("show_downloads")
        }
        @Suppress("DEPRECATION")
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: intent.clipData?.getItemAt(0)?.uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            showPage(false)
            if (controller.state.busy) Toast.makeText(this, "请等待当前任务结束", Toast.LENGTH_SHORT).show()
            else controller.import(uri)
        }
    }

    private fun buildScreen() {
        val compact = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wide = compact && resources.configuration.fontScale <= 1.3f
        val root = column().apply { setBackgroundColor(pageColor) }
        pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        pushPage = column()
        pages.addView(pushPage, FrameLayout.LayoutParams(-1, -1))
        val contentWidth = dp(minOf(resources.configuration.screenWidthDp, if (wide) 760 else 560))
        val page = column().apply { setPadding(dp(24), dp(12), dp(24), dp(8)) }
        pushScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(page, FrameLayout.LayoutParams(contentWidth, -2, Gravity.CENTER_HORIZONTAL))
        }
        pushPage.addView(pushScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = row()
        header.addView(label("推送", if (compact) 26 else 32, ink, true), weight())
        header.addView(iconButton(UiIcon.Kind.MORE, "更多选项") { showMenu(it) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        page.addView(header, margin(bottom = if (compact) 4 else 8))

        val selection = if (wide) row() else column()
        page.addView(selection, LinearLayout.LayoutParams(-1, -2, 1f))
        fileRow = column().apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(if (compact) 180 else 300)
            setPadding(dp(16), dp(if (compact) 16 else 32), dp(16), dp(if (compact) 16 else 32))
            isFocusable = true
            setOnClickListener { if (controller.state.apk == null) chooseApk() else showApkDetails() }
        }
        val iconSize = if (compact) 72 else 104
        fileIcon = object : ImageView(this) {
            private val shape = Path()
            override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
                super.onSizeChanged(width, height, oldWidth, oldHeight)
                shape.reset()
                val radius = dp(if (compact) 20 else 28).toFloat()
                shape.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
            }
            override fun onDraw(canvas: Canvas) {
                val saved = canvas.save()
                canvas.clipPath(shape)
                super.onDraw(canvas)
                canvas.restoreToCount(saved)
            }
        }.apply {
            background = rounded(AppPalette.SURFACE, if (compact) 20 else 28)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fileRow.addView(fileIcon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { bottomMargin = dp(if (compact) 16 else 24) })
        fileTitle = label("选择安装包", if (compact) 21 else 24, ink, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fileSubtitle = label("从文件或下载库选择", 13, secondary).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            fontFeatureSettings = "tnum"
        }
        fileRow.addView(fileTitle, margin())
        fileRow.addView(fileSubtitle, margin(top = 10).apply { width = -2; gravity = Gravity.CENTER_HORIZONTAL })
        fileActions = row().apply { gravity = Gravity.CENTER }
        replaceApk = button("更换安装包") { chooseApk() }.apply {
            textSize = 14f
        }
        clearApk = button("取消选择") { controller.clearApk() }.apply {
            textSize = 14f
            setTextColor(secondary)
        }
        fileActions.addView(replaceApk, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        fileActions.addView(clearApk, LinearLayout.LayoutParams(0, -2, 1f))
        fileRow.addView(fileActions, margin(top = 24).apply { width = dp(280).coerceAtMost(contentWidth - dp(80)) })
        selection.addView(fileRow, if (wide) weight().apply { marginEnd = dp(32) } else LinearLayout.LayoutParams(-1, -2, 1f))

        val destination = column()
        selection.addView(destination, if (wide) weight() else margin())
        deviceRow = row().apply {
            minimumHeight = dp(92)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = ripple(AppPalette.SURFACE, 22)
            isFocusable = true
            setOnClickListener { selectDevice() }
        }
        deviceRow.addView(icon(UiIcon.Kind.DEVICES, ink).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Color.WHITE, 14)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val deviceText = column()
        deviceText.addView(label("安装到", 12, secondary), margin(bottom = 6))
        deviceName = label("选择设备", 17, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        deviceAddress = label("", 12, secondary).apply { singleLine(); fontFeatureSettings = "tnum" }
        deviceText.addView(deviceName)
        deviceText.addView(deviceAddress, margin(top = 6))
        deviceRow.addView(deviceText, weight().apply { marginEnd = dp(10) })
        deviceRow.addView(icon(UiIcon.Kind.CHEVRON, secondary), LinearLayout.LayoutParams(dp(16), dp(16)))
        destination.addView(deviceRow, margin())

        statusCard = column().apply { setPadding(dp(4), dp(16), dp(4), dp(4)) }
        val statusHeader = row()
        statusTitle = label("", 16, ink, true)
        statusHeader.addView(statusTitle, weight())
        statusDetails = button("详情") { showStatusDetails() }.apply { textSize = 13f; setTextColor(secondary) }
        statusHeader.addView(statusDetails)
        statusCard.addView(statusHeader)
        statusMessage = label("", 14, secondary).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        statusCard.addView(statusMessage, margin(top = 6))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(blue)
            progressBackgroundTintList = ColorStateList.valueOf(separator)
            indeterminateTintList = ColorStateList.valueOf(blue)
        }
        statusCard.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(12) })
        progressText = label("", 12, secondary).apply { fontFeatureSettings = "tnum" }
        statusCard.addView(progressText, margin(top = 10))
        destination.addView(statusCard, margin())

        val footer = column().apply { setPadding(dp(24), dp(16), dp(24), dp(if (compact) 16 else 24)) }
        primary = button("推送并安装", filled = true) { performPrimaryAction() }.apply {
            minHeight = dp(56)
            minimumHeight = dp(56)
            textSize = 16f
        }
        footer.addView(primary, margin())
        pushPage.addView(footer, LinearLayout.LayoutParams(contentWidth, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val navigation = column().apply { setBackgroundColor(Color.WHITE) }
        navigation.addView(divider(), LinearLayout.LayoutParams(-1, 1))
        val tabs = row()
        pushTab = tab("推送", UiIcon.Kind.PUSH) { showPage(false) }
        appsTab = tab("应用", UiIcon.Kind.APPS) { showPage(true) }
        tabs.addView(pushTab, weight())
        tabs.addView(appsTab, weight())
        navigation.addView(tabs, LinearLayout.LayoutParams(minOf(contentWidth, dp(480)), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(navigation)
        setContentView(root)
    }

    private fun tab(title: String, kind: UiIcon.Kind, action: () -> Unit) = column().apply {
        val compact = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(if (compact) 44 else 56)
        setPadding(dp(8), dp(7), dp(8), dp(6))
        background = ripple(Color.TRANSPARENT, 16)
        isFocusable = true
        contentDescription = "${title}标签"
        val colors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()), intArrayOf(blue, secondary))
        addView(icon(kind, secondary), LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(label(title, 11, secondary, true).apply {
            setTextColor(colors)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, margin(top = if (compact) 0 else 3).apply { width = -2; marginStart = if (compact) dp(8) else 0 })
        setOnClickListener { action() }
    }

    private fun showPage(apps: Boolean) {
        if (!apps) playPage?.cancelSelection()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(pages.windowToken, 0)
        currentFocus?.clearFocus()
        if (apps && playPage == null) {
            playPage = PlayPage(this, playSavedState, { showPage(false) }, ::updateKeepScreenOn).also {
                pages.addView(it.view, FrameLayout.LayoutParams(-1, -1))
                if (started) it.start()
            }
            playSavedState = null
        }
        appsSelected = apps
        pushPage.visibility = visible(!apps)
        playPage?.view?.visibility = visible(apps)
        pushTab.isSelected = !apps
        appsTab.isSelected = apps
        (pushTab.getChildAt(0) as ImageView).setImageDrawable(UiIcon(UiIcon.Kind.PUSH, if (apps) secondary else blue))
        (appsTab.getChildAt(0) as ImageView).setImageDrawable(UiIcon(UiIcon.Kind.APPS, if (apps) blue else secondary))
        updateKeepScreenOn()
    }

    private fun updateKeepScreenOn() {
        val state = controller.state
        val playBusy = playPage?.busy == true
        enable(fileRow, !state.busy && (state.apk != null || !playBusy))
        enable(replaceApk, !state.busy && !playBusy)
        enable(clearApk, !state.busy)
        val foregroundPlayTask = playPage?.foregroundBusy == true
        if (started && (state.busy || foregroundPlayTask)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun render(state: ScreenState) {
        val device = state.device
        deviceName.text = device?.name ?: "选择设备"
        deviceName.setTextColor(if (device == null) blue else ink)
        deviceAddress.text = device?.let {
            it.address + when {
                state.hasPending -> " · 待核对"
                state.tvInfo != null -> " · 可连接"
                else -> ""
            }
        }.orEmpty()
        deviceAddress.visibility = visible(device != null)
        deviceRow.contentDescription = if (device == null) "选择设备" else "选择设备，${device.name}，${device.address}"

        val apk = state.apk
        fileTitle.text = apk?.label ?: "选择安装包"
        fileSubtitle.text = apk?.let { "${it.versionName} · ${size(it.size)}" } ?: "从文件或下载库选择"
        fileRow.contentDescription = if (apk == null) "选择 APK" else "安装包详情"
        fileActions.visibility = visible(apk != null)
        showApkIcon(apk)

        val stage = state.progress.stage
        val failed = stage == Stage.FAILED || stage == Stage.UNCERTAIN
        statusCard.visibility = visible(stage != Stage.IDLE)
        statusTitle.text = when (stage) {
            Stage.IDLE -> ""
            Stage.PREPARING -> "正在读取"
            Stage.CONNECTING -> "正在连接"
            Stage.AUTHORIZING -> "在设备上授权"
            Stage.CHECKING -> "正在检查"
            Stage.UPLOADING -> "正在发送"
            Stage.INSTALLING -> "正在安装"
            Stage.VERIFYING -> "正在核对"
            Stage.SUCCESS -> if (state.progress.message.startsWith("已请求")) "已请求打开" else "安装完成"
            Stage.FAILED -> "未能完成"
            Stage.CANCELLED -> "已取消"
            Stage.UNCERTAIN -> "结果待确认"
        }
        statusTitle.setTextColor(when { stage == Stage.SUCCESS -> green; failed -> warning; else -> ink })
        statusMessage.text = when (stage) {
            Stage.AUTHORIZING -> "请在设备上选择「允许」。"
            Stage.INSTALLING, Stage.VERIFYING -> "停止等待不会中止设备上的安装。"
            Stage.SUCCESS -> ""
            Stage.FAILED -> state.progress.message.lineSequence().firstOrNull().orEmpty()
            Stage.UNCERTAIN -> "设备可能仍在安装，请重新连接核对。"
            Stage.CANCELLED -> if (state.hasPending) "下次连接时清理临时文件。" else ""
            else -> ""
        }
        statusMessage.visibility = visible(statusMessage.text.isNotBlank())
        statusDetails.visibility = visible(!state.busy && (failed || state.hasPending || stage == Stage.SUCCESS))

        val transfer = stage == Stage.UPLOADING || stage == Stage.PREPARING
        val total = state.progress.total
        progress.visibility = visible(state.busy)
        progress.isIndeterminate = !transfer || total <= 0
        if (transfer && total > 0) progress.progress = (state.progress.bytes * 1000 / total).toInt().coerceIn(0, 1000)
        progressText.text = if (transfer) buildString {
            if (total > 0) append("${state.progress.bytes * 100 / total}%   ·   ")
            append(size(state.progress.bytes))
            if (total > 0) append(" / ${size(total)}")
            if (stage == Stage.UPLOADING) append(" · ${size(state.progress.bytesPerSecond)}/s")
        } else ""
        progressText.visibility = visible(transfer && state.busy)

        enable(deviceRow, !state.busy)
        enable(fileRow, !state.busy)
        val stopping = state.busy && state.progress.message.startsWith("正在停止")
        primary.text = when {
            stopping -> "正在停止…"
            state.busy -> if (stage == Stage.INSTALLING || stage == Stage.VERIFYING) "停止等待" else "取消"
            state.decision != null -> "处理安装结果"
            state.hasPending -> "核对结果"
            state.installed != null -> "在设备上打开"
            else -> "推送并安装"
        }
        val actionable = state.busy || state.decision != null || state.hasPending || state.installed != null || (device != null && apk != null)
        primary.background = ripple(if (state.busy || !actionable) AppPalette.SURFACE else blue, 18)
        primary.setTextColor(if (state.busy) blue else if (actionable) Color.WHITE else secondary)
        primary.isEnabled = actionable && !stopping
        playPage?.refreshPushState()
        updateKeepScreenOn()
    }

    private fun showApkIcon(apk: ApkFile?) {
        val key = apk?.file?.let { "${it.path}:${it.lastModified()}:${it.length()}" } ?: ""
        if (iconKey == key) return
        iconKey = key
        fileIcon.visibility = visible(apk != null)
        fileTitle.setTextColor(if (apk == null) blue else ink)
        fileTitle.layoutParams = (fileTitle.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (apk == null) -2 else -1
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fileTitle.compoundDrawablePadding = dp(8)
        fileTitle.setCompoundDrawablesRelative(
            if (apk == null) UiIcon(UiIcon.Kind.PLUS, blue).apply { setBounds(0, 0, dp(22), dp(22)) } else null,
            null, null, null)
        fileSubtitle.compoundDrawablePadding = dp(6)
        fileSubtitle.setCompoundDrawablesRelative(null, null,
            if (apk != null) UiIcon(UiIcon.Kind.CHEVRON, secondary).apply { setBounds(0, 0, dp(12), dp(12)) } else null, null)
        val inset = dp(if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 18 else 26)
        fileIcon.setPadding(inset, inset, inset, inset)
        fileIcon.setImageDrawable(UiIcon(UiIcon.Kind.FILE, blue))
        if (apk == null) return
        iconWorker.execute {
            val drawable = runCatching {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageArchiveInfo(apk.file.path, 0)?.applicationInfo
                info?.apply {
                    sourceDir = apk.file.path
                    publicSourceDir = apk.file.path
                    splitSourceDirs = apk.splits.map { it.path }.toTypedArray()
                    splitPublicSourceDirs = splitSourceDirs
                }?.loadIcon(packageManager)
            }.getOrNull()
            runOnUiThread {
                if (!isDestroyed && iconKey == key && drawable != null) {
                    fileIcon.setPadding(0, 0, 0, 0)
                    fileIcon.setImageDrawable(drawable)
                }
            }
        }
    }

    private fun performPrimaryAction() {
        val state = controller.state
        when {
            state.busy -> controller.cancel()
            state.decision != null -> sheet("核对安装结果") { body, dialog ->
                AppSheet.message(body, "先查看目标设备的安装提示。确认安装结束后，可以清理记录并重新安装。")
                AppSheet.action(body, "重新核对", primary = true) { dialog.dismiss(); controller.connect() }
                AppSheet.action(body, "安装已结束，清理记录") { dialog.dismiss(); controller.acknowledge() }
            }
            state.hasPending -> controller.connect()
            state.installed != null -> controller.launch()
            state.device == null -> selectDevice()
            state.apk == null -> chooseApk()
            else -> confirmInstall()
        }
    }

    private fun confirmInstall() {
        val state = controller.state
        val apk = state.apk ?: return
        val device = state.device ?: return
        sheet("安装到 ${device.name}？") { body, dialog ->
            body.addView(label(apk.label, 21, ink, true), margin(bottom = 16))
            AppSheet.message(body, "${apk.versionName} · ${size(apk.size)}")
            AppSheet.message(body, "同签名更新将保留应用数据。")
            AppSheet.action(body, "安装到此设备", primary = true) { dialog.dismiss(); controller.install() }
        }
    }

    private fun chooseApk() {
        if (playPage?.busy == true) {
            Toast.makeText(this, "请先完成或取消应用页任务", Toast.LENGTH_SHORT).show()
            return
        }
        sheet("选择安装包") { body, dialog ->
            AppSheet.action(body, "从文件选择", primary = true) { dialog.dismiss(); chooseLocalApk() }
            AppSheet.action(body, "从下载库选择", primary = true) {
                dialog.dismiss()
                showPage(true)
                playPage?.showLibrary()
            }
        }
    }

    private fun chooseLocalApk() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        try { startActivityForResult(intent, PICK_APK) } catch (_: Exception) {
            controller.report("无法打开文件选择器，请从文件管理器分享 APK")
        }
    }

    private fun selectDevice() {
        devicePicker?.dismiss()
        sheet("选择设备") { body, dialog ->
            devicePicker = dialog
            if (controller.state.devices.isNotEmpty()) body.addView(section("已保存"), margin(bottom = 4))
            controller.state.devices.forEach { device ->
                val line = row()
                val selected = device.id == controller.state.selectedId
                val choice = row().apply {
                    setPadding(0, dp(13), dp(12), dp(13))
                    background = ripple(Color.TRANSPARENT, 12)
                    isFocusable = true
                    setOnClickListener { dialog.dismiss(); controller.select(device.id) }
                    val text = column()
                    text.addView(label(device.name, 16, ink, true))
                    text.addView(label(device.address, 12, secondary), margin(top = 5))
                    addView(text, weight().apply { marginEnd = dp(10) })
                    if (selected) addView(icon(UiIcon.Kind.CHECK, blue), LinearLayout.LayoutParams(dp(20), dp(20)))
                    contentDescription = "选择 ${device.name}"
                }
                line.addView(choice, weight())
                line.addView(iconButton(UiIcon.Kind.MORE, "管理 ${device.name}") {
                    dialog.dismiss()
                    manageDevice(device)
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                body.addView(line)
            }
            body.addView(section("局域网"), margin(top = 16, bottom = 8))
            val nearby = column()
            body.addView(nearby)
            val scanRow = row().apply { setPadding(dp(8), dp(10), dp(8), dp(10)) }
            val spinner = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(blue) }
            scanRow.addView(spinner, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
            val scanStatus = label("正在搜索…", 13, secondary)
            scanRow.addView(scanStatus, weight())
            body.addView(scanRow, margin(top = 8))
            val retry = AppSheet.action(body, "重新搜索") {}
            body.addView(divider(), margin(top = 8, bottom = 8))
            AppSheet.action(body, "手动添加") { dialog.dismiss(); editDevice(null) }
            var discovery: DeviceDiscovery? = null
            fun startDiscovery() {
                discovery?.close()
                nearby.removeAllViews()
                retry.visibility = View.GONE
                discovery = DeviceDiscovery(this, controller.state.devices) { state ->
                    spinner.visibility = visible(state.searching)
                    retry.visibility = visible(!state.searching)
                    scanStatus.text = when {
                        state.searching -> "正在搜索…"
                        state.message.isNotBlank() -> state.message
                        state.devices.isEmpty() -> "未发现设备，请确认已开启网络 ADB"
                        else -> "找到 ${state.devices.size} 台设备"
                    }
                    nearby.removeAllViews()
                    state.devices.filter { found -> controller.state.devices.none { it.address == found.address } }.forEach { device ->
                        nearby.addView(row().apply {
                            minimumHeight = dp(64)
                            setPadding(0, dp(12), dp(12), dp(12))
                            background = ripple(Color.TRANSPARENT, 12)
                            isFocusable = true
                            contentDescription = "选择 ${device.name}，${device.address}"
                            val text = column()
                            text.addView(label(device.name, 16, ink, true).apply { singleLine() })
                            text.addView(label(device.address + if (device.requiresPairing) " · 需配对" else "", 13, secondary), margin(top = 4))
                            addView(text, weight().apply { marginEnd = dp(10) })
                            addView(icon(UiIcon.Kind.CHEVRON, secondary), LinearLayout.LayoutParams(dp(18), dp(18)))
                            setOnClickListener { chooseDiscovered(device, dialog) }
                        }, margin())
                    }
                }.also { it.start() }
            }
            retry.setOnClickListener { startDiscovery() }
            dialog.setOnDismissListener {
                discovery?.close()
                if (devicePicker === dialog) devicePicker = null
            }
            startDiscovery()
        }
    }

    private fun chooseDiscovered(device: DiscoveredDevice, dialog: Dialog) {
        if (device.requiresPairing) {
            sheet("此设备需要配对") { body, notice ->
                AppSheet.message(body, "当前支持传统网络 ADB，暂不支持配对码和 TLS 连接。可在设备上开启传统网络调试后重试。")
                AppSheet.action(body, "知道了", primary = true) { notice.dismiss() }
            }
        } else {
            dialog.dismiss()
            controller.saveDevice(device.name, device.host, device.port.toString())
        }
    }

    private fun manageDevice(device: TvDevice) {
        sheet(device.name) { body, dialog ->
            AppSheet.details(body, "连接地址" to device.address)
            if (device.id == controller.state.selectedId) controller.state.tvInfo?.let {
                AppSheet.details(body, "设备型号" to it.model, "系统版本" to "Android API ${it.sdk}", "架构" to it.abis.joinToString())
            }
            AppSheet.action(body, "连接设备", primary = true) {
                dialog.dismiss()
                controller.select(device.id)
                controller.connect()
            }
            AppSheet.action(body, "编辑设备") { dialog.dismiss(); editDevice(device) }
            AppSheet.action(body, "删除设备", destructive = true) {
                dialog.dismiss()
                AppSheet.confirm(this, "删除设备？", "${device.name}\n设备上的调试授权仍会保留。", "删除设备", destructive = true) {
                    controller.deleteDevice(device.id)
                }
            }
        }
    }

    private fun editDevice(device: TvDevice?) {
        sheet(if (device == null) "添加设备" else "编辑设备") { body, dialog ->
            fun field(title: String, value: String, hintText: String, input: Int): EditText {
                body.addView(label(title, 13, secondary), margin(top = 12, bottom = 8))
                return EditText(this).apply {
                    setSingleLine(true)
                    inputType = input
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    textSize = 16f
                    setTextColor(ink)
                    setHintTextColor(secondary)
                    background = rounded(AppPalette.SURFACE, 10)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    setSelectAllOnFocus(true)
                    hint = hintText
                    contentDescription = title
                    setText(value)
                    body.addView(this, margin())
                }
            }
            val name = field("名称", device?.name.orEmpty(), "Android 设备", InputType.TYPE_CLASS_TEXT)
            val host = field("IP 地址", device?.host.orEmpty(), "192.168.1.100", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val port = field("端口", device?.port?.toString() ?: "5555", "5555", InputType.TYPE_CLASS_NUMBER)
            body.addView(label("设备需开启网络 ADB", 12, secondary), margin(top = 14, bottom = 14))
            val save = AppSheet.action(body, "保存设备", primary = true) {
                try {
                    InputRules.host(host.text.toString())
                    InputRules.port(port.text.toString())
                    controller.saveDevice(name.text.toString(), host.text.toString(), port.text.toString(), device?.id)
                    dialog.dismiss()
                } catch (error: dev.tvapkpush.core.PushFailure) {
                    if (error.code == "INVALID_ADDRESS") host.error = error.userMessage else port.error = error.userMessage
                }
            }
            port.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            port.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { save.performClick(); true } else false
            }
        }
    }

    private fun showApkDetails() {
        val apk = controller.state.apk ?: return
        sheet("安装包详情") { body, _ ->
            body.addView(label(apk.label, 21, ink, true), margin(bottom = 16))
            AppSheet.details(body, "版本" to "${apk.versionName} (${apk.versionCode})", "大小" to size(apk.size),
                "包名" to apk.packageName, "系统要求" to "Android API ${apk.minSdk}+", "架构" to apk.abis.joinToString().ifBlank { "通用" })
            if (apk.libraryId != null) AppSheet.details(body, "来源" to "Google Play · ${apk.files.size} 个 APK", "下载机型" to apk.downloadProfile)
        }
    }

    private fun showStatusDetails() {
        sheet("任务详情") { body, dialog ->
            val state = controller.state
            AppSheet.details(body, "设备" to (state.device?.name ?: "未选择"), "应用" to (state.apk?.label ?: "未选择"))
            body.addView(label(state.progress.message.ifBlank { "暂无任务记录" }, 14, secondary).apply {
                setTextIsSelectable(true)
                setLineSpacing(dp(4).toFloat(), 1f)
            }, margin(bottom = 20))
            AppSheet.action(body, "复制详情") { copyStatus(); dialog.dismiss() }
        }
    }

    private fun showMenu(anchor: View) {
        moreMenu?.dismiss()
        val body = column().apply { background = rounded(Color.WHITE, 16); clipToOutline = true }
        val popup = PopupWindow(body, minOf(dp(192), resources.displayMetrics.widthPixels - dp(48)), -2, true).apply {
            setBackgroundDrawable(rounded(Color.WHITE, 16))
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        fun item(title: String, action: () -> Unit) {
            if (body.childCount > 0) body.addView(divider(), LinearLayout.LayoutParams(-1, 1).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
            body.addView(row().apply {
                minimumHeight = dp(50)
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = ripple(Color.TRANSPARENT, 0)
                isFocusable = true
                contentDescription = title
                addView(label(title, 15, ink), weight())
                setOnClickListener { popup.dismiss(); action() }
            }, margin())
        }
        item("使用帮助") { showHelp() }
        if (controller.state.progress.stage != Stage.IDLE || controller.state.hasPending) item("任务详情") { showStatusDetails() }
        item("开源许可") { showLicenses() }
        if (!controller.state.busy && controller.state.installed != null && controller.state.apk != null) {
            item("再次安装") { confirmInstall() }
        }
        popup.setOnDismissListener { if (moreMenu === popup) moreMenu = null }
        moreMenu = popup
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.END)
    }

    private fun copyStatus() {
        val state = controller.state
        val text = "APK Push $appVersion\n${state.device?.name.orEmpty()} ${state.device?.address.orEmpty()}\n${state.apk?.packageName.orEmpty()}\n${state.progress.stage}\n${state.progress.message}"
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("任务详情", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private val appVersion: String get() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    private fun showHelp() {
        sheet("使用帮助 · $appVersion") { body, _ ->
            body.addView(label("连接设备", 17, ink, true), margin(bottom = 8))
            body.addView(label("在目标设备上开启网络 ADB。点击“选择设备”搜索，或手动添加 IP 和端口。首次连接需在目标设备允许调试授权。", 15, secondary), margin(bottom = 22))
            body.addView(label("选择并安装", 17, ink, true), margin(bottom = 8))
            body.addView(label("点击“选择安装包”，从文件或下载库选择，再点“推送并安装”。切换底部标签可继续任务，请保持应用在前台。", 15, secondary), margin(bottom = 22))
            body.addView(label("发现范围", 17, ink, true), margin(bottom = 8))
            body.addView(label("手机与目标设备需在可互访的局域网。自动发现使用服务广播，并检查本机所在 /24 网段的 5555 端口；小网段按实际范围检查。未广播的自定义端口可手动添加。", 15, secondary), margin(bottom = 22))
            body.addView(label("连接范围", 17, ink, true), margin(bottom = 8))
            body.addView(label("支持手机、平板、电视和盒子的传统网络 ADB。配对码设备会显示连接限制；TLS 安装暂不支持。请在可信局域网内使用。", 15, secondary))
        }
    }

    private fun showLicenses() {
        sheet("开源许可") { body, _ ->
            body.addView(label("APK Push · GPL-3.0-or-later", 17, ink, true), margin(bottom = 12))
            body.addView(label(assets.open("NOTICE.txt").bufferedReader().use { it.readText() }, 14, secondary), margin(bottom = 16))
            AppSheet.action(body, "查看 GPLv3 全文") {
                sheet("GNU GPLv3") { content, _ ->
                    content.addView(label(assets.open("GPL-3.0.txt").bufferedReader().use { it.readText() }, 13, secondary))
                }
            }
        }
    }

    private fun sheet(title: String, content: (LinearLayout, Dialog) -> Unit) = AppSheet.show(this, title, content)

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun section(text: String) = label(text, 13, secondary).apply { setPadding(dp(4), 0, 0, 0) }
    private fun divider() = View(this).apply { setBackgroundColor(separator); minimumHeight = 1 }

    private fun label(value: String, size: Int, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun TextView.singleLine() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    private fun button(title: String, filled: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(44)
        minimumHeight = dp(44)
        minWidth = dp(44)
        minimumWidth = dp(44)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(if (filled) Color.WHITE else blue)
        background = ripple(if (filled) blue else Color.TRANSPARENT, 14)
        stateListAnimator = null
        elevation = 0f
        setOnClickListener { action() }
    }

    private fun icon(kind: UiIcon.Kind, color: Int) = ImageView(this).apply {
        setImageDrawable(UiIcon(kind, color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun iconButton(kind: UiIcon.Kind, description: String, action: (View) -> Unit) = ImageButton(this).apply {
        setImageDrawable(UiIcon(kind, secondary))
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = ripple(Color.TRANSPARENT, 22)
        contentDescription = description
        setOnClickListener { action(it) }
    }

    private fun ripple(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(0x18007AFF), rounded(color, radius), rounded(Color.WHITE, radius))
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun visible(show: Boolean) = if (show) View.VISIBLE else View.GONE
    private fun enable(view: View, enabled: Boolean) { view.isEnabled = enabled }

    companion object {
        private const val PICK_APK = 10
        fun size(bytes: Long): String = when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
