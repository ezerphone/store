package com.example.mystore

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.text.TextUtils
import android.text.util.Linkify
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors

class AppVH(view: View) : RecyclerView.ViewHolder(view)

class MainActivity : Activity() {

    private enum class State { NOT_INSTALLED, UPDATE, INSTALLED }

    private class MenuItem(val label: String, val color: Int, val run: () -> Unit)

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private lateinit var installer: SilentInstaller

    /** Everything found in the folder (minus hidden apps). */
    private var allApps: List<AppEntry> = emptyList()

    /** What is shown, in display order. */
    private var apps: List<AppEntry> = emptyList()

    private val installing = mutableSetOf<String>()
    private val adapter = AppAdapter()
    private val bg = Executors.newSingleThreadExecutor()
    private var firstLoadDone = false
    private var animateNext = false

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainPage: View
    private var settingsPage: View? = null
    private var lockPage: View? = null

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var emptyView: View
    private var settingsTab = 0
    private var editMode = false
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var editBar: View

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("pkg")?.let { installing.remove(it) }
            adapter.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        installer = SilentInstaller(this)
        buildUi()
        askNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(InstallResultReceiver.ACTION_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(refreshReceiver, filter)
        }
        installing.clear()
        applyPrivacy()
        updateLock()
        reload(animate = !firstLoadDone)
        firstLoadDone = true
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(refreshReceiver)
        // Lock again whenever the store leaves the screen (not on rotation). The lock
        // overlay is added right now, so it is already in place when the store returns.
        if (!isChangingConfigurations) {
            AppState.unlocked = false
            updateLock()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            settingsPage != null -> closeSettings()
            editMode -> setEditMode(false)
            else -> super.onBackPressed()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.askedNotifications
        ) {
            prefs.askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ------------------------------------------------------------------ UI building

    private fun buildUi(openSettings: Boolean = false) {
        p = Themes.palette(this, prefs)
        applySystemBars()

        rootFrame = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        mainPage = buildMainPage()
        rootFrame.addView(mainPage, FrameLayout.LayoutParams(MATCH, MATCH))
        settingsPage = null
        lockPage = null
        updateLock()              // lock overlay goes in before the first frame is drawn
        applyPrivacy()
        setContentView(rootFrame)

        showApps(false)
        if (openSettings) showSettings()
    }

    /** Hide the store from the recent-apps thumbnail while a code is set. */
    private fun applyPrivacy() {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!prefs.lockEnabled)
    }

