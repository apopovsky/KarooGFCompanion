package com.karoogfcompanion.simulator

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Box
import androidx.glance.layout.Column as GlanceColumn
import androidx.glance.layout.fillMaxWidth as glanceFillMaxWidth
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height as glanceHeight
import androidx.glance.layout.size
import com.itl.karoo.simulator.KarooSimulatorPlugin
import com.itl.karoo.simulator.SimulatorController
import com.itl.karoo.simulator.SimulatorRideState
import com.itl.karoo.simulator.SimulatorState
import com.karoogfcompanion.domain.DemoPacingProvider
import com.karoogfcompanion.domain.GfFormatter
import com.karoogfcompanion.domain.GfPacingSnapshot
import com.karoogfcompanion.domain.GfStrings
import com.karoogfcompanion.domain.GpxRouteParser
import com.karoogfcompanion.domain.ParsedGpxRoute
import com.karoogfcompanion.domain.RawRoutePoi
import com.karoogfcompanion.domain.RideRecordingState
import com.karoogfcompanion.domain.RoutePacingEngine
import com.karoogfcompanion.domain.StepPowerAccumulator
import com.karoogfcompanion.domain.TapActionPreference
import com.karoogfcompanion.domain.ThemePreference
import com.karoogfcompanion.render.GfCompanionRenderer
import java.util.Locale
import kotlin.math.roundToInt

class GfSimulatorPlugin : KarooSimulatorPlugin {

    override val name: String = "GF Companion"

    companion object {
        const val BUNDLED_GPX_ASSET = "GF_7_LAGOS_2026_POIS_POTENCIA.gpx"
        private const val KCS_PACKAGE = "com.karoocuesheetplus"
        private const val KCS_LIVE_ACTIVITY = "com.karoocuesheetplus.ui.LiveCueSheetActivity"
        private const val KCS_ACTION_DETAIL = "com.karoocuesheetplus.ACTION_VIEW_WAYPOINT_DETAIL"
    }

    private var controllerRef: SimulatorController? = null
    private var appContext: Context? = null

    private var loadedRoute by mutableStateOf<ParsedGpxRoute?>(null)
    private var riderDistanceM by mutableDoubleStateOf(12_100.0)
    private var currentSpeedKmh by mutableDoubleStateOf(28.0)
    private var currentGradePct by mutableDoubleStateOf(0.0)
    private var currentAltitudeM by mutableDoubleStateOf(650.0)

    private var isNightMode by mutableStateOf(false)
    private var zoneColorBackgrounds by mutableStateOf(true)
    private var forceHalfLayout by mutableStateOf(false)
    private var isPowerConnected by mutableStateOf(true)
    private var isHrAvailable by mutableStateOf(true)
    private var isCadAvailable by mutableStateOf(true)
    private var isLapAvgAvailable by mutableStateOf(true)
    private var noTargetTestMode by mutableStateOf(false)
    private var injectOrdinaryPoint by mutableStateOf(false)
    private var isKcsInstalled by mutableStateOf(false)
    private var kcsTapEnabled by mutableStateOf(true)

    private var simHrBpm by mutableIntStateOf(152)
    private var simCadRpm by mutableIntStateOf(86)
    private val stepPowerAccumulator = StepPowerAccumulator()
    private var lastRiderDistanceM: Double = 12_100.0

    private var isOverlayOpen by mutableStateOf(false)
    private var overlayRemainingSec by mutableIntStateOf(0)
    private var lastSimulatedElapsedSec: Long = 0L
    private var lastPowerWatts: Double = 198.0
    private var skipNextPhysicsStep: Boolean = false

    private var snapshotState by mutableStateOf(GfPacingSnapshot())

    override fun onInit(context: Context) {
        appContext = context.applicationContext
        isKcsInstalled = supportsKcsLiveActivity(context)
        GfTouchBus.listener = { cmd -> handleTouchCommand(cmd) }

        val filter = IntentFilter(GfTouchKeys.ACTION_SIM_TOUCH)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val cmd = intent?.getStringExtra(GfTouchKeys.EXTRA_CMD) ?: return
                handleTouchCommand(cmd)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        loadBundledSevenLagosGpx(context)
    }

