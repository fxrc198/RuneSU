package com.fufu.rune

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun FileScreen(rootPath: String) {
    val ctx = LocalContext.current
    var left by remember { mutableStateOf(rootPath) }
    var right by remember { mutableStateOf("$rootPath/Download") }
    var tick by remember { mutableStateOf(0) }
    var sortL by remember { mutableStateOf(SortSpec()) }
    var sortR by remember { mutableStateOf(SortSpec()) }
    var selL by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selR by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var menuPane by remember { mutableStateOf(0) }
    var renameFor by remember { mutableStateOf<File?>(null) }
    var deleteFor by remember { mutableStateOf<List<File>?>(null) }
    var newPane by remember { mutableStateOf(-1) }
    var bookmarks by remember { mutableStateOf(listOf<String>()) }
    var bookmarkMenu by remember { mutableStateOf(false) }
    var sortPane by remember { mutableStateOf(-1) }
    var input by remember { mutableStateOf("") }

    fun runOp(msg: () -> String) {
        Thread {
            val text = try { msg() } catch (e: Exception) { "失败：${e.message}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                tick++
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    fun clearSel(pane: Int) {
        if (pane == 0) selL = emptySet() else selR = emptySet()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("文件管理", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = RuneTokens.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = { bookmarkMenu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = "书签",
                    tint = RuneTokens.Muted, modifier = Modifier.size(19.dp))
            }
        }

        Row(Modifier.weight(1f)) {
            DirPane(
                modifier = Modifier.weight(1f), path = left, tick = tick, sort = sortL,
                selection = selL,
                onSort = { sortPane = 0 },
                onPath = { left = it; selL = emptySet() },
                onToggle = { selL = if (selL.contains(it)) selL - it else selL + it },
                onMenu = { menuFor = it; menuPane = 0 },
                onNew = { newPane = 0; input = "" }
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(RuneTokens.Border))
            DirPane(
                modifier = Modifier.weight(1f), path = right, tick = tick, sort = sortR,
                selection = selR,
                onSort = { sortPane = 1 },
                onPath = { right = it; selR = emptySet() },
                onToggle = { selR = if (selR.contains(it)) selR - it else selR + it },
                onMenu = { menuFor = it; menuPane = 1 },
                onNew = { newPane = 1; input = "" }
            )
        }
    }

    if (sortPane >= 0) {
        val spec = if (sortPane == 0) sortL else sortR
        SortDialog(spec = spec, onDismiss = { sortPane = -1 }) { newSpec ->
            if (sortPane == 0) sortL = newSpec else sortR = newSpec
            sortPane = -1
        }
    }

    menuFor?.let { target ->
        val paneSel = if (menuPane == 0) selL else selR
        val otherDir = File(if (menuPane == 0) right else left)
        val curDir = if (menuPane == 0) File(left) else File(right)

        if (paneSel.isNotEmpty()) {
            val files = paneSel.map { File(it) }
            BatchDialog(
                count = files.size,
                onDismiss = { menuFor = null },
                onCopy = {
                    menuFor = null
                    runOp { batch(files) { copyRecursive(it, otherDir) } }
                    clearSel(menuPane)
                },
                onMove = {
                    menuFor = null
                    runOp { batch(files) { it.renameTo(File(otherDir, it.name)) } }
                    clearSel(menuPane)
                },
                onDelete = { menuFor = null; deleteFor = files },
                onClear = { menuFor = null; clearSel(menuPane) }
            )
        } else {
            FileActionDialog(
                file = target,
                onDismiss = { menuFor = null },
                onCopy = { menuFor = null; runOp { if (copyRecursive(target, otherDir)) "已复制" else "复制失败" } },
                onMove = { menuFor = null; runOp { if (target.renameTo(File(otherDir, target.name))) "已移动" else "移动失败" } },
                onRename = { input = target.name; renameFor = target; menuFor = null },
                onDelete = { deleteFor = listOf(target); menuFor = null },
                onZip = {
                    menuFor = null
                    runOp {
                        val out = File(target.parentFile, "${target.name}.zip")
                        if (zipFile(target, out)) "已压缩" else "压缩失败"
                    }
                },
                onUnzip = if (target.name.lowercase().endsWith(".zip")) ({
                    menuFor = null
                    runOp { if (unzip(target, curDir)) "已解压" else "解压失败" }
                }) else null
            )
        }
    }

    if (bookmarkMenu) {
        Dialog(onDismissRequest = { bookmarkMenu = false }) {
            Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("书签与快速访问", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
                    Spacer(Modifier.height(8.dp))
                    QuickItem("添加当前目录到书签") {
                        bookmarks = (bookmarks + left + right).distinct()
                        bookmarkMenu = false
                    }
                    QuickItem("内部存储") { bookmarkMenu = false; left = rootPath }
                    QuickItem("Download") { bookmarkMenu = false; left = "$rootPath/Download" }
                    QuickItem("Android/data") { bookmarkMenu = false; left = "$rootPath/Android/data" }
                    bookmarks.forEach { b -> QuickItem(b) { bookmarkMenu = false; left = b } }
                }
            }
        }
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

    deleteFor?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = RuneTokens.Panel,
            shape = RoundedCornerShape(16.dp),
            title = { Text("删除", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (targets.size == 1) "确定删除「${targets[0].name}」？\n此操作不可恢复。"
                    else "确定删除已选的 ${targets.size} 项？\n此操作不可恢复。",
                    fontSize = 13.sp, color = RuneTokens.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    runOp { batch(targets) { deleteRecursive(it) } }
                    selL = emptySet(); selR = emptySet()
                }) { Text("删除", color = RuneTokens.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消", color = RuneTokens.Muted) } }
        )
    }
}

@Composable
private fun BatchDialog(
    count: Int,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("已选 $count 项", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
                Spacer(Modifier.height(12.dp))
                DlgAction(Icons.Outlined.ContentCopy, "复制到对面", "复制到另一栏当前目录", onCopy)
                DlgAction(Icons.Outlined.DriveFileMove, "移动到对面", "移动到另一栏当前目录", onMove)
                DlgAction(Icons.Outlined.Delete, "删除", "删除后不可恢复", onDelete, danger = true)
                DlgAction(Icons.Outlined.Deselect, "取消选择", "清除当前选中", onClear)
            }
        }
    }
}

