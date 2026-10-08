package com.example.appletvoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var audioManager: AudioManager

    // iOS 17 palette
    private val iosBlue   = Color.parseColor("#0A84FF")
    private val iosGrey   = Color.parseColor("#7C7C80")
    private val iosPanel  = Color.parseColor("#E61C1C1E")
    private val iosTile   = Color.parseColor("#2E2E30")
    private val iosScrim  = Color.parseColor("#99000000")
    private val iosText   = Color.WHITE
    private val iosSubtle = Color.parseColor("#B0B0B5")

    private var wifiOn = true
    private var btOn = true
    private var airplaneOn = false
    private var hotspotOn = false
    private var focusModeOn = false
    private var rotationLockOn = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) showOverlay()
        return START_STICKY
    }

    // ---------- drawing helpers ----------

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun focusRect(radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(3), Color.WHITE)
        }

    private fun focusCircle(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), Color.WHITE)
        }

    private fun layered(vararg d: android.graphics.drawable.Drawable) = LayerDrawable(d)

    private fun text(s: String, sp: Float, color: Int = iosText, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    // ---------- widgets ----------

    private fun buildCircleToggle(
        sizeDp: Int,
        icon: String,
        isOn: () -> Boolean,
        toggle: () -> Unit
    ): FrameLayout {
        val tile = FrameLayout(this)
        val lp = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        tile.layoutParams = lp
        tile.isFocusable = true
        tile.isFocusableInTouchMode = true
        tile.setOnClickListener { toggle() }

        val iconView = text(icon, 26f)
        tile.addView(iconView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        fun paint(focused: Boolean) {
            val bg = circle(if (isOn()) iosBlue else iosGrey)
            tile.background = if (focused) layered(bg, focusCircle()) else bg
        }
        paint(false)
        tile.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> paint(hasFocus) }
        return tile
    }

    private fun buildConnectivityWidget(): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(iosTile, 32)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val wifi = buildCircleToggle(88, "⏶", { wifiOn }) {
            wifiOn = !wifiOn
            try { startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        val bt = buildCircleToggle(88, "ᛒ", { btOn }) { btOn = !btOn }
        val air = buildCircleToggle(88, "✈", { airplaneOn }) {
            airplaneOn = !airplaneOn
            try { startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        val hotspot = buildCircleToggle(88, "◉", { hotspotOn }) { hotspotOn = !hotspotOn }

        row1.addView(wifi); row1.addView(bt)
        row2.addView(air); row2.addView(hotspot)
        grid.addView(row1); grid.addView(row2)

        grid.layoutParams = LinearLayout.LayoutParams(dp(200), dp(200)).apply { marginEnd = dp(14) }
        return grid
    }

    private fun buildMusicWidget(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(iosTile, 32)
        }
        card.layoutParams = LinearLayout.LayoutParams(0, dp(200), 1f)

        // Album art placeholder
        val art = TextView(this).apply {
            text = "♪"
            textSize = 48f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#3A3A3C"), 18)
        }
        val artLp = LinearLayout.LayoutParams(dp(150), dp(150))
        artLp.marginEnd = dp(18)
        card.addView(art, artLp)

        // Track info
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = text("Not Playing", 22f, bold = true).apply { gravity = Gravity.START }
        val artist = text("—", 16f, iosSubtle).apply { gravity = Gravity.START }
        info.addView(title)
        info.addView(artist)
        card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Transport controls
        val prev = buildCircleToggle(64, "⏮", { false }) {}
        val play = buildCircleToggle(80, "▶", { false }) {}
        val next = buildCircleToggle(64, "⏭", { false }) {}
        card.addView(prev); card.addView(play); card.addView(next)

        return card
    }

    private fun buildSlider(
        label: String,
        glyph: String,
        initialPct: Int,
        onChange: (Int) -> Unit
    ): FrameLayout {
        var pct = initialPct.coerceIn(0, 100)
        val heightDp = 300

        val container = FrameLayout(this).apply {
            background = rounded(iosTile, 44)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        container.layoutParams = LinearLayout.LayoutParams(dp(96), dp(heightDp))
            .apply { marginEnd = dp(14) }

        // Fill from bottom
        val fill = View(this)
        container.addView(fill)

        // Glyph at top
        val glyphView = text(glyph, 28f)
        container.addView(glyphView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(70)
        ).apply { gravity = Gravity.TOP })

        fun redraw(focused: Boolean) {
            val h = (heightDp * pct / 100).coerceAtLeast(2)
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(h)
            ).apply { gravity = Gravity.BOTTOM }
            fill.layoutParams = lp
            fill.background = rounded(Color.WHITE, 44)
            val base = rounded(iosTile, 44)
            container.background = if (focused) layered(base, focusRect(44)) else base
        }
        redraw(false)
        container.onFocusChangeListener = View.OnFocusChangeListener { _, f -> redraw(f) }

        container.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { pct = (pct + 10).coerceAtMost(100); redraw(true); onChange(pct); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { pct = (pct - 10).coerceAtLeast(0); redraw(true); onChange(pct); true }
                else -> false
            }
        }

        // small caption below glyph is skipped intentionally (clean look)

        return container
    }

    private fun buildSquareToggle(
        sizeDp: Int,
        glyph: String,
        caption: String,
        isOn: () -> Boolean,
        toggle: () -> Unit
    ): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { toggle() }
        }
        val tile = FrameLayout(this).apply {
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
        val glyphView = text(glyph, 30f)
        tile.addView(glyphView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val capView = text(caption, 13f, iosSubtle).apply {
            setPadding(0, dp(6), 0, 0)
        }

        fun paint(focused: Boolean) {
            val bg = rounded(if (isOn()) iosBlue else iosGrey, 22)
            tile.background = if (focused) layered(bg, focusRect(22)) else bg
        }
        paint(false)
        wrap.addView(tile)
        wrap.addView(capView)
        wrap.onFocusChangeListener = View.OnFocusChangeListener { _, f -> paint(f) }
        return wrap
    }

    private fun buildScreenMirroring(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(iosTile, 22)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_CAST_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }
        bar.addView(text("⇄", 22f))
        bar.addView(text("  Screen Mirroring", 16f))
        bar.onFocusChangeListener = View.OnFocusChangeListener { v, f ->
            v.background = if (f) layered(rounded(iosTile, 22), focusRect(22))
                           else rounded(iosTile, 22)
        }
        return bar
    }

    private fun buildCircleButton(glyph: String, caption: String, onClick: () -> Unit): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
        }
        val size = 100
        val holder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
        val g = text(glyph, 34f)
        holder.addView(g, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val cap = text(caption, 12f, iosSubtle).apply { setPadding(0, dp(6), 0, 0) }

        fun paint(f: Boolean) {
            val bg = circle(iosTile)
            holder.background = if (f) layered(bg, focusCircle()) else bg
        }
        paint(false)
        wrap.addView(holder)
        wrap.addView(cap)
        wrap.onFocusChangeListener = View.OnFocusChangeListener { _, f -> paint(f) }
        wrap.layoutParams = LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(10); marginEnd = dp(10) }
        return wrap
    }

    // ---------- overlay ----------

    private fun showOverlay() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(iosScrim)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                    hideOverlay(); true
                } else false
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = rounded(iosPanel, 48)
        }

        // Row 1: connectivity + music
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(buildConnectivityWidget())
        row1.addView(buildMusicWidget())
        panel.addView(row1)

        // Row 2: sliders + right column
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volPct = (currentVol * 100 / maxVol)

        row2.addView(buildSlider("Brightness", "☀", 70) { /* Brightness: no API without WRITE_SETTINGS */ })
        row2.addView(buildSlider("Volume", "🔊", volPct) { pct ->
            val target = (pct * maxVol / 100).coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        })

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val toggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rotLock = buildSquareToggle(96, "🔒", "Rotation Lock", { rotationLockOn }) { rotationLockOn = !rotationLockOn }
        val focusTile = buildSquareToggle(96, "🌙", "Focus", { focusModeOn }) { focusModeOn = !focusModeOn }
        val rotWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(rotLock) }
        rotWrap.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val focusWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(focusTile) }
        focusWrap.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        toggleRow.addView(rotWrap); toggleRow.addView(focusWrap)
        rightCol.addView(toggleRow)

        val mirror = buildScreenMirroring()
        val mirrorLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
        rightCol.addView(mirror, mirrorLp)
        row2.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row2)

        // Row 3: 4 circular buttons
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        row3.addView(buildCircleButton("🔦", "Flashlight") {})
        row3.addView(buildCircleButton("⏱", "Timer") {})
        row3.addView(buildCircleButton("🧮", "Calculator") {})
        row3.addView(buildCircleButton("📷", "Camera") {})
        panel.addView(row3)

        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(root, params)
        overlayView = root

        // grab focus on first focusable child so D-pad works immediately
        root.post {
            val first = findFirstFocusable(panel)
            first?.requestFocus()
        }
    }

    private fun findFirstFocusable(v: View): View? {
        if (v.isFocusable && v !is android.view.ViewGroup) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                val f = findFirstFocusable(v.getChildAt(i))
                if (f != null) return f
            }
        }
        return null
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
        stopSelf()
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("overlay", "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, "overlay")
            .setContentTitle("AppleTV Overlay")
            .setContentText("Control center is running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
}
