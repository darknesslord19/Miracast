package com.darknesslord.miracast

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** TV tarafı: bağlantıyı bekler, görüntüyü ve sesi oynatır. */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TextView

    private val lock = Object()
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var needKey = true
    private var vw = 0
    private var vh = 0
    private var lastCsd: ByteArray? = null
    private var framesShown = 0L

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var current: Socket? = null
    @Volatile private var generation = 0
    @Volatile private var ctrl: LinkedBlockingQueue<Packet>? = null

    private var beacon: Beacon? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val audio = AudioPlayer()
    private val deviceName: String by lazy {
        val n = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        if (n.isNullOrBlank()) Build.MODEL else n
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setBackgroundColor(Color.argb(200, 10, 20, 40))
            setPadding(60, 40, 60, 40)
        }
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        setContentView(root)
        surfaceView.holder.addCallback(this)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        running = true
        showWaiting()
        acquireWifi()
        beacon = Beacon(deviceName).also { it.start() }
        Thread(::acceptLoop, "rx-accept").apply { isDaemon = true; start() }
    }

    override fun onStop() {
        running = false
        generation++
        try { server?.close() } catch (_: Exception) { }
        try { current?.close() } catch (_: Exception) { }
        beacon?.stop()
        beacon = null
        audio.stop()
        synchronized(lock) { releaseDecoder(); lastCsd = null }
        try { wifiLock?.release() } catch (_: Exception) { }
        super.onStop()
    }

    private fun showWaiting() {
        runOnUiThread {
            overlay.visibility = View.VISIBLE
            overlay.text = "📺  Ekran Yansıt — Hazır\n\nCihaz: $deviceName\nIP: ${Net.localIp()}\n\n" +
                "Telefonda Ekran Yansıt'ı açıp\n“Yansıtmayı Başlat”a basın.\nBağlantı otomatik kurulur."
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifi() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "mirror-rx").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {
        }
    }

    // ---------------- Ağ ----------------

    private fun acceptLoop() {
        while (running) {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = 1 shl 20
                    bind(InetSocketAddress(Proto.PORT))
                }
                server = ss
                while (running) {
                    val s = ss.accept()
                    // Yeni telefon bağlanınca eskisini kapat (otomatik devralma).
                    try { current?.close() } catch (_: Exception) { }
                    val gen = ++generation
                    current = s
                    Thread({ handle(s, gen) }, "rx-conn").apply { isDaemon = true; start() }
                }
            } catch (_: Exception) {
                try { server?.close() } catch (_: Exception) { }
                if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun handle(s: Socket, gen: Int) {
        val q = LinkedBlockingQueue<Packet>()
        ctrl = q
        try {
            s.tcpNoDelay = true
            s.soTimeout = 6000
            s.keepAlive = true
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            Thread({
                val ping = Packet(Proto.T_PING, 0, 0, Proto.EMPTY)
                try {
                    while (gen == generation && !s.isClosed) {
                        val p = q.poll(1, TimeUnit.SECONDS) ?: ping
                        PacketIO.write(out, p)
                        out.flush()
                    }
                } catch (_: Exception) {
                    try { s.close() } catch (_: Exception) { }
                }
            }, "rx-ctrl").apply { isDaemon = true; start() }

            runOnUiThread { overlay.text = "Bağlandı, görüntü bekleniyor…" }
            synchronized(lock) { framesShown = 0 }
            audio.start()
            while (gen == generation) {
                val p = PacketIO.read(inp)
                when (p.type) {
                    Proto.T_FORMAT -> {
                        val (w, h) = PacketIO.parseFormat(p)
                        synchronized(lock) {
                            if (w != vw || h != vh) {
                                vw = w; vh = h
                                releaseDecoder()
                                lastCsd = null
                            }
                        }
                        runOnUiThread { fitSurface() }
                    }
                    Proto.T_CSD -> synchronized(lock) {
                        if (decoder == null || lastCsd == null || !lastCsd.contentEquals(p.data)) {
                            lastCsd = p.data
                            releaseDecoder()
                            createDecoder()
                        }
                    }
                    Proto.T_VIDEO -> synchronized(lock) { feed(p) }
                    Proto.T_AUDIO -> audio.push(p.data)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) { }
            if (gen == generation) {
                ctrl = null
                audio.stop()
                synchronized(lock) { releaseDecoder(); lastCsd = null }
                if (running) showWaiting()
            }
        }
    }

    private fun requestKey() {
        ctrl?.offer(Packet(Proto.T_REQ_KEY, 0, 0, Proto.EMPTY))
    }

    // ---------------- Video çözücü (lock altında çağrılır) ----------------

    private fun makeFormat(lowLatency: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(VideoEncoder.MIME, vw, vh).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max(vw * vh, 1 shl 20))
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (lowLatency && Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

    private fun createDecoder() {
        val s = surface ?: return
        val csd = lastCsd ?: return
        if (vw <= 0 || vh <= 0) return
        var d: MediaCodec? = null
        try {
            d = MediaCodec.createDecoderByType(VideoEncoder.MIME)
            try {
                d.configure(makeFormat(true), s, null, 0)
            } catch (e: Exception) {
                d.reset()
                d.configure(makeFormat(false), s, null, 0)
            }
            d.start()
            val idx = d.dequeueInputBuffer(200_000)
            if (idx >= 0) {
                val b = d.getInputBuffer(idx)!!
                b.clear()
                b.put(csd)
                d.queueInputBuffer(idx, 0, csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            decoder = d
            needKey = true
            requestKey()
        } catch (e: Exception) {
            try { d?.release() } catch (_: Exception) { }
            decoder = null
        }
    }

    private fun releaseDecoder() {
        val d = decoder ?: return
        decoder = null
        try { d.stop() } catch (_: Exception) { }
        try { d.release() } catch (_: Exception) { }
    }

    private val info = MediaCodec.BufferInfo()

    private fun feed(p: Packet) {
        val d = decoder ?: return
        val key = (p.flags and Proto.F_KEY) != 0
        if (needKey) {
            if (!key) return
            needKey = false
        }
        try {
            val idx = d.dequeueInputBuffer(40_000)
            if (idx < 0) {
                needKey = true
                requestKey()
            } else {
                val b = d.getInputBuffer(idx)!!
                b.clear()
                if (p.data.size > b.capacity()) {
                    d.queueInputBuffer(idx, 0, 0, p.pts, 0)
                    needKey = true
                    requestKey()
                } else {
                    b.put(p.data)
                    d.queueInputBuffer(idx, 0, p.data.size, p.pts, 0)
                }
            }
            drain(d)
        } catch (e: Exception) {
            releaseDecoder()
            createDecoder()
        }
    }

    private fun drain(d: MediaCodec) {
        var timeout = 4_000L
        while (true) {
            val idx = d.dequeueOutputBuffer(info, timeout)
            timeout = 0
            if (idx >= 0) {
                d.releaseOutputBuffer(idx, true)
                if (framesShown++ == 0L) runOnUiThread { overlay.visibility = View.GONE }
            } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }
    }

    // ---------------- Yüzey ----------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) {
            surface = holder.surface
            if (decoder == null && lastCsd != null) createDecoder()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) {
            releaseDecoder()
            surface = null
        }
    }

    /** Görüntüyü en-boy oranını koruyarak ekrana sığdırır. */
    private fun fitSurface() {
        val cw = root.width
        val ch = root.height
        val (w, h) = synchronized(lock) { vw to vh }
        if (cw == 0 || ch == 0 || w == 0 || h == 0) return
        val scale = minOf(cw.toFloat() / w, ch.toFloat() / h)
        val tw = (w * scale).toInt()
        val th = (h * scale).toInt()
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != tw || lp.height != th) {
            lp.width = tw
            lp.height = th
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }
}

/** Gecikmeyi düşük tutan ses çalar: kuyruk büyürse eski parçaları atar. */
class AudioPlayer {
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun push(b: ByteArray) {
        if (running) q.offer(b)
    }

    fun start() {
        if (running) return
        running = true
        q.clear()
        val th = Thread({
            var track: AudioTrack? = null
            try {
                val min = AudioTrack.getMinBufferSize(
                    Proto.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Proto.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(min * 2, 3840 * 3))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                val t = builder.build()
                track = t
                t.play()
                var primed = false
                val me = Thread.currentThread()
                while (running && thread === me) {
                    if (!primed) {
                        if (q.size < 2) {
                            Thread.sleep(5); continue
                        }
                        primed = true
                    }
                    val b = q.poll(200, TimeUnit.MILLISECONDS)
                    if (b == null) {
                        primed = false; continue
                    }
                    while (q.size > 8) q.poll()
                    t.write(b, 0, b.size)
                }
            } catch (_: Exception) {
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                track?.release()
            }
        }, "rx-audio")
        th.isDaemon = true
        thread = th
        th.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        q.clear()
    }
}
