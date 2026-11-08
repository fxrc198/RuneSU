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
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.TimeUnit

object RuneTokens {
    val Bg = Color(0xFFF5F8FB)
    val Panel = Color(0xFFFFFFFF)
    val Panel2 = Color(0xFFEDF2F7)
    val Border = Color(0xFFE1E8F0)
    val Text = Color(0xFF16202C)
    val Muted = Color(0xFF6C7C90)
    val Accent = Color(0xFF2FB9A8)
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF12A05C)
}

data class Snapshot(
    val cpu: Int? = null,
    val mem: Int? = null,
    val memText: String? = null,
    val storage: Int? = null,
    val storageText: String? = null,
    val battery: Int? = null,
    val temp: Double? = null
)

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)
    private var suPath by mutableStateOf<String?>(null)
    private var checked by mutableStateOf(false)
    private var modules by mutableStateOf<Int?>(null)
    private var page by mutableStateOf(0)
    private var snap by mutableStateOf(Snapshot())
    private var refreshing by mutableStateOf(false)

    private val requestAll = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshGrant() }

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
        refresh()
        setContent {
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
                                1 -> Placeholder("文件", "双栏文件管理，下一步做")
                                2 -> Placeholder("模块", "接入 /data/adb/modules 后显示")
                                3 -> Placeholder("设置", "外观 · 导航 · 关于")
                                else -> Home(
                                    suPath = suPath,
                                    checked = checked,
                                    modules = modules,
                                    snap = snap,
                                    refreshing = refreshing,
                                    onRefresh = { refresh() },
                                    onOpen = { page = it }
                                )
                            }
                        }
                        BottomBar(page) { idx ->
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
            val cpu = cpuLoad()
            val mem = memInfo()
            val st = storageInfo()
            val bat = batteryPct(ctx)
            val temp = tempC()
            val result = Snapshot(
                cpu = cpu,
                mem = mem?.first,
                memText = mem?.second,
                storage = st?.first,
                storageText = st?.second,
                battery = bat,
                temp = temp
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
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clickable(interactionSource = interaction, indication = null) { onClick?.invoke() }
}

@Composable
private fun Home(
    suPath: String?,
    checked: Boolean,
    modules: Int?,
    snap: Snapshot,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Int) -> Unit
) {
    val ctx = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RuneSU", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    fontSize = 11.5.sp, color = RuneTokens.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Outlined.Refresh, contentDescription = "刷新",
                    tint = if (refreshing) RuneTokens.Muted else RuneTokens.Accent,
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        StatusCard(suPath, checked)

        Spacer(Modifier.height(10.dp))
        StatStrip(snap)

        Spacer(Modifier.height(10.dp))
        SectionCard {
            ActionRow(Icons.Outlined.Extension, "模块", modules?.let { "$it" } ?: "未接入") { onOpen(2) }
            RowDivider()
            ActionRow(Icons.Outlined.Person, "超级用户", "未接入") {
                Toast.makeText(ctx, "接入 Root 后可用", Toast.LENGTH_SHORT).show()
            }
            RowDivider()
            ActionRow(Icons.Outlined.Terminal, "终端", "未接入") {
                Toast.makeText(ctx, "下一步做", Toast.LENGTH_SHORT).show()
            }
            RowDivider()
            ActionRow(Icons.Outlined.Description, "日志", "未接入") {
                Toast.makeText(ctx, "下一步做", Toast.LENGTH_SHORT).show()
            }
        }

        Spacer(Modifier.height(10.dp))
        SectionCard {
            InfoRow(Icons.Outlined.Code, "内核版本", System.getProperty("os.version") ?: "—")
            RowDivider()
            InfoRow(Icons.Outlined.Memory, "处理器", cpuModel() ?: "—")
            RowDivider()
            InfoRow(Icons.Outlined.Fingerprint, "系统指纹", Build.FINGERPRINT)
            RowDivider()
            InfoRow(Icons.Outlined.Thermostat, "电池温度", snap.temp?.let { String.format("%.1f °C", it) } ?: "—")
        }

        Spacer(Modifier.height(10.dp))
        SectionCard {
            InfoRow(Icons.Outlined.Security, "SELinux 状态", selinuxStatus())
            RowDivider()
            InfoRow(Icons.Outlined.FilterList, "Seccomp 状态", seccompStatus())
        }

        Spacer(Modifier.height(24.dp))
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
    val sub = if (suPath != null) suPath else "安装 KernelSU 后自动启用"

    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.98f, damping = 0.4f, stiffness = 500f)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    sub, fontSize = 11.sp, color = RuneTokens.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatStrip(snap: Snapshot) {
    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.98f, damping = 0.4f, stiffness = 500f)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Stat(snap.cpu, "负载")
            Stat(snap.mem, "内存")
            Stat(snap.storage, "存储")
            Stat(snap.battery, "电池")
        }
    }
}

