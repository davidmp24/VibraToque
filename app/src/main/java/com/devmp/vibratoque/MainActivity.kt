package com.devmp.vibratoque

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.regex.Pattern

// --- DADOS DE CONFIGURAÇÃO ---
data class AppConfig(
    var isFloatingButtonEnabled: Boolean = false,
    var isMiBandMode: Boolean = true,
    var pulseDuration: Float = 300f,
    var pulseSpacing: Float = 300f,
    var questionSpacing: Float = 3000f,
    var countdownTime: Float = 10000f,
    var vibrationIntensity: Float = 100f,
    var forceDarkMode: Boolean = true
)

// Objeto global para compartilhar config com o Serviço (Overlay)
object GlobalConfig {
    var config = AppConfig()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        setContent {
            var showSettingsScreen by remember { mutableStateOf(false) }
            val configState = remember { mutableStateOf(GlobalConfig.config) }
            val context = LocalContext.current

            var showPermissionDialog by remember { mutableStateOf(false) }

            // Sincroniza configuração inicial
            LaunchedEffect(Unit) {
                GlobalConfig.config = configState.value
                if (checkMissingPermissions(context)) {
                    showPermissionDialog = true
                }
            }

            // Sincroniza qualquer mudança de estado com o GlobalConfig para o Serviço ver
            LaunchedEffect(configState.value) {
                GlobalConfig.config = configState.value
            }

            val notificationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { _ ->
                    if (!Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, "Agora habilite a sobreposição.", Toast.LENGTH_SHORT).show()
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    }
                    showPermissionDialog = false
                }
            )

            val AppBackground = Color(0xFF1C1C1E)
            val AppContent = Color(0xFFE0E0E0)
            val AppSurface = Color(0xFF2C2C2E)

            val minimalColorScheme = darkColorScheme(
                primary = AppContent,
                onPrimary = AppBackground,
                background = AppBackground,
                onBackground = AppContent,
                surface = AppSurface,
                onSurface = AppContent
            )

            MaterialTheme(colorScheme = minimalColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

                    if (showSettingsScreen) {
                        SettingsScreen(
                            config = configState.value,
                            onConfigChange = { newConfig ->
                                // ATUALIZAÇÃO CRÍTICA: Atualiza o estado da UI e o Global para o Serviço
                                configState.value = newConfig
                                GlobalConfig.config = newConfig
                            },
                            onBack = { showSettingsScreen = false }
                        )
                    } else {
                        HomeScreen(
                            config = configState.value,
                            onOpenSettings = { showSettingsScreen = true }
                        )
                    }

                    if (showPermissionDialog) {
                        AlertDialog(
                            onDismissRequest = { },
                            title = {
                                Text("Configuração Inicial", color = AppContent, fontWeight = FontWeight.Bold)
                            },
                            text = {
                                Text(
                                    "Para o VibraToque funcionar corretamente com a Mi Band e o Botão Flutuante, precisamos que você habilite todas as permissões especiais.",
                                    color = Color.Gray
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        else if (!Settings.canDrawOverlays(context)) {
                                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                            context.startActivity(intent)
                                            showPermissionDialog = false
                                        } else {
                                            showPermissionDialog = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppContent, contentColor = AppBackground)
                                ) {
                                    Text("HABILITAR AGORA")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPermissionDialog = false }) {
                                    Text("DEPOIS", color = Color.Gray)
                                }
                            },
                            containerColor = AppSurface,
                            tonalElevation = 6.dp
                        )
                    }
                }
            }
        }
    }

    private fun checkMissingPermissions(context: Context): Boolean {
        val needsNotification = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val needsOverlay = !Settings.canDrawOverlays(context)
        return needsNotification || needsOverlay
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Canal Mi Band"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("MIBAND_CHANNEL", name, importance).apply {
                description = "Notificações para vibrar a pulseira"
                enableVibration(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

// --- RODAPÉ ---
@Composable
fun AppFooter() {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "VibraToque 2.0 - David MP",
            modifier = Modifier
                .clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/davidmp24"))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Erro ao abrir link", Toast.LENGTH_SHORT).show()
                    }
                },
            textAlign = TextAlign.Center,
            color = Color.Gray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// --- TELA DE CONFIGURAÇÕES ---