    private fun loadBundledSevenLagosGpx(context: Context) {
        runCatching {
            context.assets.open(BUNDLED_GPX_ASSET).use { stream ->
                GpxRouteParser.parse(stream, fallbackName = "Gran Fondo 7 Lagos 2026")
            }
        }.onSuccess { parsed ->
            applyLoadedRoute(parsed, startDistanceM = 12_100.0, powerWatts = 198.0)
            controllerRef?.logEvent(
                "GPX: ${parsed.routeName} (${(parsed.totalDistanceM / 1000.0).roundToInt()} km, ${parsed.routePois.size} POIs)"
            )
        }.onFailure { err ->
            controllerRef?.logEvent("GPX error: ${err.message}")
        }
    }

    private fun applyLoadedRoute(route: ParsedGpxRoute, startDistanceM: Double, powerWatts: Double) {
        loadedRoute = route
        riderDistanceM = startDistanceM.coerceIn(0.0, route.totalDistanceM)
        lastRiderDistanceM = riderDistanceM
        stepPowerAccumulator.reset()
        isOverlayOpen = false
        overlayRemainingSec = 0
        lastPowerWatts = powerWatts
        updateTerrainTelemetry(route, riderDistanceM, powerWatts)
        rebuildSnapshot(rideState = SimulatorRideState.RECORDING, powerWatts = powerWatts)
    }

    private fun handleTouchCommand(cmd: String) {
        when (cmd) {
            "toggle_overlay" -> {
                if (isKcsInstalled && kcsTapEnabled && tryLaunchKcsDetail()) {
                    return
                }
                isOverlayOpen = !isOverlayOpen
                overlayRemainingSec = if (isOverlayOpen) 10 else 0
                controllerRef?.logEvent(if (isOverlayOpen) "Overlay ON" else "Overlay OFF")
            }
            "close_overlay" -> {
                isOverlayOpen = false
                overlayRemainingSec = 0
                controllerRef?.logEvent("Overlay closed")
            }
        }
        refreshUiWithoutAdvancingRider()
    }

