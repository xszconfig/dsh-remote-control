package com.daniel.dshremote

/** 主题模式本地持久化（仅本机，不跨设备；key = theme_mode）。 */
object ThemePrefs {
    fun load(): ThemeMode = when (platformLoadThemeMode()) {
        "light" -> ThemeMode.Light
        "dark" -> ThemeMode.Dark
        else -> ThemeMode.FollowSystem
    }

    fun save(mode: ThemeMode) {
        platformSaveThemeMode(
            when (mode) {
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
                ThemeMode.FollowSystem -> "system"
            },
        )
    }
}

internal expect fun platformLoadThemeMode(): String?

internal expect fun platformSaveThemeMode(mode: String)
