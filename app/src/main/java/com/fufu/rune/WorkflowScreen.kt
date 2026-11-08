package com.fufu.rune

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
fun WorkflowScreen() {
    val ctx = LocalContext.current
    var tasks by remember { mutableStateOf(WorkflowStore.load(ctx)) }
    var editing by remember { mutableStateOf<WfTask?>(null) }
    var isNew by remember { mutableStateOf(false) }

    fun persist() { WorkflowStore.save(ctx, tasks) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("任务", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = RuneTokens.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                editing = WfTask(id = UUID.randomUUID().toString(), name = "", trigger = "app", action = "script")
                isNew = true
            }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建", tint = RuneTokens.Accent)
            }
        }
        Spacer(Modifier.height(8.dp))

        if (tasks.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.AccountTree, null, tint = RuneTokens.Muted.copy(alpha = 0.5f),
                    modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp))
                Text("还没有任务", fontSize = 14.sp, color = RuneTokens.Muted)
                Spacer(Modifier.height(4.dp))
                Text("点右上角 + 新建一条", fontSize = 11.5.sp, color = RuneTokens.Muted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onToggle = { checked ->
                            task.enabled = checked
                            tasks = tasks.toList()
                            persist()
                        },
                        onRun = {
                            Thread {
                                val msg = WorkflowEngine.runOnce(task)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                }
                            }.start()
                        },
                        onEdit = { editing = task.copy(); isNew = false },
                        onDelete = {
                            tasks = tasks.filter { it.id != task.id }
                            persist()
                        }
                    )
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }

    editing?.let { task ->
        TaskDialog(
            task = task,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                tasks = if (isNew) tasks + saved else tasks.map { if (it.id == saved.id) saved else it }
                persist()
                editing = null
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: WfTask,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = RuneTokens.Panel,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, RuneTokens.Border),
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).background(
                        RuneTokens.Accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AccountTree, null, tint = RuneTokens.Accent,
                        modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.name.ifBlank { "未命名任务" },
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = RuneTokens.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        WorkflowEngine.describe(task),
                        fontSize = 10.5.sp, color = RuneTokens.Muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRun,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, RuneTokens.Border)
                ) { Text("运行一次", fontSize = 11.sp, color = RuneTokens.Text) }
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, RuneTokens.Border)
                ) { Text("删除", fontSize = 11.sp, color = RuneTokens.Danger) }
            }
        }
    }
}

@Composable
private fun TaskDialog(
    task: WfTask,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (WfTask) -> Unit
) {
    var name by remember { mutableStateOf(task.name) }
    var trigger by remember { mutableStateOf(task.trigger) }
    var triggerArg by remember { mutableStateOf(task.triggerArg) }
    var action by remember { mutableStateOf(task.action) }
    var actionArg by remember { mutableStateOf(task.actionArg) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RuneTokens.Panel,
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (isNew) "新建任务" else "编辑任务", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("任务名称") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RuneTokens.Accent,
                        unfocusedBorderColor = RuneTokens.Border
                    )
                )
                Spacer(Modifier.height(10.dp))
                Text("触发条件", fontSize = 11.sp, color = RuneTokens.Muted)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("应用启动", trigger == "app") { trigger = "app" }
                    Chip("开机", trigger == "boot") { trigger = "boot" }
                    Chip("文件变化", trigger == "file") { trigger = "file" }
                }
                if (trigger != "boot") {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = triggerArg, onValueChange = { triggerArg = it }, singleLine = true,
                        label = { Text(if (trigger == "app") "包名" else "监听路径") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RuneTokens.Accent,
                            unfocusedBorderColor = RuneTokens.Border
                        )
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("执行动作", fontSize = 11.sp, color = RuneTokens.Muted)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("执行脚本", action == "script") { action = "script" }
                    Chip("复制", action == "copy") { action = "copy" }
                    Chip("删除", action == "delete") { action = "delete" }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = actionArg, onValueChange = { actionArg = it },
                    label = {
                        Text(
                            when (action) {
                                "script" -> "shell 命令"
                                "copy" -> "源路径|目标路径"
                                else -> "要删除的路径"
                            }
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RuneTokens.Accent,
                        unfocusedBorderColor = RuneTokens.Border
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    task.copy(
                        name = name.trim().ifBlank { "未命名任务" },
                        trigger = trigger, triggerArg = triggerArg.trim(),
                        action = action, actionArg = actionArg.trim()
                    )
                )
            }) { Text("保存", color = RuneTokens.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = RuneTokens.Muted) } }
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) RuneTokens.Accent.copy(alpha = 0.14f) else RuneTokens.Panel2,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label, fontSize = 11.5.sp,
            color = if (selected) RuneTokens.Accent else RuneTokens.Muted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
