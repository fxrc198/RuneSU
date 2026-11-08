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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

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
    private var termEnabled by mutableStateOf(false)
    private var foreground by mutableStateOf(true)

    private val requestAll = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshGrant() }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        super.onPause()
        foreground = false
    }

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
    onOpen: (Int) -> Unit
) {
    val stats = buildList {
        snap.cpu?.let { add(it to "负载") }
        snap.mem?.let { add(it to "内存") }
        snap.storage?.let { add(it to "存储") }
        snap.battery?.let { add(it to "电池") }
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
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("RuneSU", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
        Spacer(Modifier.height(2.dp))
        Text(
            "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            fontSize = 11.5.sp, color = RuneTokens.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(14.dp))
        StatusCard(suPath, checked, modules) { onOpen(2) }

        if (stats.isNotEmpty()) {
            SectionLabel("状态")
            SectionCard {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    stats.forEach { (value, label) -> Stat(value, label) }
                }
            }
        }

        if (info.isNotEmpty()) {
            SectionLabel("系统")
            SectionCard {
                info.forEachIndexed { index, entry ->
                    if (index > 0) RowDivider()
                    InfoRow(entry.first, entry.second.first, entry.second.second)
                }
            }
        }

        SectionLabel("安全")
        SectionCard {
            InfoRow(Icons.Outlined.Security, "SELinux 状态", selinuxStatus())
            RowDivider()
            InfoRow(Icons.Outlined.FilterList, "Seccomp 状态", seccompStatus())
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(
    suPath: String?,
    checked: Boolean,
    modules: Int?,
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
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().bouncy(depth = 0.98f, damping = 0.4f, stiffness = 500f)
    ) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
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
                    Spacer(Modifier.height(3.dp))
                    Text(
                        sub, fontSize = 12.sp, color = RuneTokens.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            RowDivider()
            Row(
                Modifier.fillMaxWidth().clickable { onModules() }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Extension, null, tint = RuneTokens.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("模块", fontSize = 14.5.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
                Text(modules?.let { "$it 个" } ?: "未接入", fontSize = 12.sp, color = RuneTokens.Muted)
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = RuneTokens.Muted, modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp, color = RuneTokens.Muted,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun RowScope.Stat(value: Int, label: String) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value%", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
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
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 4.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).background(RuneTokens.Accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = RuneTokens.Accent, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 14.5.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
        Text(badge, fontSize = 12.sp, color = RuneTokens.Muted)
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = RuneTokens.Muted, modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.Top) {
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
private fun PermissionHint(onAsk: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.Folder, null, tint = RuneTokens.Accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("需要「所有文件访问」权限", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text)
        Spacer(Modifier.height(6.dp))
        Text("用于浏览和整理 /sdcard", fontSize = 12.sp, color = RuneTokens.Muted)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAsk,
            colors = ButtonDefaults.buttonColors(containerColor = RuneTokens.Accent)
        ) { Text("去授权") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileScreen(rootPath: String) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf(rootPath) }
    var tick by remember { mutableStateOf(0) }
    var clip by remember { mutableStateOf<File?>(null) }
    var clipCut by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var renameFor by remember { mutableStateOf<File?>(null) }
    var deleteFor by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    val entries = remember(path, tick) { listDir(path) }
    val parent = File(path).parentFile

    fun runOp(action: () -> String?) {
        Thread {
            val msg = try { action() } catch (e: Exception) { "失败：${e.message}" }
            Handler(Looper.getMainLooper()).post {
                tick++
                msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { parent?.let { path = it.absolutePath } }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上级",
                    tint = if (parent != null) RuneTokens.Text else RuneTokens.Muted.copy(alpha = 0.4f)
                )
            }
            Text(
                path, fontSize = 11.5.sp, color = RuneTokens.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { input = ""; newFolder = true }) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹", tint = RuneTokens.Accent)
            }
        }

        if (clip != null) {
            Surface(color = RuneTokens.Accent.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已${if (clipCut) "剪切" else "复制"}：${clip!!.name}",
                        fontSize = 11.5.sp, color = RuneTokens.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val src = clip!!
                        val cut = clipCut
                        runOp {
                            val ok = if (cut) {
                                val dst = File(path, src.name)
                                if (src.absolutePath == dst.absolutePath) false else src.renameTo(dst)
                            } else copyRecursive(src, File(path))
                            if (ok) "已${if (cut) "移动" else "复制"}" else "操作失败"
                        }
                        clip = null
                    }) { Text("粘贴", color = RuneTokens.Accent) }
                    TextButton(onClick = { clip = null }) { Text("取消", color = RuneTokens.Muted) }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize().weight(1f)) {
            items(entries, key = { it.absolutePath }) { f ->
                FileRow(
                    file = f,
                    onClick = { if (f.isDirectory) path = f.absolutePath },
                    onLong = { menuFor = f }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    menuFor?.let { target ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, containerColor = RuneTokens.Panel) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    target.name, fontSize = 14.sp, color = RuneTokens.Text, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    target.absolutePath, fontSize = 11.sp, color = RuneTokens.Muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                SheetItem(Icons.Outlined.ContentCopy, "复制") {
                    clip = target; clipCut = false; menuFor = null
                }
                SheetItem(Icons.Outlined.DriveFileMove, "移动") {
                    clip = target; clipCut = true; menuFor = null
                }
                SheetItem(Icons.Outlined.Edit, "重命名") {
                    input = target.name; renameFor = target; menuFor = null
                }
                SheetItem(Icons.Outlined.Delete, "删除", danger = true) {
                    deleteFor = target; menuFor = null
                }
            }
        }
    }

    if (newFolder) {
        InputDialog(
            title = "新建文件夹",
            value = input,
            onChange = { input = it },
            confirm = "创建",
            onDismiss = { newFolder = false },
            onConfirm = {
                val name = input.trim()
                newFolder = false
                if (name.isNotEmpty()) {
                    runOp {
                        val ok = File(path, name).mkdirs()
                        if (ok) "已创建" else "创建失败"
                    }
                }
            }
        )
    }

    renameFor?.let { target ->
        InputDialog(
            title = "重命名",
            value = input,
            onChange = { input = it },
            confirm = "确定",
            onDismiss = { renameFor = null },
            onConfirm = {
                val name = input.trim()
                renameFor = null
                if (name.isNotEmpty()) {
                    runOp {
                        val ok = target.renameTo(File(target.parentFile, name))
                        if (ok) "已重命名" else "重命名失败"
                    }
                }
            }
        )
    }

    deleteFor?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除") },
            text = { Text("确定删除「${target.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    runOp {
                        val ok = deleteRecursive(target)
                        if (ok) "已删除" else "删除失败"
                    }
                }) { Text("删除", color = RuneTokens.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("取消", color = RuneTokens.Muted) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: File, onClick: () -> Unit, onLong: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLong
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(
                if (file.isDirectory) RuneTokens.Accent.copy(alpha = 0.12f) else RuneTokens.Panel2,
                RoundedCornerShape(9.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (file.isDirectory) Icons.Outlined.Folder else fileIcon(file),
                null,
                tint = if (file.isDirectory) RuneTokens.Accent else RuneTokens.Muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name, fontSize = 13.5.sp, color = RuneTokens.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (file.isDirectory) dateText(file.lastModified())
                else "${formatSize(file.length())} · ${dateText(file.lastModified())}",
                fontSize = 10.5.sp, color = RuneTokens.Muted
            )
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            tint = if (danger) RuneTokens.Danger else RuneTokens.Muted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label, fontSize = 14.sp,
            color = if (danger) RuneTokens.Danger else RuneTokens.Text
        )
    }
}

@Composable
private fun InputDialog(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RuneTokens.Accent,
                    unfocusedBorderColor = RuneTokens.Border
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = RuneTokens.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = RuneTokens.Muted) }
        }
    )
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
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp).background(RuneTokens.Accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Terminal, null, tint = RuneTokens.Accent, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("终端", fontSize = 14.5.sp, color = RuneTokens.Text)
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
            InfoRow(Icons.Outlined.Info, "版本", "0.6.0")
            RowDivider()
            InfoRow(Icons.Outlined.Code, "项目", "RuneSU · 基于 KernelSU 生态")
        }

        Spacer(Modifier.height(24.dp))
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
    val termWeight by animateFloatAsState(
        targetValue = if (termEnabled) 1f else 0.0001f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
        label = "termWeight"
    )
    val termAlpha by animateFloatAsState(
        targetValue = if (termEnabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
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
                if (termAlpha > 0.02f) {
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

private fun listDir(path: String): List<File> =
    File(path).listFiles()
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        ?: emptyList()

private fun fileIcon(f: File): ImageVector {
    val name = f.name.lowercase()
    return when {
        name.endsWith(".apk") -> Icons.Outlined.Android
        name.endsWith(".zip") || name.endsWith(".7z") || name.endsWith(".rar") -> Icons.Outlined.FolderZip
        name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp") -> Icons.Outlined.Image
        name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".wav") -> Icons.Outlined.MusicNote
        name.endsWith(".mp4") || name.endsWith(".mkv") -> Icons.Outlined.Movie
        name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".log") -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

private fun formatSize(n: Long): String = when {
    n >= 1L shl 30 -> String.format("%.1fG", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format("%.1fM", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format("%.0fK", n / (1L shl 10).toDouble())
    else -> "$n B"
}

private fun dateText(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun copyRecursive(src: File, dstDir: File): Boolean {
    val dst = File(dstDir, src.name)
    if (src.isDirectory) {
        if (!dst.exists() && !dst.mkdirs()) return false
        src.listFiles()?.forEach { if (!copyRecursive(it, dst)) return false }
        return true
    }
    return try {
        src.inputStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }
        true
    } catch (_: Exception) {
        false
    }
}

private fun deleteRecursive(f: File): Boolean {
    if (f.isDirectory) {
        f.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
    }
    return f.delete()
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
