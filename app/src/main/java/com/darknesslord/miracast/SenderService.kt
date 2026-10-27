package com.darknesslord.miracast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.KeyguardManager
import android.media.AudioManager
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import java.net.InetSocketAddress
import java.net.Socket

class SenderService : Service(), VideoEncoder.Sink {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_MAX_LONG = "maxLong"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_MUTE_PHONE = "mutePhone"
        const val EXTRA_DIM = "dim"
        private const val CHANNEL = "mirror"
        private const val NOTIF_ID = 7

        @Volatile var running = false
            private set
        @Volatile var statusText = "Hazır"
            private set
        var statusListener: ((String) -> Unit)? = null

        private val main = Handler(Looper.getMainLooper())

        fun setStatus(s: String) {
            statusText = s
            main.post { statusListener?.invoke(s) }
        }

        /** Uygulama beklenmedik kapanırsa telefon sessiz/karanlık kalmasın. */
        fun restoreLeftovers(ctx: Context) {
            if (running) return
            val p = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
            if (p.getBoolean("muted", false)) {
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {
                }
            }
            val b = p.getInt("oldBrightness", -1)
            if (b >= 0 && Settings.System.canWrite(ctx)) {
                try {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, b)
                    Settings.System.putInt(
                        ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        p.getInt("oldBrightnessMode", Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    )
                } catch (_: Exception) {
                }
            }
            p.edit().clear().apply()
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var link: Link? = null
    @Volatile private var csd: ByteArray? = null
    @Volatile private var active = false
    private var finder: TvFinder? = null
    private var manualHost: String? = null
    private var maxLong = 1920
    private var fps = 60
    private var curW = 0
    private var curH = 0
    private var dpi = 320
    private var bgThread: HandlerThread? = null
    private var bg: Handler? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var mutePhone = true
    private var dimPhone = false
    @Volatile private var mutedByUs = false
    private var stoppedByLock = false
    private var screenReceiver: BroadcastReceiver? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY

        // Android 14+: projeksiyon alınmadan önce ön plan servisi başlamış olmalı.
        startInForeground("Başlatılıyor…")

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        manualHost = intent.getStringExtra(EXTRA_HOST)?.trim()?.ifEmpty { null }
        maxLong = intent.getIntExtra(EXTRA_MAX_LONG, 1920)
        fps = intent.getIntExtra(EXTRA_FPS, 60)
        val wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, true)
        mutePhone = intent.getBooleanExtra(EXTRA_MUTE_PHONE, true)
        dimPhone = intent.getBooleanExtra(EXTRA_DIM, false)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            if (data != null) mpm.getMediaProjection(code, data) else null
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            setStatus("Ekran izni alınamadı")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        active = true
        running = true

