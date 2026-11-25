package com.karoogfcompanion.data

import android.content.Context
import com.karoogfcompanion.domain.GfCompanionSettings
import com.karoogfcompanion.domain.PowerSmoothingMode
import com.karoogfcompanion.domain.TapActionPreference
import com.karoogfcompanion.domain.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GfSettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<GfCompanionSettings> = _settings.asStateFlow()

    fun update(transform: (GfCompanionSettings) -> GfCompanionSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_POWER_SMOOTHING, next.powerSmoothingMode.name)
            .putInt(KEY_SINGLE_TOLERANCE_W, next.singleTargetToleranceWatts)
            .putInt(KEY_YELLOW_MARGIN_MIN_W, next.yellowMarginMinWatts)
            .putInt(KEY_APPROACHING_DIST_M, next.approachingDistanceMeters)
            .putBoolean(KEY_SEGMENT_ALERT_SOUND, next.segmentAlertSound)
            .putBoolean(KEY_SEGMENT_ALERT_POPUP, next.segmentAlertPopup)
            .putBoolean(KEY_ZONE_COLOR_BACKGROUNDS, next.zoneColorBackgrounds)
            .putString(KEY_THEME_PREF, next.themePreference.name)
            .putString(KEY_TAP_ACTION, next.tapActionPreference.name)
            .putInt(KEY_AUTO_RETURN_SEC, next.autoReturnSeconds)
            .apply()
        _settings.value = next
    }

    private fun loadSettings(): GfCompanionSettings {
        val smoothing = prefs.getString(KEY_POWER_SMOOTHING, PowerSmoothingMode.SMOOTHED_3S.name)
            ?.let { runCatching { PowerSmoothingMode.valueOf(it) }.getOrNull() }
            ?: PowerSmoothingMode.SMOOTHED_3S

        val theme = prefs.getString(KEY_THEME_PREF, ThemePreference.AUTO.name)
            ?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
            ?: ThemePreference.AUTO

        val tapAction = prefs.getString(KEY_TAP_ACTION, TapActionPreference.OPEN_CUESHEET_PLUS.name)
            ?.let { runCatching { TapActionPreference.valueOf(it) }.getOrNull() }
            ?: TapActionPreference.OPEN_CUESHEET_PLUS

        return GfCompanionSettings(
            powerSmoothingMode = smoothing,
            singleTargetToleranceWatts = prefs.getInt(KEY_SINGLE_TOLERANCE_W, 10).coerceIn(5, 25),
            yellowMarginMinWatts = prefs.getInt(KEY_YELLOW_MARGIN_MIN_W, 15).coerceIn(5, 40),
            approachingDistanceMeters = prefs.getInt(KEY_APPROACHING_DIST_M, 500).coerceIn(200, 1500),
            segmentAlertSound = prefs.getBoolean(KEY_SEGMENT_ALERT_SOUND, true),
            segmentAlertPopup = prefs.getBoolean(KEY_SEGMENT_ALERT_POPUP, false),
            zoneColorBackgrounds = prefs.getBoolean(KEY_ZONE_COLOR_BACKGROUNDS, true),
            themePreference = theme,
            tapActionPreference = tapAction,
            autoReturnSeconds = prefs.getInt(KEY_AUTO_RETURN_SEC, 10).coerceIn(6, 30),
        )
    }

    companion object {
        private const val PREFS_NAME = "karoo_gf_companion_prefs"
        private const val KEY_POWER_SMOOTHING = "power_smoothing_mode"
        private const val KEY_SINGLE_TOLERANCE_W = "single_target_tolerance_w"
        private const val KEY_YELLOW_MARGIN_MIN_W = "yellow_margin_min_w"
        private const val KEY_APPROACHING_DIST_M = "approaching_dist_m"
        private const val KEY_SEGMENT_ALERT_SOUND = "segment_alert_sound"
        private const val KEY_SEGMENT_ALERT_POPUP = "segment_alert_popup"
        private const val KEY_ZONE_COLOR_BACKGROUNDS = "zone_color_backgrounds"
        private const val KEY_THEME_PREF = "theme_pref"
        private const val KEY_TAP_ACTION = "tap_action_pref"
        private const val KEY_AUTO_RETURN_SEC = "auto_return_seconds"

        @Volatile
        private var instance: GfSettingsStore? = null

        fun getInstance(context: Context): GfSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: GfSettingsStore(context).also { instance = it }
            }
        }
    }
}
