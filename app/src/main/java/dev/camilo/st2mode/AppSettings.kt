package dev.camilo.st2mode

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemePreference { System, Light, Dark }

data class SettingsPreferences(
    val connectOnOpen: Boolean = false,
    val widgetRefresh: Boolean = true,
    val theme: ThemePreference = ThemePreference.System,
    val wallpaperColors: Boolean = true,
)

class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SettingsPreferences(
        connectOnOpen = prefs.getBoolean("connect_on_open", false),
        widgetRefresh = prefs.getBoolean("widget_refresh", true),
        theme = ThemePreference.entries.firstOrNull { it.name == prefs.getString("theme", null) }
            ?: ThemePreference.System,
        wallpaperColors = prefs.getBoolean("wallpaper_colors", true),
    ))
    val state = mutable.asStateFlow()

    fun update(value: SettingsPreferences) {
        prefs.edit()
            .putBoolean("connect_on_open", value.connectOnOpen)
            .putBoolean("widget_refresh", value.widgetRefresh)
            .putString("theme", value.theme.name)
            .putBoolean("wallpaper_colors", value.wallpaperColors)
            .apply()
        mutable.value = value
    }
}
