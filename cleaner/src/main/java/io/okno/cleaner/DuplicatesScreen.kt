package io.okno.cleaner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(modifier: Modifier, vm: DuplicatesViewModel = viewModel()) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var access by remember { mutableStateOf(hasFileAccess(ctx)) }
    var options by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val askLegacy = rememberLauncherForActivityResult(RequestMultiplePermissions()) { access = hasFileAccess(ctx) }
    LifecycleResumeEffect(Unit) {
        access = hasFileAccess(ctx)
        onPauseOrDispose {}
    }
    val done = vm.state == ScanState.Done && vm.groups.isNotEmpty()

    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            SearchTopBar("Дубликаты", vm.query, { vm.query = it }, scroll, searchable = done) {
                if (done) SortMenu(DupSort.entries, vm.sort, { it.title }, { vm.sort = it })
                DuplicatesMenu(vm, onOptions = { options = true })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(done && vm.selected.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = { confirm = true },
                    icon = { Icon(Icons.Outlined.Delete, null) },
                    text = { Text("Удалить · ${ctx.bytes(vm.selectedBytes)}") },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        val state = vm.state
        when {
            !access -> Placeholder(
                Icons.Outlined.FolderOff, "Нужен доступ к файлам",
                "Чтобы найти одинаковые файлы, приложению нужно видеть всю память телефона. Файлы никуда не отправляются.",
                inner, "Разрешить доступ" to { if (!ctx.openAllFilesSettings()) askLegacy.launch(legacyStoragePermissions) },
            )
            state is ScanState.Scanning -> Placeholder(Icons.AutoMirrored.Outlined.ManageSearch, state.phase, state.detail.ifEmpty { "Это может занять пару минут" }, inner, "Отмена" to vm::cancel) {
                val bar = Modifier.width(240.dp).padding(top = 8.dp)
                if (state.progress == null) LinearProgressIndicator(bar) else LinearProgressIndicator({ state.progress }, bar)
            }
            state == ScanState.Idle -> Placeholder(
                Icons.Outlined.ContentCopy, "Поиск дубликатов",
                "Найду одинаковые файлы по содержимому, даже если у них разные имена и папки.", inner,
            ) {
                ScanOptions(vm, Modifier.padding(top = 8.dp))
                Button(vm::scan, Modifier.padding(top = 8.dp)) { Text("Начать поиск") }
            }
            vm.groups.isEmpty() -> Placeholder(
                Icons.Outlined.CheckCircle, "Дубликатов нет", "Одинаковых файлов не найдено — память в порядке.", inner, "Искать снова" to vm::scan,
            )
            else -> GroupList(vm, padding) { scope.launch { snackbar.showSnackbar("Одна копия должна остаться") } }
        }
    }

    if (options) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Параметры поиска", style = MaterialTheme.typography.titleLarge)
            ScanOptions(vm)
            Button({ options = false; vm.scan() }, Modifier.fillMaxWidth().height(52.dp)) { Text("Искать заново") }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        icon = { Icon(Icons.Outlined.Delete, null) },
        title = { Text("Удалить ${plural(vm.selected.size, "файл", "файла", "файлов")}?") },
        text = { Text("Освободится ${ctx.bytes(vm.selectedBytes)}. Файлы удаляются насовсем, без корзины. В каждой группе остаётся хотя бы одна копия.") },
        confirmButton = {
            Button(
                onClick = {
                    confirm = false
                    scope.launch {
                        val (count, bytes, failed) = vm.deleteSelected()
                        snackbar.showSnackbar(
                            "Удалено: ${plural(count, "файл", "файла", "файлов")}, освобождено ${ctx.bytes(bytes)}" +
                                if (failed > 0) ". Не удалось удалить: $failed" else "",
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError),
            ) { Text("Удалить") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } },
    )
}

@Composable
private fun DuplicatesMenu(vm: DuplicatesViewModel, onOptions: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
    DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp)) {
        if (vm.groups.isNotEmpty()) {
            Text("Отметить лишние копии", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            Keep.entries.forEach { k ->
                DropdownMenuItem(text = { Text(k.title) }, onClick = { vm.keep = k; vm.autoSelect(); open = false })
            }
            DropdownMenuItem(text = { Text("Снять все отметки") }, onClick = { vm.clearSelection(); open = false })
            MenuDivider()
        }
        DropdownMenuItem(text = { Text("Параметры поиска") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }, onClick = { open = false; onOptions() })
        DropdownMenuItem(text = { Text("Искать заново") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { open = false; vm.scan() })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanOptions(vm: DuplicatesViewModel, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Размер файлов", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MinSize.entries.forEach { FilterChip(vm.minSize == it, { vm.minSize = it }, { Text(it.title) }) }
        }
        ListItem(
            headlineContent = { Text("Скрытые файлы") },
            supportingContent = { Text("Папки с точкой в начале: кэши, миниатюры") },
            trailingContent = { Switch(vm.hidden, vm::updateHidden) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { vm.updateHidden(!vm.hidden) },
        )
    }
}

@Composable
private fun GroupList(vm: DuplicatesViewModel, padding: PaddingValues, onLastCopy: () -> Unit) {
    val ctx = LocalContext.current
    val groups = vm.visible
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("Можно освободить", style = MaterialTheme.typography.labelLarge)
                    Text(ctx.bytes(vm.reclaimable), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    val extra = vm.groups.sumOf { it.files.size - 1 }
                    Text(
                        "${plural(vm.groups.size, "группа", "группы", "групп")} одинаковых файлов, ${plural(extra, "лишняя копия", "лишние копии", "лишних копий")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Отмечено: ${plural(vm.selected.size, "файл", "файла", "файлов")} · ${ctx.bytes(vm.selectedBytes)}",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        item(key = "kinds") {
            val counts = vm.groups.groupingBy { it.kind }.eachCount()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(vm.kind == null, { vm.kind = null }, { Text("Все · ${vm.groups.size}") }) }
                items(Kind.entries.filter { it in counts }) { k ->
                    FilterChip(
                        vm.kind == k, { vm.kind = if (vm.kind == k) null else k }, { Text("${k.title} · ${counts[k]}") },
                        leadingIcon = { Icon(k.icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    )
                }
            }
        }
        if (groups.isEmpty()) item(key = "empty") {
            Placeholder(Icons.Outlined.SearchOff, "Ничего не нашлось", "Попробуйте другой запрос или фильтр.")
        }
        items(groups, key = { it.id }) { GroupCard(it, vm, onLastCopy, Modifier.animateItem()) }
    }
}

@Composable
private fun GroupCard(group: DupGroup, vm: DuplicatesViewModel, onLastCopy: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val first = group.files.first()
    Card(modifier, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.clickable { ctx.openFile(first.file) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(first.path, group.kind, 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(first.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(
                    "${plural(group.files.size, "копия", "копии", "копий")} по ${ctx.bytes(group.size)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                Text("+${ctx.bytes(group.wasted)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        group.files.forEach { f ->
            val checked = f.path in vm.selected
            Row(
                Modifier.fillMaxWidth().clickable { ctx.openFile(f.file) }.padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked, { if (!vm.toggle(group, f)) onLastCopy() })
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(f.folder, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(
                        listOfNotNull(ctx.date(f.modified), f.name.takeIf { it != first.name }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (!checked) Text("остаётся", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
