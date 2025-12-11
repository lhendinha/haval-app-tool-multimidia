package br.com.redesurftank.havalshisuku.managers

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.MainThread
import br.com.redesurftank.App
import br.com.redesurftank.havalshisuku.models.SteeringWheelCustomActionType

/**
 * Single source of truth for the steering-wheel custom action. WHY: Avoids losing "default" by
 * persisting the enum KEY, not the description.
 */
object SteeringWheelActionManager {

    private const val PREFS_NAME = "haval_prefs"
    // Add a dedicated key in SharedPreferencesKeys if you prefer; keep local here for minimal diff.
    private const val PREF_KEY_ACTION = "steering_wheel_custom_action"

    private val prefs: SharedPreferences by lazy {
        // device-protected to survive reboots before user unlock, mirroring other managers
        App.getDeviceProtectedContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelected(): SteeringWheelCustomActionType {
        // Fallback to DEFAULT if not set or unknown
        val stored = prefs.getString(PREF_KEY_ACTION, null)
        return SteeringWheelCustomActionType.fromKey(stored ?: "")
                ?: SteeringWheelCustomActionType.DEFAULT
    }

    fun setSelected(type: SteeringWheelCustomActionType) {
        // Use commit() to reduce chance of loss on sudden process kill
        prefs.edit().putString(PREF_KEY_ACTION, type.key).commit()
    }

    /**
     * One-time migration: if a legacy value stored the *description* (e.g., "Padrão da
     * multimidia."), rewrite it to the enum key ("default").
     */
    fun migrateFromLegacy() {
        val value = prefs.getString(PREF_KEY_ACTION, null) ?: return
        // If it's already a valid key, nothing to do
        if (SteeringWheelCustomActionType.fromKey(value) != null) return

        // Map known Portuguese descriptions -> keys
        val normalized = value.trim().lowercase()
        val mappedKey =
                when (normalized) {
                    // common variants (typos, punctuation)
                    "padrão da multimidia.",
                    "padrão da multimidia",
                    "padrão da multimídia",
                    "padrão da multimedia" -> "default"
                    else -> null
                }
        if (mappedKey != null) prefs.edit().putString(PREF_KEY_ACTION, mappedKey).commit()
    }

    /** Ensure DEFAULT is set at least once so it won't "disappear". */
    fun ensureDefault() {
        if (!prefs.contains(PREF_KEY_ACTION)) {
            setSelected(SteeringWheelCustomActionType.DEFAULT)
        }
    }

    /**
     * Re-apply the currently selected action into your service layer so the SW buttons use it.
     * Change the body to whatever your Service/Projector uses.
     */
    @MainThread
    fun reapplySelection() {
        val current = getSelected()
        // Example: inform your ServiceManager/Projector/UseCase
        // ServiceManager.getInstance().setSteeringWheelCustomAction(current) // implement this
    }
}
