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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object RuneTokens {
    val Bg = Color(0xFFF7F8FA)
    val Panel = Color(0xFFFFFFFF)
    val Panel2 = Color(0xFFF1F3F7)
    val Border = Color(0xFFE8EBF1)
    val Text = Color(0xFF141821)
    val Muted = Color(0xFF6B7280)
    val Primary = Color(0xFF4C6FFF)
    val Warn = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Success = Color(0xFF10B981)
}

data class Snapshot(
    val cpu: Int? = null, val mem: Int? = null, val storage: Int? = null,
    val battery: Int? = null, val temp: Double? = null
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
                if (page == 0 && foreground) while (true) { refresh(); delay(2500) }
            }
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = RuneTokens.Primary, background = RuneTokens.Bg,
                    surface = RuneTokens.Panel, onBackground = RuneTokens.Text, onSurface = RuneTokens.Text
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
                                3 -> SettingsPage(termEnabled, radial,
                                    onTerm = { enabled -> termEnabled = enabled; if (!enabled && page == 4) page = 0 },
                                    onRadial = { radial = it })
                                4 -> Placeholder("终端", "命令执行下一步接")
                                5 -> WorkflowScreen()
                                else -> Home(suPath, checked, modules, snap)
                            }
                        }
                        if (!radial) {
                            ArchBar(termEnabled, page) { idx ->
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
            Handler(Looper.getMainLooper()).post { snap = result; refreshing = false }
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
                modules = cnt; checked = true
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
    depth: Float = 0.96f, damping: Float = 0.30f, stiffness: Float = 400f,
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
        } else scale.animateTo(1f, spring(dampingRatio = damping, stiffness = stiffness))
    }
    return this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(interactionSource = interaction, indication = null) { onClick?.invoke() }
}

