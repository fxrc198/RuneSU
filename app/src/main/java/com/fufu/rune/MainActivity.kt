package com.fufu.rune

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RuneTokens {
    val Bg = Color(0xFFF4F5F7)
    val Panel = Color(0xFFFFFFFF)
    val Panel2 = Color(0xFFEDEFF2)
    val Border = Color(0xFFE3E6EA)
    val Text = Color(0xFF1A1C1E)
    val Muted = Color(0xFF6B7280)
    val Primary = Color(0xFF1F2937)
    val Accent = Color(0xFFD97706)
    val Danger = Color(0xFFDC2626)
    val Success = Color(0xFF16A34A)
}

data class Snapshot(
    val cpu: Int? = null,
    val mem: Int? = null,
    val storage: Int? = null,
    val battery: Int? = null,
    val temp: Double? = null
)

data class NavEntry(val id: Int, val icon: ImageVector, val label: String)

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)
    private var suPath by mutableStateOf<String?>(null)
    private var checked by mutableStateOf(false)
    private var modules by mutableStateOf<Int?>(null)
    private var page by mutableStateOf(0)
    private var snap by mutableStateOf(Snapshot())
    private var refreshing by mutableStateOf(false)
    private var termEnabled by mutableStateOf(false)
    private var radial by mutableStateOf(false)
    private var foreground by mutableStateOf(true)

    private val requestAll = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshGrant() }

    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { super.onPause(); foreground = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        refreshGrant()
        detectRoot()
        setContent {
            LaunchedEffect(page, foreground) {
                if (page == 0 && foreground) {
                    while (true) {
                        refresh()
                        delay(2500)
                    }
                }
            }
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = RuneTokens.Primary,
                    background = RuneTokens.Bg,
                    surface = RuneTokens.Panel,
                    onBackground = RuneTokens.Text,
                    onSurface = RuneTokens.Text
                )
            ) {
                Box(Modifier.fillMaxSize().background(RuneTokens.Bg)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            when (page) {
                                1 -> if (granted) FileScreen(
                                    Environment.getExternalStorageDirectory().absolutePath
                                ) else PermissionHint { askAllFiles() }
                                2 -> Placeholder("模块", "接入 /data/adb/modules 后显示")
                                3 -> SettingsPage(
                                    termEnabled, radial,
                                    onTerm = { enabled ->
                                        termEnabled = enabled
                                        if (!enabled && page == 4) page = 0
                                    },
                                    onRadial = { radial = it }
                                )
                                4 -> Placeholder("终端", "命令执行下一步接")
                                5 -> WorkflowScreen()
                                else -> Home(suPath, checked, modules, snap)
                            }
                        }
                        if (!radial) {
                            BottomNav(termEnabled, page) { idx ->
                                if (idx == 1 && !granted) askAllFiles() else page = idx
                            }
                        }
                    }
                    if (radial) {
                        RadialNav(page, navEntries(termEnabled)) { idx ->
                            if (idx == 1 && !granted) askAllFiles() else page = idx
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        val ctx = applicationContext
        Thread {
            val result = Snapshot(cpuLoad(), memInfo(), storageInfo(), batteryPct(ctx), tempC())
            Handler(Looper.getMainLooper()).post {
                snap = result
                refreshing = false
            }
        }.start()
    }

    private fun refreshGrant() {
        granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true
    }

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:$packageName")
            requestAll.launch(i)
        }
    }

    private fun detectRoot() {
        Thread {
            val paths = listOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/data/adb/ksu/bin/ksu", "/data/adb/magisk/magisk"
            )
            val found = paths.firstOrNull { File(it).exists() }
            var ok = false
            try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val done = p.waitFor(3, TimeUnit.SECONDS)
                if (!done) p.destroy()
                else ok = p.inputStream.bufferedReader().readText().contains("uid=0")
            } catch (_: Exception) {
                ok = false
            }
            val cnt = try { File("/data/adb/modules").listFiles()?.size } catch (_: Exception) { null }
            Handler(Looper.getMainLooper()).post {
                suPath = if (ok) (found ?: "su") else null
                modules = cnt
                checked = true
            }
        }.start()
    }
}

