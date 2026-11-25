package com.karoogfcompanion.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import com.karoogfcompanion.R
import com.karoogfcompanion.domain.GfPacingSnapshot
import com.karoogfcompanion.domain.GfStrings
import com.karoogfcompanion.domain.LatLngPoint
import com.karoogfcompanion.domain.PolylineDecoder
import com.karoogfcompanion.domain.PowerSmoothingMode
import com.karoogfcompanion.domain.RawRoutePoi
import com.karoogfcompanion.domain.RideRecordingState
import com.karoogfcompanion.domain.RouteGeometryEngine
import com.karoogfcompanion.domain.RoutePacingEngine
import com.karoogfcompanion.domain.RouteWaypointPoint
import com.karoogfcompanion.domain.StepPowerAccumulator
import com.karoogfcompanion.domain.TapActionPreference
import com.karoogfcompanion.domain.ZoneBound
import com.karoogfcompanion.extension.CueSheetPlusIntegration
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnNavigationState.NavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.ceil
import kotlin.math.roundToInt

class GfRideRepository private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = GfSettingsStore.getInstance(appContext)
    private val stepPowerAccumulator = StepPowerAccumulator()

    private var karooSystem: KarooSystemService? = null
    private var karooConnected: Boolean = false
    private val consumerIds = ArrayList<String>()
    private var powerConsumerId: String? = null
    private var subscribedPowerMode: PowerSmoothingMode? = null
    private var activeViewCount = 0
    private var tickerJob: Job? = null

    private data class CachedRoute(
        val cacheKey: String,
        val routeName: String?,
        val indexedPolyline: RouteGeometryEngine.IndexedPolyline,
        val routePois: List<RawRoutePoi>,
        val rejoinExtraDistanceM: Double,
    )

    @Volatile
    private var cachedRoute: CachedRoute? = null

    @Volatile
    private var rideState: RideRecordingState = RideRecordingState.RECORDING

    @Volatile
    private var isMetricDistance: Boolean = true

    @Volatile
    private var heartRateZones: List<ZoneBound> = emptyList()

    @Volatile
    private var powerZones: List<ZoneBound> = emptyList()

    @Volatile
    private var isSystemNightMode: Boolean = isContextNightMode()

    @Volatile
    private var latestDistanceToDestinationM: Double? = null

    @Volatile
    private var isRiderOnRoute: Boolean = true

    @Volatile
    private var hasOnRouteSignal: Boolean = false

    @Volatile
    private var latestRideDistanceM: Double = 0.0

    @Volatile
    private var latestLocation: LatLngPoint? = null

    @Volatile
    private var pausedFrozenRouteDistM: Double? = null

    // Telemetry streams & timestamps for staleness detection
    @Volatile
    private var latestPowerWatts: Int? = null

    @Volatile
    private var powerStreamActive: Boolean = false

    @Volatile
    private var lastPowerSampleMs: Long = 0L

    @Volatile
    private var latestHrBpm: Int? = null

    @Volatile
    private var hrStreamActive: Boolean = false

    @Volatile
    private var lastHrSampleMs: Long = 0L

    @Volatile
    private var latestCadenceRpm: Int? = null

    @Volatile
    private var cadenceStreamActive: Boolean = false

    @Volatile
    private var lastCadenceSampleMs: Long = 0L

    @Volatile
    private var latestLapAvgPowerWatts: Int? = null

    @Volatile
    private var lapPowerStreamActive: Boolean = false

    @Volatile
    private var lastLapPowerSampleMs: Long = 0L

    // Overlay and segment alert state
    @Volatile
    private var isSegmentOverlayOpen: Boolean = false

    @Volatile
    private var overlayExpiresAtMs: Long = 0L

    @Volatile
    private var lastKnownPowerSegmentId: String? = null

    @Volatile
    private var lastAlertedSegmentId: String? = null

    @Volatile
    private var lastAlertDispatchedAtMs: Long = 0L

    @Volatile
    private var recentSegmentChangeUntilMs: Long = 0L

    @Volatile
    private var cachedKcsReady: Boolean = false

    @Volatile
    private var lastKcsCheckMs: Long = 0L

    private var toneGenerator: ToneGenerator? = null

    private val _snapshot = MutableStateFlow(GfPacingSnapshot())
    val snapshot: StateFlow<GfPacingSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            settingsStore.settings.collect { newSettings ->
                if (karooConnected && subscribedPowerMode != newSettings.powerSmoothingMode) {
                    karooSystem?.let { subscribePowerStream(it, newSettings.powerSmoothingMode) }
                }
                recomputeSnapshot()
            }
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        isSystemNightMode = isContextNightMode(newConfig)
        recomputeSnapshot()
    }

    private fun resolveKcsReady(nowMs: Long): Boolean {
        if (lastKcsCheckMs == 0L || (nowMs - lastKcsCheckMs) >= KCS_CHECK_INTERVAL_MS) {
            cachedKcsReady = CueSheetPlusIntegration.isInstalled(appContext) &&
                CueSheetPlusIntegration.supportsLiveActivity(appContext)
            lastKcsCheckMs = nowMs
        }
        return cachedKcsReady
    }

    private fun isContextNightMode(config: Configuration = appContext.resources.configuration): Boolean {
        return when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            else -> (appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.nightMode == UiModeManager.MODE_NIGHT_YES
        }
    }

    @Synchronized
    fun onViewAttached() {
        activeViewCount++
        lastKcsCheckMs = 0L
        if (activeViewCount == 1) {
            startKarooConnection()
            startTicker()
        }
        recomputeSnapshot()
    }

    @Synchronized
    fun onViewDetached() {
        activeViewCount = (activeViewCount - 1).coerceAtLeast(0)
        if (activeViewCount == 0) {
            stopTicker()
            stopKarooConnection()
        }
    }

    fun toggleSegmentOverlay() {
        if (isSegmentOverlayOpen) {
            closeSegmentOverlay()
        } else {
            val timeoutSec = settingsStore.settings.value.autoReturnSeconds
            isSegmentOverlayOpen = true
            overlayExpiresAtMs = System.currentTimeMillis() + timeoutSec * 1000L
            recomputeSnapshot()
        }
    }

    fun closeSegmentOverlay() {
        if (isSegmentOverlayOpen) {
            isSegmentOverlayOpen = false
            overlayExpiresAtMs = 0L
            recomputeSnapshot()
        }
    }

    fun handleNextCardTap() {
        val tapAction = settingsStore.settings.value.tapActionPreference
        if (tapAction == TapActionPreference.NONE) return

        if ((tapAction == TapActionPreference.OPEN_CUESHEET_PLUS || tapAction == TapActionPreference.OPEN_CUESHEET_LIST) &&
            CueSheetPlusIntegration.isInstalled(appContext)
        ) {
            val openDetail = tapAction == TapActionPreference.OPEN_CUESHEET_PLUS
            val snap = _snapshot.value
            CueSheetPlusIntegration.sendWaypointBroadcast(appContext, snap.nextPoint, openDetail = openDetail)
            val liveIntent = CueSheetPlusIntegration.buildLiveActivityIntent(appContext, snap, openDetail = openDetail)
            if (liveIntent != null) {
                val started = runCatching { appContext.startActivity(liveIntent) }.isSuccess
                if (started) return
            }
        }
        toggleSegmentOverlay()
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                if (isSegmentOverlayOpen && now >= overlayExpiresAtMs) {
                    isSegmentOverlayOpen = false
                    overlayExpiresAtMs = 0L
                }
                recomputeSnapshot()
                delay(1_000L)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun startKarooConnection() {
        if (karooSystem != null) return
        val service = KarooSystemService(appContext)
        karooSystem = service
        service.connect { connected ->
            Timber.i("GFCompanion connected to KarooSystemService: $connected")
            karooConnected = connected
            if (!connected) return@connect
            registerConsumers(service)
        }
    }

    private fun registerConsumers(service: KarooSystemService) {
        clearConsumers(service)

        // 1. UserProfile (Distance units + HR / Power zones)
        consumerIds += service.addConsumer<UserProfile>(UserProfile.Params) { profile ->
            isMetricDistance = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.METRIC
            heartRateZones = profile.heartRateZones.map { ZoneBound(it.min, it.max) }
            powerZones = profile.powerZones.map { ZoneBound(it.min, it.max) }
            recomputeSnapshot()
        }

        // 2. RideState (Recording / Paused / Idle)
        consumerIds += service.addConsumer<RideState> { state ->
            rideState = when (state) {
                is RideState.Recording -> {
                    pausedFrozenRouteDistM = null
                    RideRecordingState.RECORDING
                }
                is RideState.Paused -> {
                    if (pausedFrozenRouteDistM == null) {
                        pausedFrozenRouteDistM = _snapshot.value.riderDistanceAlongRouteM
                    }
                    RideRecordingState.PAUSED
                }
                is RideState.Idle -> {
                    pausedFrozenRouteDistM = null
                    lastKnownPowerSegmentId = null
                    lastAlertedSegmentId = null
                    recentSegmentChangeUntilMs = 0L
                    stepPowerAccumulator.reset()
                    RideRecordingState.IDLE
                }
            }
            recomputeSnapshot()
        }

        // 3. Active Navigation Route & POIs (strictly route POIs, no synthetic points)
        consumerIds += service.addConsumer<OnNavigationState>(OnNavigationState.Params) { event ->
            handleNavigationState(event.state)
            recomputeSnapshot()
        }

        // 4. Rider GPS Location (fallback route projection)
        consumerIds += service.addConsumer<OnLocationChanged>(OnLocationChanged.Params) { loc ->
            latestLocation = LatLngPoint(loc.lat, loc.lng)
        }

        // 5. Distance to Destination + OnRoute flag
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            if (streaming != null) {
                val values = streaming.dataPoint.values
                latestDistanceToDestinationM = values[DataType.Field.DISTANCE_TO_DESTINATION]
                    ?: streaming.dataPoint.singleValue
                val onRouteVal = values[DataType.Field.ON_ROUTE]
                if (onRouteVal != null) {
                    hasOnRouteSignal = true
                    isRiderOnRoute = onRouteVal >= 0.5
                }
            } else {
                latestDistanceToDestinationM = null
            }
        }

        // 6. Cumulative Ride Distance (m)
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.DISTANCE)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            if (streaming != null) {
                val dist = streaming.dataPoint.values[DataType.Field.DISTANCE]
                    ?: streaming.dataPoint.singleValue
                if (dist != null && dist >= 0.0) {
                    latestRideDistanceM = dist
                }
            }
        }

        // 7. Power stream (based on user smoothing preference)
        subscribePowerStream(service, settingsStore.settings.value.powerSmoothingMode)

        // 8. Heart Rate (bpm)
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.HEART_RATE)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            val value = streaming?.dataPoint?.let { dp ->
                dp.values[DataType.Field.HEART_RATE] ?: dp.singleValue
            }
            if (value != null && !value.isNaN() && value > 0.0) {
                latestHrBpm = value.roundToInt()
                hrStreamActive = true
                lastHrSampleMs = System.currentTimeMillis()
            } else if (streaming == null) {
                hrStreamActive = false
            }
        }

        // 9. Cadence (rpm)
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.CADENCE)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            val value = streaming?.dataPoint?.let { dp ->
                dp.values[DataType.Field.CADENCE] ?: dp.singleValue
            }
            if (value != null && !value.isNaN() && value >= 0.0) {
                latestCadenceRpm = value.roundToInt()
                cadenceStreamActive = true
                lastCadenceSampleMs = System.currentTimeMillis()
            } else if (streaming == null) {
                cadenceStreamActive = false
            }
        }

        // 10. Lap Average Power (W)
        consumerIds += service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(DataType.Type.POWER_LAP)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            val value = streaming?.dataPoint?.let { dp ->
                dp.singleValue ?: dp.values[DataType.Field.AVERAGE_POWER] ?: dp.values.values.firstOrNull()
            }
            if (value != null && !value.isNaN() && value >= 0.0) {
                latestLapAvgPowerWatts = value.roundToInt()
                lapPowerStreamActive = true
                lastLapPowerSampleMs = System.currentTimeMillis()
            } else if (streaming == null) {
                lapPowerStreamActive = false
            }
        }
    }

    private fun subscribePowerStream(service: KarooSystemService, mode: PowerSmoothingMode) {
        powerConsumerId?.let { oldId ->
            runCatching { service.removeConsumer(oldId) }
        }
        powerConsumerId = null
        subscribedPowerMode = mode

        val dataTypeId = when (mode) {
            PowerSmoothingMode.INSTANT_1S -> DataType.Type.POWER
            PowerSmoothingMode.SMOOTHED_3S -> DataType.Type.SMOOTHED_3S_AVERAGE_POWER
            PowerSmoothingMode.SMOOTHED_5S -> DataType.Type.SMOOTHED_5S_AVERAGE_POWER
            PowerSmoothingMode.SMOOTHED_10S -> DataType.Type.SMOOTHED_10S_AVERAGE_POWER
        }

        powerConsumerId = service.addConsumer<OnStreamState>(
            OnStreamState.StartStreaming(dataTypeId)
        ) { streamEvent ->
            val streaming = streamEvent.state as? StreamState.Streaming
            val value = streaming?.dataPoint?.let { dp ->
                dp.singleValue ?: dp.values.values.firstOrNull()
            }
            if (value != null && !value.isNaN() && value >= 0.0) {
                latestPowerWatts = value.roundToInt()
                powerStreamActive = true
                lastPowerSampleMs = System.currentTimeMillis()
            } else if (streaming == null) {
                powerStreamActive = false
            }
            recomputeSnapshot()
        }
    }

    private fun handleNavigationState(state: NavigationState) {
        when (state) {
            is NavigationState.Idle -> {
                cachedRoute = null
                latestDistanceToDestinationM = null
                pausedFrozenRouteDistM = null
                hasOnRouteSignal = false
                isRiderOnRoute = true
                lastKnownPowerSegmentId = null
                lastAlertedSegmentId = null
                recentSegmentChangeUntilMs = 0L
                isSegmentOverlayOpen = false
                stepPowerAccumulator.reset()
            }

            is NavigationState.NavigatingRoute -> {
                val key = "${state.name}_${state.routeDistance.toLong()}_${state.routePolyline.hashCode()}_${state.pois.size}_${state.rejoinPolyline?.hashCode()}_${state.reversed}"
                val existing = cachedRoute
                if (existing != null && existing.cacheKey == key) return

                val decodedLatLng = PolylineDecoder.decodeLatLng(state.routePolyline, precision = 5)
                val indexed = RouteGeometryEngine.buildIndexedPolyline(decodedLatLng, state.routeDistance)
                val rejoinExtraM = if (!state.rejoinPolyline.isNullOrBlank() && state.rejoinDistance != null) {
                    val rejoinPts = PolylineDecoder.decodeLatLng(state.rejoinPolyline, precision = 5)
                    RouteGeometryEngine.buildIndexedPolyline(rejoinPts).totalDistanceM
                } else {
                    0.0
                }

                cachedRoute = CachedRoute(
                    cacheKey = key,
                    routeName = state.name,
                    indexedPolyline = indexed,
                    routePois = state.pois.map { it.toRawRoutePoi() },
                    rejoinExtraDistanceM = rejoinExtraM,
                )
                lastKnownPowerSegmentId = null
                lastAlertedSegmentId = null
                recentSegmentChangeUntilMs = 0L
                stepPowerAccumulator.reset()
            }

            is NavigationState.NavigatingToDestination -> {
                val key = "dest_${state.destination.id}_${state.polyline.hashCode()}"
                val existing = cachedRoute
                if (existing != null && existing.cacheKey == key) return

                val decodedLatLng = PolylineDecoder.decodeLatLng(state.polyline, precision = 5)
                val indexed = RouteGeometryEngine.buildIndexedPolyline(decodedLatLng)
                val destPoi = state.destination.toRawRoutePoi()
                    .copy(distancesAlongRouteM = listOf(indexed.totalDistanceM))

                cachedRoute = CachedRoute(
                    cacheKey = key,
                    routeName = state.destination.name,
                    indexedPolyline = indexed,
                    routePois = listOf(destPoi),
                    rejoinExtraDistanceM = 0.0,
                )
                lastKnownPowerSegmentId = null
                lastAlertedSegmentId = null
                recentSegmentChangeUntilMs = 0L
                stepPowerAccumulator.reset()
            }
        }
    }

    private fun Symbol.POI.toRawRoutePoi(): RawRoutePoi {
        return RawRoutePoi(
            id = id,
            lat = lat,
            lng = lng,
            type = type,
            name = name,
            description = null,
            distancesAlongRouteM = distancesAlongRoute,
        )
    }

    private fun recomputeSnapshot() {
        val now = System.currentTimeMillis()
        val settings = settingsStore.settings.value
        val strings = GfStrings.forLocale()
        val kcsInstalled = resolveKcsReady(now)

        val powerFresh = powerStreamActive && lastPowerSampleMs > 0L && (now - lastPowerSampleMs) <= POWER_STALE_MS
        val hrFresh = hrStreamActive && lastHrSampleMs > 0L && (now - lastHrSampleMs) <= METRIC_STALE_MS
        val cadFresh = cadenceStreamActive && lastCadenceSampleMs > 0L && (now - lastCadenceSampleMs) <= METRIC_STALE_MS
        val lapFresh = lapPowerStreamActive && lastLapPowerSampleMs > 0L && (now - lastLapPowerSampleMs) <= METRIC_STALE_MS

        val currentPower = if (powerFresh) latestPowerWatts else null
        val currentHr = if (hrFresh) latestHrBpm else null
        val currentCad = if (cadFresh) latestCadenceRpm else null
        val currentLapPower = if (lapFresh) latestLapAvgPowerWatts else null

        val overlayRemSec = if (isSegmentOverlayOpen && overlayExpiresAtMs > now) {
            ceil((overlayExpiresAtMs - now) / 1000.0).toInt().coerceAtLeast(1)
        } else {
            0
        }

        val route = cachedRoute
        if (route == null || route.indexedPolyline.totalDistanceM <= 1.0) {
            val gauge = RoutePacingEngine.evaluateGauge(
                currentPowerWatts = currentPower,
                isPowerAvailable = powerFresh,
                target = null,
                yellowMarginMinWatts = settings.yellowMarginMinWatts,
            )
            _snapshot.value = GfPacingSnapshot(
                routeName = null,
                hasActiveRoute = false,
                isOffRoute = false,
                isDemoMode = false,
                rideState = rideState,
                currentPowerWatts = currentPower,
                isPowerAvailable = powerFresh,
                heartRateBpm = currentHr,
                isHeartRateAvailable = hrFresh,
                cadenceRpm = currentCad,
                isCadenceAvailable = cadFresh,
                lapAvgPowerWatts = currentLapPower,
                isLapAvgPowerAvailable = lapFresh,
                heartRateZones = heartRateZones,
                powerZones = powerZones,
                zoneColorBackgrounds = settings.zoneColorBackgrounds,
                currentTarget = null,
                gaugeZone = gauge.zone,
                deviationStatus = gauge.status,
                needleAngleDeg = gauge.needleAngleDeg,
                isSegmentOverlayOpen = isSegmentOverlayOpen,
                overlayRemainingSeconds = overlayRemSec,
                isMetricDistance = isMetricDistance,
                themePreference = settings.themePreference,
                isSystemNightMode = isSystemNightMode,
                isCueSheetPlusInstalled = kcsInstalled,
                tapActionPreference = settings.tapActionPreference,
                strings = strings,
            )
            return
        }

        val totalDistM = route.indexedPolyline.totalDistanceM
        val riderDistM = if (rideState == RideRecordingState.PAUSED && pausedFrozenRouteDistM != null) {
            pausedFrozenRouteDistM!!.coerceIn(0.0, totalDistM)
        } else {
            resolveRiderDistanceAlongRoute(route, totalDistM)
        }

        val offRouteFlag = hasOnRouteSignal && !isRiderOnRoute && route.rejoinExtraDistanceM > 0.0
        val pacing = RoutePacingEngine.resolveRoutePacing(
            indexedPolyline = route.indexedPolyline,
            routePois = route.routePois,
            riderDistanceAlongRouteM = riderDistM,
            rejoinExtraDistanceM = if (isRiderOnRoute) 0.0 else route.rejoinExtraDistanceM,
            singleTargetToleranceWatts = settings.singleTargetToleranceWatts,
            approachingDistanceMeters = settings.approachingDistanceMeters,
        )

        maybeHandleSegmentTransition(pacing.activeSegmentPoint, now, strings)

        val activeSeg = pacing.activeSegmentPoint
        val (resolvedStepAvg, resolvedStepAvail) = if (activeSeg != null) {
            stepPowerAccumulator.updateSegment(activeSeg.id)
            val stepAvg = stepPowerAccumulator.onPowerSample(
                powerWatts = currentPower,
                isPowerAvailable = powerFresh,
                isRecording = rideState == RideRecordingState.RECORDING,
                nowMs = now,
            )
            stepAvg to (stepAvg != null && (powerFresh || rideState == RideRecordingState.PAUSED))
        } else {
            currentLapPower to lapFresh
        }

        val currentTarget = activeSeg?.targetRange
        val gauge = RoutePacingEngine.evaluateGauge(
            currentPowerWatts = currentPower,
            isPowerAvailable = powerFresh,
            target = currentTarget,
            yellowMarginMinWatts = settings.yellowMarginMinWatts,
        )

        _snapshot.value = GfPacingSnapshot(
            routeName = route.routeName,
            hasActiveRoute = true,
            isOffRoute = offRouteFlag,
            isDemoMode = false,
            rideState = rideState,
            riderDistanceAlongRouteM = riderDistM,
            totalRouteDistanceM = totalDistM,
            currentPowerWatts = currentPower,
            isPowerAvailable = powerFresh,
            heartRateBpm = currentHr,
            isHeartRateAvailable = hrFresh,
            cadenceRpm = currentCad,
            isCadenceAvailable = cadFresh,
            lapAvgPowerWatts = resolvedStepAvg,
            isLapAvgPowerAvailable = resolvedStepAvail,
            heartRateZones = heartRateZones,
            powerZones = powerZones,
            zoneColorBackgrounds = settings.zoneColorBackgrounds,
            activeSegmentPoint = activeSeg,
            currentTarget = currentTarget,
            gaugeZone = gauge.zone,
            deviationStatus = gauge.status,
            needleAngleDeg = gauge.needleAngleDeg,
            nextPoint = pacing.nextPoint,
            isNextPointApproaching = pacing.isNextPointApproaching,
            allPoints = pacing.allPoints,
            isSegmentOverlayOpen = isSegmentOverlayOpen,
            overlayRemainingSeconds = overlayRemSec,
            isRecentSegmentChange = now < recentSegmentChangeUntilMs,
            isMetricDistance = isMetricDistance,
            themePreference = settings.themePreference,
            isSystemNightMode = isSystemNightMode,
            isCueSheetPlusInstalled = kcsInstalled,
            tapActionPreference = settings.tapActionPreference,
            strings = strings,
        )
    }

    private fun maybeHandleSegmentTransition(
        activeSegment: RouteWaypointPoint?,
        now: Long,
        strings: GfStrings,
    ) {
        if (rideState != RideRecordingState.RECORDING) return
        val target = activeSegment?.targetRange ?: return

        val prevId = lastKnownPowerSegmentId
        if (prevId == null) {
            lastKnownPowerSegmentId = activeSegment.id
            return
        }
        if (activeSegment.id == prevId) return

        lastKnownPowerSegmentId = activeSegment.id

        if (activeSegment.id == lastAlertedSegmentId || (now - lastAlertDispatchedAtMs) < SEGMENT_ALERT_COOLDOWN_MS) {
            return
        }

        lastAlertedSegmentId = activeSegment.id
        lastAlertDispatchedAtMs = now
        recentSegmentChangeUntilMs = now + SEGMENT_HIGHLIGHT_MS

        val settings = settingsStore.settings.value
        val service = karooSystem
        if (settings.segmentAlertSound) {
            dispatchSegmentBeep(service)
        }
        if (settings.segmentAlertPopup && service != null && karooConnected) {
            val alert = InRideAlert(
                id = "gf_seg_${activeSegment.id}",
                icon = R.drawable.ic_gf_logo,
                title = strings.newTargetBadge,
                detail = "${activeSegment.cleanName} • ${target.formatDisplay()}",
                autoDismissMs = 4_000L,
                backgroundColor = R.color.alert_segment_bg,
                textColor = R.color.alert_segment_text,
            )
            runCatching { service.dispatch(alert) }
        }
    }

    private fun dispatchSegmentBeep(service: KarooSystemService?) {
        if (service != null && karooConnected) {
            val pattern = PlayBeepPattern(
                tones = listOf(
                    PlayBeepPattern.Tone(2400, 140),
                    PlayBeepPattern.Tone(null, 80),
                    PlayBeepPattern.Tone(2800, 220),
                )
            )
            val sent = runCatching { service.dispatch(pattern) }.isSuccess
            if (sent) return
        }
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        } catch (_: Throwable) {
            // Ignore when audio stream is unavailable
        }
    }

    private fun resolveRiderDistanceAlongRoute(route: CachedRoute, totalDistM: Double): Double {
        val dtd = latestDistanceToDestinationM
        val loc = latestLocation
        val rideDistM = latestRideDistanceM

        if (loc == null && (dtd == null || dtd <= 0.0)) {
            return 0.0
        }

        val isDtdPlausible = if (dtd != null && dtd >= 0.0) {
            if (dtd < 100.0 && totalDistM > 500.0) {
                rideDistM > (totalDistM * 0.7) || (loc != null && route.indexedPolyline.points.isNotEmpty() &&
                    RouteGeometryEngine.haversineMeters(loc, route.indexedPolyline.points.last()) < 150.0)
            } else {
                dtd <= totalDistM + 1000.0
            }
        } else {
            false
        }

        if (isRiderOnRoute && isDtdPlausible && dtd != null) {
            return (totalDistM - dtd).coerceIn(0.0, totalDistM)
        }

        if (loc != null) {
            val proj = RouteGeometryEngine.projectOntoPolyline(route.indexedPolyline, loc)
            if (proj != null) {
                if (rideDistM < 200.0 && proj.distanceAlongRouteM > 500.0 && proj.crossTrackDistanceM > 100.0) {
                    return 0.0
                }
                return proj.distanceAlongRouteM.coerceIn(0.0, totalDistM)
            }
        }

        if (isDtdPlausible && dtd != null) {
            return (totalDistM - dtd).coerceIn(0.0, totalDistM)
        }

        return 0.0
    }

    private fun clearConsumers(service: KarooSystemService) {
        consumerIds.forEach { id ->
            runCatching { service.removeConsumer(id) }
        }
        consumerIds.clear()
        powerConsumerId?.let { id ->
            runCatching { service.removeConsumer(id) }
        }
        powerConsumerId = null
        subscribedPowerMode = null
    }

    private fun stopKarooConnection() {
        karooSystem?.let { service ->
            clearConsumers(service)
            runCatching { service.disconnect() }
        }
        karooConnected = false
        karooSystem = null
    }

    companion object {
        private const val POWER_STALE_MS = 4_000L
        private const val METRIC_STALE_MS = 4_500L
        private const val SEGMENT_ALERT_COOLDOWN_MS = 15_000L
        private const val SEGMENT_HIGHLIGHT_MS = 8_000L
        private const val KCS_CHECK_INTERVAL_MS = 10_000L

        @Volatile
        private var instance: GfRideRepository? = null

        fun getInstance(context: Context): GfRideRepository {
            return instance ?: synchronized(this) {
                instance ?: GfRideRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
