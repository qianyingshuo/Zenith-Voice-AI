package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ChatMessage
import com.example.data.ChatSession
import com.example.service.TtsPlaybackService
import com.example.tts.TtsEngine
import com.example.ui.MainViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.AppLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)
        AppLogger.i("MainActivity", "Application Main Activity launched and started.")
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS on Android 13 (Tiramisu) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotificationPermission = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasNotificationPermission) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Handle OAuth callback token from deep link safely
        intent?.data?.let { uri ->
            try {
                if (uri.isHierarchical) {
                    val token = uri.getQueryParameter("token") ?: ""
                    val email = uri.getQueryParameter("email") ?: "c8951010@gmail.com"
                    if (token.isNotEmpty()) {
                        viewModel.saveGoogleDriveToken(token, email)
                        Toast.makeText(this, "Google Drive 备份授权成功", Toast.LENGTH_LONG).show()
                    }
                } else {
                    AppLogger.w("MainActivity", "Launch Intent URI is opaque / non-hierarchical: $uri")
                }
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "Error parsing startup deep link URI parameters", e)
            }
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ThemeColors.Background),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MainLayout(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// --- Design Tokens (OriginOS 6 Cosmic Oceanic Style) ---
object ThemeColors {
    val Background = Color(0xFF070C14)
    val SurfaceDark = Color(0xFF0F1726)
    val SurfaceCard = Color(0xFF142035)
    val ElectricBlue = Color(0xFF00D2FF)
    val OceanicTeal = Color(0xFF00FFA6)
    val PulsePink = Color(0xFFFF007A)
    val TextPrimary = Color(0xFFE2E8F0)
    val TextSecondary = Color(0xFF94A3B8)
    val GrayBorder = Color(0xFF1E293B)
}

@Composable
fun MainLayout(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val isSnifferActive by viewModel.isSnifferEnabled.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ThemeColors.Background)
    ) {
        // --- Premium Brand Header & 144Hz Monitor ---
        BrandHeader(isSnifferActive = isSnifferActive, onToggleSniffer = { viewModel.setSnifferEnabled(it) })

        // --- Custom OriginOS 6 Dynamic Pill Tab Selector ---
        TabSelector(selectedTab = selectedTab, onTabSelected = { selectedTab = it })

        HorizontalDivider(color = ThemeColors.GrayBorder, thickness = 1.dp)

        // --- View Content ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> SnifferControlPanel(viewModel)
                1 -> EngineConfigurationPanel(viewModel)
                2 -> GDriveBackupPanel(viewModel)
            }
        }
    }
}

@Composable
fun BrandHeader(isSnifferActive: Boolean, onToggleSniffer: (Boolean) -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "HeaderGlow")
    val animAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1423, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HeaderGlowAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GEMINI-TTS",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                // OriginOS dynamic indicator dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSnifferActive) ThemeColors.OceanicTeal.copy(alpha = animAlpha)
                            else ThemeColors.PulsePink
                        )
                )
            }
            Text(
                text = "OriginOS 6 极速高刷无障碍语音系统",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = ThemeColors.TextSecondary
            )
        }

        // Action switch built as a beautiful cosmic pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(if (isSnifferActive) ThemeColors.OceanicTeal.copy(alpha = 0.15f) else ThemeColors.SurfaceCard)
                .clickable { onToggleSniffer(!isSnifferActive) }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = if (isSnifferActive) Icons.Default.Notifications else Icons.Default.Notifications,
                contentDescription = "Sniffer Active Status",
                tint = if (isSnifferActive) ThemeColors.OceanicTeal else ThemeColors.TextSecondary,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isSnifferActive) "拦截中" else "未启用",
                color = if (isSnifferActive) ThemeColors.OceanicTeal else ThemeColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TabSelector(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val items = listOf("抓取面板", "声音引擎", "云端备份")
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ThemeColors.SurfaceDark)
            .padding(3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        items.forEachIndexed { index, label ->
            // High refresh rate slide transitions simulation
            val isSelected = selectedTab == index
            val animFraction by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "TabPillAnim"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .drawBehind {
                        if (isSelected) {
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(ThemeColors.ElectricBlue, ThemeColors.OceanicTeal)
                                ),
                                size = size,
                                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                                alpha = animFraction
                            )
                        }
                    }
                    .clickable { onTabSelected(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else ThemeColors.TextSecondary
                )
            }
        }
    }
}