@Composable
fun SettingsScreen(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var localConfig by remember { mutableStateOf(config.copy()) }

    fun update() { onConfigChange(localConfig) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                localConfig = localConfig.copy(isMiBandMode = true)
                update()
                Toast.makeText(context, "Permissão concedida!", Toast.LENGTH_SHORT).show()
            } else {
                localConfig = localConfig.copy(isMiBandMode = false)
                update()
                Toast.makeText(context, "Permissão necessária.", Toast.LENGTH_LONG).show()
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(60.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Voltar", tint = MaterialTheme.colorScheme.onBackground)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ajustes", fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }

            Spacer(modifier = Modifier.height(30.dp))

            Text("INTERFACE", fontSize = 12.sp, color = Color.Gray, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Botão Flutuante (Sobrepor)", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text("Exibe botão digital fora do app", fontSize = 12.sp, color = Color.Gray)
                }
                Switch(
                    checked = localConfig.isFloatingButtonEnabled,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            if (!Settings.canDrawOverlays(context)) {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                                localConfig = localConfig.copy(isFloatingButtonEnabled = false)
                            } else {
                                localConfig = localConfig.copy(isFloatingButtonEnabled = true)
                                context.startService(Intent(context, FloatingButtonService::class.java))
                            }
                        } else {
                            localConfig = localConfig.copy(isFloatingButtonEnabled = false)
                            context.stopService(Intent(context, FloatingButtonService::class.java))
                        }
                        update()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onBackground,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            Divider(color = MaterialTheme.colorScheme.surface, thickness = 1.dp, modifier = Modifier.padding(vertical = 20.dp))

            Text("MOTOR DE VIBRAÇÃO", fontSize = 12.sp, color = Color.Gray, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notificação (Mi Band)", color = Color.Gray, fontSize = 16.sp)
                Switch(
                    checked = localConfig.isMiBandMode,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                    localConfig = localConfig.copy(isMiBandMode = true)
                                    update()
                                } else {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                localConfig = localConfig.copy(isMiBandMode = true)
                                update()
                            }
                        } else {
                            localConfig = localConfig.copy(isMiBandMode = false)
                            update()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onBackground,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            ConfigSlider("Duração do Pulso", "${localConfig.pulseDuration.toInt()} ms", localConfig.pulseDuration, 50f..1000f) {
                localConfig = localConfig.copy(pulseDuration = it); update()
            }

            ConfigSlider("Espaçamento (Pulsos)", "${localConfig.pulseSpacing.toInt()} ms", localConfig.pulseSpacing, 50f..2000f) {
                localConfig = localConfig.copy(pulseSpacing = it); update()
            }

            ConfigSlider("Intervalo (Questões)", "${(localConfig.questionSpacing/1000).toInt()} s", localConfig.questionSpacing, 1000f..10000f) {
                localConfig = localConfig.copy(questionSpacing = it); update()
            }

            ConfigSlider("Contagem Inicial", "${(localConfig.countdownTime/1000).toInt()} s", localConfig.countdownTime, 0f..30000f) {
                localConfig = localConfig.copy(countdownTime = it); update()
            }

            ConfigSlider("Intensidade", "${localConfig.vibrationIntensity.toInt()}%", localConfig.vibrationIntensity, 1f..100f) {
                localConfig = localConfig.copy(vibrationIntensity = it); update()
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
            AppFooter()
        }
    }
}

// --- TELA PRINCIPAL ---
@Composable
fun HomeScreen(config: AppConfig, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var displayText by remember { mutableStateOf("PRONTO") }
    var activeStatusIndex by remember { mutableStateOf(0) }
    var processingJob by remember { mutableStateOf<Job?>(null) }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("VT", fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, "Config", tint = MaterialTheme.colorScheme.onBackground)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth().height(100.dp).border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)),
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = displayText, fontSize = 42.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground, letterSpacing = 4.sp)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 1..4) {
                    Box(
                        modifier = Modifier.padding(horizontal = 6.dp).size(12.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (i <= activeStatusIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("01-A, 02-B...", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrect = false),
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(30.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        if (!isRunning) {
                            isRunning = true
                            processingJob = scope.launch {
                                processarGabarito(context, inputText, config, updateDisplay = { displayText = it }, updateStatus = { activeStatusIndex = it }, onFinished = { isRunning = false; displayText = "FIM"; activeStatusIndex = 0 })
                            }
                        } else {
                            processingJob?.cancel()
                            isRunning = false
                            displayText = "STOP"
                            activeStatusIndex = 0
                        }
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = if (isRunning) "PARAR" else "INICIAR", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                OutlinedButton(
                    onClick = { inputText = "" },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("LIMPAR", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
            AppFooter()
        }
    }
}

// --- HELPERS UI ---
@Composable
fun ConfigSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, fontSize = 16.sp)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.background, checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedThumbColor = MaterialTheme.colorScheme.onBackground, uncheckedTrackColor = MaterialTheme.colorScheme.surface))
    }
}