    private fun supportsKcsLiveActivity(context: Context): Boolean {
        val intent = Intent().setComponent(ComponentName(KCS_PACKAGE, KCS_LIVE_ACTIVITY))
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(intent, 0) != null
            }
        }.getOrDefault(false)
    }

    private fun tryLaunchKcsDetail(): Boolean {
        val ctx = appContext ?: return false
        if (!supportsKcsLiveActivity(ctx)) return false
        val snap = snapshotState
        val next = snap.nextPoint
        val intent = Intent().apply {
            component = ComponentName(KCS_PACKAGE, KCS_LIVE_ACTIVITY)
            action = KCS_ACTION_DETAIL
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("extra_view_mode", "DETAIL")
            putExtra("extra_auto_close_activity", true)
            putExtra("extra_rider_distance_m", snap.riderDistanceAlongRouteM)
            if (next != null) {
                putExtra("extra_waypoint_id", next.id)
                putExtra("extra_waypoint_name", next.cleanName)
                putExtra("extra_waypoint_raw_name", next.rawName)
                putExtra("extra_waypoint_distance_m", next.distanceAlongRouteM)
                putExtra("extra_waypoint_remaining_m", next.remainingDistanceM)
            }
        }
        val launched = runCatching { ctx.startActivity(intent) }.isSuccess
        if (launched) {
            controllerRef?.logEvent("KCS+ Detail: ${next?.cleanName ?: "Next"}")
        }
        return launched
    }

    private fun refreshUiWithoutAdvancingRider() {
        rebuildSnapshot(rideState = SimulatorRideState.RECORDING, powerWatts = lastPowerWatts)
        skipNextPhysicsStep = true
        controllerRef?.triggerTick()
    }

    override fun onTick(elapsedSeconds: Long, powerWatts: Double, rideState: SimulatorRideState) {
        val route = loadedRoute
        lastPowerWatts = powerWatts

        if (route == null) {
            lastSimulatedElapsedSec = elapsedSeconds
            rebuildSnapshot(rideState, powerWatts)
            return
        }

        if (skipNextPhysicsStep) {
            skipNextPhysicsStep = false
            lastSimulatedElapsedSec = elapsedSeconds
            rebuildSnapshot(rideState, powerWatts)
            return
        }

        val dtSec = if (elapsedSeconds > lastSimulatedElapsedSec) {
            (elapsedSeconds - lastSimulatedElapsedSec).coerceAtMost(120L).toDouble()
        } else {
            0.0
        }
        lastSimulatedElapsedSec = elapsedSeconds

        val gradePct = GpxRouteParser.gradeAtDistance(route.elevationProfile, riderDistanceM)
        currentGradePct = gradePct
        currentAltitudeM = GpxRouteParser.interpolateElevationAt(route.elevationProfile, riderDistanceM)

        if (rideState == SimulatorRideState.RECORDING && dtSec > 0.0) {
            val speedMps = estimateGroundSpeedMps(if (isPowerConnected) powerWatts else 0.0, gradePct)
            currentSpeedKmh = speedMps * 3.6
            riderDistanceM = (riderDistanceM + speedMps * dtSec).coerceIn(0.0, route.totalDistanceM)
            currentGradePct = GpxRouteParser.gradeAtDistance(route.elevationProfile, riderDistanceM)
            currentAltitudeM = GpxRouteParser.interpolateElevationAt(route.elevationProfile, riderDistanceM)

            simHrBpm = (128 + (powerWatts * 0.12).roundToInt()).coerceIn(95, 185)
            simCadRpm = if (isPowerConnected && powerWatts > 10.0) (74 + (powerWatts / 16.0).roundToInt()).coerceIn(60, 108) else 0

            if (isOverlayOpen && overlayRemainingSec > 0) {
                overlayRemainingSec = (overlayRemainingSec - 1).coerceAtLeast(0)
                if (overlayRemainingSec == 0) isOverlayOpen = false
            }
        } else if (rideState != SimulatorRideState.RECORDING) {
            currentSpeedKmh = 0.0
        }

        rebuildSnapshot(rideState, powerWatts)
    }

    override fun onReset() {
        lastSimulatedElapsedSec = 0L
        riderDistanceM = 0.0
        lastRiderDistanceM = 0.0
        isOverlayOpen = false
        overlayRemainingSec = 0
        stepPowerAccumulator.reset()
        loadedRoute?.let { updateTerrainTelemetry(it, 0.0, lastPowerWatts) }
        rebuildSnapshot(SimulatorRideState.RECORDING, lastPowerWatts)
    }

    private fun updateTerrainTelemetry(route: ParsedGpxRoute, distM: Double, powerWatts: Double) {
        val grade = GpxRouteParser.gradeAtDistance(route.elevationProfile, distM)
        currentGradePct = grade
        currentAltitudeM = GpxRouteParser.interpolateElevationAt(route.elevationProfile, distM)
        currentSpeedKmh = estimateGroundSpeedMps(if (isPowerConnected) powerWatts else 0.0, grade) * 3.6
    }

    private fun estimateGroundSpeedMps(powerWatts: Double, gradePct: Double): Double {
        if (powerWatts <= 5.0 && gradePct >= -1.0) return 0.0
        val baseMps = (powerWatts.coerceAtLeast(80.0) / 22.0).coerceIn(4.5, 13.0)
        val gradeFactor = (1.0 - gradePct * 0.055).coerceIn(0.35, 1.65)
        return (baseMps * gradeFactor).coerceIn(2.0, 20.0)
    }

    private fun rebuildSnapshot(rideState: SimulatorRideState, powerWatts: Double) {
        val route = loadedRoute
        val strings = GfStrings.forLocale()
        val powerAvail = isPowerConnected
        val curPower = if (powerAvail) powerWatts.roundToInt() else null

        val simNowMs = System.currentTimeMillis() + lastSimulatedElapsedSec * 1000L

        if (route == null || route.totalDistanceM <= 1.0) {
            stepPowerAccumulator.updateSegment(null)
            val stepAvg = stepPowerAccumulator.onPowerSample(
                powerWatts = curPower,
                isPowerAvailable = powerAvail,
                isRecording = rideState == SimulatorRideState.RECORDING,
                nowMs = simNowMs,
            )
            val gauge = RoutePacingEngine.evaluateGauge(curPower, powerAvail, target = null)
            snapshotState = GfPacingSnapshot(
                hasActiveRoute = false,
                rideState = rideState.toDomainState(),
                currentPowerWatts = curPower,
                isPowerAvailable = powerAvail,
                heartRateBpm = if (isHrAvailable) simHrBpm else null,
                isHeartRateAvailable = isHrAvailable,
                cadenceRpm = if (isCadAvailable) simCadRpm else null,
                isCadenceAvailable = isCadAvailable,
                lapAvgPowerWatts = if (isLapAvgAvailable) stepAvg else null,
                isLapAvgPowerAvailable = isLapAvgAvailable && stepAvg != null,
                heartRateZones = DemoPacingProvider.DEFAULT_DEMO_HR_ZONES,
                powerZones = DemoPacingProvider.DEFAULT_DEMO_POWER_ZONES,
                zoneColorBackgrounds = zoneColorBackgrounds,
                gaugeZone = gauge.zone,
                deviationStatus = gauge.status,
                needleAngleDeg = gauge.needleAngleDeg,
                strings = strings,
            )
            return
        }

        val effectivePois: List<RawRoutePoi> = when {
            noTargetTestMode -> listOf(
                RawRoutePoi(
                    id = "ordinary_only",
                    lat = 0.0,
                    lng = 0.0,
                    type = "Water",
                    name = "Control Hidratacion",
                    distancesAlongRouteM = listOf((riderDistanceM + 2_200.0).coerceAtMost(route.totalDistanceM)),
                )
            )
            injectOrdinaryPoint -> route.routePois + RawRoutePoi(
                id = "sim_ordinary_ahead",
                lat = 0.0,
                lng = 0.0,
                type = "Water",
                name = "Puesto Agua Intermedio",
                distancesAlongRouteM = listOf((riderDistanceM + 650.0).coerceAtMost(route.totalDistanceM)),
            )
            else -> route.routePois
        }

        val pacing = RoutePacingEngine.resolveRoutePacing(
            indexedPolyline = route.indexedPolyline,
            routePois = effectivePois,
            riderDistanceAlongRouteM = riderDistanceM,
            approachingDistanceMeters = 500,
        )

        if ((riderDistanceM - lastRiderDistanceM) < -50.0) {
            stepPowerAccumulator.reset()
        }
        lastRiderDistanceM = riderDistanceM

        val segmentKey = if (noTargetTestMode) "no_target" else (pacing.activeSegmentPoint?.id ?: "pre_segment")
        stepPowerAccumulator.updateSegment(segmentKey)
        val stepAvg = stepPowerAccumulator.onPowerSample(
            powerWatts = curPower,
            isPowerAvailable = powerAvail,
            isRecording = rideState == SimulatorRideState.RECORDING,
            nowMs = simNowMs,
        )

        val currentTarget = if (noTargetTestMode) null else pacing.activeSegmentPoint?.targetRange
        val gauge = RoutePacingEngine.evaluateGauge(
            currentPowerWatts = curPower,
            isPowerAvailable = powerAvail,
            target = currentTarget,
        )

        snapshotState = GfPacingSnapshot(
            routeName = route.routeName,
            hasActiveRoute = true,
            isOffRoute = false,
            isDemoMode = false,
            rideState = rideState.toDomainState(),
            riderDistanceAlongRouteM = riderDistanceM,
            totalRouteDistanceM = route.totalDistanceM,
            currentPowerWatts = curPower,
            isPowerAvailable = powerAvail,
            heartRateBpm = if (isHrAvailable) simHrBpm else null,
            isHeartRateAvailable = isHrAvailable,
            cadenceRpm = if (isCadAvailable) simCadRpm else null,
            isCadenceAvailable = isCadAvailable,
            lapAvgPowerWatts = if (isLapAvgAvailable) stepAvg else null,
            isLapAvgPowerAvailable = isLapAvgAvailable && stepAvg != null,
            heartRateZones = DemoPacingProvider.DEFAULT_DEMO_HR_ZONES,
            powerZones = DemoPacingProvider.DEFAULT_DEMO_POWER_ZONES,
            zoneColorBackgrounds = zoneColorBackgrounds,
            activeSegmentPoint = if (noTargetTestMode) null else pacing.activeSegmentPoint,
            currentTarget = currentTarget,
            gaugeZone = gauge.zone,
            deviationStatus = gauge.status,
            needleAngleDeg = gauge.needleAngleDeg,
            nextPoint = pacing.nextPoint,
            isNextPointApproaching = pacing.isNextPointApproaching,
            allPoints = pacing.allPoints,
            isSegmentOverlayOpen = isOverlayOpen,
            overlayRemainingSeconds = overlayRemainingSec,
            isMetricDistance = true,
            themePreference = if (isNightMode) ThemePreference.DARK else ThemePreference.LIGHT,
            isCueSheetPlusInstalled = isKcsInstalled && kcsTapEnabled,
            tapActionPreference = if (isKcsInstalled && kcsTapEnabled) {
                TapActionPreference.OPEN_CUESHEET_PLUS
            } else {
                TapActionPreference.IN_FIELD_SEGMENT_LIST
            },
            strings = strings,
        )
    }

    private fun SimulatorRideState.toDomainState(): RideRecordingState = when (this) {
        SimulatorRideState.RECORDING -> RideRecordingState.RECORDING
        SimulatorRideState.PAUSED -> RideRecordingState.PAUSED
        SimulatorRideState.IDLE -> RideRecordingState.IDLE
    }

    @Composable
    override fun GlanceFieldContent(state: SimulatorState, viewSizePx: Pair<Int, Int>) {
        val snap = snapshotState
        val isForcedHalf = forceHalfLayout && viewSizePx.second >= 500
        val wPx = if (isForcedHalf) 480 else viewSizePx.first
        val hPx = if (isForcedHalf) 350 else viewSizePx.second

        val widthDp = (wPx / 2f).dp
        val heightDp = (hPx / 2f).dp

        val bitmap = when {
            hPx >= 500 -> GfCompanionRenderer.renderFullPage(snap, wPx, hPx, isNightMode)
            hPx >= 220 && wPx >= 340 -> GfCompanionRenderer.renderHalfPage(snap, wPx, hPx, isNightMode)
            else -> GfCompanionRenderer.renderSingleField(
                snap, wPx, hPx, isWideSlot = wPx > 300, isNightMode = isNightMode
            )
        }

        Box(modifier = GlanceModifier.size(widthDp, heightDp)) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "GF Companion",
                modifier = GlanceModifier.fillMaxSize(),
            )

            if (hPx >= 500) {
                if (snap.isSegmentOverlayOpen) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .clickable(
                                actionRunCallback<GfTouchAction>(
                                    actionParametersOf(GfTouchKeys.Command to "close_overlay")
                                )
                            )
                    ) {}
                } else {
                    val topSpacerH = (heightDp.value * 0.74f).dp
                    val nextCardH = (heightDp.value * 0.26f).dp
                    GlanceColumn(modifier = GlanceModifier.fillMaxSize()) {
                        Box(
                            modifier = GlanceModifier
                                .glanceFillMaxWidth()
                                .glanceHeight(topSpacerH)
                        ) {}
                        Box(
                            modifier = GlanceModifier
                                .glanceFillMaxWidth()
                                .glanceHeight(nextCardH)
                                .clickable(
                                    actionRunCallback<GfTouchAction>(
                                        actionParametersOf(GfTouchKeys.Command to "toggle_overlay")
                                    )
                                )
                        ) {}
                    }
                }
            }
        }
    }

    @Composable
    override fun ExtraControls(simulatorState: SimulatorState, controller: SimulatorController) {
        controllerRef = controller
        val context = LocalContext.current
        isKcsInstalled = supportsKcsLiveActivity(context)
        val route = loadedRoute

        val gpxLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                var displayName = "GPX Route"
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIdx).removeSuffix(".gpx")
                        }
                    }
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        GpxRouteParser.parse(stream, fallbackName = displayName)
                    }
                }.onSuccess { parsed ->
                    if (parsed != null && parsed.totalDistanceM > 10.0) {
                        applyLoadedRoute(parsed, startDistanceM = 0.0, powerWatts = simulatorState.currentPowerWatts)
                        controller.logEvent("GPX: ${parsed.routeName} (${parsed.routePois.size} POIs)")
                        refreshUiWithoutAdvancingRider()
                    }
                }.onFailure { err ->
                    controller.logEvent("GPX error: ${err.message}")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222B)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header + Theme + Half Layout toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = route?.routeName ?: "No active route",
                            fontSize = 14.sp,
                            color = Color.White,
                        )
                        val totalKm = (route?.totalDistanceM ?: 0.0) / 1000.0
                        Text(
                            text = String.format(
                                Locale.US,
                                "%.1f km • %d POIs",
                                totalKm,
                                route?.routePois?.size ?: 0,
                            ),
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                isNightMode = !isNightMode
                                refreshUiWithoutAdvancingRider()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(if (isNightMode) "☀ Light" else "🌙 Dark", fontSize = 11.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = {
                                forceHalfLayout = !forceHalfLayout
                                refreshUiWithoutAdvancingRider()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(if (forceHalfLayout) "Full" else "Half 480×350", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }

                // Route Progress Scrubber
                if (route != null && route.totalDistanceM > 100.0) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Pos: %.1f / %.1f km",
                                    riderDistanceM / 1000.0,
                                    route.totalDistanceM / 1000.0,
                                ),
                                fontSize = 12.sp,
                                color = Color(0xFF38BDF8),
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Alt %d m • %+.1f%% • %.1f km/h",
                                    currentAltitudeM.roundToInt(),
                                    currentGradePct,
                                    currentSpeedKmh,
                                ),
                                fontSize = 12.sp,
                                color = Color(0xFFFACC15),
                            )
                        }
                        Slider(
                            value = riderDistanceM.toFloat(),
                            onValueChange = { newDist ->
                                riderDistanceM = newDist.toDouble().coerceIn(0.0, route.totalDistanceM)
                                updateTerrainTelemetry(route, riderDistanceM, simulatorState.currentPowerWatts)
                                refreshUiWithoutAdvancingRider()
                            },
                            valueRange = 0f..route.totalDistanceM.toFloat(),
                        )
                    }

                    // Quick jumps for 7 Lagos + Load GPX
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val presets = listOf(
                            "Largada 0k" to 0.0,
                            "Concept 12.1k" to 12_100.0,
                            "Pto Chapelco 14.5k" to 14_500.0,
                            "Techo 17.6k" to 17_600.0,
                            "Machonico 27k" to 27_000.0,
                            "Falkner 48.1k" to 48_100.0,
                            "Escondido 61k" to 61_000.0,
                            "Ruca Malen 85.2k" to 85_200.0,
                            "Final VLA 105k" to 105_000.0,
                        )
                        presets.forEach { (label, targetM) ->
                            AssistChip(
                                onClick = {
                                    riderDistanceM = targetM.coerceIn(0.0, route.totalDistanceM)
                                    updateTerrainTelemetry(route, riderDistanceM, simulatorState.currentPowerWatts)
                                    refreshUiWithoutAdvancingRider()
                                },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                        AssistChip(
                            onClick = { gpxLauncher.launch(arrayOf("*/*")) },
                            label = { Text("📂 Load GPX", fontSize = 11.sp) },
                        )
                    }
                }

                // Sensor availability & special state test toggles
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = zoneColorBackgrounds,
                        onClick = {
                            zoneColorBackgrounds = !zoneColorBackgrounds
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text(if (zoneColorBackgrounds) "Zone BG ON" else "Zone BG OFF", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = isPowerConnected,
                        onClick = {
                            isPowerConnected = !isPowerConnected
                            controller.setSensorConnected(isPowerConnected)
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text(if (isPowerConnected) "PWR ON" else "PWR OFF", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = isHrAvailable,
                        onClick = {
                            isHrAvailable = !isHrAvailable
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text(if (isHrAvailable) "HR ON" else "HR OFF", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = isCadAvailable,
                        onClick = {
                            isCadAvailable = !isCadAvailable
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text(if (isCadAvailable) "CAD ON" else "CAD OFF", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = isLapAvgAvailable,
                        onClick = {
                            isLapAvgAvailable = !isLapAvgAvailable
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text(if (isLapAvgAvailable) "STEP ON" else "STEP OFF", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = noTargetTestMode,
                        onClick = {
                            noTargetTestMode = !noTargetTestMode
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text("No Target Mode", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = injectOrdinaryPoint,
                        onClick = {
                            injectOrdinaryPoint = !injectOrdinaryPoint
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text("Inject Ordinary Pt", fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = isKcsInstalled && kcsTapEnabled,
                        enabled = isKcsInstalled,
                        onClick = {
                            kcsTapEnabled = !kcsTapEnabled
                            refreshUiWithoutAdvancingRider()
                        },
                        label = { Text(if (isKcsInstalled) "KCS+ Tap" else "KCS+ N/A", fontSize = 11.sp) },
                    )
                }
            }
        }
    }

    override fun getTelemetrySummary(): String {
        val snap = snapshotState
        val targetStr = snap.currentTarget?.formatDisplay() ?: snap.strings.noTargetHere
        val nextStr = snap.nextPoint?.let {
            val dist = GfFormatter.formatDistance(it.remainingDistanceM, true).combined
            "${it.cleanName} ($dist)"
        } ?: snap.strings.noNextPoint
        return "Target: $targetStr | Zone: ${snap.gaugeZone} | Next: $nextStr"
    }
}