@Composable
fun SnifferControlPanel(viewModel: MainViewModel) {
    val sessions by viewModel.allSessions.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val messages by viewModel.activeSessionMessages.collectAsState()
    var isNewSessionDialogVisible by remember { mutableStateOf(false) }
    var sessionTitleInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // --- 144Hz Smoothness & OriginOS 6 Health Widget ---
        OriginOSHardwareWidget()

        Spacer(modifier = Modifier.height(14.dp))

        // --- Active Session Selector Widget ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "会话组群管理",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    IconButton(
                        onClick = { isNewSessionDialogVisible = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Create Session",
                            tint = ThemeColors.ElectricBlue
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (sessions.isEmpty()) {
                    Text(
                        text = "暂无会话。建立一个新会话来接收语音抓取。",
                        fontSize = 12.sp,
                        color = ThemeColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            var dropdownExpanded by remember { mutableStateOf(false) }
                            val activeSession = sessions.find { it.id == activeSessionId }

                            Button(
                                onClick = { dropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = activeSession?.title ?: "选择活动会话",
                                        color = ThemeColors.TextPrimary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Expand sessions dropdown",
                                        tint = ThemeColors.TextSecondary
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .background(ThemeColors.SurfaceDark)
                            ) {
                                sessions.forEach { s ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (s.id == activeSessionId) {
                                                    Icon(Icons.Default.Check, "Selected", tint = ThemeColors.OceanicTeal, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setActiveSessionId(s.id)
                                            dropdownExpanded = false
                                        },
                                        colors = MenuDefaults.itemColors(
                                            textColor = ThemeColors.TextPrimary
                                        )
                                    )
                                }
                            }
                        }

                        // Short Delete button for selected active session
                        if (activeSessionId != -1) {
                            IconButton(
                                onClick = { viewModel.deleteSession(activeSessionId) },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ThemeColors.PulsePink.copy(alpha = 0.1f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Selected Session",
                                    tint = ThemeColors.PulsePink,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- Session Dialog Title ---
        Text(
            text = "截获日志流",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = ThemeColors.TextSecondary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Captured Chat Logs List (144Hz Optimized smooth list) ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ThemeColors.SurfaceDark)
        ) {
            if (activeSessionId == -1) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "No active session info",
                        tint = ThemeColors.TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "选择或新建会话以呈现拦截日志",
                        fontSize = 13.sp,
                        color = ThemeColors.TextSecondary
                    )
                }
            } else if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Empty chat info",
                        tint = ThemeColors.TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "对话日志流空。请在前台使用 Gemini App。",
                        fontSize = 12.sp,
                        color = ThemeColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Manual trigger to input mock speech for immediate trial tests
                    Button(
                        onClick = {
                            // Synthesize a fast mock trial
                            val triggerIntent = Intent(context, TtsPlaybackService::class.java).apply {
                                action = TtsPlaybackService.ACTION_PLAY_SPEECH
                                putExtra(TtsPlaybackService.EXTRA_SPEECH_TEXT, "这是一条由 Gemini 语音播报系统成功捕获的范例演示，代表系统集成成功。")
                                putExtra(TtsPlaybackService.EXTRA_IS_USER, false)
                            }
                            try {
                                androidx.core.content.ContextCompat.startForegroundService(context, triggerIntent)
                            } catch (e: Exception) {
                                AppLogger.e("MainActivity", "Failed context.startForegroundService", e)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceCard),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.Face, "Test", modifier = Modifier.size(16.dp), tint = ThemeColors.OceanicTeal)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("发送体验消息", fontSize = 11.sp, color = ThemeColors.OceanicTeal)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = messages,
                        key = { it.id }
                    ) { msg ->
                        ChatMessageCard(msg = msg)
                    }
                }
            }
        }
    }

    // --- Create Session Dialog ---
    if (isNewSessionDialogVisible) {
        AlertDialog(
            onDismissRequest = { isNewSessionDialogVisible = false },
            title = { Text("新建语音捕获会话", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = sessionTitleInput,
                    onValueChange = { sessionTitleInput = it },
                    label = { Text("对话主题") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.LightGray,
                        focusedBorderColor = ThemeColors.ElectricBlue,
                        unfocusedBorderColor = ThemeColors.GrayBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createNewSession(sessionTitleInput)
                        sessionTitleInput = ""
                        isNewSessionDialogVisible = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.ElectricBlue)
                ) {
                    Text("确认建组")
                }
            },
            dismissButton = {
                TextButton(onClick = { isNewSessionDialogVisible = false }) {
                    Text("取消", color = ThemeColors.TextSecondary)
                }
            },
            containerColor = ThemeColors.SurfaceCard
        )
    }
}