@Composable
fun ConfigSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.Gray, fontSize = 14.sp)
            Text(valueText, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled, colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surface))
    }
}

// --- SERVIÇO DE BOTÃO FLUTUANTE (OVERLAY) ---
class FloatingButtonService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        overlayView = ComposeView(this)

        val lifecycleOwner = MyLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        overlayView.setViewTreeLifecycleOwner(lifecycleOwner)
        overlayView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        overlayView.setContent {
            FingerprintOverlay(
                onVibrate = {
                    // OTOQUE DO BOTÃO FLUTUANTE CHAMA A LÓGICA MANUAL
                    CoroutineScope(Dispatchers.Main).launch {
                        vibrarManual(this@FloatingButtonService, GlobalConfig.config)
                    }
                },
                onMove = { x, y ->
                    params.x += x.toInt()
                    params.y += y.toInt()
                    windowManager.updateViewLayout(overlayView, params)
                }
            )
        }

        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}

@Composable
fun FingerprintOverlay(onVibrate: () -> Unit, onMove: (Float, Float) -> Unit) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onVibrate() }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onMove(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(60.dp)) {
            val color = Color(0xAAFFFFFF)
            val stroke = Stroke(width = 4f, cap = StrokeCap.Round)
            val center = Offset(size.width / 2, size.height / 2)
            drawArc(color, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = Offset(5f,5f), size = androidx.compose.ui.geometry.Size(size.width-10, size.height-10), style = stroke)
            drawArc(color, startAngle = 30f, sweepAngle = 260f, useCenter = false, topLeft = Offset(15f,15f), size = androidx.compose.ui.geometry.Size(size.width-30, size.height-30), style = stroke)
            drawArc(color, startAngle = 180f, sweepAngle = 260f, useCenter = false, topLeft = Offset(25f,25f), size = androidx.compose.ui.geometry.Size(size.width-50, size.height-50), style = stroke)
        }
    }
}

class MyLifecycleOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    fun handleLifecycleEvent(event: Lifecycle.Event) { lifecycleRegistry.handleLifecycleEvent(event) }
    fun performRestore(savedState: Bundle?) { savedStateRegistryController.performRestore(savedState) }
}

