package com.karoogfcompanion.extension

import android.app.PendingIntent
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import com.karoogfcompanion.R
import com.karoogfcompanion.data.GfRideRepository
import com.karoogfcompanion.data.GfSettingsStore
import com.karoogfcompanion.domain.DemoPacingProvider
import com.karoogfcompanion.domain.GfPacingSnapshot
import com.karoogfcompanion.domain.TapActionPreference
import com.karoogfcompanion.domain.ThemePreference
import com.karoogfcompanion.render.GfCompanionRenderer
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

class GfPacingDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    enum class LayoutMode {
        FULL_PAGE,
        HALF_PAGE,
        SINGLE_WIDE,
        SINGLE_COMPACT,
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val layoutMode = resolveLayoutMode(config)
        val (targetW, targetH) = computeRenderDimensions(config.viewSize, layoutMode)
        val settingsStore = GfSettingsStore.getInstance(context)

        if (config.preview) {
            val demoSnap = DemoPacingProvider.buildDemoSnapshot(settings = settingsStore.settings.value)
            emitter.updateView(
                buildRemoteViews(
                    context = context,
                    snapshot = demoSnap,
                    widthPx = targetW,
                    heightPx = targetH,
                    layoutMode = layoutMode,
                    interactive = false,
                    tapAction = TapActionPreference.NONE,
                )
            )
            return
        }

        val interactiveBase = !config.preview && layoutMode == LayoutMode.FULL_PAGE
        val repo = GfRideRepository.getInstance(context)
        val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repo.onViewAttached()

        viewScope.launch {
            repo.snapshot.collect { snap ->
                val tapAction = settingsStore.settings.value.tapActionPreference
                val rv = buildRemoteViews(
                    context = context,
                    snapshot = snap,
                    widthPx = targetW,
                    heightPx = targetH,
                    layoutMode = layoutMode,
                    interactive = interactiveBase && tapAction != TapActionPreference.NONE,
                    tapAction = tapAction,
                )
                emitter.updateView(rv)
            }
        }

