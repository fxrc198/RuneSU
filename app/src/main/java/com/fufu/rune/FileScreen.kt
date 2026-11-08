package com.fufu.rune

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileScreen(rootPath: String) {
    val ctx = LocalContext.current
    var left by remember { mutableStateOf(rootPath) }
    var right by remember { mutableStateOf("$rootPath/Download") }
    var tick by remember { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var menuPane by remember { mutableStateOf(0) }
    var renameFor by remember { mutableStateOf<File?>(null) }
    var deleteFor by remember { mutableStateOf<File?>(null) }
    var newPane by remember { mutableStateOf(-1) }
    var input by remember { mutableStateOf("") }

    fun runOp(msg: () -> String?) {
        Thread {
            val text = try { msg() } catch (e: Exception) { "失败：${e.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                tick++
                text?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "文件管理",
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 8.dp)
        )
        Row(Modifier.fillMaxSize()) {
            DirPane(
                modifier = Modifier.weight(1f),
                path = left, tick = tick,
                onPath = { left = it },
                onMenu = { menuFor = it; menuPane = 0 },
                onNew = { newPane = 0; input = "" }
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(RuneTokens.Border))
            DirPane(
                modifier = Modifier.weight(1f),
                path = right, tick = tick,
                onPath = { right = it },
                onMenu = { menuFor = it; menuPane = 1 },
                onNew = { newPane = 1; input = "" }
            )
        }
    }

    menuFor?.let { target ->
        val otherDir = File(if (menuPane == 0) right else left)
        FileActionDialog(
            file = target,
            onDismiss = { menuFor = null },
            onCopy = {
                menuFor = null
                runOp { if (copyRecursive(target, otherDir)) "已复制到「${otherDir.name}」" else "复制失败" }
            },
            onMove = {
                menuFor = null
                runOp { if (target.renameTo(File(otherDir, target.name))) "已移动到「${otherDir.name}」" else "移动失败" }
            },
            onRename = { input = target.name; renameFor = target; menuFor = null },
            onDelete = { deleteFor = target; menuFor = null }
        )
    }

    if (newPane >= 0) {
        InputDialog("新建文件夹", input, { input = it }, "创建",
            onDismiss = { newPane = -1 },
            onConfirm = {
                val dir = if (newPane == 0) left else right
                val name = input.trim()
                newPane = -1
                if (name.isNotEmpty()) runOp { if (File(dir, name).mkdirs()) "已创建" else "创建失败" }
            })
    }

    renameFor?.let { target ->
        InputDialog("重命名", input, { input = it }, "确定",
            onDismiss = { renameFor = null },
            onConfirm = {
                val name = input.trim()
                renameFor = null
                if (name.isNotEmpty()) runOp {
                    if (target.renameTo(File(target.parentFile, name))) "已重命名" else "重命名失败"
                }
            })
    }

    deleteFor?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = RuneTokens.Panel,
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定删除「${target.name}」？\n此操作不可恢复。", fontSize = 13.sp, color = RuneTokens.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    runOp { if (deleteRecursive(target)) "已删除" else "删除失败" }
                }) { Text("删除", color = RuneTokens.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFor = null }) { Text("取消", color = RuneTokens.Muted) }
            }
        )
    }
}

@Composable
private fun FileActionDialog(
    file: File,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    file.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    file.absolutePath, fontSize = 10.5.sp, color = RuneTokens.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                DlgAction(Icons.Outlined.ContentCopy, "复制到对面", "复制到另一栏当前目录", RuneTokens.Accent, onCopy)
                DlgAction(Icons.Outlined.DriveFileMove, "移动到对面", "移动到另一栏当前目录", RuneTokens.Blue, onMove)
                DlgAction(Icons.Outlined.Edit, "重命名", "修改名称", RuneTokens.Amber, onRename)
                DlgAction(Icons.Outlined.Delete, "删除", "删除后不可恢复", RuneTokens.Danger, onDelete, danger = true)
            }
        }
    }
}

@Composable
private fun DlgAction(
    icon: ImageVector,
    title: String,
    desc: String,
    tint: Color,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (danger) RuneTokens.Danger else RuneTokens.Text
            )
            Spacer(Modifier.height(1.dp))
            Text(desc, fontSize = 10.5.sp, color = RuneTokens.Muted)
        }
    }
}

@Composable
private fun DirPane(
    modifier: Modifier,
    path: String,
    tick: Int,
    onPath: (String) -> Unit,
    onMenu: (File) -> Unit,
    onNew: () -> Unit
) {
    val entries = remember(path, tick) { listDir(path) }
    val parent = File(path).parentFile

    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { parent?.let { onPath(it.absolutePath) } }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上级",
                    tint = if (parent != null) RuneTokens.Text else RuneTokens.Muted.copy(alpha = 0.35f),
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                if (path == "/sdcard" || path.endsWith("/0")) "内部存储"
                else path.substringAfterLast('/').ifEmpty { "/" },
                fontSize = 11.sp, color = RuneTokens.Text, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNew, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Outlined.CreateNewFolder, contentDescription = "新建",
                    tint = RuneTokens.Accent, modifier = Modifier.size(17.dp)
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.absolutePath }) { f ->
                MiniRow(f, onClick = { if (f.isDirectory) onPath(f.absolutePath) }, onLong = { onMenu(f) })
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniRow(file: File, onClick: () -> Unit, onLong: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "rowScale"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLong
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(26.dp).background(
                if (file.isDirectory) RuneTokens.Accent.copy(alpha = 0.12f) else RuneTokens.Panel2,
                RoundedCornerShape(7.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (file.isDirectory) Icons.Outlined.Folder else fileIcon(file),
                null,
                tint = if (file.isDirectory) RuneTokens.Accent else RuneTokens.Muted,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name, fontSize = 11.5.sp, color = RuneTokens.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (file.isDirectory) dateText(file.lastModified()) else formatSize(file.length()),
                fontSize = 9.5.sp, color = RuneTokens.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
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
        containerColor = RuneTokens.Panel,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = onChange, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RuneTokens.Accent,
                    unfocusedBorderColor = RuneTokens.Border
                )
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = RuneTokens.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = RuneTokens.Muted) } }
    )
}

private fun listDir(path: String): List<File> =
    File(path).listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

private fun fileIcon(f: File): ImageVector {
    val n = f.name.lowercase()
    return when {
        n.endsWith(".apk") -> Icons.Outlined.Android
        n.endsWith(".zip") || n.endsWith(".7z") || n.endsWith(".rar") -> Icons.Outlined.FolderZip
        n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp") -> Icons.Outlined.Image
        n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".wav") -> Icons.Outlined.MusicNote
        n.endsWith(".mp4") || n.endsWith(".mkv") -> Icons.Outlined.Movie
        n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log") -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

internal fun formatSize(n: Long): String = when {
    n >= 1L shl 30 -> String.format("%.1fG", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format("%.1fM", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format("%.0fK", n / (1L shl 10).toDouble())
    else -> "$n B"
}

private fun dateText(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun copyRecursive(src: File, dstDir: File): Boolean {
    val dst = File(dstDir, src.name)
    if (src.isDirectory) {
        if (!dst.exists() && !dst.mkdirs()) return false
        src.listFiles()?.forEach { if (!copyRecursive(it, dst)) return false }
        return true
    }
    return try {
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        true
    } catch (_: Exception) { false }
}

private fun deleteRecursive(f: File): Boolean {
    if (f.isDirectory) f.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
    return f.delete()
}
