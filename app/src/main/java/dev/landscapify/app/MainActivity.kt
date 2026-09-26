package dev.landscapify.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshAccess = object : Runnable {
        override fun run() {
            updateAccess()
            handler.postDelayed(this, 1000)
        }
    }
    private val ready = mutableStateOf(false)
    private val usageDialog = mutableStateOf(false)
    private val accessDialog = mutableStateOf(false)
    private val recoveryDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateAccess()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF9AC1F4))
                else lightColorScheme(primary = Color(0xFF315E95))) {
                Surface(Modifier.fillMaxSize()) {
                    AppScreen(model, ready.value,
                onAccess = {
                    if (model.recoveryNeeded.value) recoveryDialog.value = true
                    else accessDialog.value = true
                },
                onLaunch = ::launch,
                onStop = { SessionService.stop(this) },
                onRemoveSession = { SessionService.stopIfActive(this, it) })
                    if (usageDialog.value) {
                        AlertDialog(
                            onDismissRequest = { usageDialog.value = false },
                            title = { Text("Usage Access needed") },
                            text = { Text("Landscapify uses foreground app events during a landscape session to stop forcing when you leave the app.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    usageDialog.value = false
                                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }) { Text("Open settings") }
                            },
                            dismissButton = { TextButton(onClick = { usageDialog.value = false }) { Text("Cancel") } },
                        )
                    }
                    if (accessDialog.value) {
                        AlertDialog(onDismissRequest = { accessDialog.value = false },
                            title = { Text("Accessibility access needed") },
                            text = { Text("Enable Landscapify in Accessibility settings. It creates a transparent orientation window only during a session. It does not read screen content or perform taps.") },
                            confirmButton = { TextButton(onClick = {
                                accessDialog.value = false
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("Open settings") } },
                            dismissButton = { TextButton(onClick = { accessDialog.value = false }) { Text("Cancel") } })
                    }
                    if (recoveryDialog.value) {
                        AlertDialog(onDismissRequest = { recoveryDialog.value = false },
                            title = { Text("Restore older session") },
                            text = { Text("Version 0.2.0 left a recovery record for a previous session. Reinstall that version, open Landscapify to restore its display changes, then install this update again. The new version cannot use Wireless debugging to restore old compatibility flags.") },
                            confirmButton = { TextButton(onClick = { recoveryDialog.value = false }) {
                                Text("OK")
                            } })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccess()
        model.refresh()
        handler.removeCallbacks(refreshAccess)
        handler.postDelayed(refreshAccess, 1000)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshAccess)
        super.onPause()
    }

    private fun updateAccess() { ready.value = OrientationService.isConnected() }

    private fun launch(app: LibraryApp) {
        if (!ready.value) {
            accessDialog.value = true
            return
        }
        if (model.paused.value) {
            model.showMessage("Turn off Pause to start a landscape session.")
            return
        }
        if (model.recoveryNeeded.value) {
            model.showMessage("An older session recovery record remains. Restore it with the previous version before using this update.")
            return
        }
        if (!hasUsageAccess(this)) {
            usageDialog.value = true
            return
        }
        val target = model.catalog.launchIntent(app.packageName)
        if (target == null) {
            model.showMessage("${app.name} is no longer installed or launchable.")
            return
        }
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == SessionService.READY) {
                    try { startActivity(target) }
                    catch (error: Exception) {
                        SessionService.stop(this@MainActivity)
                        model.showMessage("Could not open ${app.name}: ${error.message}")
                    }
                } else if (resultCode == SessionService.FAILED) {
                    model.showMessage(SessionService.failureMessage(resultData))
                }
            }
        }
        runCatching { SessionService.start(this, app.packageName, receiver) }
            .onFailure { model.showMessage(it.message ?: "Could not start the session service.") }
    }
}