        emitter.setCancellable {
            viewScope.cancel()
            repo.onViewDetached()
        }
    }

    private fun buildRemoteViews(
        context: Context,
        snapshot: GfPacingSnapshot,
        widthPx: Int,
        heightPx: Int,
        layoutMode: LayoutMode,
        interactive: Boolean,
        tapAction: TapActionPreference,
    ): RemoteViews {
        return when (snapshot.themePreference) {
            ThemePreference.DARK -> createSingleThemeRemoteViews(
                context, snapshot, widthPx, heightPx, layoutMode, interactive, tapAction, isNight = true
            )
            ThemePreference.LIGHT -> createSingleThemeRemoteViews(
                context, snapshot, widthPx, heightPx, layoutMode, interactive, tapAction, isNight = false
            )
            ThemePreference.AUTO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val rv = RemoteViews(context.packageName, R.layout.remote_gf_pacing)
                    val lightBmp = renderBitmap(snapshot, widthPx, heightPx, layoutMode, isNight = false)
                    val darkBmp = renderBitmap(snapshot, widthPx, heightPx, layoutMode, isNight = true)
                    rv.setIcon(
                        R.id.img_gf_canvas,
                        "setImageIcon",
                        Icon.createWithBitmap(lightBmp),
                        Icon.createWithBitmap(darkBmp),
                    )
                    bindTouchOverlays(context, rv, snapshot, interactive, tapAction)
                    rv
                } else {
                    val sysNight = when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                        Configuration.UI_MODE_NIGHT_YES -> true
                        Configuration.UI_MODE_NIGHT_NO -> false
                        else -> snapshot.isSystemNightMode
                    }
                    createSingleThemeRemoteViews(
                        context, snapshot, widthPx, heightPx, layoutMode, interactive, tapAction, isNight = sysNight
                    )
                }
            }
        }
    }

    private fun renderBitmap(
        snapshot: GfPacingSnapshot,
        widthPx: Int,
        heightPx: Int,
        layoutMode: LayoutMode,
        isNight: Boolean,
    ): Bitmap = when (layoutMode) {
        LayoutMode.FULL_PAGE -> GfCompanionRenderer.renderFullPage(snapshot, widthPx, heightPx, isNight)
        LayoutMode.HALF_PAGE -> GfCompanionRenderer.renderHalfPage(snapshot, widthPx, heightPx, isNight)
        LayoutMode.SINGLE_WIDE -> GfCompanionRenderer.renderSingleField(
            snapshot, widthPx, heightPx, isWideSlot = true, isNightMode = isNight
        )
        LayoutMode.SINGLE_COMPACT -> GfCompanionRenderer.renderSingleField(
            snapshot, widthPx, heightPx, isWideSlot = false, isNightMode = isNight
        )
    }

    private fun createSingleThemeRemoteViews(
        context: Context,
        snapshot: GfPacingSnapshot,
        widthPx: Int,
        heightPx: Int,
        layoutMode: LayoutMode,
        interactive: Boolean,
        tapAction: TapActionPreference,
        isNight: Boolean,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.remote_gf_pacing)
        val bmp = renderBitmap(snapshot, widthPx, heightPx, layoutMode, isNight)
        rv.setImageViewBitmap(R.id.img_gf_canvas, bmp)
        bindTouchOverlays(context, rv, snapshot, interactive, tapAction)
        return rv
    }

    private fun bindTouchOverlays(
        context: Context,
        rv: RemoteViews,
        snapshot: GfPacingSnapshot,
        interactive: Boolean,
        tapAction: TapActionPreference,
    ) {
        if (!interactive) {
            rv.setViewVisibility(R.id.overlay_gauge_touch, View.GONE)
            rv.setViewVisibility(R.id.overlay_segment_list_touch, View.GONE)
            return
        }

        val isOverlayOpen = snapshot.isSegmentOverlayOpen
        rv.setViewVisibility(R.id.overlay_gauge_touch, if (isOverlayOpen) View.GONE else View.VISIBLE)
        rv.setViewVisibility(R.id.overlay_segment_list_touch, if (isOverlayOpen) View.VISIBLE else View.GONE)

        if (isOverlayOpen) {
            val closePi = GfActionReceiver.closeOverlayPendingIntent(context)
            rv.setOnClickPendingIntent(R.id.touch_close_overlay, closePi)
            rv.setOnClickPendingIntent(R.id.touch_overlay_body, closePi)
        } else {
            val nextPi = resolveNextCardPendingIntent(context, snapshot, tapAction)
            rv.setOnClickPendingIntent(R.id.touch_next_card, nextPi)
        }
    }

    private fun resolveNextCardPendingIntent(
        context: Context,
        snapshot: GfPacingSnapshot,
        tapAction: TapActionPreference,
    ): PendingIntent {
        if (tapAction == TapActionPreference.OPEN_CUESHEET_PLUS || tapAction == TapActionPreference.OPEN_CUESHEET_LIST) {
            val openDetail = tapAction == TapActionPreference.OPEN_CUESHEET_PLUS
            val liveIntent = CueSheetPlusIntegration.buildLiveActivityIntent(
                context = context,
                snapshot = snapshot,
                openDetail = openDetail,
            )
            if (liveIntent != null) {
                val reqCode = if (openDetail) REQ_OPEN_CUESHEET_DETAIL else REQ_OPEN_CUESHEET_LIST
                return PendingIntent.getActivity(
                    context,
                    reqCode,
                    liveIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }
        return GfActionReceiver.tapNextCardPendingIntent(context)
    }

    companion object {
        const val TYPE_ID = "gf-pacing-guide"
        private const val REQ_OPEN_CUESHEET_DETAIL = 2001
        private const val REQ_OPEN_CUESHEET_LIST = 2002

        fun resolveLayoutMode(config: ViewConfig): LayoutMode {
            val isFullWidth = config.gridSize.first > 35 ||
                (config.gridSize.first <= 0 && (config.viewSize.first >= 340 || config.viewSize.first <= 50))
            if (!isFullWidth) {
                return LayoutMode.SINGLE_COMPACT
            }

            val isFullHeight = (config.gridSize.second >= 45 || config.gridSize.second <= 0) &&
                (config.viewSize.second >= 480 || config.viewSize.second <= 50)
            if (isFullHeight) {
                return LayoutMode.FULL_PAGE
            }

            val hasEnoughPixelHeightForHalf = config.viewSize.second <= 50 || config.viewSize.second >= 220
            val isHalfHeight = hasEnoughPixelHeightForHalf &&
                ((config.gridSize.second in 20..44) || (config.viewSize.second in 220..479))
            if (isHalfHeight) {
                return LayoutMode.HALF_PAGE
            }

            return LayoutMode.SINGLE_WIDE
        }

        fun computeRenderDimensions(viewSize: Pair<Int, Int>, layoutMode: LayoutMode): Pair<Int, Int> {
            val (defaultW, defaultH) = when (layoutMode) {
                LayoutMode.FULL_PAGE -> 480 to 740
                LayoutMode.HALF_PAGE -> 480 to 350
                LayoutMode.SINGLE_WIDE -> 480 to 155
                LayoutMode.SINGLE_COMPACT -> 238 to 155
            }
            val rawW = if (viewSize.first > 50) viewSize.first else defaultW
            val rawH = if (viewSize.second > 50) viewSize.second else defaultH
            val scale = min(1.0f, min(420f / rawW.toFloat(), 700f / rawH.toFloat()))
            return Pair((rawW * scale).roundToInt().coerceAtLeast(120), (rawH * scale).roundToInt().coerceAtLeast(70))
        }
    }
}