@Composable
private fun Home(suPath: String?, checked: Boolean, modules: Int?, snap: Snapshot) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("RuneSU", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
        Spacer(Modifier.height(3.dp))
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}".trim(),
            fontSize = 11.5.sp, color = RuneTokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(14.dp))
        StatusCard(suPath, checked)
        Spacer(Modifier.height(10.dp))
        PerfCard(snap, modules)

        SectionLabel("设备")
        DeviceCard(modules, snap)

        SectionLabel("安全")
        SectionCard {
            InfoRow(Icons.Outlined.Security, "SELinux 状态", selinuxStatus())
            RowDivider()
            InfoRow(Icons.Outlined.FilterList, "Seccomp 状态", seccompStatus())
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun StatusCard(suPath: String?, checked: Boolean) {
    val color = when { !checked -> RuneTokens.Muted; suPath != null -> RuneTokens.Success; else -> RuneTokens.Danger }
    val title = when { !checked -> "正在检测…"; suPath != null -> "Root 已连接"; else -> "未检测到 Root" }
    Surface(
        color = RuneTokens.Panel, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, RuneTokens.Border),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.985f, damping = 0.4f, stiffness = 500f)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
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
    Surface(
        color = RuneTokens.Panel, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, RuneTokens.Border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(4.dp)) {
            Row {
                PerfCell(Icons.Outlined.Memory, "内存", snap.mem, RuneTokens.Primary, Modifier.weight(1f))
                PerfCell(Icons.Outlined.Storage, "存储", snap.storage, RuneTokens.Warn, Modifier.weight(1f))
            }
            Row {
                PerfCell(Icons.Outlined.BatteryFull, "电池", snap.battery, RuneTokens.Success, Modifier.weight(1f))
                ModuleCell(modules, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PerfCell(icon: ImageVector, label: String, value: Int?, tint: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp)) }
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 10.5.sp, color = RuneTokens.Muted)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.toString() ?: "—", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
            if (value != null) Text("%", fontSize = 11.sp, color = RuneTokens.Muted,
                modifier = Modifier.padding(start = 1.dp, bottom = 2.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(3.5.dp).background(RuneTokens.Panel2, RoundedCornerShape(2.dp))) {
            Box(
                Modifier.fillMaxWidth(((value ?: 0) / 100f).coerceIn(0f, 1f)).height(3.5.dp)
                    .background(tint, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun ModuleCell(modules: Int?, modifier: Modifier) {
    Column(modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(20.dp).background(RuneTokens.Primary.copy(alpha = 0.14f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Extension, null, tint = RuneTokens.Primary, modifier = Modifier.size(12.dp)) }
            Spacer(Modifier.width(6.dp))
            Text("模块", fontSize = 10.5.sp, color = RuneTokens.Muted)
        }
        Spacer(Modifier.height(5.dp))
        Text(
            modules?.let { "$it 个" } ?: "未接入", fontSize = 17.sp, fontWeight = FontWeight.Bold,
            color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(3.5.dp).background(RuneTokens.Panel2, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun DeviceCard(modules: Int?, snap: Snapshot) {
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, pair) -> GridCell(icon, pair.first, pair.second, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Surface(
            color = RuneTokens.Panel, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, RuneTokens.Border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Info, null, tint = RuneTokens.Primary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("更多设备信息", fontSize = 12.5.sp, color = RuneTokens.Text,
                        modifier = Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                        tint = RuneTokens.Muted,
                        modifier = Modifier.size(16.dp).graphicsLayer {
                            rotationZ = if (expanded) 90f else 0f
                        }
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                        if (extra.isEmpty()) {
                            Text("读取中…", fontSize = 11.sp, color = RuneTokens.Muted)
                        } else {
                            extra.forEach { (label, value) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(label, fontSize = 11.sp, color = RuneTokens.Muted,
                                        modifier = Modifier.width(72.dp))
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
}

@Composable
private fun GridCell(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Surface(
        color = RuneTokens.Panel, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, RuneTokens.Border),
        modifier = modifier.height(62.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = RuneTokens.Primary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(5.dp))
                Text(label, fontSize = 9.5.sp, color = RuneTokens.Muted)
            }
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, color = RuneTokens.Muted,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 7.dp))
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = RuneTokens.Panel, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, RuneTokens.Border),
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), content = content) }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, badge: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 14.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
        Text(badge, fontSize = 12.sp, color = RuneTokens.Muted)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RuneTokens.Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = RuneTokens.Muted, modifier = Modifier.padding(top = 1.dp).size(19.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
            Spacer(Modifier.height(3.dp))
            Text(value, fontSize = 11.5.sp, color = RuneTokens.Muted, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun RowDivider() { HorizontalDivider(thickness = 0.7.dp, color = RuneTokens.Border) }

@Composable
private fun PermissionHint(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Folder, null, tint = RuneTokens.Primary, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(12.dp))
        Text("需要「所有文件访问」权限", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
        Spacer(Modifier.height(6.dp))
        Text("用于浏览和整理文件", fontSize = 12.sp, color = RuneTokens.Muted)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAsk, colors = ButtonDefaults.buttonColors(containerColor = RuneTokens.Primary)) {
            Text("去授权")
        }
    }
}

@Composable
private fun SettingsPage(
    termEnabled: Boolean, radial: Boolean,
    onTerm: (Boolean) -> Unit, onRadial: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)

        SectionLabel("界面")
        SectionCard {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RadioButtonChecked, null, tint = RuneTokens.Muted, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("扇形导航", fontSize = 14.sp, color = RuneTokens.Text)
                    Text("拖动展开，松手吸附", fontSize = 11.sp, color = RuneTokens.Muted)
                }
                Switch(checked = radial, onCheckedChange = onRadial)
            }
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Terminal, null, tint = RuneTokens.Muted, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("终端", fontSize = 14.sp, color = RuneTokens.Text)
                    Text("开启后导航栏显示终端", fontSize = 11.sp, color = RuneTokens.Muted)
                }
                Switch(checked = termEnabled, onCheckedChange = onTerm)
            }
        }

        SectionLabel("Root")
        SectionCard {
            ActionRow(Icons.Outlined.Language, "网页控制", "未接入") {
                Toast.makeText(ctx, "需要 Root 环境", Toast.LENGTH_SHORT).show()
            }
            RowDivider()
            ActionRow(Icons.Outlined.Description, "导出日志", "未接入") {
                Toast.makeText(ctx, "接入 Root 后可用", Toast.LENGTH_SHORT).show()
            }
        }

        SectionLabel("关于")
        SectionCard {
            InfoRow(Icons.Outlined.Info, "版本", "1.2.0")
            RowDivider()
            InfoRow(Icons.Outlined.Code, "项目", "RuneSU · 基于 KernelSU 生态")
        }
        Spacer(Modifier.height(96.dp))
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
private fun ArchBar(termEnabled: Boolean, current: Int, onSelect: (Int) -> Unit) {
    val items = navEntries(termEnabled)
    val center = (items.size - 1) / 2f

    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(62.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { i, entry ->
                val active = current == entry.id
                val norm = if (center == 0f) 0f else (i - center) / center
                val lift = (-7f * (1f - norm * norm)).dp
                val extra = if (active) (-5).dp else 0.dp
                Column(
                    Modifier
                        .weight(1f)
                        .offset(y = lift + extra)
                        .bouncy(depth = 0.88f, damping = 0.26f, stiffness = 520f) { onSelect(entry.id) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        entry.icon, null,
                        tint = if (active) RuneTokens.Primary else RuneTokens.Muted,
                        modifier = Modifier.size(if (active) 23.dp else 21.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.label, fontSize = 10.sp,
                        color = if (active) RuneTokens.Primary else RuneTokens.Muted
                    )
                }
            }
        }
    }
}

@Composable
private fun RadialNav(current: Int, items: List<NavEntry>, onSelect: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(open) {
        progress.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = 420f))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cx = maxWidth / 2
        val cy = maxHeight - 96.dp
        val radius = 104.dp
        val btn = 54.dp
        val p = progress.value

        if (p > 0.01f) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f * p))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        open = false
                    }
            )
        }

        items.forEachIndexed { i, entry ->
            val t = ((p - i * 0.05f) / 0.75f).coerceIn(0f, 1f)
            val step = if (items.size > 1) 156.0 / (items.size - 1) else 0.0
            val angle = Math.toRadians(-78.0 + i * step)
            val x = cx + radius * kotlin.math.sin(angle).toFloat()
            val y = cy - radius * kotlin.math.cos(angle).toFloat()
            val active = current == entry.id
            Box(
                Modifier
                    .offset(x = x - btn / 2, y = y - btn / 2)
                    .size(btn)
                    .graphicsLayer {
                        alpha = t
                        scaleX = 0.7f + 0.3f * t
                        scaleY = 0.7f + 0.3f * t
                    }
                    .background(if (active) RuneTokens.Primary else RuneTokens.Panel, CircleShape)
                    .clickable(enabled = p > 0.5f) { onSelect(entry.id); open = false },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(entry.icon, null,
                        tint = if (active) Color.White else RuneTokens.Muted,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(entry.label, fontSize = 9.sp,
                        color = if (active) Color.White else RuneTokens.Muted)
                }
            }
        }

        Box(
            Modifier.offset(x = cx - 28.dp, y = cy - 28.dp).size(56.dp)
                .graphicsLayer { rotationZ = 90f * p }
                .background(RuneTokens.Primary, CircleShape)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dy ->
                            scope.launch {
                                val target = (progress.value - dy / 220f).coerceIn(0f, 1f)
                                progress.snapTo(target)
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (progress.value > 0.4f) { open = true; progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                                else { open = false; progress.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 420f)) }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                open = progress.value > 0.4f
                                progress.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = 420f))
                            }
                        }
                    )
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    open = !open
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (open) Icons.Outlined.Close else Icons.Outlined.Menu, null,
                tint = Color.White, modifier = Modifier.size(24.dp)
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

private fun cpuLoad(): Int? { ... }