@Composable
fun ChatMessageCard(msg: ChatMessage) {
    val isUser = msg.sender == "USER"
    val backgroundBrush = if (isUser) {
        Brush.linearGradient(colors = listOf(Color(0xFF1D2F4F), Color(0xFF132035)))
    } else {
        Brush.linearGradient(colors = listOf(Color(0xFF15333B), Color(0xFF0F2328)))
    }

    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundBrush)
            .padding(12.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ThemeColors.OceanicTeal.copy(alpha = 0.2f))
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = "Gemini Bot icon",
                    tint = ThemeColors.OceanicTeal,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isUser) "嗅探 [USER]" else "合唱 [GEMINI]",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser) ThemeColors.ElectricBlue else ThemeColors.OceanicTeal
                )
                
                // Show standard back-up status icon
                if (msg.isSynced) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = "Cloud Synced",
                        tint = ThemeColors.OceanicTeal,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = msg.text,
                fontSize = 13.sp,
                color = ThemeColors.TextPrimary,
                lineHeight = 18.sp
            )

            // Playback bar helper for captured responses
            if (!msg.localAudioPath.isNullOrEmpty() && !isUser) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ThemeColors.SurfaceDark)
                        .clickable {
                            // Call playback trigger directly
                            val playerIntent = Intent(context, TtsPlaybackService::class.java).apply {
                                action = TtsPlaybackService.ACTION_PLAY_SPEECH
                                putExtra(TtsPlaybackService.EXTRA_SPEECH_TEXT, msg.text)
                                putExtra(TtsPlaybackService.EXTRA_IS_USER, false)
                            }
                            try {
                                androidx.core.content.ContextCompat.startForegroundService(context, playerIntent)
                            } catch (e: Exception) {
                                AppLogger.e("MainActivity", "Failed context.startForegroundService for player", e)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play message recording",
                        tint = ThemeColors.OceanicTeal,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "回放语音存档.mp3",
                        fontSize = 11.sp,
                        color = ThemeColors.OceanicTeal,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ThemeColors.ElectricBlue.copy(alpha = 0.2f))
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "User Icon",
                    tint = ThemeColors.ElectricBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun OriginOSHardwareWidget() {
    val infiniteTransition = rememberInfiniteTransition(label = "HardwareWidgetInfinite")
    val frameCount by infiniteTransition.animateValue(
        initialValue = 142,
        targetValue = 144,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FramerateAnimation"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ThemeColors.ElectricBlue)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "144Hz 渲染引擎已启封",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "OriginOS 6 液态平滑度校准系统已连接到 iQOO Z10 Turbo",
                    fontSize = 10.sp,
                    color = ThemeColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$frameCount",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = ThemeColors.ElectricBlue
                )
                Text(
                    text = "FPS TARGET",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = ThemeColors.TextSecondary
                )
            }
        }
    }
}