@Composable
private fun SortDialog(spec: SortSpec, onDismiss: () -> Unit, onApply: (SortSpec) -> Unit) {
    var by by remember { mutableStateOf(spec.by) }
    var desc by remember { mutableStateOf(spec.desc) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("排序", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RuneTokens.Text)
                Spacer(Modifier.height(10.dp))
                SortBy.values().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { by = option }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(option.label, fontSize = 13.5.sp, color = RuneTokens.Text,
                            modifier = Modifier.weight(1f))
                        if (by == option) {
                            Icon(Icons.Outlined.Check, null, tint = RuneTokens.Accent,
                                modifier = Modifier.size(17.dp))
                        }
                    }
                }
                HorizontalDivider(thickness = 0.7.dp, color = RuneTokens.Border)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("降序", fontSize = 13.5.sp, color = RuneTokens.Text, modifier = Modifier.weight(1f))
                    Switch(checked = desc, onCheckedChange = { desc = it })
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = RuneTokens.Muted) }
                    TextButton(onClick = { onApply(SortSpec(by, desc)) }) {
                        Text("应用", color = RuneTokens.Primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DirPane(
    modifier: Modifier,
    path: String,
    tick: Int,
    sort: SortSpec,
    selection: Set<String>,
    onSort: () -> Unit,
    onPath: (String) -> Unit,
    onToggle: (String) -> Unit,
    onMenu: (File) -> Unit,
    onNew: () -> Unit
) {
    val entries = remember(path, tick, sort) { listDir(path, sort) }
    val parent = File(path).parentFile
    val selecting = selection.isNotEmpty()

    Column(modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { parent?.let { onPath(it.absolutePath) } }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上级",
                    tint = if (parent != null) RuneTokens.Text else RuneTokens.Muted.copy(alpha = 0.35f),
                    modifier = Modifier.size(15.dp))
            }
            Text(
                if (path == "/sdcard" || path.endsWith("/0")) "内部存储"
                else path.substringAfterLast('/').ifEmpty { "/" },
                fontSize = 10.5.sp, color = RuneTokens.Text, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSort, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Sort, contentDescription = sort.by.label,
                    tint = RuneTokens.Muted, modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = onNew, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建",
                    tint = RuneTokens.Muted, modifier = Modifier.size(15.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.absolutePath }) { f ->
                MiniRow(
                    file = f,
                    selected = selection.contains(f.absolutePath),
                    selecting = selecting,
                    onClick = {
                        if (selecting) onToggle(f.absolutePath)
                        else if (f.isDirectory) onPath(f.absolutePath)
                    },
                    onLong = { onMenu(f) },
                    onSwipe = { onToggle(f.absolutePath) }
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniRow(
    file: File,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onSwipe: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .pointerInput(file.absolutePath) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-56f, 56f))
                        }
                    },
                    onDragEnd = {
                        val triggered = abs(offsetX.value) > 34f
                        scope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 700f))
                        }
                        if (triggered) onSwipe()
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, spring()) }
                    }
                )
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLong
            )
            .background(
                if (selected) RuneTokens.Primary.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(26.dp).background(RuneTokens.Panel2, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Outlined.Check, null, tint = RuneTokens.Primary, modifier = Modifier.size(15.dp))
            } else {
                Icon(
                    if (file.isDirectory) Icons.Outlined.Folder else fileIcon(file), null,
                    tint = RuneTokens.Muted, modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, fontSize = 11.5.sp, color = RuneTokens.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (file.isDirectory) dateText(file.lastModified()) else formatSize(file.length()),
                fontSize = 9.5.sp, color = RuneTokens.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FileActionDialog(
    file: File,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onZip: () -> Unit,
    onUnzip: (() -> Unit)?
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(file.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(file.absolutePath, fontSize = 10.5.sp, color = RuneTokens.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                DlgAction(Icons.Outlined.ContentCopy, "复制到对面", "复制到另一栏当前目录", onCopy)
                DlgAction(Icons.Outlined.DriveFileMove, "移动到对面", "移动到另一栏当前目录", onMove)
                DlgAction(Icons.Outlined.FolderZip, "压缩", "打包为 ${file.name}.zip", onZip)
                onUnzip?.let { DlgAction(Icons.Outlined.Unarchive, "解压", "解压到当前目录", it) }
                DlgAction(Icons.Outlined.Edit, "重命名", "修改名称", onRename)
                DlgAction(Icons.Outlined.Delete, "删除", "删除后不可恢复", onDelete, danger = true)
            }
        }
    }
}

@Composable
private fun DlgAction(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (danger) RuneTokens.Danger else RuneTokens.Muted,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp,
                color = if (danger) RuneTokens.Danger else RuneTokens.Text)
            Spacer(Modifier.height(1.dp))
            Text(desc, fontSize = 10.5.sp, color = RuneTokens.Muted)
        }
    }
}

@Composable
private fun QuickItem(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.FolderOpen, null, tint = RuneTokens.Muted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InputDialog(
    title: String, value: String, onChange: (String) -> Unit,
    confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RuneTokens.Panel,
        shape = RoundedCornerShape(16.dp),
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = onChange, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RuneTokens.Primary,
                    unfocusedBorderColor = RuneTokens.Border
                )
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = RuneTokens.Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = RuneTokens.Muted) } }
    )
}
