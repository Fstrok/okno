package io.okno.cleaner

import android.app.Application
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Base64
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class Kind(val title: String, val icon: ImageVector, vararg val ext: String) {
    Image("Фото", Icons.Outlined.Image, "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "avif"),
    Video("Видео", Icons.Outlined.Movie, "mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts"),
    Audio("Музыка", Icons.Outlined.MusicNote, "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "wma"),
    Document("Документы", Icons.Outlined.Description, "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "txt", "rtf", "csv", "epub", "fb2", "djvu"),
    Archive("Архивы", Icons.Outlined.FolderZip, "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "tgz"),
    Apk("APK", Icons.Outlined.Android, "apk", "apks", "xapk"),
    Other("Другое", Icons.AutoMirrored.Outlined.InsertDriveFile);

    companion object {
        private val byExt = entries.flatMap { k -> k.ext.map { it to k } }.toMap()
        fun of(name: String) = byExt[name.substringAfterLast('.', "").lowercase()] ?: Other
    }
}

data class FileItem(val path: String, val modified: Long) {
    val file get() = File(path)
    val name get() = path.substringAfterLast('/')
    val folder get() = path.substringBeforeLast('/').removePrefix(storageRoot.path).trimStart('/').ifEmpty { "Память телефона" }
}

data class DupGroup(val id: String, val size: Long, val files: List<FileItem>) {
    val kind = Kind.of(files.first().name)
    val wasted get() = size * (files.size - 1)
    val newest get() = files.maxOf { it.modified }
}

enum class DupSort(val title: String, val comparator: Comparator<DupGroup>) {
    Wasted("Больше всего места", compareByDescending { it.wasted }),
    Copies("Больше копий", compareByDescending<DupGroup> { it.files.size }.thenByDescending { it.wasted }),
    Size("Крупные файлы", compareByDescending { it.size }),
    Name("По имени", compareBy(String.CASE_INSENSITIVE_ORDER) { it.files.first().name }),
    Newest("Сначала новые", compareByDescending { it.newest }),
    Oldest("Сначала старые", compareBy { it.newest }),
}

enum class Keep(val title: String) { Oldest("Оставлять самый старый"), Newest("Оставлять самый новый"), ShortestPath("Оставлять с коротким путём") }

enum class MinSize(val title: String, val bytes: Long) {
    Any("Любые", 1), K100("100 КБ+", 100 shl 10), M1("1 МБ+", 1 shl 20), M10("10 МБ+", 10 shl 20)
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val phase: String, val progress: Float?, val detail: String) : ScanState
    data object Done : ScanState
}

val storageRoot: File = Environment.getExternalStorageDirectory()

class DuplicatesViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("duplicates", Context.MODE_PRIVATE)

    var state by mutableStateOf<ScanState>(ScanState.Idle); private set
    var groups by mutableStateOf(emptyList<DupGroup>()); private set
    val selected = mutableStateMapOf<String, Long>()

    var sort by prefs.pref(DupSort.Wasted)
    var keep by prefs.pref(Keep.Oldest)
    var minSize by prefs.pref(MinSize.K100)
    var hidden by mutableStateOf(prefs.getBoolean("hidden", false))
    var kind by mutableStateOf<Kind?>(null)
    var query by mutableStateOf("")

    private var job: Job? = null

    fun updateHidden(value: Boolean) {
        hidden = value
        prefs.edit { putBoolean("hidden", value) }
    }

    val visible: List<DupGroup>
        get() {
            val q = query.trim()
            return groups.filter { g ->
                (kind == null || g.kind == kind) && (q.isEmpty() || g.files.any { it.path.contains(q, ignoreCase = true) })
            }.sortedWith(sort.comparator)
        }

    val reclaimable get() = groups.sumOf { it.wasted }
    val selectedBytes get() = selected.values.sum()

    fun scan() {
        job?.cancel()
        job = viewModelScope.launch {
            selected.clear()
            groups = emptyList()
            state = ScanState.Scanning("Ищу файлы", null, "")
            val scan = coroutineContext.job
            groups = withContext(Dispatchers.IO) { findDuplicates(minSize.bytes, hidden) { if (scan.isActive) state = it } }
            autoSelect()
            state = ScanState.Done
        }
    }

    fun cancel() {
        job?.cancel()
        state = if (groups.isEmpty()) ScanState.Idle else ScanState.Done
    }

    fun autoSelect() {
        selected.clear()
        for (g in groups) {
            val keeper = when (keep) {
                Keep.Oldest -> g.files.minWith(compareBy<FileItem> { it.modified }.thenBy { it.path.length })
                Keep.Newest -> g.files.minWith(compareByDescending<FileItem> { it.modified }.thenBy { it.path.length })
                Keep.ShortestPath -> g.files.minWith(compareBy<FileItem> { it.path.count { c -> c == '/' } }.thenBy { it.path.length })
            }
            g.files.forEach { if (it !== keeper) selected[it.path] = g.size }
        }
    }

    /** Returns false when the toggle would leave a group with no copy kept. */
    fun toggle(group: DupGroup, file: FileItem): Boolean {
        if (selected.remove(file.path) != null) return true
        if (group.files.count { it.path !in selected } <= 1) return false
        selected[file.path] = group.size
        return true
    }

    fun clearSelection() = selected.clear()

    /** Deletes the marked files; returns how many went, the bytes freed and how many could not be deleted. */
    suspend fun deleteSelected(): Triple<Int, Long, Int> {
        val targets = selected.toMap()
        val deleted = withContext(Dispatchers.IO) { targets.keys.filter { File(it).run { delete(); !exists() } } }
        if (deleted.isNotEmpty()) MediaScannerConnection.scanFile(getApplication(), deleted.toTypedArray(), null, null)
        val gone = deleted.toHashSet()
        groups = groups.map { g -> g.copy(files = g.files.filterNot { it.path in gone }) }.filter { it.files.size > 1 }
        gone.forEach { selected.remove(it) }
        return Triple(deleted.size, deleted.sumOf { targets.getValue(it) }, targets.size - deleted.size)
    }
}

private val skippedDirs = setOf("Android/data", "Android/obb").map { File(storageRoot, it).path }.toSet()
private val io = Dispatchers.IO.limitedParallelism(4)
private const val HEAD = 64 * 1024L

private suspend fun findDuplicates(minSize: Long, hidden: Boolean, report: (ScanState.Scanning) -> Unit): List<DupGroup> {
    val bySize = HashMap<Long, MutableList<File>>()
    var count = 0
    val dirs = ArrayDeque(listOf(storageRoot))
    while (dirs.isNotEmpty()) {
        coroutineContext.ensureActive()
        for (f in dirs.removeLast().listFiles() ?: continue) {
            if (!hidden && f.name.startsWith('.')) continue
            if (f.isDirectory) {
                if (f.path !in skippedDirs && !Files.isSymbolicLink(f.toPath())) dirs += f
            } else {
                val size = f.length()
                if (size >= minSize) bySize.getOrPut(size, ::ArrayList) += f
                if (++count % 500 == 0) report(ScanState.Scanning("Ищу файлы", null, "Просмотрено ${plural(count, "файл", "файла", "файлов")}"))
            }
        }
    }

    // Same size → same first 64 KB → same full contents.
    val sameSize = bySize.values.filter { it.size > 1 }
    val headTotal = sameSize.sumOf { it.size }
    val headDone = AtomicLong()
    val sameHead = sameSize.splitBy { f ->
        hash(f, HEAD).also { report(ScanState.Scanning("Сравниваю файлы", headDone.incrementAndGet().toFloat() / headTotal, "${headDone.get()} из $headTotal")) }
    }

    val fullTotal = sameHead.filter { it[0].length() > HEAD }.sumOf { g -> g.sumOf { it.length() } }.coerceAtLeast(1)
    val fullDone = AtomicLong()
    val same = sameHead.splitBy { f ->
        if (f.length() <= HEAD) "" else hash(f, Long.MAX_VALUE).also {
            report(ScanState.Scanning("Проверяю содержимое", fullDone.addAndGet(f.length()).toFloat() / fullTotal, ""))
        }
    }

    return same.map { files ->
        val items = files.map { FileItem(it.path, it.lastModified()) }.sortedBy { it.modified }
        DupGroup(items.first().path, files[0].length(), items)
    }
}

/** Splits each group into subgroups of equal [key], keeping only those with copies. */
private suspend fun List<List<File>>.splitBy(key: (File) -> String): List<List<File>> = coroutineScope {
    map { group -> async(io) { coroutineContext.ensureActive(); group.map { runCatching { key(it) }.getOrNull() to it } } }
        .awaitAll()
        .flatMap { pairs -> pairs.filter { it.first != null }.groupBy({ it.first }, { it.second }).values.filter { it.size > 1 } }
}

private fun hash(file: File, limit: Long): String {
    val md = MessageDigest.getInstance("MD5")
    val buf = ByteArray(HEAD.toInt())
    RandomAccessFile(file, "r").use { raf ->
        var left = limit
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            md.update(buf, 0, n)
            left -= n
        }
    }
    return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
}
