package com.legion.controlcenter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private lateinit var controller: SystemController
    private lateinit var ravenAi: RavenAiEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var handleView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var hudOverlayView: ComposeView? = null

    private var showPerformanceHud by mutableStateOf(false)
    private var showFloatingToolWindow by mutableStateOf(false)
    private var showKeyMappingHud by mutableStateOf(false)
    private var showMacroClickerHud by mutableStateOf(false)
    private var eyeProtectionActive by mutableStateOf(false)
    private var showRavenDialog by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        controller = SystemController(this)
        ravenAi = RavenAiEngine(this, controller)

        ravenAi.onUserVoiceOrTextCallback = { spoken ->
            val apiKey = getSharedPreferences("legion_prefs", Context.MODE_PRIVATE)
                .getString("gemini_api_key", "") ?: ""
            serviceScope.launch {
                ravenAi.processCommand(spoken, apiKey)
            }
        }

        startForeground(101, buildNotification())
        attachTopSwipeHandle()
        attachHudOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_PANEL -> showPanel()
            ACTION_HIDE_PANEL -> hidePanel()
            ACTION_TOGGLE_PANEL -> if (isPanelShowing) hidePanel() else showPanel()
            ACTION_OPEN_RAVEN -> {
                showPanel()
                showRavenDialog = true
            }
        }
        return START_STICKY
    }

    private fun attachTopSwipeHandle() {
        if (handleView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            34,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 10f) {
                                    showPanel()
                                }
                            }
                        }
                        .clickable { showPanel() },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(140.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xAAFF1744))
                    )
                }
            }
        }
        handleView = view
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
        }
    }

    private fun attachHudOverlay() {
        if (hudOverlayView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                FloatingGameToolsHud()
            }
        }
        hudOverlayView = view
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
        }
    }

    @Composable
    private fun FloatingGameToolsHud() {
        var stats by remember { mutableStateOf(controller.readHardwareStats()) }
        var macroRunning by remember { mutableStateOf(false) }

        LaunchedEffect(showPerformanceHud, macroRunning) {
            while (showPerformanceHud || macroRunning) {
                stats = controller.readHardwareStats()
                if (macroRunning) {
                    LegionAccessibilityService.instance?.performTap(600f, 500f)
                }
                delay(if (macroRunning) 350L else 1500L)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (eyeProtectionActive) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xCC2A0905))
                        .border(1.dp, Color(0xFFFF6D00), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Eye Comfort Active", color = Color(0xFFFFAB40), fontSize = 11.sp)
                }
            }

            if (showPerformanceHud) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xDD120307))
                        .border(1.2.dp, Color(0xFFFF1744), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("${controller.getRefreshRate()} FPS", color = Color(0xFFFF1744), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${stats.tempCelsius}°C", color = Color.White, fontSize = 12.sp)
                    Text("${stats.fanRpm} RPM", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp).clickable { showPerformanceHud = false }
                    )
                }
            }

            if (showFloatingToolWindow) {
                Column(
                    modifier = Modifier
                        .width(210.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xEE140408))
                        .border(1.5.dp, Color(0xFFFF1744), RoundedCornerShape(16.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Legion Floating Box", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp).clickable { showFloatingToolWindow = false }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { controller.takeScreenshot() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Shot", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { showRavenDialog = true; showPanel() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF880E4F)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Raven AI", fontSize = 11.sp)
                        }
                    }
                }
            }

            if (showKeyMappingHud) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xDD1A040A))
                        .border(1.2.dp, Color(0xFFFF1744), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("KeyMap:", color = Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    listOf("L1", "R1", "A", "B", "X", "Y").forEach { key ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0x66FF1744))
                                .border(1.dp, Color.White, CircleShape)
                                .clickable {
                                    LegionAccessibilityService.instance?.performTap(700f, 550f)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(key, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.size(15.dp).clickable { showKeyMappingHud = false }
                    )
                }
            }

            if (showMacroClickerHud) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xDD1A040A))
                        .border(1.2.dp, Color(0xFFFF1744), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (macroRunning) "Macro: RUNNING" else "Macro: READY",
                        color = if (macroRunning) Color(0xFF00E676) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (macroRunning) "STOP" else "START",
                        color = Color(0xFFFF1744),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { macroRunning = !macroRunning }
                    )
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.size(15.dp).clickable {
                            macroRunning = false
                            showMacroClickerHud = false
                        }
                    )
                }
            }
        }
    }

    fun togglePerformanceMonitor() {
        showPerformanceHud = !showPerformanceHud
    }

    fun toggleFloatingWindow() {
        showFloatingToolWindow = !showFloatingToolWindow
    }

    fun toggleKeyMappingOverlay() {
        showKeyMappingHud = !showKeyMappingHud
    }

    fun toggleMacroOverlay() {
        showMacroClickerHud = !showMacroClickerHud
    }

    fun toggleEyeProtection() {
        eyeProtectionActive = !eyeProtectionActive
    }

    fun hidePanelTemporarilyForAction(action: () -> Unit) {
        hidePanel()
        mainHandler.postDelayed({ action() }, 260L)
    }

    fun showPanel() {
        if (panelView != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.65f
            gravity = Gravity.CENTER
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                LegionControlCenterRoot()
            }
        }
        panelView = view
        isPanelShowing = true
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
        }
    }

    fun hidePanel() {
        val v = panelView ?: return
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {
        }
        panelView = null
        isPanelShowing = false
    }

    @Composable
    private fun LegionControlCenterRoot() {
        val config = LocalConfiguration.current
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

        var wifiOn by remember { mutableStateOf(controller.isWifiEnabled()) }
        var btOn by remember { mutableStateOf(controller.isBluetoothEnabled()) }
        var airplaneOn by remember { mutableStateOf(controller.isAirplaneMode()) }
        var rotateOn by remember { mutableStateOf(controller.isAutoRotate()) }
        var dndOn by remember { mutableStateOf(controller.isDndEnabled()) }
        var locationOn by remember { mutableStateOf(controller.isLocationEnabled()) }
        var brightness by remember { mutableFloatStateOf(controller.getBrightness()) }
        var autoBright by remember { mutableStateOf(controller.isAutoBrightness()) }
        var volume by remember { mutableFloatStateOf(controller.getVolume()) }
        var perfMode by remember { mutableStateOf(controller.getPerformanceMode()) }
        var refreshRate by remember { mutableIntStateOf(controller.getRefreshRate()) }
        var hwStats by remember { mutableStateOf(controller.readHardwareStats()) }
        val mediaState by MediaNotificationListener.mediaState.collectAsState()

        LaunchedEffect(Unit) {
            MediaNotificationListener.instance?.refreshSessions()
            while (true) {
                hwStats = controller.readHardwareStats()
                wifiOn = controller.isWifiEnabled()
                btOn = controller.isBluetoothEnabled()
                rotateOn = controller.isAutoRotate()
                dndOn = controller.isDndEnabled()
                delay(1500L)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080103))
        ) {
            Image(
                painter = painterResource(id = R.drawable.legion_bg_clean),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.36f
            )

            val panelStartFraction = if (isLandscape) 0.34f else 0.37f
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(1f - panelStartFraction)
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                    .zIndex(5f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLandscape) 56.dp else 68.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.legion_header_ornament),
                        contentDescription = "Legion Header",
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier.align(Alignment.Center).fillMaxHeight()
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC1B0308))
                            .border(1.5.dp, Color(0xFFFF1744), RoundedCornerShape(50))
                            .clickable { showRavenDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.raven_avatar),
                            contentDescription = "Raven AI",
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .border(1.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = "Raven AI",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLandscape) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                QuickTogglesCard(
                                    wifiOn = wifiOn,
                                    btOn = btOn,
                                    airplaneOn = airplaneOn,
                                    rotateOn = rotateOn,
                                    dndOn = dndOn,
                                    locationOn = locationOn,
                                    eyeOn = eyeProtectionActive,
                                    onWifi = { controller.toggleWifi(); wifiOn = !wifiOn },
                                    onBt = { controller.toggleBluetooth(); btOn = !btOn },
                                    onAirplane = { controller.toggleAirplaneMode() },
                                    onRotate = { rotateOn = controller.toggleAutoRotate() },
                                    onDnd = { controller.toggleDnd(); dndOn = !dndOn },
                                    onHotspot = { controller.openHotspot() },
                                    onLocation = { controller.toggleLocation() },
                                    onNfc = { controller.openNfc() },
                                    onBatterySaver = { controller.toggleBatterySaver() },
                                    onDark = { controller.toggleDarkMode() },
                                    onEye = { toggleEyeProtection() },
                                    onScreenshot = { controller.takeScreenshot() },
                                    onRecord = { controller.startScreenRecord() },
                                    onCast = { controller.openScreenCast() },
                                    onSplit = { controller.toggleSplitScreen() }
                                )
                                SlidersSection(
                                    brightness = brightness,
                                    autoBright = autoBright,
                                    volume = volume,
                                    onBrightnessChange = { brightness = it; controller.setBrightness(it) },
                                    onToggleAutoBright = { autoBright = controller.toggleAutoBrightness() },
                                    onVolumeChange = { volume = it; controller.setVolume(it) },
                                    onOpenSound = { controller.openSoundPanel() }
                                )
                                MediaPlayerCard(mediaState = mediaState)
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                HardwareStatsSection(hwStats)
                                PerformanceModeCard(
                                    selected = perfMode,
                                    onSelect = {
                                        perfMode = it
                                        controller.setPerformanceMode(it)
                                        refreshRate = controller.getRefreshRate()
                                    }
                                )
                                RefreshRateCard(
                                    selectedHz = refreshRate,
                                    onSelectHz = { refreshRate = it; controller.setRefreshRate(it) }
                                )
                                GameToolsCard(
                                    fpsActive = showPerformanceHud,
                                    floatingActive = showFloatingToolWindow,
                                    keyMapActive = showKeyMappingHud,
                                    macroActive = showMacroClickerHud
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QuickTogglesCard(
                                wifiOn = wifiOn,
                                btOn = btOn,
                                airplaneOn = airplaneOn,
                                rotateOn = rotateOn,
                                dndOn = dndOn,
                                locationOn = locationOn,
                                eyeOn = eyeProtectionActive,
                                onWifi = { controller.toggleWifi(); wifiOn = !wifiOn },
                                onBt = { controller.toggleBluetooth(); btOn = !btOn },
                                onAirplane = { controller.toggleAirplaneMode() },
                                onRotate = { rotateOn = controller.toggleAutoRotate() },
                                onDnd = { controller.toggleDnd(); dndOn = !dndOn },
                                onHotspot = { controller.openHotspot() },
                                onLocation = { controller.toggleLocation() },
                                onNfc = { controller.openNfc() },
                                onBatterySaver = { controller.toggleBatterySaver() },
                                onDark = { controller.toggleDarkMode() },
                                onEye = { toggleEyeProtection() },
                                onScreenshot = { controller.takeScreenshot() },
                                onRecord = { controller.startScreenRecord() },
                                onCast = { controller.openScreenCast() },
                                onSplit = { controller.toggleSplitScreen() }
                            )
                            SlidersSection(
                                brightness = brightness,
                                autoBright = autoBright,
                                volume = volume,
                                onBrightnessChange = { brightness = it; controller.setBrightness(it) },
                                onToggleAutoBright = { autoBright = controller.toggleAutoBrightness() },
                                onVolumeChange = { volume = it; controller.setVolume(it) },
                                onOpenSound = { controller.openSoundPanel() }
                            )
                            HardwareStatsSection(hwStats)
                            PerformanceModeCard(
                                selected = perfMode,
                                onSelect = {
                                    perfMode = it
                                    controller.setPerformanceMode(it)
                                    refreshRate = controller.getRefreshRate()
                                }
                            )
                            RefreshRateCard(
                                selectedHz = refreshRate,
                                onSelectHz = { refreshRate = it; controller.setRefreshRate(it) }
                            )
                            GameToolsCard(
                                fpsActive = showPerformanceHud,
                                floatingActive = showFloatingToolWindow,
                                keyMapActive = showKeyMappingHud,
                                macroActive = showMacroClickerHud
                            )
                            MediaPlayerCard(mediaState = mediaState)
                        }
                    }
                }

                Image(
                    painter = painterResource(id = R.drawable.legion_footer_ornament),
                    contentDescription = "Legion Footer Ornament",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLandscape) 42.dp else 56.dp)
                )
            }

            Image(
                painter = painterResource(id = R.drawable.raven_character_full),
                contentDescription = "Raven Full Character Overlay",
                contentScale = ContentScale.FillHeight,
                alignment = Alignment.BottomStart,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (isLandscape) 0.42f else 0.46f)
                    .align(Alignment.BottomStart)
                    .zIndex(20f)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -18f) {
                                hidePanel()
                            }
                        }
                    }
            )

            AnimatedVisibility(
                visible = showRavenDialog,
                modifier = Modifier.align(Alignment.Center).zIndex(40f)
            ) {
                RavenVoiceAssistantDialog(onDismiss = { showRavenDialog = false })
            }
        }
    }

    @Composable
    private fun QuickTogglesCard(
        wifiOn: Boolean,
        btOn: Boolean,
        airplaneOn: Boolean,
        rotateOn: Boolean,
        dndOn: Boolean,
        locationOn: Boolean,
        eyeOn: Boolean,
        onWifi: () -> Unit,
        onBt: () -> Unit,
        onAirplane: () -> Unit,
        onRotate: () -> Unit,
        onDnd: () -> Unit,
        onHotspot: () -> Unit,
        onLocation: () -> Unit,
        onNfc: () -> Unit,
        onBatterySaver: () -> Unit,
        onDark: () -> Unit,
        onEye: () -> Unit,
        onScreenshot: () -> Unit,
        onRecord: () -> Unit,
        onCast: () -> Unit,
        onSplit: () -> Unit
    ) {
        NeonGothicCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NeonCircleToggle("Wi-Fi", Icons.Default.Wifi, wifiOn, onWifi)
                    NeonCircleToggle("Bluetooth", Icons.Default.Bluetooth, btOn, onBt)
                    NeonCircleToggle("Airplane\nMode", Icons.Default.Flight, airplaneOn, onAirplane)
                    NeonCircleToggle("Auto\nRotate", Icons.Default.ScreenRotation, rotateOn, onRotate)
                    NeonCircleToggle("Do Not\nDisturb", Icons.Default.NotificationsOff, dndOn, onDnd)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NeonCircleToggle("Hotspot", Icons.Default.WifiTethering, false, onHotspot)
                    NeonCircleToggle("Location", Icons.Default.LocationOn, locationOn, onLocation)
                    NeonCircleToggle("NFC", Icons.Default.Nfc, false, onNfc)
                    NeonCircleToggle("Battery\nSaver", Icons.Default.BatterySaver, false, onBatterySaver)
                    NeonCircleToggle("Dark\nMode", Icons.Default.DarkMode, true, onDark)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NeonCircleToggle("Eye\nProtection", Icons.Default.Visibility, eyeOn, onEye)
                    NeonCircleToggle("Screenshot", Icons.Default.CropFree, false, onScreenshot)
                    NeonCircleToggle("Screen\nRecord", Icons.Default.Videocam, false, onRecord)
                    NeonCircleToggle("Screen\nCast", Icons.Default.Cast, false, onCast)
                    NeonCircleToggle("Split\nScreen", Icons.Default.VerticalSplit, false, onSplit)
                }
            }
        }
    }

    @Composable
    private fun NeonCircleToggle(
        label: String,
        icon: ImageVector,
        active: Boolean,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(56.dp).clickable { onClick() }
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .shadow(if (active) 10.dp else 0.dp, CircleShape, spotColor = Color(0xFFFF1744))
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            Brush.radialGradient(
                                colors = listOf(Color(0xFFFF5252), Color(0xFFD50000), Color(0xFF7F0000))
                            )
                        } else {
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF24080E), Color(0xFF120307))
                            )
                        }
                    )
                    .border(
                        width = if (active) 1.8.dp else 1.dp,
                        color = if (active) Color(0xFFFF8A80) else Color(0x88FF5252),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }

    @Composable
    private fun SlidersSection(
        brightness: Float,
        autoBright: Boolean,
        volume: Float,
        onBrightnessChange: (Float) -> Unit,
        onToggleAutoBright: () -> Unit,
        onVolumeChange: (Float) -> Unit,
        onOpenSound: () -> Unit
    ) {
        NeonGothicCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LightMode, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFF1744),
                        inactiveTrackColor = Color(0xFF331016)
                    )
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (autoBright) Color(0xFFD50000) else Color(0xFF23080D))
                        .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(50))
                        .clickable { onToggleAutoBright() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("A Auto", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        NeonGothicCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFFFF1744),
                        inactiveTrackColor = Color(0xFF331016)
                    )
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF23080D))
                        .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(50))
                        .clickable { onOpenSound() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    @Composable
    private fun HardwareStatsSection(stats: HardwareStats) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonGothicCard(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color(0xFFFF1744), modifier = Modifier.size(22.dp))
                    Column {
                        Text("${stats.batteryPercent}% ⚡", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(if (stats.isCharging) "Charging" else "Battery", color = Color.LightGray, fontSize = 9.5.sp)
                    }
                }
            }
            NeonGothicCard(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Thermostat, contentDescription = null, tint = Color(0xFFFF1744), modifier = Modifier.size(22.dp))
                    Column {
                        Text("${stats.tempCelsius.roundToInt()}°C", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("System", color = Color.LightGray, fontSize = 9.5.sp)
                    }
                }
            }
            NeonGothicCard(modifier = Modifier.weight(1.15f)) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Cyclone, contentDescription = null, tint = Color(0xFFFF1744), modifier = Modifier.size(22.dp))
                    Column {
                        Text("${stats.fanRpm} RPM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        Text("Cooling", color = Color.LightGray, fontSize = 9.5.sp)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NeonGothicCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Storage", color = Color.LightGray, fontSize = 10.sp)
                    Text(
                        String.format(Locale.US, "%.1f / %.0f GB", stats.storageUsedGb, stats.storageTotalGb),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (stats.storageUsedGb / stats.storageTotalGb).coerceIn(0f, 1f) },
                        color = Color(0xFFFF1744),
                        trackColor = Color(0xFF331016),
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))
                    )
                }
            }
            NeonGothicCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("RAM", color = Color.LightGray, fontSize = 10.sp)
                    Text(
                        String.format(Locale.US, "%.1f / %.0f GB", stats.ramUsedGb, stats.ramTotalGb),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (stats.ramUsedGb / stats.ramTotalGb).coerceIn(0f, 1f) },
                        color = Color(0xFFFF1744),
                        trackColor = Color(0xFF331016),
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))
                    )
                }
            }
        }
    }

    @Composable
    private fun PerformanceModeCard(selected: String, onSelect: (String) -> Unit) {
        NeonGothicCard {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("Performance Mode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(">", color = Color.LightGray, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                val modes = listOf(
                    "Power Saving" to Icons.Default.Eco,
                    "Balanced" to Icons.Default.Speed,
                    "Performance" to Icons.Default.RocketLaunch,
                    "Extreme" to Icons.Default.Tornado
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modes.forEach { pair ->
                        val name = pair.first
                        val ic = pair.second
                        val active = selected == name
                        val displayName = if (name == "Power Saving") "Power\nSaving" else name
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (active) Brush.verticalGradient(listOf(Color(0xFFFF1744), Color(0xFF8E0000)))
                                    else Brush.verticalGradient(listOf(Color(0xFF21060B), Color(0xFF130306)))
                                )
                                .border(
                                    1.2.dp,
                                    if (active) Color(0xFFFF8A80) else Color(0x66FF5252),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelect(name) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(ic, contentDescription = name, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(
                                displayName,
                                color = Color.White,
                                fontSize = 9.5.sp,
                                lineHeight = 11.sp,
                                textAlign = TextAlign.Center,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RefreshRateCard(selectedHz: Int, onSelectHz: (Int) -> Unit) {
        NeonGothicCard {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("Display & Refresh Rate", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(">", color = Color.LightGray, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(60, 90, 120, 144).forEach { hz ->
                        val active = selectedHz == hz
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (active) Brush.horizontalGradient(listOf(Color(0xFFFF1744), Color(0xFFB71C1C)))
                                    else Brush.horizontalGradient(listOf(Color(0xFF21060B), Color(0xFF130306)))
                                )
                                .border(1.dp, if (active) Color.White else Color(0x66FF5252), RoundedCornerShape(50))
                                .clickable { onSelectHz(hz) }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$hz Hz", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    data class GameToolItem(
        val label: String,
        val icon: ImageVector,
        val active: Boolean,
        val action: () -> Unit
    )

    @Composable
    private fun GameToolsCard(
        fpsActive: Boolean,
        floatingActive: Boolean,
        keyMapActive: Boolean,
        macroActive: Boolean
    ) {
        NeonGothicCard {
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Gamepad, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text("Game Tools", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Text(">", color = Color.LightGray, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                val tools = listOf(
                    GameToolItem("Game\nAssistant", Icons.Default.GpsFixed, false) { controller.openGameAssistant() },
                    GameToolItem("Performance\nMonitor", Icons.Default.BarChart, fpsActive) { togglePerformanceMonitor() },
                    GameToolItem("Floating\nWindow", Icons.Default.ContentCopy, floatingActive) { toggleFloatingWindow() },
                    GameToolItem("Key\nMapping", Icons.Default.SportsEsports, keyMapActive) { toggleKeyMappingOverlay() },
                    GameToolItem("Macros", Icons.Default.Layers, macroActive) { toggleMacroOverlay() }
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    tools.forEach { tool ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (tool.active) Color(0xFFB71C1C) else Color(0xFF1A050A))
                                .border(1.dp, if (tool.active) Color.White else Color(0x66FF5252), RoundedCornerShape(12.dp))
                                .clickable { tool.action() }
                                .padding(vertical = 7.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(tool.icon, contentDescription = tool.label, tint = Color.White, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.height(3.dp))
                            Text(tool.label, color = Color.White, fontSize = 8.8.sp, lineHeight = 10.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MediaPlayerCard(mediaState: MediaState) {
        NeonGothicCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (mediaState.artwork != null) {
                    Image(
                        bitmap = mediaState.artwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.raven_media_thumb),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mediaState.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mediaState.artist,
                        color = Color.LightGray,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { mediaState.progress },
                        color = Color(0xFFFF1744),
                        trackColor = Color(0xFF331016),
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))
                    )
                }

                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).clickable { MediaNotificationListener.previous(this@OverlayService) }
                )
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFFFF5252), Color(0xFFB71C1C))))
                        .border(1.2.dp, Color.White, CircleShape)
                        .clickable { MediaNotificationListener.playPause(this@OverlayService) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (mediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).clickable { MediaNotificationListener.next(this@OverlayService) }
                )
            }
        }
    }

    @Composable
    private fun RavenVoiceAssistantDialog(onDismiss: () -> Unit) {
        val isListening by ravenAi.isListening.collectAsState()
        val statusText by ravenAi.statusText.collectAsState()
        val messages by ravenAi.messages.collectAsState()
        var textPrompt by remember { mutableStateOf("") }
        var voiceLang by remember { mutableStateOf("ar-EG") }

        Box(
            modifier = Modifier
                .width(360.dp)
                .heightIn(min = 320.dp, max = 480.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF5140308))
                .border(2.dp, Color(0xFFFF1744), RoundedCornerShape(24.dp))
                .padding(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.raven_avatar),
                            contentDescription = "Raven",
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, Color(0xFFFF1744), CircleShape)
                        )
                        Column {
                            Text("Raven AI (Gemini 3.5 Flash-Lite)", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            Text(statusText, color = Color(0xFFFF8A80), fontSize = 10.5.sp, maxLines = 1)
                        }
                    }
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp).clickable {
                            ravenAi.stopVoiceRecognition()
                            onDismiss()
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x88090103))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages) { msg ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (msg.isUser) Color(0x44FF1744) else Color(0xFF24070E))
                                .padding(8.dp)
                        ) {
                            Text(msg.sender, color = Color(0xFFFF8A80), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(msg.text, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2D0912))
                            .border(1.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
                            .clickable { voiceLang = if (voiceLang == "ar-EG") "en-US" else "ar-EG" }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(if (voiceLang == "ar-EG") "AR 🇪🇬" else "EN 🇺🇸", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = textPrompt,
                        onValueChange = { textPrompt = it },
                        placeholder = { Text("اكتب أو اضغط المايك...", fontSize = 11.sp, color = Color.Gray) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFF1744),
                            unfocusedBorderColor = Color(0x66FF5252)
                        )
                    )

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isListening) Color(0xFF00E676) else Color(0xFFD50000))
                            .border(1.5.dp, Color.White, CircleShape)
                            .clickable {
                                if (isListening) ravenAi.stopVoiceRecognition()
                                else ravenAi.startVoiceRecognition(voiceLang)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                            contentDescription = "Mic",
                            tint = Color.White
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF880E4F))
                            .border(1.dp, Color.White, CircleShape)
                            .clickable {
                                val q = textPrompt.trim()
                                if (q.isNotEmpty()) {
                                    textPrompt = ""
                                    ravenAi.onUserVoiceOrTextCallback?.invoke(q)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun NeonGothicCard(
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xD81A050A), Color(0xE60D0205))
                    )
                )
                .border(
                    width = 1.2.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFFFF1744), Color(0x88FF5252), Color(0xFFFF1744))
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
        ) {
            content()
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "legion_control_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Legion Control Center & Raven AI",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val toggleIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_TOGGLE_PANEL
        }
        val pendingToggle = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("Legion Control Center (Raven AI)")
            .setContentText("اضغط هنا أو اسحب من أعلى الشاشة لفتح اللوحة")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingToggle)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        hidePanel()
        handleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        hudOverlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        ravenAi.destroy()
        if (instance === this) instance = null
    }

    companion object {
        const val ACTION_SHOW_PANEL = "com.legion.controlcenter.SHOW_PANEL"
        const val ACTION_HIDE_PANEL = "com.legion.controlcenter.HIDE_PANEL"
        const val ACTION_TOGGLE_PANEL = "com.legion.controlcenter.TOGGLE_PANEL"
        const val ACTION_OPEN_RAVEN = "com.legion.controlcenter.OPEN_RAVEN"

        @Volatile
        var instance: OverlayService? = null
            private set

        @Volatile
        var isPanelShowing: Boolean = false
            private set
    }
}
