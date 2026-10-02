package dev.camilo.st2mode.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.camilo.st2mode.R
import dev.camilo.st2mode.SettingsPreferences
import dev.camilo.st2mode.ThemePreference
import dev.camilo.st2mode.ble.ClientState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ClientState,
    preferences: SettingsPreferences,
    hasPermissions: Boolean,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
    onUpdate: (SettingsPreferences) -> Unit,
) {
    val context = LocalContext.current
    var resetDialog by rememberSaveable { mutableStateOf(false) }
    var licenses by rememberSaveable { mutableStateOf(false) }
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var widgetHelp by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item { SectionTitle(R.string.manage_earbuds) }
            if (state.bonded.isEmpty()) {
                item {
                    ActionRow(stringResource(R.string.no_paired_earbuds),
                        stringResource(R.string.pair_in_bluetooth)) {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                }
            } else {
                item {
                    Column(Modifier.selectableGroup()) {
                        state.bonded.forEach { device ->
                            ChoiceRow(device.name, device.address, state.selectedAddress == device.address) {
                                onSelect(device.address)
                            }
                        }
                    }
                }
            }
            item {
                ActionRow(stringResource(R.string.reset_connection),
                    stringResource(R.string.reset_connection_summary), state.selectedAddress != null) {
                    resetDialog = true
                }
            }
            item { SectionTitle(R.string.connection_settings) }
            item {
                ToggleRow(R.string.connect_on_open, R.string.connect_on_open_summary, preferences.connectOnOpen) {
                    onUpdate(preferences.copy(connectOnOpen = it))
                }
            }
            item { SectionTitle(R.string.widget_settings) }
            item {
                ToggleRow(R.string.widget_refresh, R.string.widget_refresh_summary, preferences.widgetRefresh) {
                    onUpdate(preferences.copy(widgetRefresh = it))
                }
            }
            item { SectionTitle(R.string.appearance) }
            item {
                ActionRow(stringResource(R.string.theme), themeLabel(preferences.theme)) { themeDialog = true }
            }
            item {
                ToggleRow(R.string.wallpaper_colors, R.string.wallpaper_colors_summary, preferences.wallpaperColors) {
                    onUpdate(preferences.copy(wallpaperColors = it))
                }
            }
            item { SectionTitle(R.string.help_about) }
            item {
                ActionRow(stringResource(R.string.bluetooth_access),
                    stringResource(if (hasPermissions) R.string.access_allowed else R.string.access_needed)) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
            }
            item {
                ActionRow(stringResource(R.string.open_bluetooth_settings)) {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            }
            item {
                ActionRow(stringResource(R.string.widget_setup)) { widgetHelp = true }
            }
            item {
                ActionRow(stringResource(R.string.licenses)) { licenses = true }
            }
            item {
                val version = remember {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                }
                ListItem(headlineContent = { Text(stringResource(R.string.version)) },
                    supportingContent = { Text(version) })
            }
        }
    }
    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text(stringResource(R.string.theme)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    ThemePreference.entries.forEach { theme ->
                        ChoiceRow(themeLabel(theme), null, preferences.theme == theme) {
                            onUpdate(preferences.copy(theme = theme))
                            themeDialog = false
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { themeDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (widgetHelp) {
        AlertDialog(
            onDismissRequest = { widgetHelp = false },
            title = { Text(stringResource(R.string.widget_setup)) },
            text = {
                Text(stringResource(R.string.widget_setup_help) + "\n\n" +
                    stringResource(R.string.widget_refresh_help),
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { widgetHelp = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
    if (resetDialog) {
        AlertDialog(onDismissRequest = { resetDialog = false },
            title = { Text(stringResource(R.string.reset_connection)) },
            text = { Text(stringResource(R.string.reset_connection_confirmation)) },
            confirmButton = { TextButton(onClick = { resetDialog = false; onReset() }) {
                Text(stringResource(R.string.reset))
            } },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (licenses) {
        val text = remember {
            listOf("MoonMode-MIT.txt", "Doto-OFL.txt", "adwaita-sans.txt").joinToString("\n\n") { file ->
                context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text(stringResource(R.string.licenses)) },
            text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable
private fun themeLabel(theme: ThemePreference): String = stringResource(when (theme) {
    ThemePreference.System -> R.string.theme_system
    ThemePreference.Light -> R.string.theme_light
    ThemePreference.Dark -> R.string.theme_dark
})

@Composable
private fun SectionTitle(label: Int) {
    Text(stringResource(label),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ActionRow(
    label: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it) } },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
    )
}

@Composable
private fun ChoiceRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ToggleRow(label: Int, help: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(stringResource(help)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}
