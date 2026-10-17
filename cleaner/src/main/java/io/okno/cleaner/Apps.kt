package io.okno.cleaner

import android.app.Application
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY = 86_400_000L

data class AppItem(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap,
    val installed: Long,
    /** 0 when Android has no record of the app being opened. */
    val lastUsed: Long,
    /** -1 when unknown (no usage access). */
    val size: Long,
) {
    val idleSince get() = if (lastUsed > 0) lastUsed else installed
}

enum class AppSort(val title: String, val comparator: Comparator<AppItem>) {
    Idle("Давно не открывались", compareBy { it.idleSince }),
    Size("По размеру", compareByDescending { it.size }),
    Name("По имени", compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
    Installed("Недавно установленные", compareByDescending { it.installed }),
}

enum class Idle(val title: String, val days: Int) {
    All("Все", 0), Month("Месяц+", 30), Months3("3 мес+", 90), Months6("Полгода+", 180), Year("Год+", 365)
}

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val pm = app.packageManager

    var apps by mutableStateOf<List<AppItem>?>(null); private set
    var hasUsage by mutableStateOf(hasUsageAccess(app)); private set
    val selected = mutableStateMapOf<String, Long>()

    var sort by prefs.pref(AppSort.Idle)
    var idle by prefs.pref(Idle.All)
    var query by mutableStateOf("")

    init { load() }

    val visible: List<AppItem>
        get() {
            val cutoff = System.currentTimeMillis() - idle.days * DAY
            val q = query.trim()
            return apps.orEmpty()
                .filter { it.idleSince <= cutoff && (q.isEmpty() || it.label.contains(q, true) || it.pkg.contains(q, true)) }
                .sortedWith(sort.comparator)
        }

    val selectedBytes get() = selected.values.sumOf { it.coerceAtLeast(0) }

    fun load() = viewModelScope.launch { apps = withContext(Dispatchers.IO) { loadApps() } }

    /** Called on every return to the app: picks up a granted permission and apps removed meanwhile. */
    fun refresh() {
        val access = hasUsageAccess(getApplication())
        if (access != hasUsage) {
            hasUsage = access
            load()
        }
        apps = apps?.filter { isInstalled(it.pkg) }
        selected.keys.retainAll(apps.orEmpty().map { it.pkg }.toSet())
    }

    fun toggle(app: AppItem) {
        if (selected.remove(app.pkg) == null) selected[app.pkg] = app.size
    }

    fun selectAll(items: List<AppItem>) = items.forEach { selected[it.pkg] = it.size }
    fun clearSelection() = selected.clear()

    private fun isInstalled(pkg: String) =
        runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

    private fun loadApps(): List<AppItem> {
        val ctx = getApplication<Application>()
        val now = System.currentTimeMillis()
        val usage = if (hasUsage) runCatching {
            ctx.getSystemService(UsageStatsManager::class.java)
                .queryAndAggregateUsageStats(now - 3 * 365 * DAY, now)
                .mapValues { it.value.lastTimeUsed.takeIf { t -> t in 1..now } ?: 0L }
        }.getOrNull().orEmpty() else emptyMap()
        val storage = ctx.getSystemService(StorageStatsManager::class.java)
        val iconPx = (48 * ctx.resources.displayMetrics.density).toInt()

        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }
            .mapNotNull { info ->
                runCatching {
                    val size = if (hasUsage) runCatching {
                        storage.queryStatsForPackage(info.storageUuid, info.packageName, Process.myUserHandle())
                            .let { it.appBytes + it.dataBytes }
                    }.getOrDefault(-1) else -1
                    AppItem(
                        pkg = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        icon = info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap(),
                        installed = pm.getPackageInfo(info.packageName, 0).firstInstallTime,
                        lastUsed = usage[info.packageName] ?: 0,
                        size = size,
                    )
                }.getOrNull()
            }
    }
}