private fun navEntries(term: Boolean): List<NavEntry> = buildList {
    add(NavEntry(0, Icons.Outlined.Home, "首页"))
    add(NavEntry(1, Icons.Outlined.Folder, "文件"))
    add(NavEntry(5, Icons.Outlined.AccountTree, "任务"))
    add(NavEntry(2, Icons.Outlined.Extension, "模块"))
    if (term) add(NavEntry(4, Icons.Outlined.Terminal, "终端"))
    add(NavEntry(3, Icons.Outlined.Settings, "设置"))
}

@Composable
private fun Modifier.bouncy(
    depth: Float = 0.96f,
    damping: Float = 0.32f,
    stiffness: Float = 480f,
    onClick: (() -> Unit)? = null
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scale.animateTo(depth, spring(dampingRatio = 1f, stiffness = 1400f))
        } else {
            scale.animateTo(1f, spring(dampingRatio = damping, stiffness = stiffness))
        }
    }
    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(interactionSource = interaction, indication = null) { onClick?.invoke() }
}

@Composable
private fun Home(suPath: String?, checked: Boolean, modules: Int?, snap: Snapshot) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("RuneSU", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
        Spacer(Modifier.height(2.dp))
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}".trim(),
            fontSize = 11.sp, color = RuneTokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        StatusCard(suPath, checked)
        Spacer(Modifier.height(8.dp))
        PerfCard(snap, modules)
        SectionLabel("设备")
        DeviceCard(snap)
        SectionLabel("安全")
        PlainCard {
            InfoRow(Icons.Outlined.Security, "SELinux", selinuxStatus())
            RowDivider()
            InfoRow(Icons.Outlined.FilterList, "Seccomp", seccompStatus())
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun StatusCard(suPath: String?, checked: Boolean) {
    val color = when {
        !checked -> RuneTokens.Muted
        suPath != null -> RuneTokens.Success
        else -> RuneTokens.Danger
    }
    val title = when {
        !checked -> "正在检测…"
        suPath != null -> "Root 已连接"
        else -> "未检测到 Root"
    }
    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.99f, damping = 0.42f, stiffness = 520f)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (suPath != null) suPath else "安装 KernelSU 后自动启用",
                    fontSize = 11.sp, color = RuneTokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PerfCard(snap: Snapshot, modules: Int?) {
    Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Row {
                PerfCell(Icons.Outlined.Memory, "内存", snap.mem, Modifier.weight(1f))
                PerfCell(Icons.Outlined.Storage, "存储", snap.storage, Modifier.weight(1f))
            }
            HorizontalDivider(thickness = 0.7.dp, color = RuneTokens.Border)
            Row {
                PerfCell(Icons.Outlined.BatteryFull, "电池", snap.battery, Modifier.weight(1f))
                ModuleCell(modules, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PerfCell(icon: ImageVector, label: String, value: Int?, modifier: Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 10.5.sp, color = RuneTokens.Muted)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.toString() ?: "—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
            if (value != null) {
                Text("%", fontSize = 10.5.sp, color = RuneTokens.Muted,
                    modifier = Modifier.padding(start = 1.dp, bottom = 2.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(RuneTokens.Panel2, RoundedCornerShape(2.dp))) {
            Box(
                Modifier.fillMaxWidth(((value ?: 0) / 100f).coerceIn(0f, 1f)).height(3.dp)
                    .background(RuneTokens.Accent, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun ModuleCell(modules: Int?, modifier: Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Extension, null, tint = RuneTokens.Muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text("模块", fontSize = 10.5.sp, color = RuneTokens.Muted)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            modules?.let { "$it 个" } ?: "未接入",
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(RuneTokens.Panel2, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun DeviceCard(snap: Snapshot) {
    var expanded by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(expanded) {
        if (expanded && extra.isEmpty()) {
            extra = withContext(Dispatchers.IO) { loadProps() }
        }
    }
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "-"
    val kernel = (System.getProperty("os.version") ?: "-").substringBefore("-g")
    val cells = listOf(
        Icons.Outlined.Memory to ("处理器" to (cpuModel() ?: "—")),
        Icons.Outlined.DeveloperBoard to ("架构" to abi),
        Icons.Outlined.SystemUpdate to ("系统" to "Android ${Build.VERSION.RELEASE}"),
        Icons.Outlined.Code to ("内核" to kernel),
        Icons.Outlined.Fingerprint to ("指纹" to Build.FINGERPRINT),
        Icons.Outlined.Thermostat to ("温度" to (snap.temp?.let { String.format("%.1f°C", it) } ?: "—"))
    )

    Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            cells.chunked(2).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) HorizontalDivider(thickness = 0.7.dp, color = RuneTokens.Border)
                Row {
                    row.forEach { (icon, pair) ->
                        GridCell(icon, pair.first, pair.second, Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            HorizontalDivider(thickness = 0.7.dp, color = RuneTokens.Border)
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Info, null, tint = RuneTokens.Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("更多设备信息", fontSize = 12.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RuneTokens.Muted,
                    modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    if (extra.isEmpty()) {
                        Text("读取中…", fontSize = 11.sp, color = RuneTokens.Muted)
                    } else {
                        extra.forEach { (label, value) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(label, fontSize = 11.sp, color = RuneTokens.Muted,
                                    modifier = Modifier.width(70.dp))
                                Text(value, fontSize = 11.sp, color = RuneTokens.Text,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 9.5.sp, color = RuneTokens.Muted)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, color = RuneTokens.Muted,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), content = content)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, badge: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 13.5.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
        Text(badge, fontSize = 11.5.sp, color = RuneTokens.Muted)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RuneTokens.Muted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 11.sp, color = RuneTokens.Muted, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(thickness = 0.7.dp, color = RuneTokens.Border)
}

@Composable
private fun PermissionHint(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.Folder, null, tint = RuneTokens.Muted, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(12.dp))
        Text("需要「所有文件访问」权限", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
        Spacer(Modifier.height(6.dp))
        Text("用于浏览和整理文件", fontSize = 11.5.sp, color = RuneTokens.Muted)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAsk,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RuneTokens.Primary)
        ) { Text("去授权", fontSize = 13.sp) }
    }
}

@Composable
private fun SettingsPage(
    termEnabled: Boolean,
    radial: Boolean,
    onTerm: (Boolean) -> Unit,
    onRadial: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)

        SectionLabel("界面")
        PlainCard {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RadioButtonChecked, null, tint = RuneTokens.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("环形手势导航", fontSize = 13.5.sp, color = RuneTokens.Text)
                    Text("按住中心钮向上滑动选择", fontSize = 11.sp, color = RuneTokens.Muted)
                }
                Switch(checked = radial, onCheckedChange = onRadial)
            }
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = RuneTokens.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("终端", fontSize = 13.5.sp, color = RuneTokens.Text)
                    Text("开启后导航栏显示终端", fontSize = 11.sp, color = RuneTokens.Muted)
                }
                Switch(checked = termEnabled, onCheckedChange = onTerm)
            }
        }

        SectionLabel("Root")
        PlainCard {
            ActionRow(Icons.Outlined.Language, "网页控制", "未接入") {
                Toast.makeText(ctx, "需要 Root 环境", Toast.LENGTH_SHORT).show()
            }
            RowDivider()
            ActionRow(Icons.Outlined.Description, "导出日志", "未接入") {
                Toast.makeText(ctx, "接入 Root 后可用", Toast.LENGTH_SHORT).show()
            }
        }

        SectionLabel("关于")
        PlainCard {
            InfoRow(Icons.Outlined.Info, "版本", "1.3.0")
            RowDivider()
            InfoRow(Icons.Outlined.Code, "项目", "RuneSU · 基于 KernelSU 生态")
        }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun Placeholder(title: String, desc: String) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
        Spacer(Modifier.height(6.dp))
        Text(desc, fontSize = 12.sp, color = RuneTokens.Muted)
    }
}

@Composable
private fun BottomNav(termEnabled: Boolean, current: Int, onSelect: (Int) -> Unit) {
    val items = navEntries(termEnabled)
    Surface(color = RuneTokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { entry ->
                val active = current == entry.id
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .bouncy(depth = 0.9f, damping = 0.3f, stiffness = 560f) { onSelect(entry.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            entry.icon, null,
                            tint = if (active) RuneTokens.Primary else RuneTokens.Muted,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        entry.label, fontSize = 10.sp,
                        color = if (active) RuneTokens.Primary else RuneTokens.Muted
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier.size(width = 14.dp, height = 3.dp).background(
                            if (active) RuneTokens.Accent else Color.Transparent,
                            RoundedCornerShape(2.dp)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RadialNav(current: Int, items: List<NavEntry>, onSelect: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(-1) }
    val progress = remember { Animatable(0f) }
    val btnDp = 56.dp
    val radiusDp = 100.dp

    LaunchedEffect(open) {
        progress.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f))
    }

    val step = if (items.size > 1) 156.0 / (items.size - 1) else 0.0

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cx = maxWidth / 2
        val cy = maxHeight - 92.dp
        val p = progress.value

        if (p > 0.01f) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.14f * p))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { open = false; active = -1 }
            )
        }

        items.forEachIndexed { i, entry ->
            val t = ((p - i * 0.04f) / 0.8f).coerceIn(0f, 1f)
            val angle = Math.toRadians(-78.0 + i * step)
            val x = cx + radiusDp * sin(angle).toFloat()
            val y = cy - radiusDp * cos(angle).toFloat()
            val isActive = active == i
            Box(
                Modifier
                    .offset(x = x - 24.dp, y = y - 24.dp)
                    .size(48.dp)
                    .graphicsLayer {
                        alpha = t
                        scaleX = if (isActive) 1.12f else 0.8f + 0.2f * t
                        scaleY = if (isActive) 1.12f else 0.8f + 0.2f * t
                    }
                    .background(if (isActive) RuneTokens.Primary else RuneTokens.Panel, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    entry.icon, null,
                    tint = if (isActive) Color.White else RuneTokens.Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(
            Modifier
                .offset(x = cx - btnDp / 2, y = cy - btnDp / 2)
                .size(btnDp)
                .background(RuneTokens.Primary, CircleShape)
                .pointerInput(items) {
                    detectDragGestures(
                        onDragStart = {
                            open = true
                            active = -1
                            scope.launch {
                                progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 600f))
                            }
                        },
                        onDrag = { change, _ ->
                            val half = with(density) { (btnDp / 2).toPx() }
                            val dx = change.position.x - half
                            val dy = change.position.y - half
                            var rel = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
                            if (rel > 180) rel -= 360
                            if (rel < -180) rel += 360
                            var best = -1
                            var bestDiff = 30.0
                            items.forEachIndexed { i, _ ->
                                val a = -78.0 + i * step
                                val d = abs(rel - a)
                                if (d < bestDiff) { bestDiff = d; best = i }
                            }
                            active = best
                        },
                        onDragEnd = {
                            val index = active
                            scope.launch {
                                open = false
                                active = -1
                                progress.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                            if (index in items.indices) {
                                onSelect(items[index].id)
                            }
                        },
                        onDragCancel = {
                            active = -1
                            scope.launch {
                                open = false
                                progress.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                        }
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { open = !open },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (open) Icons.Outlined.Close else Icons.Outlined.Menu, null,
                tint = Color.White, modifier = Modifier.size(22.dp)
            )
        }
    }
}

private fun loadProps(): List<Pair<String, String>> {
    val keys = listOf(
        "ro.product.device" to "设备代号",
        "ro.build.id" to "构建 ID",
        "ro.boot.slot_suffix" to "槽位",
        "ro.build.version.security_patch" to "安全补丁",
        "ro.soc.model" to "SoC",
        "ro.product.board" to "主板",
        "ro.boot.hardware" to "硬件",
        "ro.build.type" to "构建类型"
    )
    return keys.map { (k, label) -> label to prop(k) }
}

private fun prop(key: String): String = try {
    val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    out.ifEmpty { "—" }
} catch (_: Exception) { "—" }

private fun cpuLoad(): Int? {
    val stat = cpuUsage()
    if (stat != null) return stat
    return try {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val load = File("/proc/loadavg").readText().trim().split(Regex("\\s+")).first().toDouble()
        ((load / cores) * 100.0).toInt().coerceIn(0, 100)
    } catch (_: Exception) {
        null
    }
}

private fun cpuUsage(): Int? {
    fun read(): LongArray {
        val line = File("/proc/stat").readLines().firstOrNull { it.startsWith("cpu ") } ?: return LongArray(8)
        val p = line.trim().split(Regex("\\s+"))
        return LongArray(8) { i -> p.getOrNull(i + 1)?.toLongOrNull() ?: 0L }
    }
    return try {
        val a = read()
        Thread.sleep(320)
        val b = read()
        val idle = (b[3] + b[4]) - (a[3] + a[4])
        val total = b.sum() - a.sum()
        if (total <= 0) null else (((total - idle) * 100) / total).toInt().coerceIn(0, 100)
    } catch (_: Exception) {
        null
    }
}

private fun cpuModel(): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val soc = Build.SOC_MODEL
        if (!soc.isNullOrBlank() && !soc.equals("unknown", ignoreCase = true)) return soc
    }
    for (key in listOf("ro.soc.model", "ro.board.platform", "ro.hardware")) {
        try {
            val proc = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotEmpty() && !out.equals("unknown", ignoreCase = true)) return out
        } catch (_: Exception) { }
    }
    return try {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(':')?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

private fun memInfo(): Int? = try {
    var total = 0L
    var avail = 0L
    File("/proc/meminfo").forEachLine { line ->
        when {
            line.startsWith("MemTotal:") -> total = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            line.startsWith("MemAvailable:") -> avail = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
        }
    }
    if (total <= 0) null else ((total - avail) * 100 / total).toInt()
} catch (_: Exception) { null }

private fun storageInfo(): Int? = try {
    val s = StatFs(Environment.getExternalStorageDirectory().absolutePath)
    val total = s.blockCountLong * s.blockSizeLong
    val avail = s.availableBlocksLong * s.blockSizeLong
    if (total <= 0) null else ((total - avail) * 100 / total).toInt()
} catch (_: Exception) { null }

private fun batteryPct(ctx: Context): Int? = try {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (v in 0..100) v else null
} catch (_: Exception) { null }

private fun tempC(): Double? = try {
    File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toLong() / 1000.0
} catch (_: Exception) { null }

private fun selinuxStatus(): String {
    try {
        val cls = Class.forName("android.os.SELinux")
        val m = cls.getMethod("isSELinuxEnforced")
        val enforced = m.invoke(null) as? Boolean
        if (enforced != null) return if (enforced) "强制执行" else "宽容模式"
    } catch (_: Throwable) { }
    return "不可读（需要 Root）"
}

private fun seccompStatus(): String = try {
    val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("Seccomp:") }
    when (line?.substringAfter(":")?.trim()) {
        "0" -> "关闭"
        "1" -> "严格模式"
        "2" -> "过滤模式"
        else -> "未知"
    }
} catch (_: Exception) { "未知" }
