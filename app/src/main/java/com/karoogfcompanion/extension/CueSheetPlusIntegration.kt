package com.karoogfcompanion.extension

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.karoogfcompanion.domain.GfPacingSnapshot
import com.karoogfcompanion.domain.RouteWaypointPoint

object CueSheetPlusIntegration {

    const val CUESHEET_PLUS_PACKAGE = "com.karoocuesheetplus"
    const val LIVE_CUESHEET_ACTIVITY = "com.karoocuesheetplus.ui.LiveCueSheetActivity"
    const val CUESHEET_RECEIVER = "com.karoocuesheetplus.extension.CueSheetActionReceiver"

    const val ACTION_VIEW_WAYPOINT_DETAIL = "com.karoocuesheetplus.ACTION_VIEW_WAYPOINT_DETAIL"
    const val ACTION_VIEW_CUESHEET = "com.karoocuesheetplus.ACTION_VIEW_CUESHEET"
    const val ACTION_OPEN_WAYPOINT_DETAIL = "com.karoocuesheetplus.ACTION_OPEN_WAYPOINT_DETAIL"
    const val ACTION_OPEN_CUESHEET_LIST = "com.karoocuesheetplus.ACTION_OPEN_CUESHEET_LIST"

    const val EXTRA_VIEW_MODE = "extra_view_mode"
    const val EXTRA_WAYPOINT_ID = "extra_waypoint_id"
    const val EXTRA_WAYPOINT_NAME = "extra_waypoint_name"
    const val EXTRA_WAYPOINT_RAW_NAME = "extra_waypoint_raw_name"
    const val EXTRA_WAYPOINT_DISTANCE_M = "extra_waypoint_distance_m"
    const val EXTRA_WAYPOINT_REMAINING_M = "extra_waypoint_remaining_m"
    const val EXTRA_RIDER_DISTANCE_M = "extra_rider_distance_m"
    const val EXTRA_AUTO_CLOSE_ACTIVITY = "extra_auto_close_activity"

    fun isInstalled(context: Context): Boolean = getPackageInfo(context) != null

    fun getInstalledVersion(context: Context): String? = getPackageInfo(context)?.versionName

    fun supportsLiveActivity(context: Context): Boolean {
        val intent = Intent().setComponent(ComponentName(CUESHEET_PLUS_PACKAGE, LIVE_CUESHEET_ACTIVITY))
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L),
                ) != null
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(intent, 0) != null
            }
        }.getOrDefault(false)
    }

    fun buildLiveActivityIntent(
        context: Context,
        snapshot: GfPacingSnapshot,
        openDetail: Boolean,
    ): Intent? {
        if (!isInstalled(context) || !supportsLiveActivity(context)) return null
        val next = snapshot.nextPoint
        return Intent().apply {
            component = ComponentName(CUESHEET_PLUS_PACKAGE, LIVE_CUESHEET_ACTIVITY)
            action = if (openDetail) ACTION_VIEW_WAYPOINT_DETAIL else ACTION_VIEW_CUESHEET
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_VIEW_MODE, if (openDetail) "DETAIL" else "LIST")
            putExtra(EXTRA_AUTO_CLOSE_ACTIVITY, true)
            putExtra(EXTRA_RIDER_DISTANCE_M, snapshot.riderDistanceAlongRouteM)
            if (next != null) {
                putExtra(EXTRA_WAYPOINT_ID, next.id)
                putExtra(EXTRA_WAYPOINT_NAME, next.cleanName)
                putExtra(EXTRA_WAYPOINT_RAW_NAME, next.rawName)
                putExtra(EXTRA_WAYPOINT_DISTANCE_M, next.distanceAlongRouteM)
                putExtra(EXTRA_WAYPOINT_REMAINING_M, next.remainingDistanceM)
            }
        }
    }

    fun sendWaypointBroadcast(
        context: Context,
        nextPoint: RouteWaypointPoint?,
        openDetail: Boolean,
    ) {
        if (!isInstalled(context)) return
        val intent = Intent().apply {
            component = ComponentName(CUESHEET_PLUS_PACKAGE, CUESHEET_RECEIVER)
            action = if (openDetail) ACTION_OPEN_WAYPOINT_DETAIL else ACTION_OPEN_CUESHEET_LIST
            if (nextPoint != null) {
                putExtra(EXTRA_WAYPOINT_ID, nextPoint.id)
                putExtra(EXTRA_WAYPOINT_NAME, nextPoint.cleanName)
                putExtra(EXTRA_WAYPOINT_RAW_NAME, nextPoint.rawName)
                putExtra(EXTRA_WAYPOINT_DISTANCE_M, nextPoint.distanceAlongRouteM)
            }
        }
        runCatching { context.sendBroadcast(intent) }
    }

    private fun getPackageInfo(context: Context): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                CUESHEET_PLUS_PACKAGE,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(CUESHEET_PLUS_PACKAGE, 0)
        }
    }.getOrNull()
}
