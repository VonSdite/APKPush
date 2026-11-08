package dev.tvapkpush

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.aurora.gplayapi.model.App
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.play.PlayRepository
import dev.tvapkpush.play.PlayState
import dev.tvapkpush.play.PlayController
import dev.tvapkpush.play.savedApp
import dev.tvapkpush.play.downloadTask
import dev.tvapkpush.play.profileFor
import dev.tvapkpush.play.updateFor
import dev.tvapkpush.play.DownloadPhase
import dev.tvapkpush.play.DownloadTask
import dev.tvapkpush.play.filterProfiles

class PlayPage(
    private val activity: Activity,
    savedState: Bundle?,
    private val onSelect: () -> Unit,
    private val onStateChange: () -> Unit,
    private val controller: PlayController = (activity.application as PushApplication).play,
) {
    val view = FrameLayout(activity)
    private val context get() = activity
    private val resources get() = activity.resources
    private val push get() = (activity.application as PushApplication).controller
    private val icons get() = (activity.application as PushApplication).icons
    private val ink = AppPalette.INK
    private val secondary = AppPalette.SECONDARY
    private val blue = AppPalette.BLUE
    private lateinit var googleTab: Button
    private lateinit var localTab: Button
    private lateinit var searchArea: LinearLayout
    private lateinit var query: EditText
    private lateinit var clearSearch: ImageButton
    private lateinit var search: ImageButton
    private lateinit var profileSelector: LinearLayout
    private lateinit var profileName: TextView
    private lateinit var searchFeedback: LinearLayout
    private lateinit var searchMessage: TextView
    private lateinit var searchCancel: Button
    private var moreMenu: PopupWindow? = null
    private lateinit var status: LinearLayout
    private lateinit var message: TextView
    private lateinit var cancel: Button
    private lateinit var taskArea: LinearLayout
    private lateinit var queueSummary: LinearLayout
    private lateinit var queueMessage: TextView
    private val taskRows = linkedMapOf<String, DownloadRow>()
    private lateinit var rows: LinearLayout
    private lateinit var searchList: LinearLayout
    private lateinit var downloadList: LinearLayout
    private lateinit var emptyMessage: TextView
    private lateinit var emptySearch: Button
    private lateinit var moreResults: Button
    private val resultRows = linkedMapOf<String, ResultRow>()
    private val savedRows = linkedMapOf<String, SavedRow>()
    private lateinit var scroll: ScrollView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectAll: Button
    private lateinit var deleteSelected: Button
    private val selectedDownloads = savedState?.getStringArrayList("selected_downloads")?.toMutableSet() ?: mutableSetOf()
    private var selecting = savedState?.getBoolean("selecting") ?: false
    private var openDownloadRow: DownloadSwipeRow? = null
    private var local = false
    private var renderedLocal: Boolean? = null
    private var wasBusy = false
    private var wasLibraryBusy = false
    private var manualUpdateCheck = false

    val busy: Boolean get() = controller.state.let { it.libraryBusy || it.busy }
    val foregroundBusy: Boolean get() = busy || controller.state.searching
    private val downloadBusy: Boolean get() = controller.state.busy

    init {
        local = savedState?.getBoolean("local") ?: false
        buildScreen()
        query.setText(savedState?.getString("query") ?: controller.state.query)
        scroll.post { scroll.scrollTo(0, savedState?.getInt("scroll") ?: 0) }
    }

    fun start() {
        controller.listener = ::render
        render(controller.state)
    }

    fun stop(changingConfigurations: Boolean) {
        moreMenu?.dismiss()
        manualUpdateCheck = false
        openDownloadRow?.close(animated = false)
        controller.listener = null
        icons.unbind(view)
    }

    fun destroy() {
        icons.unbind(view)
    }

    fun saveState() = Bundle().apply {
        putBoolean("local", local)
        putString("query", query.text.toString())
        putInt("scroll", scroll.scrollY)
        putBoolean("selecting", selecting)
        putStringArrayList("selected_downloads", ArrayList(selectedDownloads))
    }

    fun cancelSelection(): Boolean {
        if (!selecting) return false
        selecting = false
        selectedDownloads.clear()
        render(controller.state)
        return true
    }

    private fun toggleSelection(id: String) {
        if (busy || push.state.busy) return
        if (!selectedDownloads.add(id)) selectedDownloads.remove(id)
        render(controller.state)
    }

    fun showLibrary(taskId: String? = null) {
        local = true
        render(controller.state)
        val target = taskRows[taskId]?.view
        if (target == null) scroll.smoothScrollTo(0, 0)
        else target.post {
            if (!local || target.parent !== taskArea) return@post
            val rect = Rect()
            target.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            scroll.smoothScrollTo(0, rect.top - dp(16))
        }
    }
    fun refreshPushState() { render(controller.state) }

    private fun buildScreen() {
        val root = column().apply {
            setPadding(dp(24), dp(8), dp(24), dp(24))
            isFocusableInTouchMode = true
        }
        val header = row()
        googleTab = button("Google Play") { local = false; render(controller.state) }.apply {
            contentDescription = "搜索分类"
            textSize = 19f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), dp(12), dp(10))
        }
        localTab = button("已下载") { showLibrary() }.apply {
            contentDescription = "下载分类"
            textSize = 19f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        if (resources.configuration.fontScale > 1.3f) {
            header.addView(googleTab, LinearLayout.LayoutParams(0, -2, 1.5f))
            header.addView(localTab, weight())
        } else {
            listOf(googleTab, localTab).forEach { tab ->
                tab.minWidth = kotlin.math.ceil(tab.paint.measureText(tab.text.toString())).toInt() + tab.paddingLeft + tab.paddingRight
                tab.minimumWidth = tab.minWidth
            }
            header.addView(googleTab)
            header.addView(localTab)
            header.addView(View(context), weight().apply { height = 0 })
        }
        header.addView(ImageButton(context).apply {
            setImageDrawable(UiIcon(UiIcon.Kind.MORE, secondary))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = ripple(Color.TRANSPARENT, 22)
            contentDescription = "应用选项"
            setOnClickListener { showMenu(it) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header, margin(bottom = 20))

        searchArea = column()
        val searchRow = row().apply { background = rounded(AppPalette.SURFACE, 12); setPadding(dp(14), 0, dp(4), 0) }
        query = EditText(context).apply {
            hint = "搜索应用或包名"
            contentDescription = "搜索应用"
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(secondary)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, dp(12), dp(4), dp(12))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
            }
        }
        searchRow.addView(query, weight())
        clearSearch = ImageButton(context).apply {
            setImageDrawable(UiIcon(UiIcon.Kind.CLOSE, secondary))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(Color.TRANSPARENT, 22)
            contentDescription = "清除搜索"
            visibility = View.GONE
            setOnClickListener {
                query.text.clear()
                controller.clearSearch()
                query.requestFocus()
                scroll.smoothScrollTo(0, 0)
                (context.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        searchRow.addView(clearSearch, LinearLayout.LayoutParams(dp(44), dp(44)))
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(text: Editable?) { clearSearch.visibility = visible(!text.isNullOrEmpty()) }
        })
        search = ImageButton(context).apply {
            setImageDrawable(UiIcon(UiIcon.Kind.SEARCH, secondary))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(Color.TRANSPARENT, 22)
            contentDescription = "搜索"
            setOnClickListener { search() }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(dp(44), dp(44)))
        searchArea.addView(searchRow)
        profileSelector = row().apply {
            minimumHeight = dp(44)
            setPadding(dp(2), 0, dp(2), 0)
            background = ripple(Color.TRANSPARENT, 12)
            isFocusable = true
            setOnClickListener { chooseProfile() }
        }
        profileSelector.addView(label("下载机型", 13, secondary), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
        profileName = label("正在读取", 14, blue).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        profileSelector.addView(profileName, weight())
        profileSelector.addView(icon(UiIcon.Kind.CHEVRON, secondary), LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(8) })
        searchArea.addView(profileSelector, margin(4))
        searchFeedback = row()
        searchMessage = label("", 13, secondary)
        searchFeedback.addView(searchMessage, weight())
        searchCancel = button("取消搜索") { controller.cancelSearch() }
        searchFeedback.addView(searchCancel)
        searchArea.addView(searchFeedback, margin(4))
        root.addView(searchArea, margin(bottom = 16))

        status = column().apply { setPadding(0, dp(4), 0, dp(12)) }
        val statusRow = row()
        message = label("", 14, ink)
        statusRow.addView(message, weight())
        cancel = button("取消") { controller.cancelOperation() }
        statusRow.addView(cancel)
        status.addView(statusRow)
        root.addView(status, margin(bottom = 14))
        queueSummary = row().apply { setPadding(0, dp(4), 0, dp(12)) }
        queueMessage = label("", 14, secondary).apply { maxLines = 2 }
        queueSummary.addView(queueMessage, weight())
        queueSummary.addView(button("查看") { showLibrary(controller.state.tasks.firstOrNull()?.id) })
        taskArea = column()
        rows = column()
        emptyMessage = label("", 16, secondary).apply { gravity = Gravity.CENTER }
        emptySearch = button("搜索应用") { local = false; render(controller.state); query.requestFocus() }
        searchList = column()
        downloadList = column()
        moreResults = button("更多结果") { controller.search(controller.state.query, true) }
        rows.addView(emptyMessage, margin())
        rows.addView(emptySearch, margin().apply { width = -2; gravity = Gravity.CENTER_HORIZONTAL })
        rows.addView(searchList, margin())
        rows.addView(downloadList, margin())
        rows.addView(moreResults, margin())
        root.addView(rows, margin())
        root.addView(queueSummary, margin(top = 16))
        root.addView(taskArea, margin(top = 16))
        scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setOnScrollChangeListener { _, _, y, _, oldY -> if (y != oldY) openDownloadRow?.close() }
            addView(root, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(560)), -2, Gravity.CENTER_HORIZONTAL))
        }
        view.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        selectionBar = column().apply { setBackgroundColor(Color.WHITE) }
        selectionBar.addView(View(context).apply { setBackgroundColor(AppPalette.LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
        val selectionActions = row().apply { setPadding(dp(12), dp(4), dp(12), dp(8)) }
        selectAll = button("全选") {
            val ids = controller.state.downloads.mapNotNull { it.libraryId }
            if (!busy && !push.state.busy) {
                if (selectedDownloads.containsAll(ids)) selectedDownloads.clear() else selectedDownloads.addAll(ids)
                render(controller.state)
            }
        }
        deleteSelected = button("删除") { deleteSelection() }.apply { setTextColor(0xFFC83C46.toInt()) }
        selectionActions.addView(selectAll, weight())
        selectionActions.addView(deleteSelected, weight())
        selectionActions.addView(button("取消") { cancelSelection() }.apply { setTextColor(secondary) }, weight())
        selectionBar.addView(selectionActions)
        selectionBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scroll.setPadding(0, 0, 0, if (selecting) selectionBar.height else 0)
        }
        view.addView(selectionBar, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(560)), -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    private fun render(state: PlayState) {
        if (!state.busy) selectedDownloads.retainAll(state.downloads.mapNotNull { it.libraryId }.toSet())
        if (!local || (!state.busy && state.downloads.isEmpty()) || (wasLibraryBusy && !state.libraryBusy && selectedDownloads.isEmpty())) {
            selecting = false
            selectedDownloads.clear()
        }
        selectionBar.visibility = visible(selecting)
        scroll.setPadding(0, 0, 0, if (selecting) selectionBar.height else 0)
        selectAll.setTextIfChanged(if (selectedDownloads.isNotEmpty() && selectedDownloads.size == state.downloads.size) "取消全选" else "全选")
        selectAll.isEnabled = !busy && !push.state.busy
        deleteSelected.setTextIfChanged(if (state.libraryBusy) "正在删除…" else "删除 (${selectedDownloads.size})")
        deleteSelected.isEnabled = selectedDownloads.isNotEmpty() && !busy && !push.state.busy
        deleteSelected.alpha = if (deleteSelected.isEnabled) 1f else .4f
        if (renderedLocal != local) {
            openDownloadRow?.close(animated = false)
            googleTab.setTextColor(if (!local) ink else secondary)
            localTab.setTextColor(if (local) ink else secondary)
            googleTab.textSize = if (!local) 19f else 17f
            localTab.textSize = if (local) 19f else 17f
            googleTab.typeface = Typeface.create(if (!local) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            localTab.typeface = Typeface.create(if (local) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            googleTab.isSelected = !local
            localTab.isSelected = local
            renderedLocal = local
        }
        searchArea.visibility = visible(!local)
        search.isEnabled = state.profiles.isNotEmpty()
        searchFeedback.visibility = visible(state.searching || state.searchError.isNotBlank())
        searchMessage.setTextIfChanged(if (state.searching) "正在搜索…" else state.searchError)
        searchMessage.setTextColor(if (state.searchError.isNotBlank()) Color.rgb(173, 85, 0) else secondary)
        searchCancel.visibility = visible(state.searching)
        profileName.setTextIfChanged(state.profile?.let { if (it.id == "native") "本机" else it.name } ?: "正在读取")
        profileSelector.contentDescription = "下载机型：${profileName.text}"
        profileSelector.isEnabled = !downloadBusy && state.profiles.isNotEmpty()
        profileName.setTextColor(if (profileSelector.isEnabled) blue else secondary)
        status.visibility = visible(state.busy || state.error)
        if (wasBusy && !state.busy && !state.error && state.message.isNotBlank()) Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
        wasBusy = state.busy
        if (wasLibraryBusy && !state.libraryBusy && state.libraryMessage.isNotBlank()) {
            Toast.makeText(context, state.libraryMessage, Toast.LENGTH_SHORT).show()
        }
        wasLibraryBusy = state.libraryBusy
        message.setTextIfChanged(state.message)
        message.setTextColor(if (state.error) Color.rgb(173, 85, 0) else ink)
        cancel.visibility = visible(state.busy)
        val emptyLibrary = local && state.downloads.isEmpty() && state.tasks.isEmpty()
        val rowParams = rows.layoutParams as LinearLayout.LayoutParams
        val rowWeight = if (emptyLibrary) 1f else 0f
        if (rowParams.weight != rowWeight) rows.layoutParams = rowParams.apply { weight = rowWeight }
        rows.gravity = if (emptyLibrary) Gravity.CENTER else Gravity.TOP
        val emptyResults = !local && state.apps.isEmpty() && !state.searching && state.searched && state.searchError.isBlank()
        emptyMessage.setTextIfChanged(if (emptyLibrary) "暂无下载" else "未找到应用")
        emptyMessage.visibility = visible(emptyLibrary || emptyResults)
        emptyMessage.setPadding(0, if (emptyResults) dp(64) else 0, 0, dp(8))
        emptySearch.visibility = visible(emptyLibrary)
        searchList.visibility = visible(!local)
        downloadList.visibility = visible(local)
        moreResults.visibility = visible(!local && state.next != null)
        moreResults.isEnabled = !state.searching

        val ordered = state.apps
        val appKeys = ordered.map { it.packageName }.toSet()
        resultRows.keys.retainAll(appKeys)
        val resultViews = ordered.map { app -> resultRows.getOrPut(app.packageName) { ResultRow(app) }.view }
        syncRows(searchList, resultViews)
        ordered.forEach { resultRows.getValue(it.packageName).bind(it, state) }
        val downloadKeys = state.downloads.mapNotNull { it.libraryId }.toSet()
        savedRows.keys.retainAll(downloadKeys)
        val savedViews = state.downloads.mapNotNull { apk -> apk.libraryId?.let { savedRows.getOrPut(it) { SavedRow(apk) }.view } }
        syncRows(downloadList, savedViews)
        state.downloads.forEach { apk -> savedRows[apk.libraryId]?.bind(apk, state) }
        renderTasks(state.tasks)
        if (manualUpdateCheck && !state.checkingUpdates) {
            manualUpdateCheck = false
            Toast.makeText(context, state.updateError.ifBlank { "已检查更新" }, Toast.LENGTH_SHORT).show()
        }
        onStateChange()
    }

    private fun renderTasks(tasks: List<DownloadTask>) {
        val compact = !local && tasks.isNotEmpty()
        queueSummary.visibility = visible(compact)
        val counts = listOf(
            tasks.count { it.phase.inFlight } to "下载中",
            tasks.count { it.phase == DownloadPhase.QUEUED } to "等待",
            tasks.count { it.phase == DownloadPhase.PAUSED } to "已暂停",
            tasks.count { it.phase.canRetry && it.phase != DownloadPhase.PAUSED } to "待重试",
        )
        queueMessage.setTextIfChanged(counts.filter { it.first > 0 }.joinToString(" · ") { "${it.first} 项${it.second}" }.ifBlank { "正在取消…" })
        taskRows.keys.retainAll(tasks.map { it.id }.toSet())
        syncRows(taskArea, tasks.map { taskRows.getOrPut(it.id) { DownloadRow(it.id) }.view }, bottom = 24)
        tasks.forEach { taskRows.getValue(it.id).bind(it) }
        taskArea.visibility = visible(local && tasks.isNotEmpty())
    }

    private inner class DownloadRow(private val taskId: String) {
        val view = column()
        private val artwork = artwork()
        private val title = label("", 16, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val detail = label("", 12, secondary).apply { maxLines = 3; fontFeatureSettings = "tnum" }
        private val action = button("暂停") {
            val task = controller.state.tasks.firstOrNull { it.id == taskId }
            if (task?.phase?.canPause == true) controller.pauseDownload(taskId) else controller.retryDownload(taskId)
        }
        private val cancelTask = ImageButton(context).apply {
            setImageDrawable(UiIcon(UiIcon.Kind.CLOSE, secondary))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ripple(Color.TRANSPARENT, 22)
            setOnClickListener { controller.cancel(taskId) }
        }
        private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(blue)
            indeterminateTintList = ColorStateList.valueOf(blue)
        }

        init {
            val heading = row()
            heading.addView(artwork, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            heading.addView(title, weight())
            heading.addView(cancelTask, LinearLayout.LayoutParams(dp(44), dp(44)))
            view.addView(heading, margin())
            val controls = row()
            controls.addView(detail, weight())
            controls.addView(action)
            view.addView(controls, margin(5))
            view.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        }

        fun bind(task: DownloadTask) {
            title.setTextIfChanged(task.label)
            icons.load(task.iconUrl, artwork)
            action.setTextIfChanged(when {
                task.phase == DownloadPhase.PAUSED -> "继续"
                task.phase.canRetry -> "重试"
                else -> "暂停"
            })
            action.contentDescription = "${action.text} ${task.label}"
            action.visibility = visible(task.phase != DownloadPhase.CANCELLED)
            action.isEnabled = task.phase.canPause || task.phase.canRetry
            cancelTask.contentDescription = "取消下载 ${task.label}"
            cancelTask.isEnabled = task.phase != DownloadPhase.CANCELLED
            val size = if (task.total > 0) "${task.bytes * 100 / task.total}% · ${MainActivity.size(task.bytes)} / ${MainActivity.size(task.total)}" else ""
            detail.setTextIfChanged(when {
                task.phase == DownloadPhase.RUNNING && size.isNotBlank() -> size
                size.isNotBlank() -> task.message + "\n" + size
                else -> task.message
            })
            detail.setTextColor(if (task.phase == DownloadPhase.FAILED) Color.rgb(173, 85, 0) else secondary)
            progress.visibility = visible(task.phase.inFlight || task.total > 0)
            progress.isIndeterminate = task.total <= 0
            if (task.total > 0) progress.progress = (task.bytes * 1000 / task.total).toInt().coerceIn(0, 1000)
        }
    }

    private fun appTask(app: App) = controller.state.downloadTask(app.packageName)

    private fun taskLabel(task: DownloadTask) = when (task.phase) {
        DownloadPhase.QUEUED -> "排队中"
        DownloadPhase.RUNNING, DownloadPhase.PAUSING -> "下载中"
        DownloadPhase.PAUSED -> "已暂停"
        DownloadPhase.CANCELLED -> "取消中"
        else -> "待重试"
    }

    private inner class ResultRow(private var app: App) {
        val view = row().apply { background = ripple(Color.TRANSPARENT, 12); setPadding(0, dp(14), 0, dp(14)); isFocusable = true }
        private val artwork = artwork()
        private val title = label("", 16, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val developer = label("", 12, secondary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val action = button("") { actOn(app) }.apply { minWidth = dp(84); minimumWidth = dp(84) }

        init {
            view.addView(artwork, LinearLayout.LayoutParams(dp(44), dp(44)))
            val text = column()
            text.addView(title)
            text.addView(developer, margin(5))
            view.addView(text, weight().apply { marginStart = dp(12); marginEnd = dp(8) })
            view.addView(action)
            view.setOnClickListener { showApp(app) }
        }

        fun bind(app: App, state: PlayState) {
            this.app = app
            title.setTextIfChanged(app.displayName.ifBlank { app.packageName })
            developer.setTextIfChanged(app.developerName)
            icons.load(app.iconArtwork.url, artwork)
            val task = state.downloadTask(app.packageName)
            val saved = state.savedApp(app.packageName)
            val update = saved?.let(state::updateFor)
            action.setTextIfChanged(when {
                task != null -> taskLabel(task)
                update != null -> "更新"
                saved != null -> "推送"
                app.isFree -> "获取"
                else -> "付费"
            })
            action.contentDescription = "${action.text} ${title.text}"
            action.isEnabled = when {
                task != null -> true
                update != null -> !downloadBusy
                saved != null -> !busy && !push.state.busy
                else -> !downloadBusy && app.isFree
            }
            action.alpha = if (action.isEnabled) 1f else .4f
        }
    }

    private fun actOn(app: App) {
        val state = controller.state
        val saved = state.savedApp(app.packageName)
        when {
            appTask(app) != null -> showLibrary(appTask(app)?.id)
            saved != null && state.updateFor(saved) != null -> requestDownload { controller.updateDownload(checkNotNull(saved.libraryId)) }
            saved != null -> select(saved)
            else -> download(app)
        }
    }

    private inner class SavedRow(private var apk: ApkFile) {
        private val tile = row().apply { background = ripple(Color.WHITE, 12); setPadding(0, dp(18), 0, dp(18)); isFocusable = true }
        private val artwork = artwork()
        private val title = label("", 16, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        private val detail = label("", 12, secondary)
        private val send = button("推送") { select(apk) }
        private val update = button("更新") { requestDownload { controller.updateDownload(checkNotNull(apk.libraryId)) } }
        private val selection = ImageButton(context).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(Color.TRANSPARENT, 24)
            setOnClickListener { apk.libraryId?.let(::toggleSelection) }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.CheckBox"
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            }
        }
        val view = DownloadSwipeRow(context, tile, false, apk.label, onInteraction = { row ->
            if (openDownloadRow !== row) openDownloadRow?.close()
            openDownloadRow = row
        }, onDelete = { deleteDownload(apk) })

        init {
            tile.addView(artwork, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            val text = column()
            text.addView(title)
            text.addView(detail, margin(5))
            tile.addView(text, weight().apply { marginEnd = dp(8) })
            tile.addView(update)
            tile.addView(send)
            tile.addView(selection, LinearLayout.LayoutParams(dp(48), dp(48)))
            tile.setOnClickListener {
                if (selecting) apk.libraryId?.let(::toggleSelection) else if (!busy) showDownload(apk)
            }
            tile.setOnLongClickListener {
                val id = apk.libraryId
                if (id == null || busy || push.state.busy) false else {
                    openDownloadRow?.close(animated = false)
                    selecting = true
                    selectedDownloads.add(id)
                    render(controller.state)
                    true
                }
            }
        }

        fun bind(apk: ApkFile, state: PlayState) {
            this.apk = apk
            title.setTextIfChanged(apk.label)
            detail.setTextIfChanged("${apk.versionName} · ${MainActivity.size(apk.size)}")
            icons.load(apk.iconFile, artwork)
            val task = state.downloadTask(apk.packageName, state.profileFor(apk))
            update.visibility = visible(!selecting && (state.updateFor(apk) != null || task != null))
            update.setTextIfChanged(task?.let(::taskLabel) ?: "更新")
            update.contentDescription = "${update.text} ${apk.label}"
            update.isEnabled = !downloadBusy && task == null
            update.alpha = if (update.isEnabled) 1f else .4f
            send.isEnabled = !busy && !push.state.busy
            send.alpha = if (send.isEnabled) 1f else .4f
            send.visibility = visible(!selecting)
            selection.visibility = visible(selecting)
            selection.isEnabled = !busy && !push.state.busy
            val checked = apk.libraryId in selectedDownloads
            if (selection.drawable == null || selection.isSelected != checked) {
                selection.setImageDrawable(UiIcon(if (checked) UiIcon.Kind.CHECK else UiIcon.Kind.CIRCLE, if (checked) blue else secondary))
            }
            selection.isSelected = checked
            selection.contentDescription = "选择 ${apk.label}"
            view.setDeletionEnabled(!selecting && !busy && !push.state.busy)
        }
    }

    private fun syncRows(container: LinearLayout, desired: List<View>, bottom: Int = 0) {
        if (container.childCount == desired.size && desired.indices.all { container.getChildAt(it) === desired[it] }) return
        fun windowY(view: View) = IntArray(2).also(view::getLocationInWindow)[1]
        val oldScroll = scroll.scrollY
        val anchor = if (container.isShown && oldScroll > 0) (0 until container.childCount).map(container::getChildAt)
            .firstOrNull { it in desired && windowY(it) + it.height > windowY(scroll) } else null
        val anchorY = anchor?.let(::windowY)
        val section = local
        val query = controller.state.query
        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index)
            if (child !in desired) { icons.unbind(child); container.removeViewAt(index) }
        }
        desired.forEachIndexed { index, child ->
            if (container.getChildAt(index) !== child) {
                container.removeView(child)
                container.addView(child, index, margin(bottom = bottom))
            }
        }
        if (anchor != null && anchorY != null) container.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                container.removeOnLayoutChangeListener(this)
                if (section == local && query == controller.state.query && scroll.scrollY == oldScroll && anchor.parent === container) {
                    scroll.scrollBy(0, windowY(anchor) - anchorY)
                }
            }
        })
    }

    private fun select(apk: ApkFile) {
        if (busy || push.state.busy) return
        push.useDownload(apk.libraryId ?: return)
        onSelect()
    }

    private fun search() {
        if (query.text.isBlank()) { query.requestFocus(); return }
        (context.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
        controller.search(query.text.toString())
    }

    private fun showMenu(anchor: View) {
        openDownloadRow?.close()
        moreMenu?.dismiss()
        val body = column().apply { background = rounded(Color.WHITE, 16); clipToOutline = true }
        val popup = PopupWindow(body, minOf(dp(236), resources.displayMetrics.widthPixels - dp(48)), -2, true).apply {
            setBackgroundDrawable(rounded(Color.WHITE, 16))
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        if (controller.state.downloads.isNotEmpty()) body.addView(button(if (controller.state.checkingUpdates) "正在检查更新…" else "检查更新") {
            popup.dismiss()
            manualUpdateCheck = controller.checkUpdates(force = true)
            if (!manualUpdateCheck) {
                Toast.makeText(context, controller.state.updateError.ifBlank { "暂无可检查的下载" }, Toast.LENGTH_SHORT).show()
            }
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(ink)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isEnabled = !controller.state.checkingUpdates && !controller.state.busy
        }, margin())
        body.addView(button("下载说明") { popup.dismiss(); help() }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(ink)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }, margin())
        popup.setOnDismissListener { if (moreMenu === popup) moreMenu = null }
        moreMenu = popup
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.END)
    }

    private fun chooseProfile() {
        if (downloadBusy) return
        val state = controller.state
        val profiles = state.profiles
        val filter = EditText(context).apply {
            hint = "搜索品牌、型号、API 或架构"
            contentDescription = "搜索下载机型"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(secondary)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(0, dp(12), 0, dp(12))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    (context.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }
        val clear = ImageButton(context).apply {
            setImageDrawable(UiIcon(UiIcon.Kind.CLOSE, secondary))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ripple(Color.TRANSPARENT, 22)
            contentDescription = "清除机型搜索"
            visibility = View.GONE
            setOnClickListener { filter.text.clear(); filter.requestFocus() }
        }
        AppSheet.show(activity, "下载机型", expanded = true, fixedContent = { header, _ ->
            header.addView(row().apply {
                background = rounded(AppPalette.SURFACE, 12)
                setPadding(dp(14), 0, dp(4), 0)
                addView(icon(UiIcon.Kind.SEARCH, secondary), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
                addView(filter, weight())
                addView(clear, LinearLayout.LayoutParams(dp(44), dp(44)))
            }, margin(bottom = 12))
        }) { body, dialog ->
            val rows = profiles.associateWith { profile ->
                row().apply {
                    setPadding(0, dp(14), 0, dp(14))
                    val selected = profile.id == state.profileId
                    background = ripple(Color.TRANSPARENT, 12)
                    isFocusable = true
                    contentDescription = PlayRepository.profileLabel(profile)
                    val text = column()
                    text.addView(label(if (profile.id == "native") "本机" else profile.name, 16, ink, true))
                    text.addView(label("API ${profile.properties["Build.VERSION.SDK_INT"]} · ${profile.properties["Platforms"]?.substringBefore(',')}", 12, secondary), margin(5))
                    addView(text, weight().apply { marginEnd = dp(10) })
                    if (selected) addView(icon(UiIcon.Kind.CHECK, blue), LinearLayout.LayoutParams(dp(22), dp(22)))
                    setOnClickListener { controller.selectProfile(profile.id); dialog.dismiss() }
                    body.addView(this, margin(bottom = 4))
                }
            }
            val empty = label("没有匹配的机型", 15, secondary).apply {
                gravity = Gravity.CENTER
                minHeight = dp(160)
                visibility = View.GONE
            }
            body.addView(empty, margin())
            filter.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(text: Editable?) {
                    val matches = filterProfiles(profiles, text?.toString().orEmpty()).toSet()
                    rows.forEach { (profile, row) -> row.visibility = visible(profile in matches) }
                    empty.visibility = visible(matches.isEmpty())
                    clear.visibility = visible(!text.isNullOrEmpty())
                    (body.parent as? ScrollView)?.scrollTo(0, 0)
                }
            })
        }
    }

    private fun showApp(app: App) {
        AppSheet.show(activity, "应用详情") { body, dialog ->
            val heading = row()
            val artwork = icon(UiIcon.Kind.APPS, blue).apply { background = rounded(AppPalette.SURFACE, 12); clipToOutline = true }
            icons.load(app.iconArtwork.url, artwork)
            heading.addView(artwork, LinearLayout.LayoutParams(dp(56), dp(56)))
            val text = column()
            text.addView(label(app.displayName, 20, ink, true))
            text.addView(label(app.developerName, 13, secondary), margin(6))
            heading.addView(text, weight().apply { marginStart = dp(14) })
            body.addView(heading, margin(bottom = 20))
            app.shortDescription.ifBlank { app.description }.take(180).takeIf { it.isNotBlank() }?.let { AppSheet.message(body, it) }
            AppSheet.details(body, "包名" to app.packageName)
            val saved = controller.state.savedApp(app.packageName)
            val update = saved?.let(controller.state::updateFor)
            val task = appTask(app)
            AppSheet.action(body, if (task != null) "查看下载" else if (update != null) "更新" else if (saved != null) "推送" else if (app.isFree) "获取" else "付费应用暂不支持", primary = true) {
                dialog.dismiss()
                actOn(app)
            }.apply {
                isEnabled = if (task != null) true else if (update != null) !downloadBusy else if (saved != null) !busy && !push.state.busy else !downloadBusy && app.isFree
                alpha = if (isEnabled) 1f else .4f
            }
            AppSheet.action(body, "在 Google Play 查看") {
                if (busy || push.state.busy) Toast.makeText(context, "请等待当前任务完成", Toast.LENGTH_SHORT).show()
                else runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Uri.encode(app.packageName)}"))) }
            }
        }
    }

    private fun showDownload(apk: ApkFile) {
        AppSheet.show(activity, "下载详情") { body, dialog ->
            val heading = row()
            val artwork = artwork()
            icons.load(apk.iconFile, artwork)
            heading.addView(artwork, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
            heading.addView(label(apk.label, 21, ink, true), weight())
            body.addView(heading, margin(bottom = 18))
            AppSheet.details(body, "版本" to apk.versionName, "大小" to "${MainActivity.size(apk.size)} · ${apk.files.size} 个 APK",
                "包名" to apk.packageName, "下载机型" to apk.downloadProfile,
                "保存位置" to apk.downloadLocation.ifBlank { "等待保存到 Download/APK Push" })
            AppSheet.action(body, "安装到本机") {
                dialog.dismiss()
                activity.startActivity(Intent(activity, dev.tvapkpush.install.LocalInstallActivity::class.java)
                    .putExtra("library", apk.libraryId).putExtra("label", apk.label))
            }.apply { isEnabled = !busy && !push.state.busy; alpha = if (isEnabled) 1f else .4f }
        }
    }

    private fun deleteDownload(apk: ApkFile) {
        if (busy || push.state.busy) return
        openDownloadRow?.close()
        AppSheet.confirm(activity, "删除安装包？", "${apk.label}\n将删除本机保存的这份安装包。", "确认", destructive = true) {
            val id = apk.libraryId
            if (id != null && !busy && !push.state.busy) { push.clearDownload(id); controller.delete(id) }
        }
    }

    private fun deleteSelection() {
        if (busy || push.state.busy || selectedDownloads.isEmpty()) return
        val ids = selectedDownloads.toList()
        AppSheet.confirm(activity, "删除 ${ids.size} 个安装包？", "将删除本机保存的这些安装包。", "确认", destructive = true) {
            if (!busy && !push.state.busy) {
                ids.forEach(push::clearDownload)
                controller.delete(ids)
            }
        }
    }

    private fun download(app: App) = requestDownload { controller.download(app) }

    private fun requestDownload(action: () -> Unit) {
        if (downloadBusy) return
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val preferences = context.getSharedPreferences("play_settings", Activity.MODE_PRIVATE)
            if (!preferences.getBoolean("notification_requested", false)) {
                preferences.edit().putBoolean("notification_requested", true).apply()
                activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 21)
            }
        }
        action()
    }

    private fun help() {
        AppSheet.show(activity, "下载说明") { body, _ ->
            listOf(
                "搜索与下载" to "无需登录，支持免费应用。手机需要能访问 Google 服务。",
                "选择机型" to "机型决定安装包的系统版本与架构。向电视推送时，选择相符的电视配置。",
                "保存位置" to "下载完成后保存在 Download/APK Push，文件管理器可直接查看。独立包为 .apk，拆分包为 .apks。卸载 APK Push 后文件仍保留，可在已下载列表左滑删除。",
                "本机安装" to "在已下载列表打开应用，点击安装到本机，再按系统提示确认。拆分 APK 会整组安装。给当前手机安装时，下载机型请选择本机。",
                "后台与恢复" to "下载可在后台和锁屏时继续。暂停会保留进度，点击继续恢复。断网或异常退出后，需手动点击重试。取消会删除未完成文件。",
                "下载队列" to "最多同时下载 3 个应用，其余按加入顺序等待。每项可单独暂停、继续或取消。暂停和失败不占用下载名额，改变机型只影响新加入的任务。",
                "检查更新" to "联网打开应用时按原下载机型检查版本，也可从菜单手动检查。有新版时显示更新，点击后加入下载队列；旧安装包保留。",
                "续传与限制" to "服务器支持时断点续传，版本变化或不支持续传时重新下载。付费应用、OBB、游戏资源包及额外共享库暂不支持。",
                "应用来源" to "Google Play · Aurora gplayapi。部分应用运行需要 Google 服务或账号。APK Push 与 Google 无隶属关系。",
            ).forEach { (title, text) ->
                body.addView(label(title, 16, ink, true), margin(bottom = 8))
                AppSheet.message(body, text)
            }
        }
    }

    private fun artwork() = icon(UiIcon.Kind.FILE, blue).apply {
        background = rounded(AppPalette.SURFACE, 10)
        clipToOutline = true
    }

    private fun TextView.setTextIfChanged(value: String) { if (text.toString() != value) text = value }

    private fun icon(kind: UiIcon.Kind, color: Int) = ImageView(context).apply {
        setImageDrawable(UiIcon(kind, color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Int, color: Int, medium: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun button(title: String, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 15f; isAllCaps = false; setTextColor(blue)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(44); minimumHeight = dp(44); minWidth = dp(44); minimumWidth = dp(44)
        setPadding(dp(14), dp(10), dp(14), dp(10)); background = ripple(Color.TRANSPARENT, 12)
        stateListAnimator = null; setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun ripple(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(0x18007AFF), rounded(color, radius), rounded(Color.WHITE, radius))
    private fun margin(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE

}
