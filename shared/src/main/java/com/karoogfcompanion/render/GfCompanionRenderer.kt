package com.karoogfcompanion.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.karoogfcompanion.domain.GaugeZone
import com.karoogfcompanion.domain.GfFormatter
import com.karoogfcompanion.domain.GfPacingSnapshot
import com.karoogfcompanion.domain.MetricZoneResolver
import com.karoogfcompanion.domain.PowerDeviationStatus
import com.karoogfcompanion.domain.RideRecordingState
import com.karoogfcompanion.domain.RoutePacingEngine
import com.karoogfcompanion.domain.RouteWaypointPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure Canvas/Bitmap renderer for Karoo GF Companion.
 * Matches the visual concept across Light and Dark themes and adapts cleanly to
 * Full Field, Half Field, Single Wide, and Single Compact field sizes.
 */
object GfCompanionRenderer {

    private data class Palette(
        val bg: Int,
        val cardBg: Int,
        val cardBorder: Int,
        val cardApproachingBg: Int,
        val cardApproachingBorder: Int,
        val divider: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val needleFill: Int,
        val needleOutline: Int,
        val zoneRed: Int,
        val zoneYellow: Int,
        val zoneGreen: Int,
        val zoneNeutral: Int,
        val statusLowBg: Int,
        val statusHighBg: Int,
        val statusBadgeText: Int,
        val accentBlue: Int,
        val accentAmber: Int,
        val iconHr: Int,
        val iconCad: Int,
        val iconPower: Int,
        val pausedBg: Int,
        val activeRowBg: Int,
        val isNight: Boolean,
    )

    private enum class MetricIcon {
        HEART,
        PEDALS,
        LIGHTNING,
    }

    private val LIGHT_PALETTE = Palette(
        bg = 0xFFFFFFFF.toInt(),
        cardBg = 0xFFFAFAFA.toInt(),
        cardBorder = 0xFFD1D5DB.toInt(),
        cardApproachingBg = 0xFFF0F9FF.toInt(),
        cardApproachingBorder = 0xFF0284C7.toInt(),
        divider = 0xFFD1D5DB.toInt(),
        textPrimary = 0xFF0A0B0D.toInt(),
        textSecondary = 0xFF374151.toInt(),
        textMuted = 0xFF6B7280.toInt(),
        needleFill = 0xFF111827.toInt(),
        needleOutline = 0xFFFFFFFF.toInt(),
        zoneRed = 0xFFEF2323.toInt(),
        zoneYellow = 0xFFFACC15.toInt(),
        zoneGreen = 0xFF14C23A.toInt(),
        zoneNeutral = 0xFFE5E7EB.toInt(),
        statusLowBg = 0xFFD97706.toInt(),
        statusHighBg = 0xFFDC2626.toInt(),
        statusBadgeText = 0xFFFFFFFF.toInt(),
        accentBlue = 0xFF0284C7.toInt(),
        accentAmber = 0xFFB45309.toInt(),
        iconHr = 0xFFDC2626.toInt(),
        iconCad = 0xFF0284C7.toInt(),
        iconPower = 0xFFD97706.toInt(),
        pausedBg = 0xFFE5E7EB.toInt(),
        activeRowBg = 0xFFE0F2FE.toInt(),
        isNight = false,
    )

    private val DARK_PALETTE = Palette(
        bg = 0xFF000000.toInt(),
        cardBg = 0xFF0D0F12.toInt(),
        cardBorder = 0xFF4B5563.toInt(),
        cardApproachingBg = 0xFF0B1926.toInt(),
        cardApproachingBorder = 0xFF38BDF8.toInt(),
        divider = 0xFF374151.toInt(),
        textPrimary = 0xFFFFFFFF.toInt(),
        textSecondary = 0xFFD1D5DB.toInt(),
        textMuted = 0xFF9CA3AF.toInt(),
        needleFill = 0xFFFFFFFF.toInt(),
        needleOutline = 0xFF000000.toInt(),
        zoneRed = 0xFFEF2323.toInt(),
        zoneYellow = 0xFFFACC15.toInt(),
        zoneGreen = 0xFF14C23A.toInt(),
        zoneNeutral = 0xFF27272A.toInt(),
        statusLowBg = 0xFFF59E0B.toInt(),
        statusHighBg = 0xFFEF4444.toInt(),
        statusBadgeText = 0xFF000000.toInt(),
        accentBlue = 0xFF38BDF8.toInt(),
        accentAmber = 0xFFF5C518.toInt(),
        iconHr = 0xFFEF4444.toInt(),
        iconCad = 0xFF38BDF8.toInt(),
        iconPower = 0xFFFACC15.toInt(),
        pausedBg = 0xFF1F2937.toInt(),
        activeRowBg = 0xFF0B1926.toInt(),
        isNight = true,
    )

    private val boldSans: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val mediumSans: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val condensedBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    fun renderFullPage(
        snapshot: GfPacingSnapshot,
        widthPx: Int,
        heightPx: Int,
        isNightMode: Boolean,
    ): Bitmap {
        val w = widthPx.coerceIn(200, 1080)
        val h = heightPx.coerceIn(240, 1920)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = if (isNightMode) DARK_PALETTE else LIGHT_PALETTE

        canvas.drawColor(p.bg)

        if (snapshot.isSegmentOverlayOpen) {
            drawSegmentPlanOverlay(canvas, w.toFloat(), h.toFloat(), snapshot, p)
            return bmp
        }

        val wf = w.toFloat()
        val hf = h.toFloat()
        val padX = wf * 0.045f

        // 1. Top Gauge Section (0% .. 51.5% height)
        val gaugeBottom = hf * 0.515f
        drawSemicircleGaugeBlock(
            canvas = canvas,
            bounds = RectF(0f, 0f, wf, gaugeBottom),
            snapshot = snapshot,
            p = p,
            isHalfField = false,
        )

        // 2. Supporting Metrics Row (51.5% .. 73% height)
        val metricsTop = gaugeBottom
        val metricsBottom = hf * 0.73f
        drawSupportingMetricsRow(
            canvas = canvas,
            left = padX,
            top = metricsTop,
            right = wf - padX,
            bottom = metricsBottom,
            snapshot = snapshot,
            p = p,
        )

        // 3. Bottom Next Point Card (75.5% .. 97.5% height)
        val nextCardRect = RectF(padX, hf * 0.755f, wf - padX, hf * 0.975f)
        drawNextPointCard(canvas, nextCardRect, snapshot, p)

        return bmp
    }

