package com.karoogfcompanion.simulator

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

object GfTouchKeys {
    val Command = ActionParameters.Key<String>("gf_cmd")
    const val ACTION_SIM_TOUCH = "com.karoogfcompanion.simulator.TOUCH_ACTION"
    const val EXTRA_CMD = "cmd"
}

object GfTouchBus {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun dispatch(command: String): Boolean {
        val target = listener ?: return false
        mainHandler.post { target(command) }
        return true
    }
}

class GfTouchAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val cmd = parameters[GfTouchKeys.Command] ?: return
        if (!GfTouchBus.dispatch(cmd)) {
            val intent = Intent(GfTouchKeys.ACTION_SIM_TOUCH).apply {
                putExtra(GfTouchKeys.EXTRA_CMD, cmd)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }
}