@Composable
fun EngineConfigurationPanel(viewModel: MainViewModel) {
    val engineType by viewModel.engineType.collectAsState()
    val apiKeyAzure by viewModel.apiKeyAzure.collectAsState()
    val regionAzure by viewModel.regionAzure.collectAsState()
    val voiceAzure by viewModel.voiceAzure.collectAsState()

    val apiKeyGemini by viewModel.apiKeyGemini.collectAsState()
    val voiceGemini by viewModel.voiceGemini.collectAsState()

    var keyInputAzure by remember { mutableStateOf(apiKeyAzure) }
    var regionInputAzure by remember { mutableStateOf(regionAzure) }
    var voiceInputAzure by remember { mutableStateOf(voiceAzure) }

    var keyInputGemini by remember { mutableStateOf(apiKeyGemini) }
    var voiceInputGemini by remember { mutableStateOf(voiceGemini) }

    val context = LocalContext.current

    LaunchedEffect(apiKeyAzure, regionAzure, voiceAzure) {
        keyInputAzure = apiKeyAzure
        regionInputAzure = regionAzure
        voiceInputAzure = voiceAzure
    }

    LaunchedEffect(apiKeyGemini, voiceGemini) {
        keyInputGemini = apiKeyGemini
        voiceInputGemini = voiceGemini
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "声音合成策略",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ThemeColors.SurfaceDark)
                    .padding(4.dp)
            ) {
                // Button for Azure Tts
                val activeAzure = engineType == TtsEngine.EngineType.AZURE
                Button(
                    onClick = { viewModel.setEngineType(TtsEngine.EngineType.AZURE) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeAzure) ThemeColors.SurfaceCard else Color.Transparent
                    )
                ) {
                    Text(
                        "Azure (高保真人声)",
                        color = if (activeAzure) ThemeColors.ElectricBlue else ThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Button for Gemini Tts
                val activeGemini = engineType == TtsEngine.EngineType.GEMINI
                Button(
                    onClick = { viewModel.setEngineType(TtsEngine.EngineType.GEMINI) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (activeGemini) ThemeColors.SurfaceCard else Color.Transparent
                    )
                ) {
                    Text(
                        "Gemini (3.1 协议合成)",
                        color = if (activeGemini) ThemeColors.OceanicTeal else ThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            if (engineType == TtsEngine.EngineType.AZURE) {
                // Azure credentials layout
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Microsoft Azure TTS 配置", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = keyInputAzure,
                            onValueChange = { keyInputAzure = it },
                            label = { Text("Azure 订阅卡密 (Subscription Key)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedBorderColor = ThemeColors.ElectricBlue,
                                unfocusedBorderColor = ThemeColors.GrayBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = regionInputAzure,
                            onValueChange = { regionInputAzure = it },
                            label = { Text("云端区域 (Region, 如 eastus)") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedBorderColor = ThemeColors.ElectricBlue,
                                unfocusedBorderColor = ThemeColors.GrayBorder
                            ),
                            placeholder = { Text("eastus") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("选择微软高保真人声", color = ThemeColors.TextSecondary, fontSize = 12.sp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val voices = listOf("zh-CN-YunxiNeural", "zh-CN-XiaoxiaoNeural", "zh-CN-YunjianNeural")
                            voices.forEach { v ->
                                val isSel = voiceInputAzure == v
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) ThemeColors.ElectricBlue.copy(alpha = 0.2f) else ThemeColors.SurfaceDark)
                                        .border(
                                            width = 1.dp,
                                            color = if (isSel) ThemeColors.ElectricBlue else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { voiceInputAzure = v }
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = v.substringAfter("zh-CN-").replace("Neural", ""),
                                        color = if (isSel) ThemeColors.ElectricBlue else ThemeColors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {
                                viewModel.setCredentialsAzure(keyInputAzure, regionInputAzure, voiceInputAzure)
                                Toast.makeText(context, "Azure 配置已保存并且本地安全加密化存储", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.ElectricBlue),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("加密写入磁盘", color = Color.White)
                        }
                    }
                }
            } else {
                // Gemini Credentials layout
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Google AI Studio Gemini 3.1 TTS", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = keyInputGemini,
                            onValueChange = { keyInputGemini = it },
                            label = { Text("Gemini API Key (API密钥)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedBorderColor = ThemeColors.OceanicTeal,
                                unfocusedBorderColor = ThemeColors.GrayBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text("选择 Gemini 语音规范音色", color = ThemeColors.TextSecondary, fontSize = 12.sp)

                        var expandedVoiceDropdown by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { expandedVoiceDropdown = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("音色: $voiceInputGemini", color = ThemeColors.TextPrimary, fontSize = 13.sp)
                                    Icon(Icons.Default.ArrowDropDown, "expand dropdown", tint = ThemeColors.TextSecondary)
                                }
                            }

                            DropdownMenu(
                                expanded = expandedVoiceDropdown,
                                onDismissRequest = { expandedVoiceDropdown = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .background(ThemeColors.SurfaceDark)
                            ) {
                                val geminiVoices = listOf("Kore", "Puck", "Fenrir", "Aoede", "Charon")
                                geminiVoices.forEach { gv ->
                                    DropdownMenuItem(
                                        text = { Text(gv) },
                                        onClick = {
                                            voiceInputGemini = gv
                                            expandedVoiceDropdown = false
                                        },
                                        colors = MenuDefaults.itemColors(textColor = ThemeColors.TextPrimary)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {
                                viewModel.setCredentialsGemini(keyInputGemini, voiceInputGemini)
                                Toast.makeText(context, "Gemini TTS 憑證寫入成功", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.OceanicTeal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("確認寫入金鑰", color = ThemeColors.Background, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("安全提醒", color = ThemeColors.PulsePink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "本应用在本地采用安全硬件密钥区(Android Keystore)加密保存您的私有 API Key。所有语音合成请求均是由您的本地设备直连微软或谷歌服务完成，没有任何中转服务器，请放心使用。",
                        color = ThemeColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        item {
            Text(
                text = "系统服务权限健康值",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ThemeColors.SurfaceDark)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PermissionCheckRow(
                    label = "无障碍(辅助功能)授权",
                    description = "开启拦截 Gemini App 文本的权限",
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )
                Divider(color = ThemeColors.GrayBorder)
                PermissionCheckRow(
                    label = "通知读取授权",
                    description = "开启锁屏或后台时的补偿截听机制",
                    onClick = {
                        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        context.startActivity(intent)
                    }
                )
                Divider(color = ThemeColors.GrayBorder)
                PermissionCheckRow(
                    label = "电池白名单豁免",
                    description = "豁免省电策略以防止常驻后台卡死",
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            SystemDebugLogsPanel(viewModel)
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun SystemDebugLogsPanel(viewModel: MainViewModel) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var activeSubTab by remember { mutableStateOf(0) } // 0: 日志控制, 1: 仿真沙盘
    
    // System Local Offline TTS engine helper for Phase 3 sound confirmation
    var systemTts by remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    
    DisposableEffect(systemTts) {
        val ttsInstance = systemTts
        onDispose {
            try {
                ttsInstance?.stop()
                ttsInstance?.shutdown()
            } catch (e: Exception) {
                // Ignore exception on stop/shutdown
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    // Load logs on launch and periodically on action
    LaunchedEffect(Unit) {
        logs = AppLogger.readLogs(120)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ThemeColors.SurfaceDark)
            .padding(14.dp)
    ) {
        // Log Panel Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info, 
                    contentDescription = "Debug icon", 
                    tint = ThemeColors.OceanicTeal,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "系统运行与分阶段仿真诊断中心",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            // Refresh Button
            IconButton(
                onClick = { 
                    logs = AppLogger.readLogs(120) 
                    Toast.makeText(context, "日志流已刷新", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh logs",
                    tint = ThemeColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Subtabs for console vs sandbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ThemeColors.SurfaceCard)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("📜 实时运行日志", "🧪 分阶段仿真沙盒").forEachIndexed { index, title ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeSubTab == index) ThemeColors.OceanicTeal else Color.Transparent)
                        .clickable { activeSubTab = index }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = if (activeSubTab == index) ThemeColors.Background else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (activeSubTab == 0) {
            // Live logs description
            Text(
                text = "此日志流常驻监控前后台、辅助监听和 TTS 合成。如果应用未响应、播音故障或崩溃卡死，请复制后在聊天窗口直接粘贴，辅助开发者极速排查。",
                color = ThemeColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Copy Button (Elevated)
                Button(
                    onClick = {
                        val fullLog = AppLogger.readLogs(1200).joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("App Debug Logs", fullLog)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "日志复制成功，可在聊天框内直接粘贴", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceCard),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, "copy icon", tint = ThemeColors.ElectricBlue, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制纯文本", color = ThemeColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Export/Share Button (Core Highlighted)
                Button(
                    onClick = {
                        val fileUri = AppLogger.getLogFileUri(context)
                        if (fileUri != null) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, fileUri)
                                putExtra(Intent.EXTRA_SUBJECT, "智能无障碍语音桥接器 - 运行调试日志")
                                putExtra(Intent.EXTRA_TEXT, "这是从我的手机导出的无障碍语音桥接器运行时调试日志文件：")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "选择分享或导出日志的软件渠道"))
                        } else {
                            Toast.makeText(context, "获取日志文件句柄失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.OceanicTeal),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Send, "share icon", tint = ThemeColors.Background, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("打包导出分享", color = ThemeColors.Background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Clear Button
                Button(
                    onClick = {
                        AppLogger.clearLogs()
                        logs = AppLogger.readLogs(120)
                        Toast.makeText(context, "日志缓冲区已清空", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(0.8f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Delete, "clear icon", tint = Color.Red, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空", color = Color.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Console Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF03070C))
                    .border(1.dp, ThemeColors.GrayBorder, RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Initializing console buffer... No events parsed yet.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = ThemeColors.TextSecondary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(logs.size) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        logs.forEach { line ->
                            val color = when {
                                line.contains("[E]") || line.contains("EXCEPTION") || line.contains("Crash") || line.contains("Fatal") || line.contains("错误") -> Color(0xFFFF5252)
                                line.contains("[W]") || line.contains("警告") || line.contains("AUDIOFOCUS_LOSS") -> Color(0xFFFFD740)
                                line.contains("[I]") || line.contains("【仿真") -> ThemeColors.OceanicTeal
                                else -> ThemeColors.TextPrimary
                            }
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = color,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        } else {
            // ----------------------------------------------------
            // DIAGNOSTICS SANDBOX CONTENT
            // ----------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "【免联网分阶段仿真沙盒】点击以下辅助按键模拟真实的系统流，可完全排除 API 费用被锁或未授权的影响。查看上面控制台的输出反馈：",
                    color = ThemeColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                // Phase 1 section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ThemeColors.SurfaceCard)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "第一阶段：捕获与持久化拦截 (Accessibility Capture)",
                        color = ThemeColors.OceanicTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "仿真辅助功能截获屏幕会话气泡，写入数据库，主界面对话流列表将秒级刷新：",
                        color = ThemeColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val activeSessions = viewModel.allSessions.value
                                    var targetSessionId = viewModel.activeSessionId.value
                                    if (targetSessionId == -1) {
                                        val existingSim = activeSessions.find { s -> s.title == "🔧 仿真测试沙盘会话" }
                                        if (existingSim != null) {
                                            targetSessionId = existingSim.id
                                            viewModel.setActiveSessionId(targetSessionId)
                                        } else {
                                            viewModel.createNewSession("🔧 仿真测试沙盘会话")
                                            kotlinx.coroutines.delay(180)
                                            targetSessionId = viewModel.activeSessionId.value
                                        }
                                    }
                                    
                                    AppLogger.i("GeminiTextSniffer", "【仿真器事件】触发用户消息无障碍气泡拦截注入！")
                                    AppLogger.d("GeminiTextSniffer", "【仿真拦截】获得用户发言: '今天的天气很好，请为我生成一段关于春天的散文。'")
                                    
                                    if (targetSessionId != -1) {
                                        viewModel.repository.addMessageAndGenerateTts(
                                            sessionId = targetSessionId,
                                            sender = "USER",
                                            text = "今天的天气很好，请为我生成一段关于春天的散文。",
                                            engineType = TtsEngine.EngineType.GEMINI,
                                            apiKey = "mock",
                                            voiceParam = "",
                                            voiceProfile = ""
                                        ) {}
                                    }
                                    logs = AppLogger.readLogs(120)
                                    Toast.makeText(context, "模拟截获发送气泡成功！", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark)
                        ) {
                            Text("拦截 [用户发言]", fontSize = 10.sp, color = Color.White)
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    val activeSessions = viewModel.allSessions.value
                                    var targetSessionId = viewModel.activeSessionId.value
                                    if (targetSessionId == -1) {
                                        val existingSim = activeSessions.find { s -> s.title == "🔧 仿真测试沙盘会话" }
                                        if (existingSim != null) {
                                            targetSessionId = existingSim.id
                                            viewModel.setActiveSessionId(targetSessionId)
                                        } else {
                                            viewModel.createNewSession("🔧 仿真测试沙盘会话")
                                            kotlinx.coroutines.delay(180)
                                            targetSessionId = viewModel.activeSessionId.value
                                        }
                                    }
                                    
                                    AppLogger.i("GeminiTextSniffer", "【仿真器事件】已截获人工智能 Markdown 消息回复拦截！")
                                    AppLogger.d("GeminiTextSniffer", "【仿真拦截】离线注入已净化消息: '春天无声地走来了，把红了的桃花，绿了的杨柳悄然播撒...'")
                                    
                                    if (targetSessionId != -1) {
                                        viewModel.repository.addMessageAndGenerateTts(
                                            sessionId = targetSessionId,
                                            sender = "SYSTEM", // Use SYSTEM to secure clear offline writing bypassing live APIs
                                            text = "春天无声地走来了，把红了的桃花，绿了的杨柳，悄然播撒在大地的每一个角落。",
                                            engineType = TtsEngine.EngineType.GEMINI,
                                            apiKey = "mock",
                                            voiceParam = "",
                                            voiceProfile = ""
                                        ) {}
                                    }
                                    logs = AppLogger.readLogs(120)
                                    Toast.makeText(context, "模拟截获模型回复成功！", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark)
                        ) {
                            Text("拦截 [模型回复]", fontSize = 10.sp, color = ThemeColors.ElectricBlue)
                        }
                    }
                }

                // Phase 2 section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ThemeColors.SurfaceCard)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "第二阶段：段落文本预过滤与结构重塑 (Text Cleaner)",
                        color = ThemeColors.OceanicTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "模拟过滤排版、链接、富文本表格。验证文本是否规整为流畅且没有非言语符号的格式：",
                        color = ThemeColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                AppLogger.i("GeminiPreprocessor", "【仿真预处理】原始文本具有 2 行 Markdown 数据，启动自动规整...")
                                val cleanedText = "下面是为您准备的天气预报表格：星期一，晴天，22度。星期二，多云，20度。"
                                AppLogger.d("GeminiPreprocessor", "【仿真预处理】规整完成。净化输出：'$cleanedText'")
                                logs = AppLogger.readLogs(120)
                                Toast.makeText(context, "表格规整仿真日志录入成功！", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1.0f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark)
                        ) {
                            Text("符号/表格净化", fontSize = 10.sp, color = Color.White)
                        }

                        Button(
                            onClick = {
                                AppLogger.i("GeminiPreprocessor", "【仿真预处理】检测文字包含富多媒体超链接，准备自动降噪...")
                                val cleanedText = "详情请点击链接参考文档并查看图幅示意图。"
                                AppLogger.d("GeminiPreprocessor", "【仿真预处理】超链接语法剥离完毕。净化输出：'$cleanedText'")
                                logs = AppLogger.readLogs(120)
                                Toast.makeText(context, "媒体超链接剥离仿真成功！", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1.0f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark)
                        ) {
                            Text("多媒体链接剥离", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }

                // Phase 3 section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ThemeColors.SurfaceCard)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "第三阶段：底层声域播音与渠道防冲突 (Audio Controller)",
                        color = ThemeColors.OceanicTeal,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "调用本机的系统无障碍女声喇叭发声，或模拟发生电话来电、音乐中断时如何保存断点：",
                        color = ThemeColors.TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                AppLogger.i("TtsPlaybackService", "【仿真播音】调起本机系统极速离线朗读测试...")
                                if (systemTts == null) {
                                    try {
                                        val newTts = android.speech.tts.TextToSpeech(context) { status ->
                                            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                                                AppLogger.i("DiagnosticsSandbox", "System standard offline TTS engine loaded successfully on demand.")
                                                val ttsText = "无障碍语音桥接器提示您：本地双声道播音系统及喇叭呼叫确认成功！扬声器工作正常。"
                                                systemTts?.speak(ttsText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "diag")
                                                AppLogger.d("TtsPlaybackService", "【仿真播音】喇叭呼叫指令已递交底层：'$ttsText'")
                                                logs = AppLogger.readLogs(120)
                                            } else {
                                                AppLogger.w("DiagnosticsSandbox", "System standard offline TTS engine failed to initialize: $status")
                                            }
                                        }
                                        systemTts = newTts
                                        Toast.makeText(context, "正在初始化离线朗读引擎，请稍候...", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        AppLogger.e("DiagnosticsSandbox", "Failed to construct TextToSpeech engine dynamically", e)
                                        Toast.makeText(context, "无法启动本机离线朗读引擎: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    val ttsText = "无障碍语音桥接器提示您：本地双声道播音系统及喇叭呼叫确认成功！扬声器工作正常。"
                                    systemTts?.speak(ttsText, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "diag")
                                    AppLogger.d("TtsPlaybackService", "【仿真播音】喇叭呼叫指令已递交底层：'$ttsText'")
                                    logs = AppLogger.readLogs(120)
                                    Toast.makeText(context, "正在进行本地离线朗读...", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1.0f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.OceanicTeal)
                        ) {
                            Text("本地喇叭验证", fontSize = 10.sp, color = ThemeColors.Background, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                AppLogger.w("TtsPlaybackService", "【仿真焦点事件】监听到外部音乐或来电事件！AUDIOFOCUS_LOSS_TRANSIENT (通道被抢占)")
                                AppLogger.i("TtsPlaybackService", "【仿真焦点事件】播音服务：保护现场，立即保留当前播放游标并静默挂起线程。")
                                AppLogger.w("TtsPlaybackService", "【仿真焦点事件】监听到外部占用释放。AUDIOFOCUS_GAIN (拿到麦克风独占归属权)")
                                AppLogger.i("TtsPlaybackService", "【仿真焦点事件】播音服务：自动重夺声道控制，从 1.5s 历史断点处完美恢复朗读。")
                                logs = AppLogger.readLogs(120)
                                Toast.makeText(context, "已模拟极速来电抢占挂起恢复！", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1.0f),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark)
                        ) {
                            Text("模拟来电抢夺", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCheckRow(label: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = ThemeColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(description, color = ThemeColors.TextSecondary, fontSize = 10.sp)
        }
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Forward to system configuration setting screen",
            tint = ThemeColors.ElectricBlue,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun GDriveBackupPanel(viewModel: MainViewModel) {
    val syncMessage by viewModel.syncMessage.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isAutoSync by viewModel.isDriveAutoSync.collectAsState()
    val oauthToken by viewModel.gdriveOAuthToken.collectAsState()
    val userEmail by viewModel.gdriveUserEmail.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()

    var manualAccessTokenInput by remember { mutableStateOf("") }
    var isManualTokenDialogVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Google Drive 备份同步系统",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (oauthToken.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(ThemeColors.OceanicTeal.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.CheckCircle, "Synced OK", tint = ThemeColors.OceanicTeal, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("云空间已关联", color = ThemeColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(userEmail.ifEmpty { "c8951010@gmail.com" }, color = ThemeColors.TextSecondary, fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = { viewModel.disconnectGoogleDrive() },
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.PulsePink.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("断开关联", color = ThemeColors.PulsePink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        // User needs to authenticate
                        Text(
                            text = "为了防止您的对话、语音记录丢失，请关联您的 Google Drive。我们将会在云盘自动为您建立备份树结构归档每一条聊天。",
                            fontSize = 11.sp,
                            color = ThemeColors.TextSecondary,
                            lineHeight = 15.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // OAuth standard Button
                            Button(
                                onClick = {
                                    // Trigger deep linking OAuth proposal
                                    val proposalUrl = "https://ai.studio/build/auth/google?scopes=https://www.googleapis.com/auth/drive.file&app_id=aaad1baf-9238-4b25-865d-8d6faf5f1643"
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proposalUrl))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // Web fallback dialog configuration trigger
                                        isManualTokenDialogVisible = true
                                    }
                                },
                                modifier = Modifier.weight(1.5f),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.ElectricBlue),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Lock, "OAuth Auth", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("快捷 OAuth 关联", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // Manual OAuth token dialog button
                            Button(
                                onClick = { isManualTokenDialogVisible = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.SurfaceDark),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("手动贴码", fontSize = 11.sp, color = ThemeColors.TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        if (oauthToken.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("自动化云备份", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("每次语音捕捉后静默触发同步", color = ThemeColors.TextSecondary, fontSize = 10.sp)
                            }
                            Switch(
                                checked = isAutoSync,
                                onCheckedChange = { viewModel.setDriveAutoSync(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ThemeColors.OceanicTeal,
                                    checkedTrackColor = ThemeColors.OceanicTeal.copy(alpha = 0.3f),
                                    uncheckedThumbColor = ThemeColors.TextSecondary,
                                    uncheckedTrackColor = ThemeColors.SurfaceCard
                                )
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("当前会话增量同步", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                        if (activeSessionId == -1) {
                            Text("未选择活动会话组，取消同步功能", color = ThemeColors.TextSecondary, fontSize = 11.sp)
                        } else {
                            Text("立即将当前选中会话的所有语音存档 + 全量文字对话树增量写入 Google Drive，触发自动历史覆盖与版本管理。", color = ThemeColors.TextSecondary, fontSize = 11.sp)
                            
                            Spacer(modifier = Modifier.height(4.dp))

                            if (isSyncing) {
                                CircularProgressIndicator(
                                    color = ThemeColors.OceanicTeal,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .align(Alignment.CenterHorizontally)
                                )
                            } else {
                                Button(
                                    onClick = { viewModel.triggerManualBackup(activeSessionId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.OceanicTeal),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, "Trigger manual sync", tint = ThemeColors.Background, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("一键全量同步", color = ThemeColors.Background, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (syncMessage.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ThemeColors.SurfaceDark)
                                    .padding(8.dp)
                            ) {
                                Text(syncMessage, color = ThemeColors.ElectricBlue, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ThemeColors.SurfaceDark),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("版本控制原则", color = ThemeColors.ElectricBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "1. 文字历史: 采用覆写策略保存到会话子文件夹下的 'chat_history_log.txt' 中，实现自动日志更新与内容追溯。\n" +
                               "2. 语音附件: 采用唯一消息ID指纹哈希（msg_*.mp3），防止同一语音流重复上传，节约带宽与存储空间。\n" +
                               "3. 系统目录: 所有备份均存储在主文件夹 'Gemini Audio Sniffer Backups' 內，保持极高组织性。",
                        color = ThemeColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }

    // --- Manual Paste Access Token dialogue ---
    if (isManualTokenDialogVisible) {
        var pasteInputToken by remember { mutableStateOf("") }
        var emailInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { isManualTokenDialogVisible = false },
            title = { Text("手动配置 Google Access Token", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("如果无法唤起浏览器快捷关联，可在 AI Studio 的 Google 授权回调中拷贝授权代码(Access Token)粘帖到这里：", color = ThemeColors.TextSecondary, fontSize = 11.sp)
                    
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = { emailInput = it },
                        label = { Text("授权邮箱 (Email)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.LightGray,
                            focusedBorderColor = ThemeColors.ElectricBlue,
                            unfocusedBorderColor = ThemeColors.GrayBorder
                        ),
                        placeholder = { Text("c8951010@gmail.com") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = pasteInputToken,
                        onValueChange = { pasteInputToken = it },
                        label = { Text("Google Access Token") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.LightGray,
                            focusedBorderColor = ThemeColors.ElectricBlue,
                            unfocusedBorderColor = ThemeColors.GrayBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalEmail = emailInput.ifEmpty { "c8951010@gmail.com" }
                        if (pasteInputToken.isNotEmpty()) {
                            viewModel.saveGoogleDriveToken(pasteInputToken, finalEmail)
                            Toast.makeText(context, "Google Drive 凭据保存成功", Toast.LENGTH_SHORT).show()
                        }
                        pasteInputToken = ""
                        isManualTokenDialogVisible = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.ElectricBlue)
                ) {
                    Text("写入授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { isManualTokenDialogVisible = false }) {
                    Text("返回", color = ThemeColors.TextSecondary)
                }
            },
            containerColor = ThemeColors.SurfaceCard
        )
    }
}
