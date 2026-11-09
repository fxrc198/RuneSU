package com.fufu.rune

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal enum class SortBy(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    TIME("时间"),
    TYPE("类型")
}

internal data class SortSpec(
    val by: SortBy = SortBy.NAME,
    val desc: Boolean = false
)

internal fun listDir(path: String, spec: SortSpec): List<File> {
    val list = File(path).listFiles() ?: return emptyList()
    val dirs = list.filter { it.isDirectory }
    val files = list.filter { !it.isDirectory }
    val cmp: Comparator<File> = when (spec.by) {
        SortBy.NAME -> compareBy { it.name.lowercase() }
        SortBy.SIZE -> compareBy { it.length() }
        SortBy.TIME -> compareBy { it.lastModified() }
        SortBy.TYPE -> compareBy { it.extension.lowercase() }
    }
    val ordered = if (spec.desc) cmp.reversed() else cmp
    return dirs.sortedWith(ordered) + files.sortedWith(ordered)
}

internal fun fileIcon(f: File): ImageVector {
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

internal fun dateText(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

internal fun copyRecursive(src: File, dstDir: File): Boolean {
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

internal fun deleteRecursive(f: File): Boolean {
    if (f.isDirectory) f.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
    return f.delete()
}

internal fun batch(files: List<File>, op: (File) -> Boolean): String {
    var ok = 0
    var err: String? = null
    files.forEach { f ->
        try {
            if (op(f)) ok++ else if (err == null) err = "操作失败"
        } catch (e: Exception) {
            if (err == null) err = e.message
        }
    }
    return if (err != null) "完成 $ok/${files.size}：$err" else "完成 $ok 项"
}

internal fun zipFile(src: File, out: File): Boolean = try {
    val root = src.parentFile?.absolutePath ?: ""
    ZipOutputStream(out.outputStream().buffered()).use { zos ->
        val walk = if (src.isDirectory) src.walkTopDown() else sequenceOf(src)
        walk.forEach { f ->
            var name = f.absolutePath.removePrefix("$root/")
            if (name.isEmpty()) name = src.name
            if (f.isDirectory) {
                zos.putNextEntry(ZipEntry("$name/"))
                zos.closeEntry()
            } else {
                zos.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
    true
} catch (_: Exception) { false }

internal fun unzip(src: File, dstDir: File): Boolean = try {
    ZipInputStream(src.inputStream().buffered()).use { zis ->
        var e = zis.nextEntry
        while (e != null) {
            val out = File(dstDir, e.name)
            if (e.isDirectory) out.mkdirs()
            else {
                out.parentFile?.mkdirs()
                out.outputStream().use { zis.copyTo(it) }
            }
            zis.closeEntry()
            e = zis.nextEntry
        }
    }
    true
} catch (_: Exception) { false }
