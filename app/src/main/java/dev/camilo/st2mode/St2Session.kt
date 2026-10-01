package dev.camilo.st2mode

import android.content.Context
import dev.camilo.st2mode.ble.St2GattClient

/**
 * Process-scoped owner of the single [St2GattClient].
 *
 * Activity and the widget command service share this so they never open
 * competing GATT connections. [acquire] creates the client on first use;
 * [release] closes the worker only when the last owner drops its ref.
 *
 * Polling is **not** owned here. Only a resumed [MainActivity] may call
 * [St2GattClient.setPollingEnabled]. A widget service must leave polling off.
 *
 * The widget validates permissions, the bonded selection and the confirmed endpoint
 * before starting its foreground service. Missing setup opens [MainActivity] directly
 * through an activity PendingIntent, not a background activity-launch trampoline.
 *
 * The service acquires this client, connects without scan fallback and waits for the
 * individual SET write to finish. Its attempt has a timeout and it releases its ref
 * when destroyed. It never changes polling or the activity's selected device.
 *
 * If Activity is still resumed it may have polling on; that is fine. If Activity
 * [release]s while the service still holds a ref, the GATT client stays alive.
 */
object St2Session {
    private var client: St2GattClient? = null
    private var refs: Int = 0

    @Synchronized
    fun acquire(context: Context): St2GattClient {
        val existing = client
        if (existing != null) {
            refs += 1
            return existing
        }
        val created = St2GattClient(context.applicationContext)
        client = created
        refs = 1
        return created
    }

    /**
     * Drop one owner. No-op if nothing is held (duplicate [android.app.Activity.onDestroy]
     * or a service timeout after a previous release).
     */
    @Synchronized
    fun release() {
        if (refs <= 0) return
        refs -= 1
        if (refs == 0) {
            client?.release()
            client = null
        }
    }

    @Synchronized
    fun clientOrNull(): St2GattClient? = client
}