        bgThread = HandlerThread("mirror-bg").also { it.start() }
        bg = Handler(bgThread!!.looper)

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (stoppedByLock || isLocked()) {
                    setStatus("Telefon kilitlendi, yansıtma durdu. Tekrar başlatın.")
                    stoppedByLock = true
                }
                stopSelf()
            }
        }, main)

        acquireLocks()
        keepScreenOn()
        if (dimPhone) dimScreen()
        startFinder()
        try {
            startVideo()
        } catch (e: Exception) {
            setStatus("Video kodlayıcı açılamadı: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wantAudio && Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startAudio(mp)
        }
        Thread(::connectionLoop, "mirror-conn").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    // ---------------- Video ----------------

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val m = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    private fun startVideo() {
        val m = realMetrics()
        dpi = m.densityDpi
        val (w, h) = VideoEncoder.pickSize(m.widthPixels, m.heightPixels, maxLong)
        curW = w; curH = h
        val enc = VideoEncoder(w, h, fps, this)
        encoder = enc
        virtualDisplay = projection!!.createVirtualDisplay(
            "EkranYansit", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            enc.surface, null, null
        )
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) bg?.post { onRotation() }
            }
        }
        displayListener = l
        dm.registerDisplayListener(l, bg)
    }

    /** Telefon döndürüldüğünde akışı kopmadan yeni yöne uyarlar. */
    private fun onRotation() {
        if (!active) return
        val m = realMetrics()
        val landscape = m.widthPixels > m.heightPixels
        if (landscape == (curW > curH)) return
        val (w, h) = VideoEncoder.pickSize(m.widthPixels, m.heightPixels, maxLong)
        val vd = virtualDisplay ?: return
        val old = encoder
        val enc = try {
            VideoEncoder(w, h, fps, this)
        } catch (e: Exception) {
            return
        }
        csd = null
        curW = w; curH = h
        encoder = enc
        link?.sendControl(PacketIO.format(w, h))
        vd.resize(w, h, dpi)
        vd.surface = enc.surface
        old?.release()
        enc.requestKeyFrame()
    }

    override fun onCsd(data: ByteArray) {
        csd = data
        link?.sendControl(Packet(Proto.T_CSD, 0, 0, data))
    }

    override fun onFrame(data: ByteArray, pts: Long, key: Boolean) {
        link?.send(Packet(Proto.T_VIDEO, if (key) Proto.F_KEY else 0, pts, data))
    }

    // ---------------- Ses ----------------

    @SuppressLint("MissingPermission")
    private fun startAudio(mp: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29) return
        Thread({
            var rec: AudioRecord? = null
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Proto.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val min = AudioRecord.getMinBufferSize(
                    Proto.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
                rec = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(maxOf(min * 2, 3840 * 4))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                rec.startRecording()
                val buf = ByteArray(3840) // 20 ms
                var heardMs = 0
                var mutedAt = 0L
                var verified = false
                var muteFailed = false
                var userOverride = false
                while (active) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val l = link
                    val connected = l != null && l.alive
                    l?.send(Packet(Proto.T_AUDIO, 0, 0, buf.copyOf(n)))

                    if (!mutePhone || muteFailed || userOverride) continue
                    val peak = peakOf(buf, n)
                    if (!connected) {
                        // TV bağlı değilken ses telefondan çıksın.
                        if (mutedByUs) setPhoneMute(false)
                        heardMs = 0
                        continue
                    }
                    if (!mutedByUs) {
                        // Önce gerçekten ses yakalandığını gör, sonra telefonu sustur.
                        if (peak > 64) heardMs += 20
                        if (heardMs >= 200) {
                            setPhoneMute(true)
                            mutedAt = System.currentTimeMillis()
                        }
                    } else {
                        if (!isMusicMuted()) {
                            // Kullanıcı ses tuşuyla telefonu açtı; buna saygı göster.
                            mutedByUs = false
                            userOverride = true
                            markMuted(false)
                            continue
                        }
                        if (!verified) {
                            if (peak > 0) {
                                verified = true
                            } else if (System.currentTimeMillis() - mutedAt > 1500) {
                                // Bu cihazda susturma yakalamayı da kesiyor: geri al.
                                setPhoneMute(false)
                                muteFailed = true
                                setStatus("Bu telefonda ses hem telefondan hem TV'den çıkar (cihaz kısıtı)")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec?.stop() } catch (_: Exception) { }
                rec?.release()
            }
        }, "mirror-audio").apply { isDaemon = true; start() }
    }

    private fun peakOf(b: ByteArray, n: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < n) {
            val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            i += 8
        }
        return peak
    }

    private fun audioManager() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun isMusicMuted(): Boolean = try {
        val am = audioManager()
        am.isStreamMute(AudioManager.STREAM_MUSIC) || am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
    } catch (_: Exception) {
        true
    }

    private fun markMuted(m: Boolean) {
        getSharedPreferences("state", Context.MODE_PRIVATE).edit().putBoolean("muted", m).apply()
    }

    private fun setPhoneMute(mute: Boolean) {
        try {
            audioManager().adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0
            )
            mutedByUs = mute
            markMuted(mute)
        } catch (_: Exception) {
        }
    }

    // ---------------- Ekran ----------------

    private fun isLocked(): Boolean = try {
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (_: Exception) {
        false
    }

    /** Yansıtma sürerken telefon ekranı kendiliğinden kapanmasın (kapanırsa Android yakalamayı durdurur). */
    @Suppress("DEPRECATION")
    private fun keepScreenOn() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "miracast:screen"
            ).apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) {
                    stoppedByLock = true
                    setStatus("Telefon ekranı kapatıldı — görüntü durdu. Ekranı açın.")
                    updateNotif("Ekran kapalı — görüntü gitmiyor")
                } else if (i.action == Intent.ACTION_USER_PRESENT || i.action == Intent.ACTION_SCREEN_ON) {
                    stoppedByLock = false
                    encoder?.requestKeyFrame()
                }
            }
        }
        screenReceiver = r
        registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    /** Ekran açık kalır ama parlaklık en düşüğe iner (pil ve göz için). */
    private fun dimScreen() {
        if (!Settings.System.canWrite(this)) return
        try {
            val cr = contentResolver
            val p = getSharedPreferences("state", Context.MODE_PRIVATE)
            if (p.getInt("oldBrightness", -1) < 0) {
                p.edit()
                    .putInt("oldBrightness", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                    .putInt("oldBrightnessMode", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0))
                    .apply()
            }
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 1)
        } catch (_: Exception) {
        }
    }

    // ---------------- Bağlantı ----------------

    private fun startFinder() {
        finder = TvFinder(this) { }.also { it.start() }
    }

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun pickHost(): Pair<String, String>? {
        manualHost?.let { return it to it }
        val list = finder?.current.orEmpty()
        val last = prefs().getString("lastHost", null)
        list.firstOrNull { it.host == last }?.let { return it.host to it.name }
        list.firstOrNull()?.let { return it.host to it.name }
        if (last != null) return last to (prefs().getString("lastName", null) ?: last)
        return null
    }

    private fun connectionLoop() {
        var lastTried: String? = null
        while (active) {
            val target = pickHost()
            if (target == null) {
                setStatus("TV aranıyor… (TV'de uygulama açık olmalı)")
                updateNotif("TV aranıyor…")
                if (!sleep(700)) break
                continue
            }
            val (host, name) = target
            if (lastTried != host) setStatus("Bağlanıyor: $name")
            lastTried = host
            var sock: Socket? = null
            try {
                sock = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    sendBufferSize = 1 shl 20
                    connect(InetSocketAddress(host, Proto.PORT), 3000)
                    soTimeout = 6000
                }
                val l = Link(sock) { encoder?.requestKeyFrame() }
                l.start()
                l.sendControl(PacketIO.format(curW, curH))
                csd?.let { l.sendControl(Packet(Proto.T_CSD, 0, 0, it)) }
                link = l
                encoder?.requestKeyFrame()
                prefs().edit().putString("lastHost", host).putString("lastName", name).apply()
                setStatus("Yansıtılıyor → $name")
                updateNotif("Yansıtılıyor → $name")
                while (active && l.alive) {
                    if (!sleep(250)) break
                }
                link = null
                l.close()
                if (active) {
                    setStatus("Bağlantı koptu, yeniden bağlanıyor…")
                    updateNotif("Yeniden bağlanıyor…")
                }
            } catch (_: Exception) {
                try { sock?.close() } catch (_: Exception) { }
                if (active) setStatus("$name bağlanamadı, tekrar deneniyor…")
                if (!sleep(1000)) break
            }
        }
    }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms); true
    } catch (_: InterruptedException) {
        false
    }

    // ---------------- Servis yardımcıları ----------------

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "mirror-wifi").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "miracast:sender").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Ekran yansıtma", NotificationManager.IMPORTANCE_LOW)
                )
            }
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        @Suppress("DEPRECATION")
        return b.setContentTitle("Ekran Yansıt")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
    }

    private fun startInForeground(text: String) {
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotif(text))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        active = false
        running = false
        link?.close()
        link = null
        displayListener?.let {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it)
        }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        encoder?.release()
        encoder = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        finder?.stop()
        bgThread?.quitSafely()
        try { wifiLock?.release() } catch (_: Exception) { }
        try { wakeLock?.release() } catch (_: Exception) { }
        try { screenLock?.release() } catch (_: Exception) { }
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        restoreLeftovers(this)
        if (!stoppedByLock) setStatus("Durduruldu")
        super.onDestroy()
    }
}