@Composable
private fun AppScreen(
    model: MainViewModel,
    ready: Boolean,
    onAccess: () -> Unit,
    onLaunch: (LibraryApp) -> Unit,
    onStop: () -> Unit,
    onRemoveSession: (String) -> Unit,
) {
    var picker by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<LibraryApp?>(null) }
    var onboarding by remember {
        mutableStateOf(!model.getApplication<android.app.Application>()
            .getSharedPreferences("landscapify", 0).getBoolean("onboarded", false))
    }
    BackHandler(picker) { picker = false }
    if (picker) {
        PickerScreen(model, onBack = { picker = false })
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Landscapify", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Your landscape library", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { picker = true }, enabled = ready && !model.recoveryNeeded.value) {
                    Text("Add apps")
                }
            }
            StatusCard(ready, model.recoveryNeeded.value, onAccess)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Pause forcing", style = MaterialTheme.typography.titleMedium)
                    Text("Keep the library, stop new sessions", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = model.paused.value, onCheckedChange = {
                    model.setPaused(it)
                    if (it) onStop()
                })
            }
            HorizontalDivider()
            if (model.library.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(88.dp, 58.dp)
                            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) {
                            Text("↔", style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(22.dp))
                        Text("Your library is empty", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(if (ready) "Add apps to launch them through Landscapify."
                            else "Enable Landscapify in Accessibility settings, then add apps.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(model.library, key = { it.packageName }) { app ->
                        LibraryTile(app, enabled = ready && !model.paused.value && !model.recoveryNeeded.value,
                            onClick = {
                                if (app.unsupported) model.showMessage("${app.name} could not be forced to landscape on this device. Remove and add it again to retry after a system update.")
                                else onLaunch(app)
                            }, onLongClick = { remove = app })
                    }
                }
            }
        }
    }
    remove?.let { app ->
        AlertDialog(onDismissRequest = { remove = null },
            title = { Text("Remove ${app.name}?") },
            text = { Text("This removes the app from your library.") },
            confirmButton = { TextButton(onClick = {
                onRemoveSession(app.packageName)
                model.remove(app.packageName)
                remove = null
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
    model.message.value?.let { message ->
        AlertDialog(onDismissRequest = model::clearMessage,
            title = { Text("Landscapify") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = model::clearMessage) { Text("OK") } })
    }
    if (onboarding) {
        AlertDialog(onDismissRequest = {}, title = { Text("Set up Landscapify") },
            text = { Text("Enable Landscapify in Accessibility settings and grant Usage Access when prompted. It uses a transparent orientation window during each session. Some apps may not rotate or may render poorly.") },
            confirmButton = { TextButton(onClick = {
                model.getApplication<android.app.Application>()
                    .getSharedPreferences("landscapify", 0).edit().putBoolean("onboarded", true).apply()
                onboarding = false
                onAccess()
            }) { Text("Set up") } },
            dismissButton = { TextButton(onClick = {
                model.getApplication<android.app.Application>()
                    .getSharedPreferences("landscapify", 0).edit().putBoolean("onboarded", true).apply()
                onboarding = false
            }) { Text("Later") } })
    }
}

@Composable
private fun StatusCard(ready: Boolean, recovery: Boolean, onAccess: () -> Unit) {
    val text = when {
        recovery -> "An older session needs recovery before this update can run"
        ready -> "Accessibility connected · Ready for landscape sessions"
        else -> "Enable Landscapify in Accessibility settings"
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = if (ready && !recovery) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAccess) {
                Text(if (recovery) "Recovery" else if (ready) "Settings" else "Set up")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTile(app: LibraryApp, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().aspectRatio(0.85f)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(app.packageName, Modifier.size(48.dp))
            Text(app.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(if (app.unsupported) "Unsupported" else if (enabled) "Tap to launch" else "Unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerScreen(model: MainViewModel, onBack: () -> Unit) {
    val apps = remember { model.catalog.installedApps() }
    val inLibrary = remember { model.library.map { it.packageName }.toSet() }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Add apps", style = MaterialTheme.typography.titleLarge)
            Button(onClick = {
                model.add(apps.filter { it.packageName in selected })
                onBack()
            }, enabled = selected.isNotEmpty()) { Text("Add ${selected.size}") }
        }
        OutlinedTextField(value = search, onValueChange = { search = it },
            label = { Text("Search installed apps") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(apps.filter { search.isBlank() || it.name.contains(search, true) ||
                it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
                val disabled = app.packageName in inLibrary || app.unsupported
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                    if (!disabled) selected = if (app.packageName in selected)
                        selected - app.packageName else selected + app.packageName
                }), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, Modifier.padding(12.dp).size(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            app.packageName in inLibrary -> "Already in library"
                            app.unsupported -> "App opts out of orientation override"
                            else -> app.packageName
                        }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked = app.packageName in selected || app.packageName in inLibrary,
                        enabled = !disabled,
                        onCheckedChange = { selected = if (it) selected + app.packageName
                            else selected - app.packageName })
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val image = remember(packageName, preview) {
        if (preview) null else runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(128, 128).asImageBitmap()
        }.getOrNull()
    }
    if (image != null) Image(BitmapPainter(image), contentDescription = null, modifier = modifier)
    else Box(modifier, contentAlignment = Alignment.Center) { Text("□") }
}
