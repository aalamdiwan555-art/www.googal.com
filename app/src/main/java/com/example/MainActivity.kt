package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import kotlin.random.Random
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import coil.compose.AsyncImage
import com.example.service.RideAutomationLogger
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.models.ClickConfig
import com.example.data.models.ClickMode
import com.example.data.models.ClickType
import com.example.data.models.PriceConfig
import com.example.data.models.TemplateItem
import androidx.compose.ui.text.TextStyle
import com.example.service.ClickEngine
import com.example.service.FloatingWindowService
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AutoClickerMainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoClickerMainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()

    val settings by viewModel.settingsState.collectAsState()

    // Live permission check polling
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasAccessibilityPermission by remember { mutableStateOf(false) }
    var isOverlayServiceRunning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            hasOverlayPermission = viewModel.isOverlayPermissionEnabled(context)
            hasAccessibilityPermission = viewModel.isAccessibilityServiceEnabled(context)
            isOverlayServiceRunning = FloatingWindowService.isServiceRunning
            delay(1000)
        }
    }

    LaunchedEffect(hasOverlayPermission, hasAccessibilityPermission) {
        if (hasOverlayPermission && hasAccessibilityPermission && !FloatingWindowService.isServiceRunning) {
            try {
                val intent = Intent(context, FloatingWindowService::class.java)
                context.startService(intent)
                viewModel.updatePriceConfig(settings.priceConfig.copy(enabled = true))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    PriceFilterConfigView(
        config = settings,
        viewModel = viewModel,
        hasOverlayPermission = hasOverlayPermission,
        hasAccessibilityPermission = hasAccessibilityPermission,
        isOverlayServiceRunning = isOverlayServiceRunning,
        modifier = modifier
    )
}

@Composable
fun EngineConfigView(config: ClickConfig, viewModel: MainViewModel) {
    var intervalText by remember { mutableStateOf(config.intervalMs.toString()) }
    var maxClicksText by remember { mutableStateOf(config.maxClicks.toString()) }
    var clickMode by remember { mutableStateOf(config.clickMode) }
    var clickType by remember { mutableStateOf(config.clickType) }
    var targetText by remember { mutableStateOf(config.targetText ?: "") }
    var targetIdText by remember { mutableStateOf(config.targetViewId ?: "") }
    var randomize by remember { mutableStateOf(config.randomizeInterval) }
    var stopScreenOff by remember { mutableStateOf(config.stopOnScreenOff) }

    LaunchedEffect(config) {
        intervalText = config.intervalMs.toString()
        maxClicksText = config.maxClicks.toString()
        clickMode = config.clickMode
        clickType = config.clickType
        targetText = config.targetText ?: ""
        targetIdText = config.targetViewId ?: ""
        randomize = config.randomizeInterval
        stopScreenOff = config.stopOnScreenOff
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "CLICK SETTINGS",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Click Interval & Count
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Click Speed & Repeats",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = {
                                intervalText = it
                                val longVal = it.toLongOrNull() ?: 500L
                                viewModel.updateSettings(config.copy(intervalMs = longVal))
                            },
                            label = { Text("Interval (ms)", color = Color.Gray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFFF9800),
                                unfocusedBorderColor = Color.DarkGray
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = maxClicksText,
                            onValueChange = {
                                maxClicksText = it
                                val intVal = it.toIntOrNull() ?: 0
                                viewModel.updateSettings(config.copy(maxClicks = intVal))
                            },
                            label = { Text("Max Clicks (0=unlim)", color = Color.Gray) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFFF9800),
                                unfocusedBorderColor = Color.DarkGray
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = randomize,
                                onCheckedChange = {
                                    randomize = it
                                    viewModel.updateSettings(config.copy(randomizeInterval = it))
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF9800))
                            )
                            Text("Randomize (anti-detect)", color = Color.White, fontSize = 12.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = stopScreenOff,
                                onCheckedChange = {
                                    stopScreenOff = it
                                    viewModel.updateSettings(config.copy(stopOnScreenOff = it))
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF9800))
                            )
                            Text("Stop Screen Off", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Clicking Mode Selection
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Automation Mode",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ClickMode.values().forEach { mode ->
                        val isSelected = clickMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0x11FF9800) else Color.Transparent)
                                .clickable {
                                    clickMode = mode
                                    viewModel.updateSettings(config.copy(clickMode = mode))
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    clickMode = mode
                                    viewModel.updateSettings(config.copy(clickMode = mode))
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFF9800))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = when (mode) {
                                        ClickMode.FIXED_POINT -> "📌 Fixed Position"
                                        ClickMode.FOLLOW_CURSOR -> "🎯 Follow Cursor"
                                        ClickMode.TARGET_ELEMENT -> "🔍 Target Accessibility Element"
                                        ClickMode.SEQUENCE -> "🔁 Pre-Recorded Sequence"
                                    },
                                    color = if (isSelected) Color(0xFFFF9800) else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = when (mode) {
                                        ClickMode.FIXED_POINT -> "Clicks at exact coords designated by crosshair target overlay."
                                        ClickMode.FOLLOW_CURSOR -> "Clicks wherever the last position was set or focused."
                                        ClickMode.TARGET_ELEMENT -> "Locates view elements by text or ID using accessibility inspection."
                                        ClickMode.SEQUENCE -> "Cycles through a recorded pattern of multi-point clicks."
                                    },
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Custom inputs for target elements
        if (clickMode == ClickMode.TARGET_ELEMENT) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Target Element Criteria",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = targetText,
                            onValueChange = {
                                targetText = it
                                viewModel.updateSettings(config.copy(targetText = if (it.isBlank()) null else it))
                            },
                            label = { Text("Match Element Text", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFFF9800),
                                unfocusedBorderColor = Color.DarkGray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = targetIdText,
                            onValueChange = {
                                targetIdText = it
                                viewModel.updateSettings(config.copy(targetViewId = if (it.isBlank()) null else it))
                            },
                            label = { Text("Match Element Resource ID", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFFF9800),
                                unfocusedBorderColor = Color.DarkGray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Click Gesture Type
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Click Type Trigger",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ClickType.values().forEach { type ->
                            val isSelected = clickType == type
                            Button(
                                onClick = {
                                    clickType = type
                                    viewModel.updateSettings(config.copy(clickType = type))
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) Color(0xFFFF9800) else Color(0xFF2E2E38)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text(
                                    text = when (type) {
                                        ClickType.SINGLE -> "Single Click"
                                        ClickType.DOUBLE -> "Double Click"
                                        ClickType.LONG_PRESS -> "Long Press"
                                    },
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PriceFilterConfigView(
    config: ClickConfig,
    viewModel: MainViewModel,
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    isOverlayServiceRunning: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isEngineRunning by ClickEngine.isRunning.collectAsState()

    var isIgnoringBatteryOptimizations by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
            delay(1000)
        }
    }

    var minPriceText by remember { mutableStateOf(config.priceConfig.minPrice.toString()) }
    var symbol by remember { mutableStateOf(config.priceConfig.currencySymbol) }
    var acceptButtonKeywordsText by remember { mutableStateOf(config.priceConfig.acceptButtonKeywords) }
    var pickupKeywords by remember { mutableStateOf(config.priceConfig.pickupKeywords) }
    var dropKeywords by remember { mutableStateOf(config.priceConfig.dropKeywords) }
    var useTemplateMatching by remember { mutableStateOf(config.priceConfig.useTemplateMatching) }

    var minPickupText by remember { mutableStateOf(config.priceConfig.minPickupDistance.toString()) }
    var maxPickupText by remember { mutableStateOf(config.priceConfig.maxPickupDistance.toString()) }
    var minDropText by remember { mutableStateOf(config.priceConfig.minDropDistance.toString()) }
    var maxDropText by remember { mutableStateOf(config.priceConfig.maxDropDistance.toString()) }

    var minDelayText by remember { mutableStateOf(config.priceConfig.randomClickDelayMinMs.toString()) }
    var maxDelayText by remember { mutableStateOf(config.priceConfig.randomClickDelayMaxMs.toString()) }
    var maxPriceText by remember { mutableStateOf(if (config.priceConfig.maxPrice == Double.MAX_VALUE) "" else config.priceConfig.maxPrice.toString()) }
    var vibrationEnabled by remember { mutableStateOf(config.priceConfig.vibrationTriggerEnabled) }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    fun keepOnlyNumbersAndDecimal(input: String): String {
        val stripped = input.filter { it.isDigit() || it == '.' }
        val dotCount = stripped.count { it == '.' }
        return if (dotCount > 1) {
            val firstDotIdx = stripped.indexOf('.')
            stripped.filterIndexed { idx, c -> c.isDigit() || idx == firstDotIdx }
        } else {
            stripped
        }
    }

    fun keepOnlyNumbers(input: String): String {
        return input.filter { it.isDigit() }
    }

    val templates = remember(config.priceConfig.templatesSerialized) {
        config.priceConfig.getTemplates()
    }

    var showOnboardingDialog by remember { mutableStateOf(false) }

    LaunchedEffect(hasOverlayPermission, hasAccessibilityPermission) {
        if (!hasOverlayPermission || !hasAccessibilityPermission) {
            showOnboardingDialog = true
        }
    }

    LaunchedEffect(config) {
        minPriceText = config.priceConfig.minPrice.toString()
        symbol = config.priceConfig.currencySymbol
        acceptButtonKeywordsText = config.priceConfig.acceptButtonKeywords
        pickupKeywords = config.priceConfig.pickupKeywords
        dropKeywords = config.priceConfig.dropKeywords
        useTemplateMatching = config.priceConfig.useTemplateMatching
        minPickupText = config.priceConfig.minPickupDistance.toString()
        maxPickupText = config.priceConfig.maxPickupDistance.toString()
        minDropText = config.priceConfig.minDropDistance.toString()
        maxDropText = config.priceConfig.maxDropDistance.toString()
        minDelayText = config.priceConfig.randomClickDelayMinMs.toString()
        maxDelayText = config.priceConfig.randomClickDelayMaxMs.toString()
        maxPriceText = if (config.priceConfig.maxPrice == Double.MAX_VALUE) "" else config.priceConfig.maxPrice.toString()
        vibrationEnabled = config.priceConfig.vibrationTriggerEnabled
    }

    fun updateTemplates(newList: List<TemplateItem>) {
        val serialized = newList.joinToString("||") { "${it.imagePath}::${it.buttonText}" }
        viewModel.updatePriceConfig(
            config.priceConfig.copy(templatesSerialized = serialized, useTemplateMatching = newList.isNotEmpty())
        )
    }

    // Image Upload Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val uniqueId = System.currentTimeMillis()
            val fileName = "template_$uniqueId.png"
            val path = copyUriToInternalStorage(context, uri, fileName)
            if (path != null) {
                val updatedList = templates + TemplateItem(imagePath = path, buttonText = "")
                updateTemplates(updatedList)
                Toast.makeText(context, "New template added successfully!", Toast.LENGTH_SHORT).show()
                RideAutomationLogger.log("📸 Added new accept button template: $path")
            } else {
                Toast.makeText(context, "Failed to save template image.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showOnboardingDialog) {
        AlertDialog(
            onDismissRequest = { showOnboardingDialog = false },
            title = {
                Text(
                    text = "System Setup Required 🛠️",
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "To start auto-scanning screen details and matching rides, please grant the following permissions:",
                        color = Color.DarkGray,
                        fontSize = 13.sp
                    )

                    // Overlay Status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (hasOverlayPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (hasOverlayPermission) Color(0xFF2E7D32) else Color(0xFFFF9800),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("1. Screen Overlay Permission", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (hasOverlayPermission) "Status: Enabled" else "Status: Missing (Tap to grant)",
                                color = if (hasOverlayPermission) Color(0xFF2E7D32) else Color.DarkGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Accessibility Status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (hasAccessibilityPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (hasAccessibilityPermission) Color(0xFF2E7D32) else Color(0xFFFF9800),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("2. Accessibility Click Engine Service", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (hasAccessibilityPermission) "Status: Enabled" else "Status: Missing (Tap to grant)",
                                color = if (hasAccessibilityPermission) Color(0xFF2E7D32) else Color.DarkGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Battery Optimization Status (Optional)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isIgnoringBatteryOptimizations) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isIgnoringBatteryOptimizations) Color(0xFF2E7D32) else Color(0xFF3F51B5),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("3. Disable Battery Optimization", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (isIgnoringBatteryOptimizations) "Status: Optimized" else "Status: Highly Recommended for background durability",
                                color = if (isIgnoringBatteryOptimizations) Color(0xFF2E7D32) else Color.DarkGray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!hasOverlayPermission) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else if (!hasAccessibilityPermission) {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        } else if (!isIgnoringBatteryOptimizations) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (ex: Exception) {
                                        showOnboardingDialog = false
                                    }
                                }
                            } else {
                                showOnboardingDialog = false
                            }
                        } else {
                            showOnboardingDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text(
                        text = if (!hasOverlayPermission) "Grant Overlay" else if (!hasAccessibilityPermission) "Grant Accessibility" else if (!isIgnoringBatteryOptimizations) "Disable Battery Opt" else "All Done!",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showOnboardingDialog = false }) {
                    Text("Skip Setup", color = Color.DarkGray)
                }
            },
            containerColor = Color.White
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "enchanted")
    val pulseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgFraction"
    )
    val startColor = Color(0xFFFAF6F0)
    val stopColor = Color(0xFFFCFAF7)
    val pulseBgColor = Color(
        red = startColor.red + (stopColor.red - startColor.red) * pulseFraction,
        green = startColor.green + (stopColor.green - startColor.green) * pulseFraction,
        blue = startColor.blue + (stopColor.blue - startColor.blue) * pulseFraction,
        alpha = startColor.alpha + (stopColor.alpha - startColor.alpha) * pulseFraction
    )

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .background(pulseBgColor)
            .padding(16.dp)
    ) {
        // App Hero Header Card
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 0)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFFFF4E5), Color(0xFFFDFBF7))
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .border(1.dp, Color(0xFFFFCC80), RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ride Automation Console",
                                color = Color.Black,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                            Text(
                                text = "Intelligent Ride Scanner & Auto-Acceptor",
                                color = Color(0xFFE65100),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (isOverlayServiceRunning && isEngineRunning) {
                            // Spinning active scan pulse decoration
                            val angle by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(3000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "radarRotation"
                            )
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Scanning Animation",
                                tint = Color(0xFFFF9800),
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer {
                                        rotationZ = angle
                                    }
                            )
                        }
                    }
                }
            }
        }

        // Session Stats Dashboard
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 80)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                val sessionStats by com.example.service.RideAutomationLogger.stats.collectAsState()
                var tickMs by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        tickMs = System.currentTimeMillis()
                    }
                }
                val uptimeSecs = (tickMs - sessionStats.sessionStartMs) / 1000
                val uptimeStr = if (uptimeSecs < 60) "${uptimeSecs}s"
                    else if (uptimeSecs < 3600) "${uptimeSecs / 60}m ${uptimeSecs % 60}s"
                    else "${uptimeSecs / 3600}h ${(uptimeSecs % 3600) / 60}m"

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF1A2744), Color(0xFF0D1B36))
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .border(1.dp, Color(0xFF2A3F6F), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📊 SESSION STATISTICS",
                                color = Color(0xFF90CAF9),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⏱ $uptimeStr",
                                    color = Color(0xFF78909C),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                TextButton(
                                    onClick = { com.example.service.RideAutomationLogger.resetSession() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Reset", color = Color(0xFF78909C), fontSize = 10.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatBox(
                                icon = "✅",
                                label = "Accepted",
                                value = "${sessionStats.acceptCount}",
                                valueColor = Color(0xFF66BB6A)
                            )
                            Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color(0xFF2A3F6F)))
                            StatBox(
                                icon = "🚫",
                                label = "Rejected",
                                value = "${sessionStats.rejectCount}",
                                valueColor = Color(0xFFEF5350)
                            )
                            Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color(0xFF2A3F6F)))
                            StatBox(
                                icon = "💰",
                                label = "Earnings",
                                value = if (sessionStats.totalEarnings > 0) "₹${String.format("%.0f", sessionStats.totalEarnings)}" else "₹0",
                                valueColor = Color(0xFFFFB74D)
                            )
                            Box(modifier = Modifier.width(1.dp).height(40.dp).background(Color(0xFF2A3F6F)))
                            StatBox(
                                icon = "🎯",
                                label = "Hit Rate",
                                value = run {
                                    val total = sessionStats.acceptCount + sessionStats.rejectCount
                                    if (total == 0) "—" else "${(sessionStats.acceptCount * 100 / total)}%"
                                },
                                valueColor = Color(0xFF80DEEA)
                            )
                        }
                        if (sessionStats.lastAcceptedPrice > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Last accepted: ₹${String.format("%.2f", sessionStats.lastAcceptedPrice)}",
                                color = Color(0xFF4DB6AC),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 1st: Start switch
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 100)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (isOverlayServiceRunning) Color(0xFFFF9800) else Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isOverlayServiceRunning) {
                                        val ringScale by infiniteTransition.animateFloat(
                                            initialValue = 1.0f,
                                            targetValue = 2.5f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(1500, easing = LinearOutSlowInEasing),
                                                repeatMode = RepeatMode.Restart
                                            ),
                                            label = "glowingRingScale"
                                        )
                                        val ringAlpha by infiniteTransition.animateFloat(
                                            initialValue = 0.8f,
                                            targetValue = 0.0f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(1500, easing = LinearOutSlowInEasing),
                                                repeatMode = RepeatMode.Restart
                                            ),
                                            label = "glowingRingAlpha"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .graphicsLayer {
                                                    scaleX = ringScale
                                                    scaleY = ringScale
                                                    alpha = ringAlpha
                                                }
                                                .clip(CircleShape)
                                                .background(Color(0xFF2E7D32))
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isOverlayServiceRunning) Color(0xFF2E7D32) else Color.Red)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "1. Ride Automation Service",
                                        color = Color.Black,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (isOverlayServiceRunning) "Status: ACTIVE (Draggable helper is visible)" else "Status: INACTIVE (Enable floating helper)",
                                        color = Color.DarkGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Switch(
                                checked = isOverlayServiceRunning,
                                onCheckedChange = { start ->
                                    if (start) {
                                        if (!hasOverlayPermission) {
                                            Toast.makeText(context, "Grant Screen Overlay Permission first", Toast.LENGTH_SHORT).show()
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                            return@Switch
                                        }
                                        if (!hasAccessibilityPermission) {
                                            Toast.makeText(context, "Enable Accessibility Click Engine Service first", Toast.LENGTH_LONG).show()
                                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            context.startActivity(intent)
                                            return@Switch
                                        }

                                        val intent = Intent(context, FloatingWindowService::class.java)
                                        context.startService(intent)
                                        viewModel.updatePriceConfig(config.priceConfig.copy(enabled = true))
                                    } else {
                                        val intent = Intent(context, FloatingWindowService::class.java)
                                        context.stopService(intent)
                                        viewModel.updatePriceConfig(config.priceConfig.copy(enabled = false))
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF9800))
                            )
                        }
                    }
                }
            }
        }

        // Live Screen & Logs Monitor Console
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 120)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                val liveScreenText by com.example.service.AutoClickService.lastScannedText.collectAsState()
                val liveLogs by RideAutomationLogger.logs.collectAsState()

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF3A3A42))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val monitorScale by infiniteTransition.animateFloat(
                                    initialValue = 1.0f,
                                    targetValue = 1.8f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearOutSlowInEasing),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "monitorDotScale"
                                )
                                val monitorAlpha by infiniteTransition.animateFloat(
                                    initialValue = 1.0f,
                                    targetValue = 0.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1200, easing = LinearOutSlowInEasing),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "monitorDotAlpha"
                                )
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .graphicsLayer {
                                                scaleX = monitorScale
                                                scaleY = monitorScale
                                                alpha = monitorAlpha
                                            }
                                            .clip(CircleShape)
                                            .background(Color(0xFF4CAF50))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF4CAF50))
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "⚡ LIVE MONITOR & LOGS",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            
                            // Clear logs button
                            Text(
                                text = "CLEAR LOGS",
                                color = Color(0xFFFF9800),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { RideAutomationLogger.clear() }
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Selected Monitor Tab/Section
                        var selectedTab by remember { mutableStateOf(0) } // 0 = Live Logs, 1 = Scanned Screen Text
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2C2C35), RoundedCornerShape(8.dp))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selectedTab == 0) Color(0xFF3F51B5) else Color.Transparent)
                                    .clickable { selectedTab = 0 }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Live Logs (${liveLogs.size})",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selectedTab == 1) Color(0xFF3F51B5) else Color.Transparent)
                                    .clickable { selectedTab = 1 }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Current Screen Text",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0D0D10))
                                .border(1.dp, Color(0xFF2C2C35), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            if (selectedTab == 0) {
                                if (liveLogs.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No logs yet. Waiting for incoming ride offers...",
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(liveLogs) { log ->
                                            Text(
                                                text = log,
                                                color = if (log.contains("✅")) Color(0xFF81C784) 
                                                        else if (log.contains("🚫") || log.contains("❌")) Color(0xFFE57373)
                                                        else if (log.contains("⚡") || log.contains("📳")) Color(0xFFFFB74D)
                                                        else Color.LightGray,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            } else {
                                if (liveScreenText.isBlank()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Screen content is empty or Accessibility service is off.\nNavigate to target app to start scanning.",
                                            color = Color.Gray,
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        item {
                                            Text(
                                                text = liveScreenText,
                                                color = Color(0xFF80DEEA),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Battery Optimization Card
        if (!isIgnoringBatteryOptimizations) {
            item {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(animationSpec = tween(500, delayMillis = 150)) + slideInVertically(
                        initialOffsetY = { 40 },
                        animationSpec = spring(stiffness = Spring.StiffnessLow)
                    )
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFFFB74D))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Ignore Battery Optimizations",
                                        color = Color.Black,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Highly recommended to keep the auto-clicker scanner background engine running reliably.",
                                        color = Color.DarkGray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        try {
                                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                                context.startActivity(intent)
                                            } catch (ex: Exception) {
                                                Toast.makeText(context, "Please disable battery optimization manually.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                            ) {
                                Text("Disable", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2nd: Pickup Distance Filter (Km)
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 200)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "2. Pickup Distance Filter (Km)",
                            color = Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Only accept ride requests where the pickup distance (km) falls in this range. Set both to 0.0 to bypass.",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = minPickupText,
                                onValueChange = {
                                    val clean = keepOnlyNumbersAndDecimal(it)
                                    minPickupText = clean
                                    val d = clean.toDoubleOrNull() ?: 0.0
                                    viewModel.updatePriceConfig(config.priceConfig.copy(minPickupDistance = d))
                                },
                                label = { Text("Min Pickup (Km)", color = Color.Gray, fontSize = 11.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color(0xFFFF9800),
                                    unfocusedBorderColor = Color(0xFFE0E0E0)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Text("to", fontSize = 11.sp, color = Color.DarkGray)
                            OutlinedTextField(
                                value = maxPickupText,
                                onValueChange = {
                                    val clean = keepOnlyNumbersAndDecimal(it)
                                    maxPickupText = clean
                                    val d = clean.toDoubleOrNull() ?: 0.0
                                    viewModel.updatePriceConfig(config.priceConfig.copy(maxPickupDistance = d))
                                },
                                label = { Text("Max Pickup (Km)", color = Color.Gray, fontSize = 11.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color(0xFFFF9800),
                                    unfocusedBorderColor = Color(0xFFE0E0E0)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 3rd: Drop Distance Filter (Km)
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "3. Drop Distance Filter (Km)",
                            color = Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Only accept ride requests where the drop distance (km) falls in this range. Set both to 0.0 to bypass.",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = minDropText,
                                onValueChange = {
                                    val clean = keepOnlyNumbersAndDecimal(it)
                                    minDropText = clean
                                    val d = clean.toDoubleOrNull() ?: 0.0
                                    viewModel.updatePriceConfig(config.priceConfig.copy(minDropDistance = d))
                                },
                                label = { Text("Min Drop (Km)", color = Color.Gray, fontSize = 11.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color(0xFFFF9800),
                                    unfocusedBorderColor = Color(0xFFE0E0E0)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Text("to", fontSize = 11.sp, color = Color.DarkGray)
                            OutlinedTextField(
                                value = maxDropText,
                                onValueChange = {
                                    val clean = keepOnlyNumbersAndDecimal(it)
                                    maxDropText = clean
                                    val d = clean.toDoubleOrNull() ?: 0.0
                                    viewModel.updatePriceConfig(config.priceConfig.copy(maxDropDistance = d))
                                },
                                label = { Text("Max Drop (Km)", color = Color.Gray, fontSize = 11.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color(0xFFFF9800),
                                    unfocusedBorderColor = Color(0xFFE0E0E0)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 4th: Price
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 400)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "4. Price Filter Range (₹)",
                            color = Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Only accept rides within this price range. Leave Max blank for no upper limit.",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = minPriceText,
                                onValueChange = {
                                    val clean = keepOnlyNumbersAndDecimal(it)
                                    minPriceText = clean
                                    val doubleVal = clean.toDoubleOrNull() ?: 0.0
                                    viewModel.updatePriceConfig(config.priceConfig.copy(minPrice = doubleVal, currencySymbol = "₹"))
                                },
                                label = { Text("Min Price (₹)", color = Color.Gray, fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color(0xFF4CAF50),
                                    unfocusedBorderColor = Color(0xFFE0E0E0)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Text("to", fontSize = 11.sp, color = Color.DarkGray)
                            OutlinedTextField(
                                value = maxPriceText,
                                onValueChange = {
                                    val clean = keepOnlyNumbersAndDecimal(it)
                                    maxPriceText = clean
                                    val doubleVal = if (clean.isBlank()) Double.MAX_VALUE else clean.toDoubleOrNull() ?: Double.MAX_VALUE
                                    viewModel.updatePriceConfig(config.priceConfig.copy(maxPrice = doubleVal))
                                },
                                label = { Text("Max Price (₹)", color = Color.Gray, fontSize = 11.sp) },
                                placeholder = { Text("No limit", color = Color(0xFFBBBBBB), fontSize = 11.sp) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color(0xFFEF5350),
                                    unfocusedBorderColor = Color(0xFFE0E0E0)
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 4.5: Vibration Trigger & Smart Mode
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 450)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "4.5. Smart Detection Settings",
                            color = Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "📳 Vibration Trigger",
                                    color = Color.Black,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Use phone vibration to instantly scan when a new ride arrives.",
                                    color = Color.DarkGray,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = vibrationEnabled,
                                onCheckedChange = {
                                    vibrationEnabled = it
                                    viewModel.updatePriceConfig(config.priceConfig.copy(vibrationTriggerEnabled = it))
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF9800))
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFEFF8FF), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFBBDEFB), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "ℹ️ Vibration detection works by reading the accelerometer sensor. When the phone vibrates from a new ride notification, it triggers an immediate screen scan — faster than the polling interval.",
                                color = Color(0xFF1565C0),
                                fontSize = 10.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // 5th: Accept Button Keywords & Image Templates
        item {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 500)) + slideInVertically(
                    initialOffsetY = { 40 },
                    animationSpec = spring(stiffness = Spring.StiffnessLow)
                )
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F7)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "5. Accept Button Keywords",
                            color = Color.Black,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Customize the text keywords that the engine will look for to automatically click and accept rides (comma-separated).",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        OutlinedTextField(
                            value = acceptButtonKeywordsText,
                            onValueChange = {
                                acceptButtonKeywordsText = it
                                viewModel.updatePriceConfig(config.priceConfig.copy(acceptButtonKeywords = it))
                            },
                            placeholder = { Text("e.g. Accept, Take, Confirm, Go", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.Black,
                                unfocusedTextColor = Color.Black,
                                focusedBorderColor = Color(0xFFFF9800),
                                unfocusedBorderColor = Color(0xFFE0E0E0)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Image-Based Button Templates (Optional)",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = "You can optionally upload templates of the accept button. The engine will match both text and template images.",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (templates.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFEEEEEE))
                                    .border(1.dp, Color(0xFFCCCCCC), RoundedCornerShape(8.dp))
                                    .clickable { imagePickerLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.DarkGray)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("No templates uploaded. Tap here to upload template screenshot.", color = Color.DarkGray, fontSize = 12.sp, textAlign = TextAlign.Center)
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                templates.forEachIndexed { index, item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
                                            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val templateFile = File(item.imagePath)
                                            if (templateFile.exists()) {
                                                AsyncImage(
                                                    model = templateFile,
                                                    contentDescription = "Template thumbnail",
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red)
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            var textVal by remember(item.buttonText) { mutableStateOf(item.buttonText) }
                                            OutlinedTextField(
                                                value = textVal,
                                                onValueChange = {
                                                    textVal = it
                                                    val newList = templates.toMutableList()
                                                    newList[index] = item.copy(buttonText = it)
                                                    updateTemplates(newList)
                                                },
                                                label = { Text("Associated Keyword", color = Color.DarkGray, fontSize = 10.sp) },
                                                placeholder = { Text("e.g. Accept, Go", color = Color.Gray) },
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = Color.Black,
                                                    unfocusedTextColor = Color.Black,
                                                    focusedBorderColor = Color(0xFFFF9800),
                                                    unfocusedBorderColor = Color(0xFFE0E0E0)
                                                ),
                                                textStyle = TextStyle(fontSize = 12.sp),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                val newList = templates.toMutableList()
                                                newList.removeAt(index)
                                                updateTemplates(newList)
                                                try {
                                                    File(item.imagePath).delete()
                                                } catch (e: Exception) {}
                                                Toast.makeText(context, "Template removed.", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove Template", tint = Color.Red)
                                        }
                                    }
                                }

                                Button(
                                    onClick = { imagePickerLauncher.launch("image/*") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Another Template Image", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun copyUriToInternalStorage(context: Context, uri: Uri, fileName: String = "accept_button_template.png"): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val directory = File(context.filesDir, "templates")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val file = File(directory, fileName)
        val outputStream = FileOutputStream(file)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
        file.absolutePath
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to copy template image", e)
        null
    }
}

@Composable
fun SavedRecordingsView(recordings: List<com.example.data.database.RecordingEntity>, viewModel: MainViewModel) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "SAVED MULTI-CLICK SEQUENCES",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        if (recordings.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No saved recording sequences found",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Enable 'Sequence Mode' and press play to record.",
                            color = Color.DarkGray,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(recordings) { recording ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = recording.name,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${recording.actions.size} clicks / swipe actions recorded",
                                color = Color(0xFFFF9800),
                                fontSize = 11.sp
                            )
                            val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(recording.timestamp))
                            Text(
                                text = "Saved: $formattedDate",
                                color = Color.DarkGray,
                                fontSize = 10.sp
                            )
                        }

                        IconButton(
                            onClick = {
                                viewModel.deleteRecording(recording.id)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color(0xFFE57373)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GuidelinesView(
    hasOverlay: Boolean,
    hasAccessibility: Boolean,
    ethicalAgreed: Boolean,
    onAgreeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "ETHICAL USAGE GUIDELINES & PERMISSIONS",
                color = Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Responsible Use Agreement
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C130D)),
                border = BorderStroke(1.dp, Color(0xFF5E3F2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFFFF9800)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Responsible Use Commitment",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "This auto-clicker app is developed for accessibility assistance, automated UI/UX client testing, and harmless productivity workflows.",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Allowed legitimate uses:", color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(" ✅ Testing mobile application user interfaces as a developer.", color = Color.LightGray, fontSize = 11.sp)
                    Text(" ✅ Accessibility assistance for repetitive physical gestures.", color = Color.LightGray, fontSize = 11.sp)

                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Prohibited illegal uses:", color = Color(0xFFE57373), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(" ❌ Cheating in games, farming in-game currency illegally.", color = Color.LightGray, fontSize = 11.sp)
                    Text(" ❌ Bypassing platform terms of service or API limits.", color = Color.LightGray, fontSize = 11.sp)

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (ethicalAgreed) Color(0x224CAF50) else Color(0x1AFFFFFF))
                            .clickable { onAgreeChanged(!ethicalAgreed) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = ethicalAgreed,
                            onCheckedChange = { onAgreeChanged(it) },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF9800))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "I Understand & Explicitly Agree to legitimate personal use.",
                            color = if (ethicalAgreed) Color.Green else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Quick Manual System Deep Links
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "System Permissions Control Panel",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("1. Screen Overlay Permission", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (hasOverlay) "Granted successfully" else "Required for dragging floating windows",
                                color = if (hasOverlay) Color.Green else Color.LightGray,
                                fontSize = 10.sp
                            )
                        }
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasOverlay) Color(0xFF2E2E38) else Color(0xFFFF9800)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(if (hasOverlay) "Configure" else "Grant", color = if (hasOverlay) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("2. Accessibility Click Engine", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (hasAccessibility) "Enabled successfully" else "Required to physical click simulation",
                                color = if (hasAccessibility) Color.Green else Color.LightGray,
                                fontSize = 10.sp
                            )
                        }
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasAccessibility) Color(0xFF2E2E38) else Color(0xFFFF9800)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(if (hasAccessibility) "Configure" else "Enable", color = if (hasAccessibility) Color.White else Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(icon: String, label: String, value: String, valueColor: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(text = icon, fontSize = 16.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            color = Color(0xFF78909C),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}