    private fun isLocked() = prefs.lockEnabled && !AppState.unlocked

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.setBackgroundDrawable(ColorDrawable(p.bg))
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        var flags = window.decorView.systemUiVisibility
        flags = if (p.dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            background = rippleBg(roundedBg(p.surface, 22, p.border, 1), withAlpha(p.text, 0x1A))
            isClickable = true
            contentDescription = desc
            setOnClickListener { onClick() }
            addView(
                icon(res, p.text),
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun iconDp(): Int = listOf(40, 48, 64)[prefs.iconSize]

    private fun computeSpans(): Int {
        if (prefs.layoutMode == 1) return 1
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val target = listOf(84, 100, 128)[prefs.iconSize]
        return maxOf(2, (widthDp / target).toInt())
    }

    private fun buildMainPage(): View {
        if (::rv.isInitialized) rv.adapter = null

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // header: title + settings button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(4))
        }
        header.addView(
            tv(getString(R.string.app_name), 21f, p.text, true),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        header.addView(iconButton(R.drawable.ic_settings, "הגדרות") {
            settingsTab = 0
            showSettings()
        })
        page.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // list / grid
        rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, computeSpans())
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(dp(11), dp(8), dp(11), dp(20))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
                ) {
                    outRect.set(dp(5), dp(5), dp(5), dp(5))
                }
            })
        }
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(rv)

        // empty state
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(32), dp(32), dp(32), dp(32))
            val ic = icon(R.drawable.ic_store, withAlpha(p.textDim, 0x99))
            addView(ic, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(tv("אין אפליקציות להצגה", 17f, p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(6))
            })
            addView(tv("משוך את המסך למטה כדי לרענן", 13f, p.textDim).apply {
                gravity = Gravity.CENTER
            })
        }

        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(p.accent)
            setProgressBackgroundColorSchemeColor(p.surface)
            setOnChildScrollUpCallback { _, _ -> rv.canScrollVertically(-1) }
            setOnRefreshListener { reload(animate = true) }
            addView(FrameLayout(this@MainActivity).apply {
                addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))
            })
        }
        swipe.isEnabled = !editMode
        page.addView(swipe, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // shown only while arranging apps manually
        editBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(p.surface, 20, p.accent, 1)
            setPadding(dp(16), dp(10), dp(10), dp(10))
            visibility = if (editMode) View.VISIBLE else View.GONE
            addView(tv("גרור אפליקציה כדי להזיז אותה", 14f, p.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button(p, "סיום") { setEditMode(false) }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        page.addView(editBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(16), dp(4), dp(16), dp(14))
        })
        return page
    }

    // ------------------------------------------------------------------ data

    private fun reload(animate: Boolean) {
        bg.execute {
            val result = ApkScanner.scan(this)
            runOnUiThread {
                allApps = result
                showApps(animate)
                swipe.isRefreshing = false
            }
        }
    }

    private fun isPinned(app: AppEntry) = app.packageName in prefs.pinned

    /** Pinned apps first; then the order the user arranged. New apps go last, by name. */
    private fun applyOrder(list: List<AppEntry>): List<AppEntry> {
        val pinned = prefs.pinned
        val byName = compareBy<AppEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
        val index = prefs.manualOrder.withIndex().associate { it.value to it.index }
        return list.sortedWith(
            compareBy<AppEntry> { it.packageName !in pinned }
                .thenBy { index[it.packageName] ?: Int.MAX_VALUE }
                .then(byName)
        )
    }

    private fun animationsEnabled(): Boolean =
        prefs.animations &&
                Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun showApps(animate: Boolean) {
        val hidden = prefs.hidden
        apps = applyOrder(allApps.filter { it.packageName !in hidden })
        emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        animateNext = animate && animationsEnabled()
        adapter.notifyDataSetChanged()
        if (animateNext) rv.postDelayed({ animateNext = false }, 700)
    }

    /** Called after a layout preference changed. */
    private fun applyLayoutPrefs() {
        (rv.layoutManager as GridLayoutManager).spanCount = computeSpans()
        showApps(false)
    }

    private fun setEditMode(on: Boolean) {
        editMode = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        swipe.isEnabled = !on
        showApps(false)
    }

    private fun cardBg(radiusDp: Int): Drawable =
        rippleBg(
            roundedBg(p.surface, radiusDp, if (editMode) p.accent else p.border, if (editMode) 2 else 1),
            withAlpha(p.text, 0x1A)
        )

    private fun stateOf(app: AppEntry): State = try {
        val info = packageManager.getPackageInfo(app.packageName, 0)
        if (ApkScanner.versionOf(info) < app.versionCode) State.UPDATE else State.INSTALLED
    } catch (e: PackageManager.NameNotFoundException) {
        State.NOT_INSTALLED
    }

    // ------------------------------------------------------------------ app actions

    private fun onAppClick(app: AppEntry) {
        if (app.packageName in installing) return
        if (stateOf(app) == State.INSTALLED) {
            openApp(app)
            return
        }
        installApp(app)
    }

    private fun openApp(app: AppEntry) {
        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch != null) startActivity(launch) else toast(Errors.NO_LAUNCHER)
    }

    private fun installApp(app: AppEntry) {
        if (app.packageName in installing) return
        installing.add(app.packageName)
        adapter.notifyDataSetChanged()
        Thread {
            try {
                installer.install(app)
            } catch (e: Exception) {
                Log.e("MyStore", "install failed: ${app.packageName}", e)
                runOnUiThread {
                    installing.remove(app.packageName)
                    adapter.notifyDataSetChanged()
                    toast(Errors.forException(e, "install"))
                }
            }
        }.start()
    }

    private fun confirmUninstall(app: AppEntry) {
        confirmDialog(p, "הסרת אפליקציה", "להסיר את ${app.label}?", "הסר") {
            try {
                installer.uninstall(app.packageName)
            } catch (e: Exception) {
                Log.e("MyStore", "uninstall failed: ${app.packageName}", e)
                toast(Errors.forException(e, "uninstall"))
            }
        }
    }

    private fun togglePin(app: AppEntry) {
        val set = prefs.pinned.toMutableSet()
        if (!set.add(app.packageName)) set.remove(app.packageName)
        prefs.pinned = set
        showApps(false)
    }

    private fun confirmHide(app: AppEntry) {
        confirmDialog(p, "הסתרה מהחנות", "פעולה זו לא ניתנת לשחזור. האם אתה בטוח?", "הסתר") {
            prefs.hidden = prefs.hidden + app.packageName
            prefs.pinned = prefs.pinned - app.packageName
            showApps(false)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun fmtSize(b: Long): String =
        if (b >= 1048576L) "%.1f MB".format(b / 1048576.0) else "${maxOf(1L, b / 1024)} KB"

    // ------------------------------------------------------------------ context menu + details

    private fun roundedIcon(d: Drawable): ImageView =
        ImageView(this).apply {
            setImageDrawable(d.constantState?.newDrawable(resources)?.mutate() ?: d)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(11))
                }
            }
        }

    private fun divider(): View =
        View(this).apply { setBackgroundColor(p.border) }

    private fun showAppMenu(app: AppEntry) {
        val installed = stateOf(app) != State.NOT_INSTALLED
        val pinned = isPinned(app)
        var dlg: AlertDialog? = null

        val items = mutableListOf<MenuItem>()
        if (installed) {
            items.add(MenuItem("פתח", p.text) { openApp(app) })
        } else {
            items.add(MenuItem("התקן", p.text) { installApp(app) })
        }
        items.add(MenuItem("פרטים", p.text) { showDetails(app) })
        items.add(MenuItem(if (pinned) "בטל נעיצה" else "נעץ", p.text) { togglePin(app) })
        items.add(MenuItem("הזז", p.text) { setEditMode(true) })
        items.add(MenuItem("הסתר מהחנות", p.text) { confirmHide(app) })
        if (installed) {
            items.add(MenuItem("הסר", p.danger) { confirmUninstall(app) })
        }

        val card = cardView(p, 8, 24)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        head.addView(roundedIcon(app.icon), LinearLayout.LayoutParams(dp(40), dp(40)))
        head.addView(
            tv(app.label, 16f, p.text, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        card.addView(head)
        card.addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            setMargins(dp(14), 0, dp(14), dp(4))
        })

        for (item in items) {
            card.addView(
                tv(item.label, 16f, item.color).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = rippleBg(roundedBg(p.surface, 12), withAlpha(p.text, 0x1A))
                    isClickable = true
                    setOnClickListener { dlg?.dismiss(); item.run() }
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
        }
        dlg = showCardDialog(ScrollView(this).apply { addView(card) })
    }

    private fun infoRow(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(tv(label, 14f, p.textDim), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(tv(value, 14f, p.text, true).apply { gravity = Gravity.END })
        }

    private fun showDetails(app: AppEntry) {
        val installed = stateOf(app) != State.NOT_INSTALLED
        var dlg: AlertDialog? = null

        val card = cardView(p, 22, 24)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(roundedIcon(app.icon), LinearLayout.LayoutParams(dp(52), dp(52)))
        head.addView(
            tv(app.label, 18f, p.text, true).apply { setPadding(dp(14), 0, dp(4), 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        card.addView(head)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        info.addView(infoRow("גרסה", if (app.versionName.isNotEmpty()) app.versionName else "-"))
        info.addView(infoRow("גודל", fmtSize(app.sizeBytes)))
        card.addView(info)

        if (installed) {
            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch != null) {
                card.addView(
                    button(p, "פתח") { dlg?.dismiss(); startActivity(launch) },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) }
                )
            }
            card.addView(
                button(p, "הסר", BtnStyle.DANGER) { dlg?.dismiss(); confirmUninstall(app) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            )
        }
        card.addView(
            button(p, "סגור", BtnStyle.TEXT) { dlg?.dismiss() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (installed) 4 else 18) }
        )
        dlg = showCardDialog(ScrollView(this).apply { addView(card) })
    }

    // ------------------------------------------------------------------ lock

    private fun updateLock() {
        val locked = isLocked()
        if (locked) {
            if (lockPage == null) {
                val page = buildLockPage()
                lockPage = page
                rootFrame.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        } else {
            lockPage?.let { rootFrame.removeView(it) }
            lockPage = null
        }
        applyContentVisibility()
    }

    /** While locked the store content is not just covered - it is invisible. */
    private fun applyContentVisibility() {
        val locked = isLocked()
        mainPage.visibility = when {
            locked -> View.INVISIBLE
            settingsPage != null -> View.GONE
            else -> View.VISIBLE
        }
        settingsPage?.visibility = if (locked) View.INVISIBLE else View.VISIBLE
    }

    private fun buildLockPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val badge = FrameLayout(this).apply { background = roundedBg(p.accentSoft, 36) }
        badge.addView(
            icon(R.drawable.ic_lock, p.accent),
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        )
        col.addView(badge, LinearLayout.LayoutParams(dp(72), dp(72)))
        col.addView(tv("החנות נעולה", 24f, p.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })
        col.addView(tv("הזן את הקוד כדי להמשיך", 14f, p.textDim).apply { gravity = Gravity.CENTER })

        lateinit var pad: PinPad
        pad = PinPad(this, p) { pin ->
            if (prefs.checkPin(pin)) {
                AppState.unlocked = true
                updateLock()
            } else {
                pad.clear()
                pad.message("קוד שגוי", true)
                pad.shake()
            }
        }
        col.addView(pad.view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(20) })

        return ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isClickable = true
            isFillViewport = true
            addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    // ------------------------------------------------------------------ settings

    private fun showSettings() {
        settingsPage?.let { rootFrame.removeView(it) }
        val page = buildSettingsPage()
        settingsPage = page
        // index 1: above the main page, below the lock overlay (if any)
        rootFrame.addView(page, 1, FrameLayout.LayoutParams(MATCH, MATCH))
        applyContentVisibility()
    }

    private fun closeSettings() {
        settingsPage?.let { rootFrame.removeView(it) }
        settingsPage = null
        applyContentVisibility()
    }

    private fun buildSettingsPage(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(32))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        bar.addView(iconButton(R.drawable.ic_back, "חזרה") { closeSettings() })
        bar.addView(tv("הגדרות", 22f, p.text, true).apply { setPadding(dp(14), 0, dp(14), 0) })
        col.addView(bar)

        col.addView(tabBar(), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

        when (settingsTab) {
            0 -> {
                addCard(col, settingCard(
                    "מצב תצוגה", null,
                    segmented(listOf("אוטומטי", "בהיר", "כהה"), prefs.displayMode) {
                        prefs.displayMode = it
                        buildUi(openSettings = true)
                    }
                ))
                addCard(col, settingCard("ערכת נושא", null, themeRow()))
                addCard(col, toggleCard("אנימציות", "אנימציית כניסה עדינה לכרטיסים.", prefs.animations) {
                    prefs.animations = it
                })
            }
            1 -> {
                addCard(col, settingCard(
                    "פריסה", null,
                    segmented(listOf("רשת", "רשימה"), prefs.layoutMode) {
                        prefs.layoutMode = it
                        applyLayoutPrefs()
                        showSettings()
                    }
                ))
                addCard(col, settingCard(
                    "גודל אייקונים", null,
                    segmented(listOf("קטן", "בינוני", "גדול"), prefs.iconSize) {
                        prefs.iconSize = it
                        applyLayoutPrefs()
                        showSettings()
                    }
                ))
            }
            2 -> addCard(col, lockCard())
            else -> {
                addCard(col, aboutCard(
                    "שימוש בחנות",
                    tv(
                        "לחיצה ארוכה על אפליקציה פותחת תפריט עם פתיחה, פרטים, נעיצה, הזזה, הסתרה והסרה. " +
                                "משיכת המסך כלפי מטה מרעננת את הרשימה.",
                        12f, p.textDim
                    )
                ))
                addCard(col, noticeCard())
            }
        }

        col.addView(linked("להכשרת נגנים חייגו 043136429", 13f, Linkify.PHONE_NUMBERS).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isClickable = true
            addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun addCard(col: LinearLayout, view: View) {
        col.addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
    }

    private fun settingCard(title: String, subtitle: String?, content: View?): View {
        val card = cardView(p, 16)
        card.addView(tv(title, 15f, p.text, true))
        if (subtitle != null) {
            card.addView(tv(subtitle, 12f, p.textDim).apply { setPadding(0, dp(3), 0, 0) })
        }
        if (content != null) {
            card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        }
        return card
    }

    private fun toggleCard(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val card = cardView(p, 16)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 15f, p.text, true))
        texts.addView(tv(subtitle, 12f, p.textDim).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(
            toggle(checked, onChange),
            LinearLayout.LayoutParams(dp(48), dp(28)).apply { marginStart = dp(12) }
        )
        card.addView(row)
        return card
    }

    private fun toggle(initial: Boolean, onChange: (Boolean) -> Unit): View {
        var on = initial
        val thumbBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val thumb = View(this).apply { background = thumbBg }
        val track = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = true
        }
        track.addView(
            thumb,
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL).apply { marginStart = dp(3) }
        )
        fun render(animate: Boolean) {
            track.background = roundedBg(if (on) p.accent else withAlpha(p.textDim, 0x88), 14)
            thumbBg.setColor(if (on) p.onAccent else Color.WHITE)
            val x = if (on) dpf(20) else 0f
            if (animate) thumb.animate().translationX(x).setDuration(150).start() else thumb.translationX = x
        }
        render(false)
        track.setOnClickListener {
            on = !on
            render(true)
            onChange(on)
        }
        return track
    }

    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(p.surfaceAlt, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        options.forEachIndexed { i, label ->
            val sel = i == selected
            seg.addView(tv(label, 14f, if (sel) p.onAccent else p.text, sel).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                if (sel) background = roundedBg(p.accent, 11)
                isClickable = true
                setOnClickListener { if (i != selected) onSelect(i) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return seg
    }

    private fun linked(t: String, sizeSp: Float, mask: Int): TextView =
        tv(t, sizeSp, p.textDim).apply {
            Linkify.addLinks(this, mask)
            setLinkTextColor(p.accent)
        }

    private fun tabBar(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("מראה", "סידור", "נעילה", "אודות").forEachIndexed { i, label ->
            val sel = settingsTab == i
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    if (settingsTab != i) {
                        settingsTab = i
                        showSettings()
                    }
                }
            }
            item.addView(
                tv(label, 15f, if (sel) p.accent else p.textDim, sel)
                    .apply { setPadding(dp(8), dp(10), dp(8), dp(9)) }
            )
            item.addView(
                View(this).apply { background = roundedBg(if (sel) p.accent else Color.TRANSPARENT, 2) },
                LinearLayout.LayoutParams(MATCH, dp(3))
            )
            row.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        wrap.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        wrap.addView(
            View(this).apply { setBackgroundColor(p.border) },
            LinearLayout.LayoutParams(MATCH, dp(1))
        )
        return wrap
    }

    private fun themeRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Themes.accents.forEachIndexed { i, a ->
            val color = if (p.dark) a.dark else a.light
            val selected = prefs.themeIndex == i
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener {
                    if (prefs.themeIndex != i) {
                        prefs.themeIndex = i
                        buildUi(openSettings = true)
                    }
                }
            }
            val swatch = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (selected) setStroke(dp(3), p.text)
                }
            }
            if (selected) {
                swatch.addView(
                    icon(R.drawable.ic_check, Themes.onColor(color)),
                    FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
                )
            }
            item.addView(swatch, LinearLayout.LayoutParams(dp(40), dp(40)))
            item.addView(tv(a.name, 11f, if (selected) p.text else p.textDim, selected)
                .apply { setPadding(0, dp(6), 0, 0) })
            row.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return row
    }

    private fun lockCard(): View {
        val enabled = prefs.lockEnabled
        val card = cardView(p, 16)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = FrameLayout(this).apply {
            background = roundedBg(if (enabled) withAlpha(p.success, 0x26) else p.surfaceAlt, 22)
        }
        badge.addView(
            icon(R.drawable.ic_lock, if (enabled) p.success else p.textDim),
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
        )
        head.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        head.addView(
            tv(if (enabled) "הנעילה פעילה" else "הנעילה כבויה", 16f, p.text, true).apply {
                setPaddingRelative(dp(14), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        card.addView(head)

        if (!enabled) {
            card.addView(
                button(p, "הגדר קוד נעילה") { setNewPin() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
            )
        } else {
            card.addView(
                button(p, "שנה קוד", BtnStyle.TONAL) { changePin() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
            )
            card.addView(
                button(p, "בטל נעילה", BtnStyle.DANGER) { disableLock() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            )
        }
        return card
    }

    private fun aboutCard(title: String, content: View): View {
        val card = cardView(p, 14)
        card.addView(tv(title, 13f, p.text, true))
        card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        return card
    }

    private fun noticeCard(): View =
        aboutCard(
            "דיווח",
            linked(
                "אנחנו עושים מאמץ לוודא שכל האפליקציות בחנות ללא גישה לווידאו. " +
                        "אם מצאתם משהו שפותח ווידאו אנא דווחו לנו ezerphone0@gmail.com",
                12f, Linkify.EMAIL_ADDRESSES
            )
        )

    private fun setNewPin() {
        pinDialog(p, "קוד חדש", "בחר קוד של 4 עד 8 ספרות") { first ->
            pinDialog(
                p, "אימות הקוד", "הזן שוב את הקוד",
                verify = { it == first },
                errorText = "הקודים אינם תואמים"
            ) { confirmed ->
                prefs.setPin(confirmed)
                AppState.unlocked = true
                toast("קוד הנעילה הוגדר")
                buildUi(openSettings = true)
            }
        }
    }

    private fun changePin() {
        pinDialog(p, "הקוד הנוכחי", "הזן את הקוד הנוכחי", verify = { prefs.checkPin(it) }) {
            setNewPin()
        }
    }

    private fun disableLock() {
        pinDialog(p, "ביטול נעילה", "הזן את הקוד הנוכחי", verify = { prefs.checkPin(it) }) {
            prefs.clearPin()
            toast("הנעילה בוטלה")
            buildUi(openSettings = true)
        }
    }

    // ------------------------------------------------------------------ list / grid adapter

    private fun pill(busy: Boolean, state: State, maxWidthPx: Int = 0): TextView {
        val (text, fg, bgColor) = when {
            busy -> Triple("מתקין...", p.textDim, p.surfaceAlt)
            state == State.INSTALLED -> Triple("מותקן", p.success, withAlpha(p.success, 0x26))
            state == State.UPDATE -> Triple("עדכון", p.warn, withAlpha(p.warn, 0x26))
            else -> Triple("התקן", p.accent, p.accentSoft)
        }
        val padH = if (prefs.iconSize == 0) 8 else 12
        return tv(text, 11f, fg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(padH), dp(4), dp(padH), dp(4))
            background = roundedBg(bgColor, 12)
            fitToWidth(if (maxWidthPx > 0) maxWidthPx - dp(padH) * 2 else 0, 11f, 7f)
        }
    }

    /** Width in px that fits inside a grid card (cell width minus the item margins and the card padding). */
    private fun gridInnerWidth(): Int {
        val total = if (rv.width > 0) rv.width else resources.displayMetrics.widthPixels
        val cell = (total - rv.paddingLeft - rv.paddingRight) / computeSpans()
        return cell - dp(10) - dp(16)
    }

    private fun gridCard(app: AppEntry, busy: Boolean, state: State, iconDp: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), dp(12))
            background = cardBg(20)
            alpha = if (busy) 0.55f else 1f
        }
        card.addView(roundedIcon(app.icon), LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
        val inner = gridInnerWidth()
        card.addView(
            tv(app.label, 12f, p.text).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(6))
                fitToWidth(inner, listOf(12f, 12.5f, 14f)[prefs.iconSize], 8f)
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        card.addView(pill(busy, state, inner), LinearLayout.LayoutParams(WRAP, WRAP))
        return card
    }

    private fun listCard(app: AppEntry, busy: Boolean, state: State, iconDp: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = cardBg(18)
            alpha = if (busy) 0.55f else 1f
        }
        row.addView(roundedIcon(app.icon), LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        texts.addView(tv(app.label, 15f, p.text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val sub = listOfNotNull(
            if (app.versionName.isNotEmpty()) "גרסה ${app.versionName}" else null,
            fmtSize(app.sizeBytes)
        ).joinToString("  •  ")
        texts.addView(tv(sub, 12f, p.textDim).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(pill(busy, state))
        return row
    }

    private fun animateIn(v: View, position: Int) {
        v.alpha = 0f
        v.translationY = dpf(12)
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(minOf(position, 12) * 25L)
            .setDuration(220)
            .start()
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppVH>() {
        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppVH {
            val root = FrameLayout(this@MainActivity)
            root.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return AppVH(root)
        }

        override fun onBindViewHolder(holder: AppVH, position: Int) {
            val root = holder.itemView as FrameLayout
            root.animate().cancel()
            root.alpha = 1f
            root.translationY = 0f
            root.scaleX = 1f
            root.scaleY = 1f
            root.removeAllViews()

            val app = apps[position]
            val busy = app.packageName in installing
            val state = stateOf(app)
            val card = if (prefs.layoutMode == 1) {
                listCard(app, busy, state, iconDp())
            } else {
                gridCard(app, busy, state, iconDp())
            }
            root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))

            if (isPinned(app)) {
                root.addView(
                    icon(R.drawable.ic_pin, p.accent),
                    FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply {
                        setMargins(dp(9), dp(9), dp(9), dp(9))
                    }
                )
            }

            if (editMode) {
                // arranging: touching an app starts dragging it, no clicks / menu
                root.setOnClickListener(null)
                root.isClickable = false
                root.setOnLongClickListener(null)
                root.isLongClickable = false
                root.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                    false
                }
            } else {
                root.setOnTouchListener(null)
                root.isClickable = true
                root.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos >= 0) onAppClick(apps[pos])
                }
                root.setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos >= 0) showAppMenu(apps[pos])
                    true
                }
            }

            if (animateNext) animateIn(root, position)
        }
    }

    /** Drag to reorder. Dragging is started by touch while in arrange mode (see onBindViewHolder). */
    private inner class DragCallback : ItemTouchHelper.Callback() {
        private var moved = false

        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0
            )

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0 || from >= apps.size || to >= apps.size) return false
            // pinned apps stay above the others
            if (isPinned(apps[from]) != isPinned(apps[to])) return false
            val list = apps.toMutableList()
            list.add(to, list.removeAt(from))
            apps = list
            adapter.notifyItemMoved(from, to)
            moved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                moved = false
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                viewHolder.itemView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            if (moved) {
                prefs.manualOrder = apps.map { it.packageName }
                moved = false
            }
        }
    }
}
