package com.example.appletvoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.platform.ViewTreeLifecycleOwner
import androidx.compose.ui.platform.ViewTreeSavedStateRegistryOwner

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var audioManager: AudioManager

    private val iosBlue = ComposeColor(0xFF0A84FF)
    private val iosGrey = ComposeColor(0xFF7C7C80)
    private val iosTile = ComposeColor(0xFF2E2E30)
    private val iosText = ComposeColor.White
    private val iosSubtle = ComposeColor(0xFFB0B0B5)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (composeView == null) showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        val view = ComposeView(this).apply {
            setContent { ControlCenterOverlay() }
            setViewTreeLifecycleOwner(this@OverlayService as LifecycleOwner)
            setViewTreeViewModelStoreOwner(this@OverlayService as ViewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(this@OverlayService as SavedStateRegistryOwner)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        windowManager.addView(view, params)
        composeView = view
    }

    @Composable
    private fun ControlCenterOverlay() {
        // The backdrop captures whatever is behind the overlay.
        // On Android 13+, this uses RenderEffect for real blur.
        // On older devices, it falls back to a translucent scrim.
        val backdrop = rememberLayerBackdrop()

        var wifiOn by remember { mutableStateOf(true) }
        var btOn by remember { mutableStateOf(true) }
        var airplaneOn by remember { mutableStateOf(false) }
        var hotspotOn by remember { mutableStateOf(false) }
        var focusModeOn by remember { mutableStateOf(false) }
        var rotationLockOn by remember { mutableStateOf(false) }

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var volumePct by remember {
            mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVol)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(0x99000000))
                .layerBackdrop(backdrop)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                        hideOverlay(); true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(48.dp) },
                        effects = {
                            vibrancy()
                            blur(30.dp.toPx())
                        },
                        highlight = { Highlight.Default },
                        shadow = { Shadow.Default },
                        onDrawSurface = { drawRect(ComposeColor(0xE61C1C1E)) }
                    )
                    .padding(22.dp)
            ) {
                // ROW 1: Connectivity + Music
                Row {
                    ConnectivityWidget(
                        wifiOn = wifiOn, btOn = btOn,
                        airplaneOn = airplaneOn, hotspotOn = hotspotOn,
                        onWifi = { wifiOn = !wifiOn; openWifiPanel() },
                        onBt = { btOn = !btOn },
                        onAirplane = { airplaneOn = !airplaneOn; openAirplane() },
                        onHotspot = { hotspotOn = !hotspotOn }
                    )
                    Spacer(Modifier.width(14.dp))
                    MusicWidget()
                }

                Spacer(Modifier.height(14.dp))

                // ROW 2: Sliders + right column
                Row {
                    GlassSlider(
                        label = "Brightness", glyph = "☀",
                        initialPct = 70,
                        onChange = { /* no-op on TV */ }
                    )
                    Spacer(Modifier.width(14.dp))
                    GlassSlider(
                        label = "Volume", glyph = "🔊",
                        initialPct = volumePct,
                        onChange = { pct ->
                            volumePct = pct
                            val target = (pct * maxVol / 100).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        }
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row {
                            SquareToggle(
                                glyph = "🔒", caption = "Rotation Lock",
                                isOn = { rotationLockOn },
                                toggle = { rotationLockOn = !rotationLockOn },
                                modifier = Modifier.weight(1f)
                            )
                            SquareToggle(
                                glyph = "🌙", caption = "Focus",
                                isOn = { focusModeOn },
                                toggle = { focusModeOn = !focusModeOn },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        ScreenMirroringBar(onClick = { openCastSettings() })
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ROW 3: Circular utility buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircleButton("🔦", "Flashlight") {}
                    CircleButton("⏱", "Timer") {}
                    CircleButton("🧮", "Calculator") {}
                    CircleButton("📷", "Camera") {}
                }
            }
        }
    }

    // ---------- Compose widgets using drawBackdrop ----------

    @Composable
    private fun ConnectivityWidget(
        wifiOn: Boolean, btOn: Boolean, airplaneOn: Boolean, hotspotOn: Boolean,
        onWifi: () -> Unit, onBt: () -> Unit, onAirplane: () -> Unit, onHotspot: () -> Unit
    ) {
        val backdrop = rememberLayerBackdrop()
        Column(
            modifier = Modifier
                .size(200.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(32.dp) },
                    effects = { blur(20.dp.toPx()) },
                    onDrawSurface = { drawRect(ComposeColor(0x662E2E30)) }
                )
                .padding(8.dp)
        ) {
            Row {
                GlassCircleToggle(88.dp, "⏶", wifiOn, onWifi)
                GlassCircleToggle(88.dp, "ᛒ", btOn, onBt)
            }
            Row {
                GlassCircleToggle(88.dp, "✈", airplaneOn, onAirplane)
                GlassCircleToggle(88.dp, "◉", hotspotOn, onHotspot)
            }
        }
    }

    @Composable
    private fun MusicWidget() {
        val backdrop = rememberLayerBackdrop()
        Row(
            modifier = Modifier
                .height(200.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(32.dp) },
                    effects = { blur(20.dp.toPx()); vibrancy() },
                    onDrawSurface = { drawRect(ComposeColor(0x662E2E30)) }
                )
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ComposeColor(0xFF3A3A3C)),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", fontSize = 48.sp, color = iosText)
            }
            Spacer(Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Not Playing", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = iosText)
                Text("—", fontSize = 16.sp, color = iosSubtle)
            }
            GlassCircleToggle(64.dp, "⏮", false) {}
            GlassCircleToggle(80.dp, "▶", false) {}
            GlassCircleToggle(64.dp, "⏭", false) {}
        }
    }

    @Composable
    private fun GlassCircleToggle(
        size: androidx.compose.ui.unit.Dp,
        glyph: String,
        isOn: Boolean,
        onClick: () -> Unit
    ) {
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .size(size)
                .padding(4.dp)
                .clip(CircleShape)
                .background(if (isOn) iosBlue else iosGrey)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                        onClick(); true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, fontSize = (size.value * 0.32f).sp, color = iosText)
            if (focused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(ComposeColor.Transparent)
                )
            }
        }
    }

    @Composable
    private fun GlassSlider(
        label: String,
        glyph: String,
        initialPct: Int,
        onChange: (Int) -> Unit
    ) {
        var pct by remember { mutableIntStateOf(initialPct.coerceIn(0, 100)) }
        val backdrop = rememberLayerBackdrop()
        var focused by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .width(96.dp)
                .height(300.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(44.dp) },
                    effects = { blur(24.dp.toPx()); vibrancy() },
                    onDrawSurface = { drawRect(ComposeColor(0x662E2E30)) }
                )
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.DirectionUp -> {
                            pct = (pct + 10).coerceAtMost(100); onChange(pct); true
                        }
                        event.key == Key.DirectionDown -> {
                            pct = (pct - 10).coerceAtLeast(0); onChange(pct); true
                        }
                        else -> false
                    }
                }
        ) {
            // Fill from bottom
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(pct / 100f)
                    .clip(RoundedCornerShape(44.dp))
                    .background(ComposeColor.White)
            )
            Text(
                glyph, fontSize = 28.sp, color = iosText,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
            )
        }
    }

    @Composable
    private fun SquareToggle(
        glyph: String, caption: String,
        isOn: () -> Boolean, toggle: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        var focused by remember { mutableStateOf(false) }
        Column(
            modifier = modifier
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                        toggle(); true
                    } else false
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isOn()) iosBlue else iosGrey),
                contentAlignment = Alignment.Center
            ) {
                Text(glyph, fontSize = 30.sp, color = iosText)
            }
            Spacer(Modifier.height(6.dp))
            Text(caption, fontSize = 13.sp, color = iosSubtle)
        }
    }

    @Composable
    private fun ScreenMirroringBar(onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(iosTile)
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                        onClick(); true
                    } else false
                }
                .padding(18.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⇄", fontSize = 22.sp, color = iosText)
            Spacer(Modifier.width(8.dp))
            Text("Screen Mirroring", fontSize = 16.sp, color = iosText)
        }
    }

    @Composable
    private fun CircleButton(glyph: String, caption: String, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .padding(10.dp)
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                        onClick(); true
                    } else false
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(iosTile),
                contentAlignment = Alignment.Center
            ) {
                Text(glyph, fontSize = 34.sp, color = iosText)
            }
            Spacer(Modifier.height(6.dp))
            Text(caption, fontSize = 12.sp, color = iosSubtle)
        }
    }

    // ---------- system helpers ----------

    private fun openWifiPanel() {
        try {
            startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun openAirplane() {
        try {
            startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun openCastSettings() {
        try {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        composeView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            composeView = null
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
