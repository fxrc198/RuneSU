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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

object RuneTokens {
    val Bg = Color(0xFFF4F8FB)
    val Panel = Color(0xFFFFFFFF)
    val Panel2 = Color(0xFFEFF4F8)
    val Border = Color(0xFFE4EBF2)
    val Text = Color(0xFF141C26)
    val Muted = Color(0xFF6B7A8D)
    val Accent = Color(0xFF2FB9A8)
    val Blue = Color(0xFF5B8DEF)
    val Amber = Color(0xFFE8A33D)
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF12A05C)
    val HeroTop = Color(0xFFE3F7F2)
}

data class Snapshot(
    val cpu: Int? = null,
    val mem: Int? = null,
    val storage: Int? = null,
    val battery: Int? = null,
    val temp: Double? = null
)

data class StatItem(val icon: ImageVector, val value: Int, val label: String, val tint: Color)

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)
    private var suPath by mutableStateOf<String?>(null)
    private var checked by mutableStateOf(false)
    private var modules by mutableStateOf<Int?>(null)
    private var page by mutableStateOf(0)
    private var snap by mutableStateOf(Snapshot())
    private var refreshing by mutableStateOf(false)
    private var termEnabled by mutableStateOf(false)
    private var foreground by mutableStateOf(true)

    private val requestAll = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshGrant() }

    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { super.onPause(); foreground = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
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
                    primary = RuneTokens.Accent,
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
                                3 -> SettingsPage(termEnabled) { enabled ->
                                    termEnabled = enabled
                                    if (!enabled && page == 4) page = 0
                                }
                                4 -> Placeholder("终端", "命令执行下一步接")
                                else -> Home(
                                    suPath = suPath,
                                    checked = checked,
                                    modules = modules,
                                    snap = snap,
                                    onOpen = { page = it }
                                )
                            }
                        }
                        BottomBar(termEnabled, page) { idx ->
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
            val result = Snapshot(
                cpu = cpuLoad(),
                mem = memInfo(),
                storage = storageInfo(),
                battery = batteryPct(ctx),
                temp = tempC()
            )
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
            } catch (_: Exception) { ok = false }
            val cnt = try { File("/data/adb/modules").listFiles()?.size } catch (_: Exception) { null }
            Handler(Looper.getMainLooper()).post {
                suPath = if (ok) (found ?: "su") else null
                modules = cnt
                checked = true
            }
        }.start()
    }
}

@Composable
private fun Modifier.bouncy(
    depth: Float = 0.96f,
    damping: Float = 0.30f,
    stiffness: Float = 400f,
    onClick: (() -> Unit)? = null
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }

    LaunchedEffect(pressed) {
        if (pressed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            scale.animateTo(depth, spring(dampingRatio = 1f, stiffness = 1600f))
        } else {
            scale.animateTo(1f, spring(dampingRatio = damping, stiffness = stiffness))
        }
    }

    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(interactionSource = interaction, indication = null) { onClick?.invoke() }
}

@Composable
private fun Home(
    suPath: String?,
    checked: Boolean,
    modules: Int?,
    snap: Snapshot,
    onOpen: (Int) -> Unit
) {
    val stats = buildList {
        snap.cpu?.let { add(StatItem(Icons.Outlined.Memory, it, "负载", RuneTokens.Accent)) }
        snap.mem?.let { add(StatItem(Icons.Outlined.DeveloperBoard, it, "内存", RuneTokens.Blue)) }
        snap.storage?.let { add(StatItem(Icons.Outlined.Storage, it, "存储", RuneTokens.Amber)) }
        snap.battery?.let { add(StatItem(Icons.Outlined.BatteryFull, it, "电池", RuneTokens.Success)) }
    }
    val info = buildList {
        add(Icons.Outlined.Code to ("内核版本" to (System.getProperty("os.version") ?: "")))
        cpuModel()?.let { add(Icons.Outlined.Memory to ("处理器" to it)) }
        add(Icons.Outlined.Fingerprint to ("系统指纹" to Build.FINGERPRINT))
        snap.temp?.let { add(Icons.Outlined.Thermostat to ("电池温度" to String.format("%.1f °C", it))) }
    }.filter { it.second.second.isNotBlank() }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to RuneTokens.HeroTop,
                    0.35f to RuneTokens.Bg,
                    1f to RuneTokens.Bg
                )
            )
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Text("RuneSU", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
        Spacer(Modifier.height(3.dp))
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            fontSize = 11.5.sp, color = RuneTokens.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(14.dp))
        StatusModuleCard(suPath, checked, modules, stats) { onOpen(2) }

        SectionLabel("系统")
        SectionCard {
            info.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                InfoRow(entry.first, entry.second.first, entry.second.second)
            }
        }

        SectionLabel("安全")
        SectionCard {
            InfoRow(Icons.Outlined.Security, "SELinux 状态", selinuxStatus())
            RowDivider()
            InfoRow(Icons.Outlined.FilterList, "Seccomp 状态", seccompStatus())
        }

        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun StatusModuleCard(
    suPath: String?,
    checked: Boolean,
    modules: Int?,
    stats: List<StatItem>,
    onModules: () -> Unit
) {
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
    val sub = if (suPath != null) suPath else "安装 KernelSU 后自动启用"

    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, RuneTokens.Border),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.985f, damping = 0.4f, stiffness = 500f)
    ) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (suPath != null) Icons.Outlined.VerifiedUser else Icons.Outlined.GppBad,
                        null, tint = color, modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub, fontSize = 11.5.sp, color = RuneTokens.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            RowDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onModules() }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Extension, null, tint = RuneTokens.Accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(12.dp))
                Text("模块", fontSize = 14.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
                Text(modules?.let { "$it 个" } ?: "未接入", fontSize = 12.sp, color = RuneTokens.Muted)
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = RuneTokens.Muted, modifier = Modifier.size(16.dp)
                )
            }
            if (stats.isNotEmpty()) {
                RowDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                    stats.forEach { StatCell(it) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatCell(item: StatItem) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(26.dp).background(item.tint.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(item.icon, null, tint = item.tint, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text("${item.value}%", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
        Spacer(Modifier.height(1.dp))
        Text(item.label, fontSize = 9.5.sp, color = RuneTokens.Muted)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(
        Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 3.dp, height = 11.dp).background(RuneTokens.Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 11.sp, color = RuneTokens.Muted)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, RuneTokens.Border),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.985f, damping = 0.36f, stiffness = 450f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), content = content)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, badge: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).background(RuneTokens.Accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = RuneTokens.Accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 14.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
        Text(badge, fontSize = 12.sp, color = RuneTokens.Muted)
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = RuneTokens.Muted, modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.padding(top = 1.dp).size(19.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 11.5.sp, color = RuneTokens.Muted, lineHeight = 16.sp)
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
        Icon(Icons.Outlined.Folder, null, tint = RuneTokens.Accent, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(12.dp))
        Text("需要「所有文件访问」权限", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
        Spacer(Modifier.height(6.dp))
        Text("用于浏览和整理文件", fontSize = 12.sp, color = RuneTokens.Muted)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAsk,
            colors = ButtonDefaults.buttonColors(containerColor = RuneTokens.Accent)
        ) { Text("去授权") }
    }
}

