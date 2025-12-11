package br.com.redesurftank.havalshisuku.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import br.com.redesurftank.havalshisuku.managers.SteeringWheelActionManager

class SteeringWheelActionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED -> {
                SteeringWheelActionManager.migrateFromLegacy()
                SteeringWheelActionManager.ensureDefault()
                SteeringWheelActionManager.reapplySelection()
            }
        }
    }
}
