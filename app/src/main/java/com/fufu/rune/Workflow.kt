package com.fufu.rune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WfTask(
    var id: String = "",
    var name: String = "",
    var trigger: String = "app",      // app / boot / file
    var triggerArg: String = "",
    var action: String = "script",    // script / copy / delete
    var actionArg: String = "",
    var enabled: Boolean = true
)

object WorkflowStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "workflow.json")

    fun load(ctx: Context): MutableList<WfTask> {
        val out = mutableListOf<WfTask>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    WfTask(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        trigger = o.optString("trigger", "app"),
                        triggerArg = o.optString("triggerArg"),
                        action = o.optString("action", "script"),
                        actionArg = o.optString("actionArg"),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    fun save(ctx: Context, list: List<WfTask>) {
        try {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("trigger", t.trigger)
                    put("triggerArg", t.triggerArg)
                    put("action", t.action)
                    put("actionArg", t.actionArg)
                    put("enabled", t.enabled)
                })
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) { }
    }
}

object WorkflowEngine {

    fun describe(task: WfTask): String {
        val t = when (task.trigger) {
            "app" -> "应用启动：${task.triggerArg.ifBlank { "-" }}"
            "boot" -> "开机完成"
            "file" -> "文件变化：${task.triggerArg.ifBlank { "-" }}"
            else -> task.trigger
        }
        val a = when (task.action) {
            "script" -> "执行脚本：${task.actionArg.ifBlank { "-" }}"
            "copy" -> "复制到：${task.actionArg.ifBlank { "-" }}"
            "delete" -> "删除：${task.actionArg.ifBlank { "-" }}"
            else -> task.action
        }
        return "$t → $a"
    }

    fun runOnce(task: WfTask): String {
        return try {
            when (task.action) {
                "script" -> exec(task.actionArg)
                "copy" -> {
                    val parts = task.actionArg.split("|", limit = 2)
                    if (parts.size < 2) "参数应为「源|目标」"
                    else if (copyRecursive(File(parts[0]), File(parts[1]))) "已复制" else "复制失败"
                }
                "delete" -> {
                    if (task.actionArg.isBlank()) "路径为空"
                    else if (deleteRecursive(File(task.actionArg))) "已删除" else "删除失败"
                }
                else -> "未知动作"
            }
        } catch (e: Exception) {
            "失败：${e.message}"
        }
    }

    private fun exec(cmd: String): String {
        if (cmd.isBlank()) return "命令为空"
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return if (out.isBlank()) "已执行" else out.take(300)
    }
}
