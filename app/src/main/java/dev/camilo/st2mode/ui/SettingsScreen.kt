package dev.camilo.st2mode.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.camilo.st2mode.R
import dev.camilo.st2mode.SettingsPreferences
import dev.camilo.st2mode.ThemePreference
import dev.camilo.st2mode.ble.ClientState
import dev.camilo.st2mode.ui.common.FloatingScaffold
import dev.camilo.st2mode.ui.common.FloatingScreenHeader
import dev.camilo.st2mode.ui.common.ScreenGutter
import dev.camilo.st2mode.ui.common.SectionLabel
import dev.camilo.st2mode.ui.common.SegmentGap
import dev.camilo.st2mode.ui.common.SegmentGroupGap
import dev.camilo.st2mode.ui.common.SegmentedGroup
import dev.camilo.st2mode.ui.common.SegmentedRow

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
    var widgetHelp by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    FloatingScaffold(
        topBar = { FloatingScreenHeader(stringResource(R.string.settings), stringResource(R.string.back), onBack) },
        topEdgeVisible = { list.canScrollBackward },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(
                start = ScreenGutter, end = ScreenGutter,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + SegmentGroupGap,
            ),
        ) {
            item { SectionLabel(stringResource(R.string.manage_earbuds)) }
            item {
                SegmentedGroup {
                    if (state.bonded.isEmpty()) {
                        ActionRow(0, 2, Icons.Rounded.Bluetooth, stringResource(R.string.no_paired_earbuds),
                            stringResource(R.string.pair_in_bluetooth)) {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    } else {
                        val count = state.bonded.size + 1
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                            state.bonded.forEachIndexed { index, device ->
                                ChoiceRow(index, count, Icons.Rounded.Headphones, device.name, device.address,
                                    state.selectedAddress == device.address) { onSelect(device.address) }
                            }
                        }
                    }
                    ActionRow(if (state.bonded.isEmpty()) 1 else state.bonded.size,
                        if (state.bonded.isEmpty()) 2 else state.bonded.size + 1,
                        Icons.Rounded.LinkOff, stringResource(R.string.reset_connection),
                        stringResource(R.string.reset_connection_summary), state.selectedAddress != null) {
                        resetDialog = true
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.connection_settings)) }
            item {
                SegmentedGroup {
                    ToggleRow(0, 1, Icons.Rounded.PowerSettingsNew, R.string.connect_on_open,
                        R.string.connect_on_open_summary, preferences.connectOnOpen) {
                        onUpdate(preferences.copy(connectOnOpen = it))
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.widget_settings)) }
            item {
                SegmentedGroup {
                    ToggleRow(0, 2, Icons.Rounded.Sync, R.string.widget_refresh,
                        R.string.widget_refresh_summary, preferences.widgetRefresh) {
                        onUpdate(preferences.copy(widgetRefresh = it))
                    }
                    ActionRow(1, 2, Icons.Rounded.Widgets, stringResource(R.string.widget_setup)) { widgetHelp = true }
                }
            }
            item { SectionLabel(stringResource(R.string.appearance)) }
            item {
                SegmentedGroup(Modifier.selectableGroup()) {
                    val themes = ThemePreference.entries
                    themes.forEachIndexed { index, theme ->
                        ChoiceRow(index, themes.size, themeIcon(theme), themeLabel(theme), null,
                            preferences.theme == theme) { onUpdate(preferences.copy(theme = theme)) }
                    }
                }
            }
            item { Spacer(Modifier.height(SegmentGroupGap)) }
            item {
                SegmentedGroup {
                    ToggleRow(0, 1, Icons.Rounded.Palette, R.string.wallpaper_colors,
                        R.string.wallpaper_colors_summary, preferences.wallpaperColors) {
                        onUpdate(preferences.copy(wallpaperColors = it))
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.help_about)) }
            item {
                SegmentedGroup {
                    ActionRow(0, 4, Icons.Rounded.Security, stringResource(R.string.bluetooth_access),
                        stringResource(if (hasPermissions) R.string.access_allowed else R.string.access_needed)) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }
                    ActionRow(1, 4, Icons.Rounded.Bluetooth, stringResource(R.string.open_bluetooth_settings)) {
                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                    ActionRow(2, 4, Icons.Rounded.Description, stringResource(R.string.licenses)) { licenses = true }
                    val version = remember {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                    }
                    SegmentedRow(3, 4, stringResource(R.string.version), icon = Icons.Rounded.Info, supporting = version)
                }
            }
        }
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

private fun themeIcon(theme: ThemePreference): ImageVector = when (theme) {
    ThemePreference.System -> Icons.Rounded.BrightnessAuto
    ThemePreference.Light -> Icons.Rounded.LightMode
    ThemePreference.Dark -> Icons.Rounded.DarkMode
}

@Composable
private fun ActionRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    label: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SegmentedRow(
        index, count, label, icon = icon, supporting = detail, enabled = enabled,
        interaction = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        trailing = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
private fun ChoiceRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SegmentedRow(
        index, count, label, icon = icon, supporting = detail, selected = selected,
        interaction = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        trailing = { RadioButton(selected = selected, onClick = null) },
    )
}

@Composable
private fun ToggleRow(index: Int, count: Int, icon: ImageVector, label: Int, help: Int, checked: Boolean,
    onChange: (Boolean) -> Unit) {
    SegmentedRow(
        index, count, stringResource(label), icon = icon, supporting = stringResource(help),
        interaction = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}
