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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
    val Blue = Color(0xFF5B8DEF)
    val Amber = Color(0xFFE8A33D)
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
                                else -> Home(suPath, checked, modules, snap, refreshing) { refresh() }
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
            val cpu = cpuUsage()
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

/**
 * 轻触手感：按下立刻凹陷，松手低阻尼弹回。
 */
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
    onRefresh: () -> Unit
) {
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
                Text("RuneSU", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    fontSize = 12.sp, color = RuneTokens.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Outlined.Refresh, contentDescription = "刷新",
                    tint = if (refreshing) RuneTokens.Muted else RuneTokens.Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RootBadge(suPath, checked)
            Spacer(Modifier.width(10.dp))
            Text(
                "Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "-"}",
                fontSize = 11.sp, color = RuneTokens.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("CPU", snap.cpu, RuneTokens.Accent, Icons.Outlined.Memory)
            Tile("内存", snap.mem, RuneTokens.Blue, Icons.Outlined.DeveloperBoard)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("存储", snap.storage, RuneTokens.Amber, Icons.Outlined.Storage)
            Tile("电池", snap.battery, RuneTokens.Success, Icons.Outlined.BatteryFull)
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            InfoRow(Icons.Outlined.Thermostat, "电池温度", snap.temp?.let { String.format("%.1f °C", it) } ?: "—")
            RowDivider()
            InfoRow(Icons.Outlined.Memory, "内存", snap.memText ?: "—")
            RowDivider()
            InfoRow(Icons.Outlined.Storage, "存储", snap.storageText ?: "—")
            RowDivider()
            InfoRow(Icons.Outlined.Thermostat, "内核版本", System.getProperty("os.version") ?: "—")
            RowDivider()
            InfoRow(Icons.Outlined.Fingerprint, "系统指纹", Build.FINGERPRINT)
        }

        Spacer(Modifier.height(12.dp))
        SectionCard {
            InfoRow(Icons.Outlined.Security, "SELinux 状态", selinuxStatus())
            RowDivider()
            InfoRow(Icons.Outlined.FilterList, "Seccomp 状态", seccompStatus())
            RowDivider()
            InfoRow(Icons.Outlined.Extension, "模块数量", modules?.let { "$it 个" } ?: "—")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RowScope.Tile(label: String, value: Int?, color: Color, icon: ImageVector) {
    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.weight(1f).height(104.dp).bouncy(depth = 0.95f, damping = 0.28f, stiffness = 380f)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 11.sp, color = RuneTokens.Muted)
            }
            Spacer(Modifier.weight(1f))
            Text(
                value?.let { "$it%" } ?: "—",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(4.dp)
                    .background(RuneTokens.Panel2, RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth(((value ?: 0) / 100f).coerceIn(0f, 1f))
                        .height(4.dp).background(color, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun RootBadge(suPath: String?, checked: Boolean) {
    val color = when {
        !checked -> RuneTokens.Muted
        suPath != null -> RuneTokens.Success
        else -> RuneTokens.Danger
    }
    val label = when {
        !checked -> "检测中"
        suPath != null -> "Root 已连接"
        else -> "未 Root"
    }
    Row(
        Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.97f, damping = 0.34f, stiffness = 420f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), content = content)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.padding(top = 1.dp).size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 12.5.sp, color = RuneTokens.Muted, lineHeight = 18.sp)
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

private fun cpuUsage(): Int? = try {
    fun read(): LongArray {
        val line = File("/proc/stat").readLines().first { it.startsWith("cpu ") }
        val p = line.trim().split(Regex("\\s+"))
        LongArray(8) { i -> p.getOrNull(i + 1)?.toLongOrNull() ?: 0L }
    }
    val a = read()
    Thread.sleep(320)
    val b = read()
    val idle = (b[3] + b[4]) - (a[3] + a[4])
    val total = b.sum() - a.sum()
    if (total <= 0) null
    else (((total - idle) * 100) / total).toInt().coerceIn(0, 100)
} catch (_: Exception) { null }

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
