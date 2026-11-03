package org.jellyfin.mobile.settings

import androidx.annotation.FontRes
import androidx.annotation.StringRes
import org.jellyfin.mobile.R

/**
 * Fonts that can be used to render subtitles in the video player.
 *
 * @property value value stored in the preferences
 * @property labelRes user facing name of the font
 * @property fontRes bundled font resource, or null to use the system font
 */
enum class SubtitleFont(
    val value: String,
    @StringRes val labelRes: Int,
    @FontRes val fontRes: Int?,
) {
    SYSTEM("system", R.string.subtitle_font_system, null),
    ACINEMA_M("acinema_m", R.string.subtitle_font_acinema_m, R.font.acinema_m),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun fromValue(value: String?): SubtitleFont = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}
