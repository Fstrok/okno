package io.okno.cleaner

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { CleanerTheme { CleanerApp() } }
    }
}

@Composable
fun CleanerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = Color(0xFF84D6C2), onPrimary = Color(0xFF00382E), primaryContainer = Color(0xFF005144),
            onPrimaryContainer = Color(0xFFA0F2DE), secondaryContainer = Color(0xFF334B45), onSecondaryContainer = Color(0xFFCDE8DF),
            background = Color(0xFF0F1513), surface = Color(0xFF0F1513), surfaceContainer = Color(0xFF1B211F),
            surfaceContainerLow = Color(0xFF171D1B), surfaceContainerHigh = Color(0xFF252B29),
        )
        else -> lightColorScheme(
            primary = Color(0xFF0F6B5C), primaryContainer = Color(0xFFA0F2DE), onPrimaryContainer = Color(0xFF00201A),
            secondaryContainer = Color(0xFFCDE8DF), onSecondaryContainer = Color(0xFF06201B),
            background = Color(0xFFF5FBF8), surface = Color(0xFFF5FBF8), surfaceContainer = Color(0xFFE9EFEC),
            surfaceContainerLow = Color(0xFFEFF5F2), surfaceContainerHigh = Color(0xFFE3EAE7),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun CleanerApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, label = { Text("Дубликаты") },
                    icon = { Icon(if (tab == 0) Icons.Filled.FileCopy else Icons.Outlined.FileCopy, null) },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 }, label = { Text("Приложения") },
                    icon = { Icon(if (tab == 1) Icons.Filled.Apps else Icons.Outlined.Apps, null) },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        if (tab == 0) DuplicatesScreen(modifier) else AppsScreen(modifier)
    }
}
