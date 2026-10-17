package io.okno.cleaner

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// ---- Settings, permissions, system actions ----

/** An enum setting that is Compose state and survives restarts. */
fun <E : Enum<E>> SharedPreferences.pref(default: E) = object : ReadWriteProperty<Any?, E> {
    val key = default.declaringJavaClass.simpleName
    val state = mutableStateOf(default.declaringJavaClass.enumConstants!!.find { it.name == getString(key, null) } ?: default)
    override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: E) {
        state.value = value
        edit { putString(key, value.name) }
    }
}

fun hasFileAccess(ctx: Context) =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

val legacyStoragePermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

fun Context.openAllFilesSettings() = Build.VERSION.SDK_INT >= 30 && tryStart(
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()),
    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
)

@Suppress("DEPRECATION")
fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    return if (mode == AppOpsManager.MODE_DEFAULT) ctx.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    else mode == AppOpsManager.MODE_ALLOWED
}

fun Context.openUsageSettings() = tryStart(
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri()),
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
)

fun Context.openAppInfo(pkg: String) = tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri()))

fun Context.openFile(file: File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (!tryStart(intent)) Toast.makeText(this, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_SHORT).show()
}

private fun Context.tryStart(vararg intents: Intent) = intents.any {
    try {
        startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

// ---- Text ----

fun Context.bytes(n: Long): String = Formatter.formatShortFileSize(this, n)

fun Context.date(t: Long): String = DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

fun ago(t: Long): String = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString().lowercase()

fun plural(n: Int, one: String, few: String, many: String): String {
    val form = when {
        n % 100 in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }
    return "$n $form"
}

// ---- UI pieces ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    title: String,
    query: String,
    onQuery: (String) -> Unit,
    scroll: TopAppBarScrollBehavior,
    searchable: Boolean = true,
    actions: @Composable () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val focus = remember { FocusRequester() }
    TopAppBar(
        scrollBehavior = scroll,
        colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        navigationIcon = {
            if (searching) IconButton(onClick = { searching = false; onQuery("") }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Закрыть поиск")
            }
        },
        title = {
            if (!searching) Text(title)
            else {
                TextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text("Поиск") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        },
        actions = {
            if (!searching) { if (searchable) IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, "Поиск") } }
            else if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Очистить") }
            actions()
        },
    )
}

@Composable
fun <T> SortMenu(options: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit, extra: @Composable (close: () -> Unit) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Сортировка") }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(16.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(title(option)) },
                    onClick = { onSelect(option); open = false },
                    trailingIcon = { if (option == selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
            extra { open = false }
        }
    }
}

@Composable
fun MenuDivider() = HorizontalDivider(Modifier.padding(vertical = 4.dp))

/** Full-screen message: empty list, missing permission, finished scan. */
@Composable
fun Placeholder(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null, content: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
            Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        content()
        action?.let { (label, onClick) -> FilledTonalButton(onClick, Modifier.padding(top = 8.dp)) { Text(label) } }
    }
}

/** File type icon, or a picture/video preview when one can be made. */
@Composable
fun FileThumb(path: String, kind: Kind, size: Dp) {
    val bitmap by produceState(thumbs[path], path) {
        if (value == null && (kind == Kind.Image || kind == Kind.Video) && Build.VERSION.SDK_INT >= 29) {
            value = withContext(Dispatchers.IO) { runCatching { thumbnail(File(path), kind) }.getOrNull() }
                ?.also { thumbs.put(path, it) }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(kind.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private val thumbs = LruCache<String, ImageBitmap>(120)

@androidx.annotation.RequiresApi(29)
private fun thumbnail(file: File, kind: Kind): ImageBitmap {
    val size = Size(192, 192)
    val bmp: Bitmap = if (kind == Kind.Image) ThumbnailUtils.createImageThumbnail(file, size, null)
    else ThumbnailUtils.createVideoThumbnail(file, size, null)
    return bmp.asImageBitmap()
}