    fun renderHalfPage(
        snapshot: GfPacingSnapshot,
        widthPx: Int,
        heightPx: Int,
        isNightMode: Boolean,
    ): Bitmap {
        val w = widthPx.coerceIn(200, 1080)
        val h = heightPx.coerceIn(160, 1920)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = if (isNightMode) DARK_PALETTE else LIGHT_PALETTE

        canvas.drawColor(p.bg)

        drawSemicircleGaugeBlock(
            canvas = canvas,
            bounds = RectF(0f, 0f, w.toFloat(), h.toFloat()),
            snapshot = snapshot,
            p = p,
            isHalfField = true,
        )
        return bmp
    }

    fun renderSingleField(
        snapshot: GfPacingSnapshot,
        widthPx: Int,
        heightPx: Int,
        isWideSlot: Boolean,
        isNightMode: Boolean,
    ): Bitmap {
        val w = widthPx.coerceIn(120, 1080)
        val h = heightPx.coerceIn(70, 600)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = if (isNightMode) DARK_PALETTE else LIGHT_PALETTE

        canvas.drawColor(p.bg)

        if (isWideSlot) {
            drawWideSingleField(canvas, w.toFloat(), h.toFloat(), snapshot, p)
        } else {
            drawCompactSingleField(canvas, w.toFloat(), h.toFloat(), snapshot, p)
        }
        return bmp
    }

    // -------------------------------------------------------------------------
    // SEMICIRCULAR POWER GAUGE BLOCK (Full Field top + Half Field)
    // -------------------------------------------------------------------------

    private fun drawSemicircleGaugeBlock(
        canvas: Canvas,
        bounds: RectF,
        snapshot: GfPacingSnapshot,
        p: Palette,
        isHalfField: Boolean,
    ) {
        val w = bounds.width()
        val h = bounds.height()
        val padX = w * 0.055f

        val isPaused = snapshot.rideState == RideRecordingState.PAUSED
        val hasTarget = snapshot.currentTarget != null

        // Gauge arc geometry: keep centered semicircular arc with generous stroke and space underneath for 198W + range
        val maxDiameterByW = w - padX * 2f
        val topPad = h * (if (isHalfField) 0.075f else 0.065f)
        val arcBottomY = bounds.top + h * (if (isHalfField) 0.65f else 0.66f)
        val maxRadiusByH = (arcBottomY - (bounds.top + topPad)).coerceAtLeast(30f)
        val outerRadius = min(maxDiameterByW * 0.5f, maxRadiusByH)
        val strokeW = (outerRadius * 0.21f).coerceIn(14f, 44f)
        val midRadius = outerRadius - strokeW * 0.5f
        val cx = bounds.centerX()
        val cy = arcBottomY

        val arcOval = RectF(
            cx - midRadius,
            cy - midRadius,
            cx + midRadius,
            cy + midRadius,
        )

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            strokeCap = Paint.Cap.BUTT
            alpha = if (isPaused) 150 else 255
        }

        if (hasTarget) {
            // 5 zones from 180° (left) to 360° (right)
            drawArcZone(canvas, arcOval, 180f, 36f, p.zoneRed, arcPaint)
            drawArcZone(canvas, arcOval, 216f, 32f, p.zoneYellow, arcPaint)
            drawArcZone(canvas, arcOval, 248f, 44f, p.zoneGreen, arcPaint)
            drawArcZone(canvas, arcOval, 292f, 32f, p.zoneYellow, arcPaint)
            drawArcZone(canvas, arcOval, 324f, 36f, p.zoneRed, arcPaint)
        } else {
            drawArcZone(canvas, arcOval, 180f, 180f, p.zoneNeutral, arcPaint)
        }

        // Tapered needle pointer
        val angleDeg = snapshot.needleAngleDeg
        if (hasTarget && angleDeg != null) {
            drawGaugeNeedle(
                canvas = canvas,
                cx = cx,
                cy = cy,
                innerRadius = midRadius - strokeW * 0.62f,
                outerRadius = midRadius + strokeW * 0.72f,
                angleDeg = angleDeg,
                isPaused = isPaused,
                p = p,
            )
        }

        // Optional status corner badges (PAUSED / OFF ROUTE / DEMO) in top corners
        val badgeTextSize = (h * 0.056f).coerceIn(11f, 18f)
        if (isPaused) {
            drawCornerPill(
                canvas = canvas,
                text = snapshot.strings.pausedBadge,
                left = padX * 0.7f,
                top = bounds.top + h * 0.03f,
                bgColor = p.pausedBg,
                textColor = p.textPrimary,
                textSize = badgeTextSize,
                alignRight = false,
            )
        } else if (snapshot.isDemoMode) {
            drawCornerPill(
                canvas = canvas,
                text = snapshot.strings.demoBadge,
                left = padX * 0.7f,
                top = bounds.top + h * 0.03f,
                bgColor = p.pausedBg,
                textColor = p.textMuted,
                textSize = badgeTextSize * 0.9f,
                alignRight = false,
            )
        }

        if (snapshot.isOffRoute) {
            drawCornerPill(
                canvas = canvas,
                text = snapshot.strings.offRouteBadge,
                left = w - padX * 0.7f,
                top = bounds.top + h * 0.03f,
                bgColor = p.statusHighBg,
                textColor = p.statusBadgeText,
                textSize = badgeTextSize,
                alignRight = true,
            )
        }

        // Giant Current Power ("198" + "W") centered cleanly below the inner arch
        val powerText = if (snapshot.isPowerAvailable && snapshot.currentPowerWatts != null) {
            snapshot.currentPowerWatts.toString()
        } else {
            "—"
        }
        val powerBaselineY = cy + h * (if (isHalfField) 0.045f else 0.05f)
        val powerSize = (h * (if (isHalfField) 0.31f else 0.305f)).coerceIn(40f, 130f)
        val unitSize = powerSize * 0.41f

