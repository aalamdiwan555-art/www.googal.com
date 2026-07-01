package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.data.models.ActionType
import com.example.data.models.ClickConfig
import com.example.data.models.ClickMode
import com.example.data.models.ClickType
import com.example.data.models.RecordedAction
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    
    // Main Panel Window
    private var mainComposeView: ComposeView? = null
    private var mainLifecycleOwner: MyLifecycleOwner? = null
    private lateinit var mainParams: WindowManager.LayoutParams

    // Target Crosshair Window
    private var targetComposeView: ComposeView? = null
    private var targetLifecycleOwner: MyLifecycleOwner? = null
    private lateinit var targetParams: WindowManager.LayoutParams

    // Sequence Target Windows
    private val sequenceViews = mutableListOf<ComposeView>()
    private val sequenceLifecycleOwners = mutableListOf<MyLifecycleOwner>()
    private val sequencePoints = mutableListOf<RecordedAction>()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settingsRepository: SettingsRepository

    companion object {
        private const val CHANNEL_ID = "autoclicker_fgs_channel"
        private const val NOTIFICATION_ID = 1002
        
        var isServiceRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository(this)

        createNotificationChannel()
        startForegroundServiceNotification()

        setupMainPanel()

        // Auto-start ClickEngine automation instantly
        serviceScope.launch {
            try {
                val config = settingsRepository.settingsFlow.first()
                if (!ClickEngine.isRunning.value) {
                    ClickEngine.start(this@FloatingWindowService, config)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker Pro Active")
            .setContentText("Floating control overlay is on. Tap to configure.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoClicker Control Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the auto-clicker overlay active."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun setupMainPanel() {
        mainLifecycleOwner = MyLifecycleOwner().apply { onCreate(); onStart(); onResume() }

        mainParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        mainComposeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(mainLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(mainLifecycleOwner)
            setContent {
                MainControlPanel()
            }
        }

        windowManager.addView(mainComposeView, mainParams)
    }

    private fun setupTargetCrosshair() {
        // Disabled per user request to keep screen clean and stealthy. No crosshairs will be shown.
    }

    private fun removeTargetCrosshair() {
        targetComposeView?.let {
            windowManager.removeView(it)
            targetLifecycleOwner?.onDestroy()
            targetComposeView = null
            targetLifecycleOwner = null
        }
    }

    private fun addSequencePoint() {
        val index = sequencePoints.size + 1
        val action = RecordedAction(
            type = ActionType.CLICK,
            x = 500f + (index * 40f),
            y = 1000f,
            durationMs = 50L,
            delayAfterMs = 500L
        )
        sequencePoints.add(action)

        val lifeOwner = MyLifecycleOwner().apply { onCreate(); onStart(); onResume() }
        sequenceLifecycleOwners.add(lifeOwner)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = action.x.toInt() - 28
            y = action.y.toInt() - 28
        }

        val seqView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(lifeOwner)
            setViewTreeSavedStateRegistryOwner(lifeOwner)
            setContent {
                SequencePointView(index = index, initialAction = action, onPositionChanged = { newX, newY ->
                    val idx = index - 1
                    if (idx in sequencePoints.indices) {
                        sequencePoints[idx] = sequencePoints[idx].copy(x = newX, y = newY)
                        // Sync with running ClickEngine if active
                        ClickEngine.updateSequence(sequencePoints.toList())
                    }
                })
            }
        }

        sequenceViews.add(seqView)
        windowManager.addView(seqView, params)
    }

    private fun clearSequencePoints() {
        for (v in sequenceViews) {
            try { windowManager.removeView(v) } catch (e: Exception) {}
        }
        for (lo in sequenceLifecycleOwners) {
            lo.onDestroy()
        }
        sequenceViews.clear()
        sequenceLifecycleOwners.clear()
        sequencePoints.clear()
    }

    override fun onDestroy() {
        isServiceRunning = false
        ClickEngine.stop()
        
        mainComposeView?.let {
            windowManager.removeView(it)
            mainLifecycleOwner?.onDestroy()
        }
        removeTargetCrosshair()
        clearSequencePoints()

        serviceScope.cancel()
        super.onDestroy()
    }

    @Composable
    fun MainControlPanel() {
        val infiniteTransition = rememberInfiniteTransition(label = "stealth_radar")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.8f,
            animationSpec = infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
            ),
            label = "pulseScale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
            ),
            label = "pulseAlpha"
        )

        val stats by RideAutomationLogger.stats.collectAsState()

        Column(
            modifier = Modifier
                .wrapContentSize()
                .shadow(6.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF01E1E28))
                .border(1.dp, Color(0xFF3A3A4A), RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        mainParams.x += dragAmount.x.toInt()
                        mainParams.y += dragAmount.y.toInt()
                        windowManager.updateViewLayout(mainComposeView, mainParams)
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = pulseAlpha }
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                }

                Text(
                    text = "⚡ RIDE RADAR",
                    color = Color(0xFF4CAF50),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                Box(modifier = Modifier.width(1.dp).height(14.dp).background(Color(0xFF3A3A4A)))

                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop",
                    tint = Color(0xFFEF5350),
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .clickable { stopSelf() }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip(label = "✅", value = "${stats.acceptCount}", color = Color(0xFF66BB6A))
                StatChip(label = "🚫", value = "${stats.rejectCount}", color = Color(0xFFEF5350))
                if (stats.totalEarnings > 0) {
                    StatChip(label = "₹", value = String.format("%.0f", stats.totalEarnings), color = Color(0xFFFFB74D))
                }
            }
        }
    }

    @Composable
    fun StatChip(label: String, value: String, color: Color) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(text = label, fontSize = 10.sp)
            Text(text = value, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun TargetCrosshairView() {
        Box(
            modifier = Modifier
                .size(80.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        targetParams.x += dragAmount.x.toInt()
                        targetParams.y += dragAmount.y.toInt()
                        windowManager.updateViewLayout(targetComposeView, targetParams)
                        
                        // Update click point (adding half target size to calculate center)
                        ClickEngine.targetPoint = PointF(
                            targetParams.x.toFloat() + 40f,
                            targetParams.y.toFloat() + 40f
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Ring
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FF9800))
                    .shadow(2.dp, CircleShape)
            )
            // Target icons
            Icon(
                imageVector = Icons.Default.Place,
                contentDescription = "Target pointer",
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(36.dp)
            )
            // Center pin
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color.Red)
            )
        }
    }

    @Composable
    fun SequencePointView(index: Int, initialAction: RecordedAction, onPositionChanged: (Float, Float) -> Unit) {
        val currentContext = LocalContext.current
        var x by remember { mutableStateOf(initialAction.x) }
        var y by remember { mutableStateOf(initialAction.y) }

        Box(
            modifier = Modifier
                .size(56.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // Since multiple overlays are added, we must update the specific view's position
                        val currentView = sequenceViews[index - 1]
                        val currentParams = currentView.layoutParams as WindowManager.LayoutParams
                        currentParams.x += dragAmount.x.toInt()
                        currentParams.y += dragAmount.y.toInt()
                        windowManager.updateViewLayout(currentView, currentParams)

                        onPositionChanged(
                            currentParams.x.toFloat() + 28f,
                            currentParams.y.toFloat() + 28f
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Numbered Circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xE63F51B5)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

// Custom mock lifecycle owner for Compose Overlay
class MyLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
