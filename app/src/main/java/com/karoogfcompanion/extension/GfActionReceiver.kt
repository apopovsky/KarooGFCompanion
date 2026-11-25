package com.karoogfcompanion.extension

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.karoogfcompanion.data.GfRideRepository
import timber.log.Timber

class GfActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("GfActionReceiver received action: $action")
        val repo = GfRideRepository.getInstance(context)

        when (action) {
            ACTION_TAP_NEXT_CARD -> repo.handleNextCardTap()
            ACTION_CLOSE_OVERLAY -> repo.closeSegmentOverlay()
        }
    }

    companion object {
        const val ACTION_TAP_NEXT_CARD = "com.karoogfcompanion.ACTION_TAP_NEXT_CARD"
        const val ACTION_CLOSE_OVERLAY = "com.karoogfcompanion.ACTION_CLOSE_OVERLAY"

        private const val REQ_TAP_NEXT = 2101
        private const val REQ_CLOSE_OVERLAY = 2102

        fun tapNextCardPendingIntent(context: Context): PendingIntent =
            buildBroadcast(context, REQ_TAP_NEXT, ACTION_TAP_NEXT_CARD)

        fun closeOverlayPendingIntent(context: Context): PendingIntent =
            buildBroadcast(context, REQ_CLOSE_OVERLAY, ACTION_CLOSE_OVERLAY)

        private fun buildBroadcast(context: Context, reqCode: Int, actionName: String): PendingIntent {
            val intent = Intent(context, GfActionReceiver::class.java).apply {
                action = actionName
            }
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