        val powerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = powerSize
            color = if (snapshot.isPowerAvailable) p.textPrimary else p.textMuted
            if (isPaused) alpha = 190
        }
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = unitSize
            color = if (snapshot.isPowerAvailable) p.textPrimary else p.textMuted
            if (isPaused) alpha = 190
        }
        val unitStr = " ${snapshot.strings.unitWatts}"
        val numW = powerPaint.measureText(powerText)
        val unitW = unitPaint.measureText(unitStr)
        val startX = cx - (numW + unitW) * 0.5f
        canvas.drawText(powerText, startX, powerBaselineY, powerPaint)
        canvas.drawText(unitStr, startX + numW, powerBaselineY, unitPaint)

        // Target Range + Deviation Status Row beneath Current Power
        val targetCenterY = bounds.top + h * (if (isHalfField) 0.855f else 0.865f)
        drawTargetAndStatusRow(
            canvas = canvas,
            cx = cx,
            centerY = targetCenterY,
            maxWidth = w - padX * 1.4f,
            rowHeight = h * 0.145f,
            snapshot = snapshot,
            p = p,
        )
    }

    private fun drawArcZone(
        canvas: Canvas,
        oval: RectF,
        startDeg: Float,
        sweepDeg: Float,
        color: Int,
        paint: Paint,
    ) {
        paint.color = color
        canvas.drawArc(oval, startDeg, sweepDeg + 0.4f, false, paint)
    }

    private fun drawGaugeNeedle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        innerRadius: Float,
        outerRadius: Float,
        angleDeg: Float,
        isPaused: Boolean,
        p: Palette,
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val perpX = -sinA
        val perpY = cosA

        val innerX = cx + innerRadius * cosA
        val innerY = cy + innerRadius * sinA
        val outerX = cx + outerRadius * cosA
        val outerY = cy + outerRadius * sinA

        val halfBaseInner = max(2.2f, (outerRadius - innerRadius) * 0.045f)
        val halfBaseOuter = max(4.2f, (outerRadius - innerRadius) * 0.095f)

        val path = Path().apply {
            moveTo(innerX - perpX * halfBaseInner, innerY - perpY * halfBaseInner)
            lineTo(outerX - perpX * halfBaseOuter, outerY - perpY * halfBaseOuter)
            lineTo(outerX + perpX * halfBaseOuter, outerY + perpY * halfBaseOuter)
            lineTo(innerX + perpX * halfBaseInner, innerY + perpY * halfBaseInner)
            close()
        }

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeJoin = Paint.Join.ROUND
            color = p.needleOutline
            alpha = if (isPaused) 170 else 235
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.needleFill
            alpha = if (isPaused) 180 else 255
        }
        canvas.drawPath(path, outlinePaint)
        canvas.drawPath(path, fillPaint)
    }

    private fun drawTargetAndStatusRow(
        canvas: Canvas,
        cx: Float,
        centerY: Float,
        maxWidth: Float,
        rowHeight: Float,
        snapshot: GfPacingSnapshot,
        p: Palette,
    ) {
        val s = snapshot.strings
        val target = snapshot.currentTarget
        val textSize = (rowHeight * 0.62f).coerceIn(16f, 34f)
        val targetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            this.textSize = textSize
            color = when {
                target == null -> p.textMuted
                snapshot.isRecentSegmentChange -> p.accentBlue
                else -> p.textPrimary
            }
        }

        val targetText = target?.formatDisplay() ?: s.noTargetHere
        val badgeInfo: Pair<String, Int>? = when (snapshot.deviationStatus) {
            PowerDeviationStatus.LOW -> "▼ ${s.statusLow}" to p.statusLowBg
            PowerDeviationStatus.HIGH -> "▲ ${s.statusHigh}" to p.statusHighBg
            PowerDeviationStatus.NO_POWER -> s.noPowerSensor to p.pausedBg
            PowerDeviationStatus.WITHIN_TARGET -> if (snapshot.isRecentSegmentChange) s.newTargetBadge to p.accentBlue else null
            PowerDeviationStatus.NO_TARGET -> null
        }

        if (badgeInfo == null) {
            val clipped = TextUtils.ellipsize(targetText, targetPaint, maxWidth, TextUtils.TruncateAt.END).toString()
            val w = targetPaint.measureText(clipped)
            val baseline = centerY - (targetPaint.descent() + targetPaint.ascent()) * 0.5f
            canvas.drawText(clipped, cx - w * 0.5f, baseline, targetPaint)
            return
        }

        val (badgeText, badgeBg) = badgeInfo
        val badgeTextColor = if (snapshot.deviationStatus == PowerDeviationStatus.NO_POWER) p.textPrimary else p.statusBadgeText
        val badgeTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            this.textSize = (textSize * 0.72f).coerceIn(12f, 22f)
            color = badgeTextColor
        }
        val badgePadX = textSize * 0.42f
        val badgeH = badgeTextPaint.textSize * 1.48f
        val badgeW = badgeTextPaint.measureText(badgeText) + badgePadX * 2f
        val gap = textSize * 0.42f

        val availTargetW = (maxWidth - badgeW - gap).coerceAtLeast(40f)
        val clippedTarget = TextUtils.ellipsize(targetText, targetPaint, availTargetW, TextUtils.TruncateAt.END).toString()
        val targetW = targetPaint.measureText(clippedTarget)

        val totalW = targetW + gap + badgeW
        val startX = cx - totalW * 0.5f
        val targetBaseline = centerY - (targetPaint.descent() + targetPaint.ascent()) * 0.5f

        val badgeFirst = snapshot.deviationStatus == PowerDeviationStatus.LOW
        val targetX = if (badgeFirst) startX + badgeW + gap else startX
        val badgeLeft = if (badgeFirst) startX else startX + targetW + gap

        canvas.drawText(clippedTarget, targetX, targetBaseline, targetPaint)

        val pillRect = RectF(badgeLeft, centerY - badgeH * 0.5f, badgeLeft + badgeW, centerY + badgeH * 0.5f)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = badgeBg
        }
        canvas.drawRoundRect(pillRect, badgeH * 0.32f, badgeH * 0.32f, pillPaint)
        val badgeBase = pillRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) * 0.5f
        canvas.drawText(badgeText, pillRect.left + badgePadX, badgeBase, badgeTextPaint)
    }

    // -------------------------------------------------------------------------
    // SUPPORTING METRICS ROW (HR / CAD / LAP AVG)
    // -------------------------------------------------------------------------

    private fun drawSupportingMetricsRow(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        snapshot: GfPacingSnapshot,
        p: Palette,
    ) {
        val rowW = right - left
        val rowH = bottom - top
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, rowH * 0.012f)
            color = p.divider
        }

        // Top and bottom horizontal dividers matching concept
        canvas.drawLine(left, top, right, top, linePaint)
        canvas.drawLine(left, bottom, right, bottom, linePaint)

        val colW = rowW / 3f
        val divPad = rowH * 0.13f

        val hrBg = if (snapshot.zoneColorBackgrounds) {
            MetricZoneResolver.resolveHrOrPowerZoneStyle(
                value = snapshot.heartRateBpm,
                isAvailable = snapshot.isHeartRateAvailable,
                zones = snapshot.heartRateZones,
            )
        } else {
            null
        }
        val cadBg = if (snapshot.zoneColorBackgrounds) {
            MetricZoneResolver.resolveCadenceStyle(
                cadenceRpm = snapshot.cadenceRpm,
                isAvailable = snapshot.isCadenceAvailable,
            )
        } else {
            null
        }
        val stepBg = if (snapshot.zoneColorBackgrounds) {
            MetricZoneResolver.resolveHrOrPowerZoneStyle(
                value = snapshot.lapAvgPowerWatts,
                isAvailable = snapshot.isLapAvgPowerAvailable,
                zones = snapshot.powerZones,
            )
        } else {
            null
        }

        if (hrBg == null && cadBg == null) {
            canvas.drawLine(left + colW, top + divPad, left + colW, bottom - divPad, linePaint)
        }
        if (cadBg == null && stepBg == null) {
            canvas.drawLine(left + colW * 2f, top + divPad, left + colW * 2f, bottom - divPad, linePaint)
        }

        val s = snapshot.strings
        val hrVal = if (snapshot.isHeartRateAvailable && snapshot.heartRateBpm != null) {
            snapshot.heartRateBpm.toString()
        } else {
            "—"
        }
        val cadVal = if (snapshot.isCadenceAvailable && snapshot.cadenceRpm != null) {
            snapshot.cadenceRpm.toString()
        } else {
            "—"
        }
        val lapVal = if (snapshot.isLapAvgPowerAvailable && snapshot.lapAvgPowerWatts != null) {
            snapshot.lapAvgPowerWatts.toString()
        } else {
            "—"
        }

        // Compute a shared value font size across all 3 columns so 2-digit and 3-digit
        // numbers share the exact same height and baseline (like native Karoo fields).
        val maxValW = colW * 0.88f
        val baseValSize = (rowH * 0.56f).coerceIn(30f, 84f)
        val probePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensedBold
            textSize = baseValSize
        }
        val widestSample = max(
            probePaint.measureText("888"),
            max(
                probePaint.measureText(hrVal),
                max(probePaint.measureText(cadVal), probePaint.measureText(lapVal)),
            ),
        )
        val sharedValSize = if (widestSample > maxValW && widestSample > 0f) {
            baseValSize * (maxValW / widestSample)
        } else {
            baseValSize
        }

        drawMetricColumn(
            canvas, left, top, colW, rowH,
            label = s.hrLabel,
            value = hrVal,
            valueTextSize = sharedValSize,
            icon = MetricIcon.HEART,
            iconColor = p.iconHr,
            zoneBg = hrBg,
            isAvailable = snapshot.isHeartRateAvailable,
            p = p,
        )
        drawMetricColumn(
            canvas, left + colW, top, colW, rowH,
            label = s.cadLabel,
            value = cadVal,
            valueTextSize = sharedValSize,
            icon = MetricIcon.PEDALS,
            iconColor = p.iconCad,
            zoneBg = cadBg,
            isAvailable = snapshot.isCadenceAvailable,
            p = p,
        )
        drawMetricColumn(
            canvas, left + colW * 2f, top, colW, rowH,
            label = s.lapAvgLabel,
            value = lapVal,
            valueTextSize = sharedValSize,
            icon = MetricIcon.LIGHTNING,
            iconColor = p.iconPower,
            zoneBg = stepBg,
            isAvailable = snapshot.isLapAvgPowerAvailable,
            p = p,
        )
    }

    private fun drawMetricColumn(
        canvas: Canvas,
        colLeft: Float,
        top: Float,
        colW: Float,
        rowH: Float,
        label: String,
        value: String,
        valueTextSize: Float,
        icon: MetricIcon,
        iconColor: Int,
        zoneBg: MetricZoneResolver.ZoneVisualStyle?,
        isAvailable: Boolean,
        p: Palette,
    ) {
        val cx = colLeft + colW * 0.5f

        if (zoneBg != null) {
            val insetX = colW * 0.032f
            val insetY = rowH * 0.065f
            val tileRect = RectF(
                colLeft + insetX,
                top + insetY,
                colLeft + colW - insetX,
                top + rowH - insetY,
            )
            val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = zoneBg.bgColor
            }
            val radius = rowH * 0.11f
            canvas.drawRoundRect(tileRect, radius, radius, tilePaint)
        }

        val maxHeaderW = colW * (if (zoneBg != null) 0.86f else 0.92f)
        val resolvedLabelColor = when {
            zoneBg != null -> if (zoneBg.useDarkContent) 0xFF111827.toInt() else 0xFFFFFFFF.toInt()
            else -> p.textMuted
        }
        val resolvedIconColor = when {
            zoneBg != null -> if (zoneBg.useDarkContent) 0xFF111827.toInt() else 0xFFFFFFFF.toInt()
            else -> iconColor
        }
        val resolvedValColor = when {
            zoneBg != null -> if (zoneBg.useDarkContent) 0xFF0A0B0D.toInt() else 0xFFFFFFFF.toInt()
            isAvailable -> p.textPrimary
            else -> p.textMuted
        }

        // Header: colored icon (or contrast-matched icon on zone tile) + metric title centered horizontally
        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (rowH * 0.14f).coerceIn(11f, 21f)
            color = resolvedLabelColor
            textAlign = Paint.Align.LEFT
        }
        var iconSize = labelPaint.textSize * 1.08f
        var iconGap = (iconSize * 0.26f).coerceIn(3f, 6f)
        val rawLabelW = labelPaint.measureText(label)
        val neededHeaderW = iconSize + iconGap + rawLabelW
        if (neededHeaderW > maxHeaderW && neededHeaderW > 0f) {
            val shrink = (maxHeaderW / neededHeaderW).coerceIn(0.72f, 1.0f)
            labelPaint.textSize *= shrink
            iconSize = labelPaint.textSize * 1.08f
            iconGap = (iconSize * 0.24f).coerceIn(2.5f, 5f)
        }
        val availLabelW = (maxHeaderW - iconSize - iconGap).coerceAtLeast(24f)
        val clippedLabel = TextUtils.ellipsize(label, labelPaint, availLabelW, TextUtils.TruncateAt.END).toString()
        val finalLabelW = labelPaint.measureText(clippedLabel)
        val totalHeaderW = iconSize + iconGap + finalLabelW
        val headerStartX = cx - totalHeaderW * 0.5f
        val headerCenterY = top + rowH * 0.215f

        drawMetricIcon(
            canvas = canvas,
            cx = headerStartX + iconSize * 0.5f,
            cy = headerCenterY,
            size = iconSize,
            icon = icon,
            color = resolvedIconColor,
        )
        val labelBaseline = headerCenterY - (labelPaint.descent() + labelPaint.ascent()) * 0.5f
        canvas.drawText(clippedLabel, headerStartX + iconSize + iconGap, labelBaseline, labelPaint)

        // Large Karoo-style unitless value filling the freed vertical space
        val valPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensedBold
            textSize = valueTextSize
            color = resolvedValColor
            textAlign = Paint.Align.CENTER
        }
        val valCenterY = top + rowH * 0.625f
        val capHeight = -valPaint.ascent() * 0.76f
        val valBaseline = valCenterY + capHeight * 0.5f
        canvas.drawText(value, cx, valBaseline, valPaint)
    }

    private fun drawMetricIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        icon: MetricIcon,
        color: Int,
    ) {
        val l = cx - size * 0.5f
        val t = cy - size * 0.5f
        val s = size

        when (icon) {
            MetricIcon.HEART -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = color
                }
                val path = Path().apply {
                    moveTo(cx, t + s * 0.90f)
                    cubicTo(
                        l + s * 0.06f, t + s * 0.62f,
                        l + s * 0.02f, t + s * 0.24f,
                        l + s * 0.27f, t + s * 0.14f,
                    )
                    cubicTo(
                        l + s * 0.40f, t + s * 0.09f,
                        cx - s * 0.02f, t + s * 0.19f,
                        cx, t + s * 0.30f,
                    )
                    cubicTo(
                        cx + s * 0.02f, t + s * 0.19f,
                        l + s * 0.60f, t + s * 0.09f,
                        l + s * 0.73f, t + s * 0.14f,
                    )
                    cubicTo(
                        l + s * 0.98f, t + s * 0.24f,
                        l + s * 0.94f, t + s * 0.62f,
                        cx, t + s * 0.90f,
                    )
                    close()
                }
                canvas.drawPath(path, paint)
            }

            MetricIcon.PEDALS -> {
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(1.5f, s * 0.11f)
                    strokeCap = Paint.Cap.ROUND
                    this.color = color
                }
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = color
                }
                val ringR = s * 0.22f
                val topPedalX = l + s * 0.26f
                val topPedalY = t + s * 0.17f
                val botPedalX = l + s * 0.74f
                val botPedalY = t + s * 0.83f

                // Open chainring circle at center
                canvas.drawCircle(cx, cy, ringR, strokePaint)

                // Crank arms extending from the chainring perimeter to each pedal
                val dx = botPedalX - cx
                val dy = botPedalY - cy
                val len = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                val ux = dx / len
                val uy = dy / len
                canvas.drawLine(cx - ux * ringR, cy - uy * ringR, topPedalX, topPedalY, strokePaint)
                canvas.drawLine(cx + ux * ringR, cy + uy * ringR, botPedalX, botPedalY, strokePaint)

                // Crisp horizontal pedal platforms at top-left and bottom-right
                val pedalHalfW = s * 0.23f
                val pedalHalfH = max(1.1f, s * 0.085f)
                val pedalR = pedalHalfH * 0.45f
                canvas.drawRoundRect(
                    RectF(
                        topPedalX - pedalHalfW,
                        topPedalY - pedalHalfH,
                        topPedalX + pedalHalfW,
                        topPedalY + pedalHalfH,
                    ),
                    pedalR,
                    pedalR,
                    fillPaint,
                )
                canvas.drawRoundRect(
                    RectF(
                        botPedalX - pedalHalfW,
                        botPedalY - pedalHalfH,
                        botPedalX + pedalHalfW,
                        botPedalY + pedalHalfH,
                    ),
                    pedalR,
                    pedalR,
                    fillPaint,
                )
            }

            MetricIcon.LIGHTNING -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    this.color = color
                }
                val path = Path().apply {
                    moveTo(l + s * 0.58f, t + s * 0.04f)
                    lineTo(l + s * 0.18f, t + s * 0.54f)
                    lineTo(l + s * 0.47f, t + s * 0.54f)
                    lineTo(l + s * 0.38f, t + s * 0.96f)
                    lineTo(l + s * 0.82f, t + s * 0.44f)
                    lineTo(l + s * 0.53f, t + s * 0.44f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    // -------------------------------------------------------------------------
    // BOTTOM NEXT POINT CARD
    // -------------------------------------------------------------------------

    private fun drawNextPointCard(
        canvas: Canvas,
        rect: RectF,
        snapshot: GfPacingSnapshot,
        p: Palette,
    ) {
        val cardH = rect.height()
        val radius = cardH * 0.14f
        val padX = rect.width() * 0.045f
        val approaching = snapshot.isNextPointApproaching && snapshot.nextPoint != null

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (approaching) p.cardApproachingBg else p.cardBg
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (approaching) max(3f, cardH * 0.024f) else max(1.5f, cardH * 0.012f)
            color = if (approaching) p.cardApproachingBorder else p.cardBorder
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val s = snapshot.strings
        val contentLeft = rect.left + padX
        val contentMaxW = rect.width() - padX * 2f

        val next = snapshot.nextPoint
        if (!snapshot.hasActiveRoute || next == null) {
            val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = (cardH * 0.145f).coerceIn(12f, 20f)
                color = p.textMuted
            }
            canvas.drawText(s.nextLabel, contentLeft, rect.top + cardH * 0.27f, headerPaint)

            val emptyMsg = when {
                !snapshot.hasActiveRoute -> s.noActiveRoute
                snapshot.allPoints.isEmpty() -> s.noNextPoint
                else -> s.routePointsComplete
            }
            val msgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = (cardH * 0.22f).coerceIn(15f, 30f)
                color = p.textSecondary
            }
            val clipped = TextUtils.ellipsize(emptyMsg, msgPaint, contentMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clipped, contentLeft, rect.top + cardH * 0.62f, msgPaint)
            return
        }

        // Line 1: NEXT (or NEXT • APPROACHING) + optional KCS+ indicator on right
        val headerY = rect.top + cardH * 0.27f
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (cardH * 0.145f).coerceIn(12f, 20f)
            color = if (approaching) p.accentBlue else p.textMuted
        }
        val headerText = if (approaching) "${s.nextLabel} • ${s.approachingTag}" else s.nextLabel
        val kcsHint = if (snapshot.isCueSheetPlusInstalled) {
            s.nextCardKcsHint(snapshot.tapActionPreference)
        } else {
            null
        }
        if (kcsHint != null) {
            val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = (cardH * 0.135f).coerceIn(11f, 18f)
                color = p.accentAmber
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(kcsHint, rect.right - padX, headerY, hintPaint)
            val hintW = hintPaint.measureText(kcsHint)
            val maxHeaderW = (contentMaxW - hintW - padX * 0.4f).coerceAtLeast(30f)
            val clippedHeader = TextUtils.ellipsize(headerText, headerPaint, maxHeaderW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedHeader, contentLeft, headerY, headerPaint)
        } else {
            val clippedHeader = TextUtils.ellipsize(headerText, headerPaint, contentMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedHeader, contentLeft, headerY, headerPaint)
        }

        // Line 2: Point Clean Name
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (cardH * 0.265f).coerceIn(18f, 36f)
            color = p.textPrimary
        }
        if (namePaint.measureText(next.cleanName) > contentMaxW) {
            namePaint.textSize *= 0.84f
        }
        val clippedName = TextUtils.ellipsize(next.cleanName, namePaint, contentMaxW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedName, contentLeft, rect.top + cardH * 0.565f, namePaint)

        // Line 3: Distance Remaining (+ "| Target Range" ONLY when next point defines a valid target)
        val distStr = GfFormatter.formatDistance(next.remainingDistanceM, snapshot.isMetricDistance).combined
        val metricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (cardH * 0.205f).coerceIn(15f, 28f)
            color = if (approaching) p.accentBlue else p.textPrimary
        }
        val distW = metricPaint.measureText(distStr)
        val line3Y = rect.top + cardH * 0.855f
        canvas.drawText(distStr, contentLeft, line3Y, metricPaint)

        val nextTarget = next.targetRange
        if (nextTarget != null) {
            val gap = cardH * 0.11f
            val sepX = contentLeft + distW + gap
            val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1.8f, cardH * 0.014f)
                color = p.divider
            }
            val sepTop = line3Y - metricPaint.textSize * 0.85f
            val sepBot = line3Y + metricPaint.textSize * 0.14f
            canvas.drawLine(sepX, sepTop, sepX, sepBot, sepPaint)

            val targetX = sepX + gap
            val availTargetW = (rect.right - padX - targetX).coerceAtLeast(30f)
            val targetStr = nextTarget.formatDisplay()
            val targetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = metricPaint.textSize
                color = p.textPrimary
            }
            if (targetPaint.measureText(targetStr) > availTargetW) {
                targetPaint.textSize *= 0.85f
            }
            val clippedTarget = TextUtils.ellipsize(targetStr, targetPaint, availTargetW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedTarget, targetX, line3Y, targetPaint)
        }
    }

    // -------------------------------------------------------------------------
    // IN-FIELD SEGMENT PLAN OVERLAY (Tap NEXT card in FULL_PAGE)
    // -------------------------------------------------------------------------

    private fun drawSegmentPlanOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        snapshot: GfPacingSnapshot,
        p: Palette,
    ) {
        val padX = w * 0.045f
        val headerH = h * 0.11f
        val s = snapshot.strings

        // Header Bar with Back button pill
        val backH = headerH * 0.68f
        val backTop = (headerH - backH) * 0.5f
        val backLabel = s.backToGaugeFormat(snapshot.overlayRemainingSeconds.coerceAtLeast(1))
        val backPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (backH * 0.44f).coerceIn(12f, 20f)
            color = p.textPrimary
        }
        val backPad = backH * 0.45f
        val backW = backPaint.measureText(backLabel) + backPad * 2f
        val backRect = RectF(padX, backTop, padX + backW, backTop + backH)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = p.cardBg
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = p.cardBorder
        }
        canvas.drawRoundRect(backRect, backH * 0.5f, backH * 0.5f, fillPaint)
        canvas.drawRoundRect(backRect, backH * 0.5f, backH * 0.5f, borderPaint)
        val backBase = backRect.centerY() - (backPaint.descent() + backPaint.ascent()) * 0.5f
        canvas.drawText(backLabel, backRect.left + backPad, backBase, backPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = condensedBold
            textSize = (headerH * 0.36f).coerceIn(13f, 24f)
            color = p.textMuted
            textAlign = Paint.Align.RIGHT
        }
        val titleBase = headerH * 0.5f - (titlePaint.descent() + titlePaint.ascent()) * 0.5f
        canvas.drawText(s.segmentListHeader, w - padX, titleBase, titlePaint)

        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = p.divider
        }
        canvas.drawLine(0f, headerH, w, headerH, divPaint)

        val relevant = buildOverlayPointWindow(snapshot)
        if (relevant.isEmpty()) {
            val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = (h * 0.04f).coerceIn(14f, 26f)
                color = p.textSecondary
                textAlign = Paint.Align.CENTER
            }
            val msg = if (!snapshot.hasActiveRoute) s.noActiveRoute else s.noPowerTargetsInRoute
            canvas.drawText(msg, w * 0.5f, h * 0.5f, emptyPaint)
            return
        }

        val maxRows = 6
        val rowH = (h - headerH) / maxRows.toFloat()
        relevant.take(maxRows).forEachIndexed { idx, pt ->
            val rowTop = headerH + idx * rowH
            val rowBot = rowTop + rowH
            drawOverlayRow(canvas, w, rowTop, rowBot, padX, pt, snapshot, p, divPaint)
        }
    }

    private fun buildOverlayPointWindow(snapshot: GfPacingSnapshot): List<RouteWaypointPoint> {
        val all = snapshot.allPoints
        if (all.isEmpty()) return emptyList()
        val activeIdx = all.indexOfFirst { it.isActiveSegmentStart }
        val nextIdx = all.indexOfFirst { it.isNextPoint }
        val startIdx = when {
            activeIdx >= 0 -> activeIdx
            nextIdx >= 0 -> nextIdx
            else -> (all.size - 6).coerceAtLeast(0)
        }
        return all.drop(startIdx)
    }

    private fun drawOverlayRow(
        canvas: Canvas,
        w: Float,
        top: Float,
        bottom: Float,
        padX: Float,
        point: RouteWaypointPoint,
        snapshot: GfPacingSnapshot,
        p: Palette,
        divPaint: Paint,
    ) {
        val rowH = bottom - top
        if (point.isActiveSegmentStart || point.isNextPoint) {
            val inset = rowH * 0.06f
            val bgRect = RectF(padX * 0.45f, top + inset, w - padX * 0.45f, bottom - inset)
            val r = rowH * 0.14f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = p.activeRowBg
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = if (point.isNextPoint) 2.5f else 1.5f
                color = if (point.isNextPoint) p.accentBlue else p.cardBorder
            }
            canvas.drawRoundRect(bgRect, r, r, fill)
            canvas.drawRoundRect(bgRect, r, r, stroke)
        }

        val rightEdge = w - padX
        val distStr = if (point.isActiveSegmentStart && point.remainingDistanceM <= 15.0) {
            "NOW"
        } else {
            GfFormatter.formatDistance(point.remainingDistanceM, snapshot.isMetricDistance).combined
        }
        val distPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (rowH * 0.28f).coerceIn(13f, 24f)
            color = if (point.isNextPoint) p.accentBlue else p.textPrimary
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(distStr, rightEdge, top + rowH * 0.44f, distPaint)

        val targetStr = point.targetRange?.formatDisplay() ?: "—"
        val targetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (rowH * 0.25f).coerceIn(12f, 22f)
            color = if (point.targetRange != null) p.textSecondary else p.textMuted
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(targetStr, rightEdge, top + rowH * 0.78f, targetPaint)

        val rightBlockW = max(distPaint.measureText(distStr), targetPaint.measureText(targetStr)) + padX * 0.6f
        val availNameW = (rightEdge - rightBlockW - padX).coerceAtLeast(60f)
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (rowH * 0.30f).coerceIn(14f, 26f)
            color = p.textPrimary
        }
        if (namePaint.measureText(point.cleanName) > availNameW) {
            namePaint.textSize *= 0.85f
        }
        val clippedName = TextUtils.ellipsize(point.cleanName, namePaint, availNameW, TextUtils.TruncateAt.END).toString()
        val nameBaseline = top + rowH * 0.5f - (namePaint.descent() + namePaint.ascent()) * 0.5f
        canvas.drawText(clippedName, padX, nameBaseline, namePaint)

        if (!point.isActiveSegmentStart && !point.isNextPoint) {
            canvas.drawLine(padX, bottom, w - padX, bottom, divPaint)
        }
    }

    // -------------------------------------------------------------------------
    // COMPACT & WIDE SINGLE-ROW ADAPTIVE LAYOUTS
    // -------------------------------------------------------------------------

    private fun drawCompactSingleField(
        canvas: Canvas,
        w: Float,
        h: Float,
        snapshot: GfPacingSnapshot,
        p: Palette,
    ) {
        val padX = w * 0.06f
        val barTop = h * 0.08f
        val barH = max(8f, h * 0.095f)
        drawHorizontalGaugeBar(
            canvas = canvas,
            left = padX,
            top = barTop,
            right = w - padX,
            bottom = barTop + barH,
            snapshot = snapshot,
            p = p,
        )

        val powerText = if (snapshot.isPowerAvailable && snapshot.currentPowerWatts != null) {
            snapshot.currentPowerWatts.toString()
        } else {
            "—"
        }
        val powerSize = (h * 0.38f).coerceIn(24f, 64f)
        val unitSize = powerSize * 0.44f
        val powerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = powerSize
            color = if (snapshot.isPowerAvailable) p.textPrimary else p.textMuted
        }
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = unitSize
            color = if (snapshot.isPowerAvailable) p.textPrimary else p.textMuted
        }
        val unitStr = " ${snapshot.strings.unitWatts}"
        val totalW = powerPaint.measureText(powerText) + unitPaint.measureText(unitStr)
        val startX = (w - totalW) * 0.5f
        val powerY = h * 0.62f
        canvas.drawText(powerText, startX, powerY, powerPaint)
        canvas.drawText(unitStr, startX + powerPaint.measureText(powerText), powerY, unitPaint)

        drawTargetAndStatusRow(
            canvas = canvas,
            cx = w * 0.5f,
            centerY = h * 0.84f,
            maxWidth = w - padX * 1.4f,
            rowHeight = h * 0.22f,
            snapshot = snapshot,
            p = p,
        )
    }

    private fun drawWideSingleField(
        canvas: Canvas,
        w: Float,
        h: Float,
        snapshot: GfPacingSnapshot,
        p: Palette,
    ) {
        val padX = w * 0.04f
        val splitX = w * 0.54f

        // Left side: horizontal power bar + current power + target
        val leftMaxW = splitX - padX * 1.5f
        val barTop = h * 0.08f
        val barH = max(8f, h * 0.095f)
        drawHorizontalGaugeBar(
            canvas = canvas,
            left = padX,
            top = barTop,
            right = splitX - padX * 0.6f,
            bottom = barTop + barH,
            snapshot = snapshot,
            p = p,
        )

        val powerText = if (snapshot.isPowerAvailable && snapshot.currentPowerWatts != null) {
            snapshot.currentPowerWatts.toString()
        } else {
            "—"
        }
        val powerSize = (h * 0.37f).coerceIn(24f, 64f)
        val unitSize = powerSize * 0.44f
        val powerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = powerSize
            color = if (snapshot.isPowerAvailable) p.textPrimary else p.textMuted
        }
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = unitSize
            color = if (snapshot.isPowerAvailable) p.textPrimary else p.textMuted
        }
        val unitStr = " ${snapshot.strings.unitWatts}"
        val leftCenterX = (padX + (splitX - padX * 0.6f)) * 0.5f
        val pW = powerPaint.measureText(powerText)
        val uW = unitPaint.measureText(unitStr)
        val startX = leftCenterX - (pW + uW) * 0.5f
        val powerY = h * 0.61f
        canvas.drawText(powerText, startX, powerY, powerPaint)
        canvas.drawText(unitStr, startX + pW, powerY, unitPaint)

        drawTargetAndStatusRow(
            canvas = canvas,
            cx = leftCenterX,
            centerY = h * 0.84f,
            maxWidth = leftMaxW,
            rowHeight = h * 0.22f,
            snapshot = snapshot,
            p = p,
        )

        // Vertical separator
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = p.divider
        }
        canvas.drawLine(splitX, h * 0.12f, splitX, h * 0.88f, divPaint)

        // Right side: NEXT point name + remaining distance (+ target if space allows)
        val rightLeft = splitX + padX * 0.7f
        val rightMaxW = (w - padX - rightLeft).coerceAtLeast(40f)
        val s = snapshot.strings
        val next = snapshot.nextPoint

        val hdrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (h * 0.135f).coerceIn(10f, 18f)
            color = if (snapshot.isNextPointApproaching) p.accentBlue else p.textMuted
        }
        canvas.drawText(s.nextLabel, rightLeft, h * 0.25f, hdrPaint)

        if (!snapshot.hasActiveRoute || next == null) {
            val msgPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = boldSans
                textSize = (h * 0.19f).coerceIn(12f, 24f)
                color = p.textSecondary
            }
            val msg = if (!snapshot.hasActiveRoute) s.noActiveRoute else s.noNextPoint
            val clipped = TextUtils.ellipsize(msg, msgPaint, rightMaxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clipped, rightLeft, h * 0.56f, msgPaint)
            return
        }

        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (h * 0.21f).coerceIn(13f, 26f)
            color = p.textPrimary
        }
        val clippedName = TextUtils.ellipsize(next.cleanName, namePaint, rightMaxW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedName, rightLeft, h * 0.54f, namePaint)

        val distStr = GfFormatter.formatDistance(next.remainingDistanceM, snapshot.isMetricDistance).combined
        val subStr = if (next.targetRange != null) {
            "$distStr • ${next.targetRange.formatCompact()}"
        } else {
            distStr
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            textSize = (h * 0.175f).coerceIn(12f, 22f)
            color = if (snapshot.isNextPointApproaching) p.accentBlue else p.textSecondary
        }
        val clippedSub = TextUtils.ellipsize(subStr, subPaint, rightMaxW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedSub, rightLeft, h * 0.82f, subPaint)
    }

    private fun drawHorizontalGaugeBar(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        snapshot: GfPacingSnapshot,
        p: Palette,
    ) {
        val barW = right - left
        val barH = bottom - top
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        if (snapshot.currentTarget == null) {
            barPaint.color = p.zoneNeutral
            canvas.drawRoundRect(RectF(left, top, right, bottom), barH * 0.5f, barH * 0.5f, barPaint)
            return
        }

        // Proportions match the 180° arc (36° / 32° / 44° / 32° / 36°)
        val weights = floatArrayOf(0.20f, 0.178f, 0.244f, 0.178f, 0.20f)
        val colors = intArrayOf(p.zoneRed, p.zoneYellow, p.zoneGreen, p.zoneYellow, p.zoneRed)
        var cursor = left
        for (i in weights.indices) {
            val segW = barW * weights[i]
            barPaint.color = colors[i]
            canvas.drawRect(cursor, top, cursor + segW + 0.5f, bottom, barPaint)
            cursor += segW
        }

        val angle = snapshot.needleAngleDeg
        if (angle != null) {
            val t = ((angle - RoutePacingEngine.ANGLE_MIN_DEG) /
                (RoutePacingEngine.ANGLE_MAX_DEG - RoutePacingEngine.ANGLE_MIN_DEG)).coerceIn(0f, 1f)
            val markerX = left + t * barW
            val markerW = max(4f, barH * 0.45f)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = p.needleOutline
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = p.needleFill
            }
            canvas.drawRoundRect(
                RectF(markerX - markerW * 0.5f - 1.5f, top - 3f, markerX + markerW * 0.5f + 1.5f, bottom + 3f),
                3f, 3f, outline
            )
            canvas.drawRoundRect(
                RectF(markerX - markerW * 0.5f, top - 2f, markerX + markerW * 0.5f, bottom + 2f),
                2f, 2f, fill
            )
        }
    }

    private fun drawCornerPill(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        bgColor: Int,
        textColor: Int,
        textSize: Float,
        alignRight: Boolean,
    ) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = boldSans
            this.textSize = textSize
            color = textColor
        }
        val padH = textSize * 0.55f
        val pillW = textPaint.measureText(text) + padH * 2f
        val pillH = textSize * 1.55f
        val pillLeft = if (alignRight) left - pillW else left
        val rect = RectF(pillLeft, top, pillLeft + pillW, top + pillH)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bgColor
        }
        canvas.drawRoundRect(rect, pillH * 0.35f, pillH * 0.35f, bgPaint)
        val base = rect.centerY() - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(text, rect.left + padH, base, textPaint)
    }
}