// --- LÓGICA DE VIBRAÇÃO MANUAL (AJUSTADA E BLINDADA) ---
suspend fun vibrarManual(context: Context, config: AppConfig) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val duration = config.pulseDuration.toLong()
    val amplitude = (config.vibrationIntensity * 2.55).toInt().coerceIn(1, 255)

    // Atributos de Áudio para FURAR O BLOQUEIO DA MIUI
    val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ALARM) // Prioridade Máxima
        .build()

    // VERIFICAÇÃO CLARA DO MODO MI BAND (GLOBAL)
    if (config.isMiBandMode) {
        val manualId = (System.currentTimeMillis() % 100000).toInt()
        enviarNotificacao(context, manualId)
    } else {
        // Vibração física com atributos
        if (Build.VERSION.SDK_INT >= 26) {
            val effect = if (amplitude > 240) {
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createOneShot(duration, amplitude)
            }
            vibrator.vibrate(effect, audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}

// --- PROCESSAMENTO DO GABARITO ---
suspend fun processarGabarito(
    context: Context, rawText: String, config: AppConfig,
    updateDisplay: (String) -> Unit, updateStatus: (Int) -> Unit, onFinished: () -> Unit
) {
    val lista = extrairQuestoes(rawText)
    if (lista.isEmpty()) { updateDisplay("ERRO"); delay(2000); onFinished(); return }

    // 1. ALERTA INICIAL (2 toques rápidos)
    updateDisplay("ALERTA")
    executarSinais(context, 2, config, updateStatus)

    // 2. CONTAGEM REGRESSIVA (Controlada pelo Slider)
    val countdownSeconds = (config.countdownTime / 1000).toInt()
    if (countdownSeconds > 0) {
        for (i in countdownSeconds downTo 1) {
            updateDisplay("INICIO $i")
            delay(1000)
        }
    }

    // 3. INICIAR TRANSMISSÃO NORMAL
    updateDisplay("...")
    for (questao in lista) {
        updateDisplay("${questao.numero}-${questao.resposta}")
        val pulsos = when (questao.resposta.uppercase()) { "A"->1; "B"->2; "C"->3; "D"->4; "E"->5; else->0 }
        if (pulsos > 0) executarSinais(context, pulsos, config, updateStatus)
        updateStatus(0)
        delay(config.questionSpacing.toLong())
    }
    onFinished()
}

suspend fun executarSinais(context: Context, pulsos: Int, config: AppConfig, updateStatus: (Int) -> Unit) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    val duration = config.pulseDuration.toLong()
    val amplitude = (config.vibrationIntensity * 2.55).toInt().coerceIn(1, 255)

    val audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_ALARM)
        .build()

    for (i in 1..pulsos) {
        updateStatus(if (i > 4) 4 else i)

        // VERIFICAÇÃO CLARA PARA SEQUÊNCIA
        if (config.isMiBandMode) {
            enviarNotificacao(context, i)
        } else {
            if (Build.VERSION.SDK_INT >= 26) {
                val effect = if (amplitude > 240) {
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createOneShot(duration, amplitude)
                }
                vibrator.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
        delay(duration + config.pulseSpacing.toLong())
    }
}

fun enviarNotificacao(context: Context, id: Int) {
    val uniqueNotificationId = (System.currentTimeMillis() % 100000).toInt() + id

    val builder = NotificationCompat.Builder(context, "MIBAND_CHANNEL")
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("VibraToque")
        .setContentText("Sinal $id")
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setVibrate(longArrayOf(0, 100))
        .setOnlyAlertOnce(false)
        .setAutoCancel(true)

    try {
        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(uniqueNotificationId, builder.build())
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

data class Questao(val numero: String, val resposta: String)
fun extrairQuestoes(texto: String): List<Questao> {
    val lista = mutableListOf<Questao>()
    val regex = Pattern.compile("(\\d+)[^0-9A-Ea-e]*([A-Ea-e])")
    val matcher = regex.matcher(texto)
    while (matcher.find()) lista.add(Questao(matcher.group(1)?:"?", matcher.group(2)?.uppercase()?:"?"))
    return lista
}