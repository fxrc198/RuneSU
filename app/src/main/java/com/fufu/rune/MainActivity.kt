package com.fufu.rune

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.TimeUnit

object RuneTokens {
    val Bg = Color(0xFF0D1017)
    val Panel = Color(0xFF151A24)
    val Panel2 = Color(0xFF1B2230)
    val Border = Color(0xFF262E3D)
    val Text = Color(0xFFDBE2EF)
    val Muted = Color(0xFF8494AD)
    val Accent = Color(0xFF7C6CF0)
    val Danger = Color(0xFFFF5C5C)
    val Success = Color(0xFF37C978)
}

class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)
    private var suPath by mutableStateOf<String?>(null)
    private var checked by mutableStateOf(false)
    private var modules by mutableStateOf<Int?>(null)
    private var page by mutableStateOf(0)

    private val requestAll = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshGrant() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshGrant()
        detectRoot()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = RuneTokens.Accent)) {
                Box(Modifier.fillMaxSize().background(RuneTokens.Bg)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            when (page) {
                                1 -> FileBrowser(Environment.getExternalStorageDirectory().absolutePath)
                                2 -> Placeholder("模块", "接入 /data/adb/modules 后显示")
                                3 -> Placeholder("设置", "外观 · 导航 · 关于")
                                else -> Home(suPath, checked, modules)
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
private fun Home(suPath: String?, checked: Boolean, modules: Int?) {
    val storage = remember { storageInfo() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Text("RuneSU", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = RuneTokens.Text)
        Spacer(Modifier.height(2.dp))
        Text("设备与 Root 状态", fontSize = 12.sp, color = RuneTokens.Muted)

        Spacer(Modifier.height(14.dp))
        DeviceCard(suPath, checked, modules, storage)

        Spacer(Modifier.height(10.dp))
        StorageCard(storage)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceCard(suPath: String?, checked: Boolean, modules: Int?, storage: Pair<Long, Long>?) {
    Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).background(RuneTokens.Accent.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.PhoneAndroid, null, tint = RuneTokens.Accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${Build.MANUFACTURER} ${Build.MODEL}",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "-"}",
                        fontSize = 11.sp, color = RuneTokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                RootBadge(suPath, checked)
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiCell(Icons.Outlined.Memory, "内核", System.getProperty("os.version") ?: "-")
                DiCell(Icons.Outlined.DeveloperBoard, "架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DiCell(Icons.Outlined.Extension, "模块", modules?.let { "$it 个" } ?: "-")
                DiCell(Icons.Outlined.Storage, "可用", storage?.let { formatSize(it.second) } ?: "-")
            }
        }
    }
}

@Composable
private fun RowScope.DiCell(icon: ImageVector, label: String, value: String) {
    Surface(
        color = RuneTokens.Panel2,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.weight(1f).height(52.dp)
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).background(RuneTokens.Accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = RuneTokens.Accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(7.dp))
            Column {
                Text(label, fontSize = 10.sp, color = RuneTokens.Muted)
                Spacer(Modifier.height(1.dp))
                Text(value, fontSize = 12.sp, color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        suPath != null -> "Root"
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
private fun StorageCard(storage: Pair<Long, Long>?) {
    Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("内部存储", fontSize = 12.sp, color = RuneTokens.Muted)
                Text(
                    storage?.let { "${formatSize(it.first - it.second)} / ${formatSize(it.first)}" } ?: "-",
                    fontSize = 11.sp, color = RuneTokens.Text
                )
            }
            Spacer(Modifier.height(8.dp))
            val ratio = storage?.let {
                if (it.first <= 0) 0f else ((it.first - it.second).toFloat() / it.first)
            } ?: 0f
            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .background(RuneTokens.Panel2, RoundedCornerShape(4.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).height(6.dp)
                        .background(RuneTokens.Accent, RoundedCornerShape(4.dp))
                )
            }
        }
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
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(idx) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileBrowser(startPath: String) {
    var path by remember { mutableStateOf(startPath) }
    val entries = remember(path) {
        File(path).listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text(path, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = {
                    val p = File(path).parentFile
                    if (p != null) path = p.absolutePath
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上级")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = RuneTokens.Panel,
                titleContentColor = RuneTokens.Text,
                navigationIconContentColor = RuneTokens.Text
            )
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(entries) { f ->
                FileRow(f) { if (f.isDirectory) path = f.absolutePath }
            }
        }
    }
}

@Composable
private fun FileRow(f: File, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(24.dp).background(
                if (f.isDirectory) RuneTokens.Accent.copy(alpha = 0.12f) else RuneTokens.Panel2,
                RoundedCornerShape(6.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (f.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                null,
                tint = if (f.isDirectory) RuneTokens.Accent else RuneTokens.Muted,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 12.sp, color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (f.isDirectory) "文件夹" else formatSize(f.length()),
                fontSize = 10.sp, color = RuneTokens.Muted
            )
        }
    }
}

private fun storageInfo(): Pair<Long, Long>? = try {
    val s = StatFs(Environment.getExternalStorageDirectory().absolutePath)
    (s.blockCountLong * s.blockSizeLong) to (s.availableBlocksLong * s.blockSizeLong)
} catch (_: Exception) { null }

private fun formatSize(n: Long): String = when {
    n >= 1L shl 30 -> String.format("%.1fG", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format("%.1fM", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format("%.0fK", n / (1L shl 10).toDouble())
    else -> "$n B"
}
