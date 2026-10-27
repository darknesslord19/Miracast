package com.darknesslord.miracast

import android.Manifest
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_CAPTURE = 2
    }

    private lateinit var status: TextView
    private lateinit var tvGroup: RadioGroup
    private lateinit var tvEmpty: TextView
    private lateinit var manualIp: EditText
    private lateinit var q1080: RadioButton
    private lateinit var q720: RadioButton
    private lateinit var audioBox: CheckBox
    private lateinit var autoBox: CheckBox
    private lateinit var muteBox: CheckBox
    private lateinit var dimBox: CheckBox
    private lateinit var startBtn: Button
    private var finder: TvFinder? = null
    private var tvs: List<TvInfo> = emptyList()
    private var selectedHost: String? = null
    private var autoTried = false

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            // TV'de direkt alıcı modunda aç.
            startActivity(Intent(this, ReceiverActivity::class.java))
            finish()
            return
        }
        buildUi()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.rgb(24, 32, 48)); cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun label(text: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun buildUi() {
        val p = prefs()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        col.addView(label("Ekran Yansıt", 28f, true))
        col.addView(label("Telefon ekranını ve sesini TV'ye aktarır", 14f, color = Color.rgb(160, 180, 210)))

        status = label(SenderService.statusText, 16f, true, Color.rgb(120, 220, 160)).apply {
            setPadding(0, dp(14), 0, 0)
        }
        col.addView(status)

        startBtn = Button(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(14) }
            setOnClickListener { if (SenderService.running) stopMirror() else beginMirror() }
        }
        col.addView(startBtn)

        // TV listesi
        val tvCard = card()
        tvCard.addView(label("Bulunan TV'ler", 16f, true))
        tvEmpty = label("Aranıyor… TV'de Ekran Yansıt uygulaması açık olmalı ve aynı Wi‑Fi'da olmalı.",
            13f, color = Color.rgb(170, 170, 190))
        tvCard.addView(tvEmpty)
        tvGroup = RadioGroup(this).apply {
            setOnCheckedChangeListener { _, id -> selectedHost = tvs.getOrNull(id - 1)?.host }
        }
        tvCard.addView(tvGroup)
        manualIp = EditText(this).apply {
            hint = "veya TV IP adresini elle yaz (ör. 192.168.1.40)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(p.getString("manualIp", ""))
        }
        tvCard.addView(manualIp)
        col.addView(tvCard)

        // Ayarlar
        val setCard = card()
        setCard.addView(label("Kalite", 16f, true))
        val qGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        q1080 = RadioButton(this).apply { text = "Net (1080p 60fps)"; setTextColor(Color.WHITE); id = View.generateViewId() }
        q720 = RadioButton(this).apply { text = "Akıcı (720p)"; setTextColor(Color.WHITE); id = View.generateViewId() }
        qGroup.addView(q1080); qGroup.addView(q720)
        if (p.getInt("maxLong", 1920) >= 1920) q1080.isChecked = true else q720.isChecked = true
        setCard.addView(qGroup)

        audioBox = CheckBox(this).apply {
            text = if (Build.VERSION.SDK_INT >= 29) "Sesi TV'ye aktar" else "Sesi TV'ye aktar (Android 10+ gerekir)"
            setTextColor(Color.WHITE)
            isEnabled = Build.VERSION.SDK_INT >= 29
            isChecked = Build.VERSION.SDK_INT >= 29 && p.getBoolean("audio", true)
        }
        setCard.addView(audioBox)
        muteBox = CheckBox(this).apply {
            text = "Ses sadece TV'den çıksın (telefonu sustur)"
            setTextColor(Color.WHITE)
            isEnabled = Build.VERSION.SDK_INT >= 29
            isChecked = p.getBoolean("mutePhone", true)
        }
        setCard.addView(muteBox)
        dimBox = CheckBox(this).apply {
            text = "Telefon ekranını karart (ekran açık kalır, parlaklık en düşük)"
            setTextColor(Color.WHITE)
            isChecked = p.getBoolean("dim", false) && Settings.System.canWrite(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (on && !Settings.System.canWrite(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Parlaklık için “Sistem ayarlarını değiştir” iznini açın", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                    }
                }
            }
        }
        setCard.addView(dimBox)
        autoBox = CheckBox(this).apply {
            text = "Uygulama açılınca otomatik yansıt"
            setTextColor(Color.WHITE)
            isChecked = p.getBoolean("auto", false)
        }
        setCard.addView(autoBox)
        col.addView(setCard)

        val rxBtn = Button(this).apply {
            text = "Bu cihazı alıcı (TV) olarak kullan"
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
            setOnClickListener { startActivity(Intent(this@MainActivity, ReceiverActivity::class.java)) }
        }
        col.addView(rxBtn)

        col.addView(label(
            "İpuçları:\n• TV'de bu APK'yı kurup açın, ekranda “Hazır” yazar.\n" +
                "• İkisi aynı Wi‑Fi'da olsun; mümkünse 5 GHz.\n" +
                "• Bağlantı koparsa uygulama kendiliğinden yeniden bağlanır.\n" +
                "• Yansıtma sürerken telefon ekranı kendiliğinden kapanmaz.\n" +
                "• Güç tuşuna basıp telefonu kilitlemeyin: Android kilitli ekranın aktarılmasına izin vermez.\n" +
                "• Telefonda uygulamadan çıkabilirsiniz; yansıtma arka planda sürer.",
            13f, color = Color.rgb(160, 170, 190)
        ).apply { setPadding(0, dp(16), 0, 0) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12, 16, 26))
            fitsSystemWindows = true
            addView(col)
        }
        setContentView(scroll)
        refreshButton()
    }

    private fun savePrefs() {
        prefs().edit()
            .putString("manualIp", manualIp.text.toString().trim())
            .putInt("maxLong", if (q720.isChecked) 1280 else 1920)
            .putBoolean("audio", audioBox.isChecked)
            .putBoolean("auto", autoBox.isChecked)
            .putBoolean("mutePhone", muteBox.isChecked)
            .putBoolean("dim", dimBox.isChecked)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        SenderService.restoreLeftovers(this)
        if (dimBox.isChecked && !Settings.System.canWrite(this)) dimBox.isChecked = false
        SenderService.statusListener = { s -> status.text = s; refreshButton() }
        status.text = SenderService.statusText
        refreshButton()
        finder = TvFinder(this) { list -> runOnUiThread { showTvs(list) } }.also { it.start() }
        if (autoBox.isChecked && !SenderService.running && !autoTried) {
            autoTried = true
            status.postDelayed({ if (!SenderService.running) beginMirror() }, 1500)
        }
    }

    override fun onPause() {
        if (::status.isInitialized) {
            savePrefs()
            SenderService.statusListener = null
        }
        finder?.stop()
        finder = null
        super.onPause()
    }

    private fun showTvs(list: List<TvInfo>) {
        tvs = list
        tvGroup.setOnCheckedChangeListener(null)
        tvGroup.removeAllViews()
        val last = selectedHost ?: prefs().getString("lastHost", null)
        var checkedId = -1
        list.forEachIndexed { i, tv ->
            val rb = RadioButton(this).apply {
                id = i + 1
                text = "${tv.name}  (${tv.host})"
                setTextColor(Color.WHITE)
            }
            tvGroup.addView(rb)
            if (tv.host == last) checkedId = i
        }
        if (checkedId == -1 && list.isNotEmpty()) checkedId = 0
        if (checkedId >= 0) tvGroup.check(checkedId + 1)
        selectedHost = list.getOrNull(checkedId)?.host
        tvGroup.setOnCheckedChangeListener { _, id -> selectedHost = tvs.getOrNull(id - 1)?.host }
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshButton() {
        startBtn.text = if (SenderService.running) "■  Yansıtmayı Durdur" else "▶  Yansıtmayı Başlat"
        startBtn.background = GradientDrawable().apply {
            setColor(if (SenderService.running) Color.rgb(200, 60, 60) else Color.rgb(30, 120, 230))
            cornerRadius = dp(16).toFloat()
        }
        startBtn.setTextColor(Color.WHITE)
    }

    private fun beginMirror() {
        savePrefs()
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 29 && audioBox.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERM)
        } else {
            askCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) askCapture()
    }

    private fun askCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Tüm ekranı paylaş (tek uygulama seçeneği yerine).
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CAPTURE)
        } catch (e: Exception) {
            Toast.makeText(this, "Ekran yakalama başlatılamadı", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Ekran paylaşım izni verilmedi"
            return
        }
        val manual = manualIp.text.toString().trim()
        val host = manual.ifEmpty { null }
        selectedHost?.let { h ->
            val name = tvs.firstOrNull { it.host == h }?.name ?: h
            prefs().edit().putString("lastHost", h).putString("lastName", name).apply()
        }
        val audioOk = Build.VERSION.SDK_INT >= 29 && audioBox.isChecked &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val svc = Intent(this, SenderService::class.java).apply {
            action = SenderService.ACTION_START
            putExtra(SenderService.EXTRA_CODE, resultCode)
            putExtra(SenderService.EXTRA_DATA, data)
            putExtra(SenderService.EXTRA_HOST, host)
            putExtra(SenderService.EXTRA_MAX_LONG, if (q720.isChecked) 1280 else 1920)
            putExtra(SenderService.EXTRA_FPS, 60)
            putExtra(SenderService.EXTRA_AUDIO, audioOk)
            putExtra(SenderService.EXTRA_MUTE_PHONE, muteBox.isChecked)
            putExtra(SenderService.EXTRA_DIM, dimBox.isChecked)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        status.text = "Başlatılıyor…"
        status.postDelayed({ refreshButton() }, 400)
    }

    private fun stopMirror() {
        startService(Intent(this, SenderService::class.java).setAction(SenderService.ACTION_STOP))
        status.postDelayed({ refreshButton() }, 400)
    }
}
