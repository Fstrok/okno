package io.okno.cleaner

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(modifier: Modifier, vm: AppsViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }
    // Android asks to confirm each removal, so selected apps go through its dialog one by one.
    val queue = remember { mutableStateListOf<String>() }
    val uninstall = rememberLauncherForActivityResult(StartActivityForResult()) {
        queue.removeFirstOrNull()
        vm.refresh()
    }
    LaunchedEffect(queue.firstOrNull()) {
        queue.firstOrNull()?.let { uninstall.launch(Intent(Intent.ACTION_DELETE, "package:$it".toUri())) }
    }

    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SearchTopBar("Приложения", vm.query, { vm.query = it }, scroll) {
                SortMenu(AppSort.entries, vm.sort, { it.title }, { vm.sort = it }) { close ->
                    MenuDivider()
                    DropdownMenuItem(text = { Text("Отметить все в списке") }, onClick = { vm.selectAll(vm.visible); close() })
                    DropdownMenuItem(text = { Text("Снять все отметки") }, onClick = { vm.clearSelection(); close() })
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(vm.selected.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                val size = vm.selectedBytes.takeIf { it > 0 }?.let { " · ${ctx.bytes(it)}" }.orEmpty()
                ExtendedFloatingActionButton(
                    onClick = { queue.addAll(vm.selected.keys - queue.toSet()) },
                    icon = { Icon(Icons.Outlined.Delete, null) },
                    text = { Text("Удалить ${vm.selected.size}$size") },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) { padding ->
        if (vm.apps == null) Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
        else AppList(vm, padding)
    }
}

@Composable
private fun AppList(vm: AppsViewModel, padding: PaddingValues) {
    val ctx = LocalContext.current
    val apps = vm.visible
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp)) {
        if (!vm.hasUsage) item(key = "usage") {
            Card(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(24.dp),
            ) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.QueryStats, null, Modifier.size(28.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Когда приложения открывались", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Разрешите доступ к статистике использования — так видно, какие приложения давно не открывались и сколько места они занимают.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilledTonalButton({ ctx.openUsageSettings() }) { Text("Разрешить") }
                    }
                }
            }
        }
        item(key = "summary") {
            Card(
                Modifier.padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        if (vm.idle == Idle.All) "Установлено вами" else "Не открывались ${vm.idle.title.lowercase().removeSuffix("+")} и дольше",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(plural(apps.size, "приложение", "приложения", "приложений"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    if (vm.hasUsage && apps.isNotEmpty()) Text("Занимают ${ctx.bytes(apps.sumOf { it.size.coerceAtLeast(0) })}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Нажмите, чтобы отметить, удерживайте — сведения о приложении",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                }
            }
        }
        item(key = "idle") {
            LazyRow(contentPadding = PaddingValues(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Idle.entries) { FilterChip(vm.idle == it, { vm.idle = it }, { Text(it.title) }) }
            }
        }
        if (apps.isEmpty()) item(key = "empty") {
            Placeholder(Icons.Outlined.SearchOff, "Ничего не нашлось", "Таких приложений нет — попробуйте другой фильтр.")
        }
        items(apps, key = { it.pkg }) { AppRow(it, it.pkg in vm.selected, vm.hasUsage, { vm.toggle(it) }, Modifier.animateItem()) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(app: AppItem, checked: Boolean, hasUsage: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val status = when {
        !hasUsage -> "Установлено ${ctx.date(app.installed)}"
        app.lastUsed > 0 -> "Открывалось ${ago(app.lastUsed)}"
        else -> "Не открывалось с установки, ${ctx.date(app.installed)}"
    }
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = { ctx.openAppInfo(app.pkg) })
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(app.icon, null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (app.size >= 0) Text(ctx.bytes(app.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Checkbox(checked, { onToggle() })
    }
}
