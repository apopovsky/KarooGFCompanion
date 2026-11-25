package com.karoogfcompanion.extension

import android.content.res.Configuration
import com.karoogfcompanion.data.GfRideRepository
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import timber.log.Timber

class GfCompanionExtensionService : KarooExtension(EXTENSION_ID, EXTENSION_VERSION) {

    override val types: List<DataTypeImpl> by lazy {
        listOf(GfPacingDataType(extension))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        GfRideRepository.getInstance(this).onConfigurationChanged(newConfig)
    }

    override fun onBonusAction(actionId: String) {
        Timber.i("GFCompanion BonusAction triggered: $actionId")
        val repo = GfRideRepository.getInstance(this)
        when (actionId) {
            "toggle-segment-overlay" -> repo.toggleSegmentOverlay()
            "open-cue-sheet" -> {
                val snap = repo.snapshot.value
                CueSheetPlusIntegration.sendWaypointBroadcast(this, snap.nextPoint, openDetail = true)
                val liveIntent = CueSheetPlusIntegration.buildLiveActivityIntent(
                    context = this,
                    snapshot = snap,
                    openDetail = true,
                )
                if (liveIntent != null) {
                    runCatching { startActivity(liveIntent) }
                        .onFailure { repo.toggleSegmentOverlay() }
                } else {
                    repo.toggleSegmentOverlay()
                }
            }
        }
    }

    companion object {
        const val EXTENSION_ID = "karoo-gf-companion"
        const val EXTENSION_VERSION = "1.0.0"
    }
}
