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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

@Composable
fun FileScreen(rootPath: String) {
    val ctx = LocalContext.current
    var left by remember { mutableStateOf(rootPath) }
    var right by remember { mutableStateOf("$rootPath/Download") }
    var tick by remember { mutableStateOf(0) }
    var sortL by remember { mutableStateOf(SortBy.NAME) }
    var sortR by remember { mutableStateOf(SortBy.NAME) }
    var multiL by remember { mutableStateOf(false) }
    var multiR by remember { mutableStateOf(false) }
    var selL by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selR by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var menuPane by remember { mutableStateOf(0) }
    var renameFor by remember { mutableStateOf<File?>(null) }
    var deleteFor by remember { mutableStateOf<List<File>?>(null) }
    var newPane by remember { mutableStateOf(-1) }
    var bookmarks by remember { mutableStateOf(listOf<String>()) }
    var bookmarkMenu by remember { mutableStateOf(false) }
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

    fun exitMulti(pane: Int) {
        if (pane == 0) { multiL = false; selL = emptySet() } else { multiR = false; selR = emptySet() }
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
                multi = multiL, selection = selL,
                onSort = { sortL = nextSort(sortL) },
                onMulti = { if (multiL) exitMulti(0) else { multiL = true; selL = emptySet() } },
                onPath = { left = it; exitMulti(0) },
                onToggle = { selL = toggle(selL, it) },
                onDragSelect = { selL = selL + it },
                onMenu = { menuFor = it; menuPane = 0 },
                onNew = { newPane = 0; input = "" }
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(RuneTokens.Border))
            DirPane(
                modifier = Modifier.weight(1f), path = right, tick = tick, sort = sortR,
                multi = multiR, selection = selR,
                onSort = { sortR = nextSort(sortR) },
                onMulti = { if (multiR) exitMulti(1) else { multiR = true; selR = emptySet() } },
                onPath = { right = it; exitMulti(1) },
                onToggle = { selR = toggle(selR, it) },
                onDragSelect = { selR = selR + it },
                onMenu = { menuFor = it; menuPane = 1 },
                onNew = { newPane = 1; input = "" }
            )
        }

        if (selL.isNotEmpty() || selR.isNotEmpty()) {
            val pane = if (selL.isNotEmpty()) 0 else 1
            val sel = if (pane == 0) selL else selR
            val files = sel.map { File(it) }
            val other = if (pane == 0) File(right) else File(left)
            Surface(color = RuneTokens.Panel2) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BatchBtn(Icons.Outlined.ContentCopy, "复制") {
                        runOp { batch(files) { copyRecursive(it, other) } }
                        exitMulti(pane)
                    }
                    BatchBtn(Icons.Outlined.DriveFileMove, "移动") {
                        runOp { batch(files) { it.renameTo(File(other, it.name)) } }
                        exitMulti(pane)
                    }
                    BatchBtn(Icons.Outlined.Delete, "删除", danger = true) { deleteFor = files }
                    BatchBtn(Icons.Outlined.SelectAll, "全选") {
                        val dir = if (pane == 0) left else right
                        val s = if (pane == 0) sortL else sortR
                        val all = listDir(dir, s).map { it.absolutePath }.toSet()
                        if (pane == 0) selL = all else selR = all
                    }
                    BatchBtn(Icons.Outlined.Close, "取消") { exitMulti(pane) }
                }
            }
        }
    }

    menuFor?.let { target ->
        val otherDir = File(if (menuPane == 0) right else left)
        val curDir = if (menuPane == 0) File(left) else File(right)
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

    if (bookmarkMenu) {
        Dialog(onDismissRequest = { bookmarkMenu = false }) {
            Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(24.dp)) {
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
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除", fontWeight = FontWeight.SemiBold) },
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

private fun nextSort(s: SortBy): SortBy = when (s) {
    SortBy.NAME -> SortBy.SIZE
    SortBy.SIZE -> SortBy.TIME
    SortBy.TIME -> SortBy.NAME
}

private fun toggle(set: Set<String>, path: String): Set<String> =
    if (set.contains(path)) set - path else set + path

private fun indexAt(state: LazyListState, y: Float): Int {
    val info = state.layoutInfo
    return info.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index ?: -1
}

@Composable
private fun DirPane(
    modifier: Modifier,
    path: String,
    tick: Int,
    sort: SortBy,
    multi: Boolean,
    selection: Set<String>,
    onSort: () -> Unit,
    onMulti: () -> Unit,
    onPath: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDragSelect: (String) -> Unit,
    onMenu: (File) -> Unit,
    onNew: () -> Unit
) {
    val entries = remember(path, tick, sort) { listDir(path, sort) }
    val parent = File(path).parentFile
    val listState = rememberLazyListState()

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
                Icon(Icons.Outlined.Sort, contentDescription = sort.label,
                    tint = RuneTokens.Muted, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = onMulti, modifier = Modifier.size(28.dp)) {
                Icon(if (multi) Icons.Outlined.Close else Icons.Outlined.SelectAll,
                    contentDescription = "多选",
                    tint = if (multi) RuneTokens.Primary else RuneTokens.Muted,
                    modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = onNew, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建",
                    tint = RuneTokens.Primary, modifier = Modifier.size(15.dp))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().pointerInput(multi, entries) {
                if (!multi) return@pointerInput
                awaitPointerEventScope {
                    var last: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.pressed } ?: continue
                        val pos = change.position
                        val prev = last
                        if (prev != null && (pos - prev).getDistance() > 8f) {
                            val idx = indexAt(listState, pos.y)
                            entries.getOrNull(idx)?.let { onDragSelect(it.absolutePath) }
                        }
                        last = pos
                    }
                }
            }
        ) {
            items(entries, key = { it.absolutePath }) { f ->
                MiniRow(
                    file = f,
                    selected = selection.contains(f.absolutePath),
                    multi = multi,
                    onClick = {
                        if (multi) onToggle(f.absolutePath)
                        else if (f.isDirectory) onPath(f.absolutePath)
                    },
                    onLong = { onMenu(f) }
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
    multi: Boolean,
    onClick: () -> Unit,
    onLong: () -> Unit
) {
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
            .background(
                if (selected) RuneTokens.Primary.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(26.dp).background(
                if (file.isDirectory) RuneTokens.Primary.copy(alpha = 0.12f) else RuneTokens.Panel2,
                RoundedCornerShape(7.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            if (multi && selected) {
                Icon(Icons.Outlined.Check, null, tint = RuneTokens.Primary, modifier = Modifier.size(15.dp))
            } else {
                Icon(
                    if (file.isDirectory) Icons.Outlined.Folder else fileIcon(file), null,
                    tint = if (file.isDirectory) RuneTokens.Primary else RuneTokens.Muted,
                    modifier = Modifier.size(15.dp)
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
        if (multi) {
            Box(
                Modifier.size(18.dp).background(
                    if (selected) RuneTokens.Primary else RuneTokens.Panel2,
                    RoundedCornerShape(9.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
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
        Surface(color = RuneTokens.Panel, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(file.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = RuneTokens.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(file.absolutePath, fontSize = 10.5.sp, color = RuneTokens.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                DlgAction(Icons.Outlined.ContentCopy, "复制到对面", "复制到另一栏当前目录", RuneTokens.Primary, onCopy)
                DlgAction(Icons.Outlined.DriveFileMove, "移动到对面", "移动到另一栏当前目录", RuneTokens.Primary, onMove)
                DlgAction(Icons.Outlined.FolderZip, "压缩", "打包为 ${file.name}.zip", RuneTokens.Muted, onZip)
                onUnzip?.let { DlgAction(Icons.Outlined.Unarchive, "解压", "解压到当前目录", RuneTokens.Muted, it) }
                DlgAction(Icons.Outlined.Edit, "重命名", "修改名称", RuneTokens.Muted, onRename)
                DlgAction(Icons.Outlined.Delete, "删除", "删除后不可恢复", RuneTokens.Danger, onDelete, danger = true)
            }
        }
    }
}

@Composable
private fun DlgAction(
    icon: ImageVector, title: String, desc: String, tint: Color,
    onClick: () -> Unit, danger: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (danger) RuneTokens.Danger else RuneTokens.Text)
            Spacer(Modifier.height(1.dp))
            Text(desc, fontSize = 10.5.sp, color = RuneTokens.Muted)
        }
    }
}

@Composable
private fun RowScope.BatchBtn(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) RuneTokens.Danger else RuneTokens.Text
    Column(
        Modifier.weight(1f).clickable { onClick() }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 9.5.sp, color = tint)
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
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
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