@Composable
private fun SettingsPage(termEnabled: Boolean, onTerm: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)

        SectionLabel("功能")
        SectionCard {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(30.dp).background(RuneTokens.Accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Terminal, null, tint = RuneTokens.Accent, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("终端", fontSize = 14.sp, color = RuneTokens.Text)
                    Text("开启后导航栏显示终端", fontSize = 11.sp, color = RuneTokens.Muted)
                }
                Switch(checked = termEnabled, onCheckedChange = onTerm)
            }
            RowDivider()
            ActionRow(Icons.Outlined.Description, "导出日志", "未接入") {
                Toast.makeText(ctx, "接入 Root 后可用", Toast.LENGTH_SHORT).show()
            }
        }

        SectionLabel("关于")
        SectionCard {
            InfoRow(Icons.Outlined.Info, "版本", "0.9.0")
            RowDivider()
            InfoRow(Icons.Outlined.Code, "项目", "RuneSU · 基于 KernelSU 生态")
        }

        Spacer(Modifier.height(22.dp))
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
private fun BottomBar(termEnabled: Boolean, current: Int, onSelect: (Int) -> Unit) {
    val animatedWeight by animateFloatAsState(
        targetValue = if (termEnabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 700f),
        label = "termWeight"
    )
    val termWeight = animatedWeight.coerceIn(0.0001f, 1f)
    val termAlpha by animateFloatAsState(
        targetValue = if (termEnabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 600f),
        label = "termAlpha"
    )

    Surface(color = RuneTokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(Icons.Outlined.Home, "首页", 0, current, onSelect)
            NavItem(Icons.Outlined.Folder, "文件", 1, current, onSelect)
            NavItem(Icons.Outlined.Extension, "模块", 2, current, onSelect)
            Box(Modifier.weight(termWeight).fillMaxHeight().clipToBounds()) {
                Column(
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = termAlpha }
                        .clickable { onSelect(4) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Terminal, null,
                        tint = if (current == 4) RuneTokens.Accent else RuneTokens.Muted,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "终端", fontSize = 11.sp,
                        color = if (current == 4) RuneTokens.Accent else RuneTokens.Muted
                    )
                }
            }
            NavItem(Icons.Outlined.Settings, "设置", 3, current, onSelect)
        }
    }
}

@Composable
private fun RowScope.NavItem(
    icon: ImageVector,
    label: String,
    idx: Int,
    current: Int,
    onSelect: (Int) -> Unit
) {
    val active = current == idx
    val tint = if (active) RuneTokens.Accent else RuneTokens.Muted
    Column(
        Modifier.weight(1f).fillMaxHeight()
            .bouncy(depth = 0.90f, damping = 0.28f, stiffness = 520f) { onSelect(idx) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = tint)
    }
}

private fun cpuLoad(): Int? {
    val stat = cpuUsage()
    if (stat != null) return stat
    return try {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val load = File("/proc/loadavg").readText().trim().split(Regex("\\s+")).first().toDouble()
        ((load / cores) * 100.0).toInt().coerceIn(0, 100)
    } catch (_: Exception) { null }
}

private fun cpuUsage(): Int? {
    fun read(): LongArray {
        val line = File("/proc/stat").readLines()
            .firstOrNull { it.startsWith("cpu ") } ?: return LongArray(8)
        val p = line.trim().split(Regex("\\s+"))
        return LongArray(8) { i -> p.getOrNull(i + 1)?.toLongOrNull() ?: 0L }
    }
    return try {
        val a = read()
        Thread.sleep(320)
        val b = read()
        val idle = (b[3] + b[4]) - (a[3] + a[4])
        val total = b.sum() - a.sum()
        if (total <= 0) null
        else (((total - idle) * 100) / total).toInt().coerceIn(0, 100)
    } catch (_: Exception) { null }
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
        File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(':')?.trim()?.ifEmpty { null }
    } catch (_: Exception) { null }
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