@Composable
private fun RowScope.Stat(value: Int?, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value?.let { "$it%" } ?: "—",
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = RuneTokens.Muted)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.98f, damping = 0.36f, stiffness = 450f)
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
            Modifier.size(32.dp).background(RuneTokens.Accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = RuneTokens.Accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 14.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
        Text(badge, fontSize = 12.sp, color = RuneTokens.Muted)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = RuneTokens.Muted, modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 12.sp, color = RuneTokens.Muted, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(thickness = 0.7.dp, color = RuneTokens.Border)
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
private fun BottomBar(current: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple(Icons.Outlined.Home, "首页", 0),
        Triple(Icons.Outlined.Folder, "文件", 1),
        Triple(Icons.Outlined.Extension, "模块", 2),
        Triple(Icons.Outlined.Settings, "设置", 3)
    )
    Surface(color = RuneTokens.Panel, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (icon, label, idx) ->
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
        }
    }
}

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
        File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(':')?.trim()?.ifEmpty { null }
    } catch (_: Exception) { null }
}

private fun memInfo(): Pair<Int, String>? = try {
    val map = File("/proc/meminfo").readLines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else {
            val key = line.substring(0, i)
            val num = line.substring(i + 1).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
            if (num == null) null else key to num
        }
    }.toMap()
    val total = map["MemTotal"]
    val avail = map["MemAvailable"]
    if (total == null || avail == null || total <= 0) null else {
        val used = total - avail
        val pct = (used * 100 / total).toInt()
        pct to String.format("%.1f / %.1f GB", used / 1048576.0, total / 1048576.0)
    }
} catch (_: Exception) { null }

private fun storageInfo(): Pair<Int, String>? = try {
    val s = StatFs(Environment.getExternalStorageDirectory().absolutePath)
    val total = s.blockCountLong * s.blockSizeLong
    val avail = s.availableBlocksLong * s.blockSizeLong
    if (total <= 0) null else {
        val used = total - avail
        val pct = (used * 100 / total).toInt()
        pct to String.format("%.1f / %.1f GB", used / 1073741824.0, total / 1073741824.0)
    }
} catch (_: Exception) { null }

private fun batteryPct(ctx: Context): Int? = try {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (v in 0..100) v else null
} catch (_: Exception) { null }

private fun tempC(): Double? = try {
    File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toLong() / 1000.0
} catch (_: Exception) { null }

private fun selinuxStatus(): String = try {
    val v = File("/sys/fs/selinux/enforce").readText().trim()
    if (v == "1") "强制执行" else "宽容模式"
} catch (_: Exception) { "未知" }

private fun seccompStatus(): String = try {
    val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("Seccomp:") }
    when (line?.substringAfter(":")?.trim()) {
        "0" -> "关闭"
        "1" -> "严格模式"
        "2" -> "过滤模式"
        else -> "未知"
    }
} catch (_: Exception) { "未知" }
