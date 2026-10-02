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
    private val ownership = SessionOwnership()

    @Synchronized
    fun acquire(context: Context, owner: SessionOwner = SessionOwner.Activity): St2GattClient {
        val shared = client ?: St2GattClient(context.applicationContext).also { client = it }
        ownership.acquire(owner)
        return shared
    }

    /** Resumed activities and user commands take priority; a paused activity may share its client. */
    @Synchronized
    fun acquireRefreshOrNull(context: Context): St2GattClient? =
        if (ownership.canRefresh()) acquire(context, SessionOwner.WidgetRefresh) else null

    @Synchronized
    fun canContinueRefresh(): Boolean = ownership.canContinueRefresh()

    /** Runs on the GATT worker; ownership cannot change between the check and close. */
    @Synchronized
    fun runRefreshCleanupIfIdle(shared: St2GattClient, close: () -> Unit) {
        if (client === shared) ownership.runRefreshCleanupIfIdle(close)
    }

    @Synchronized
    fun activityResumed() { ownership.activityResumed() }

    @Synchronized
    fun activityPaused() { ownership.activityPaused() }

    /** The launch attempt belongs to this client, including any owners keeping it alive. */
    @Synchronized
    fun connectOnOpenIfNeeded(enabled: Boolean) {
        val shared = client ?: return
        if (ownership.claimAutoConnect(enabled, shared.hasPermissions(), shared.state.value.selectedAddress != null)) {
            shared.connect()
        }
    }

    @Synchronized
    fun release(owner: SessionOwner = SessionOwner.Activity) {
        ownership.release(owner)
        if (ownership.references == 0) {
            client?.release()
            client = null
        }
    }

    @Synchronized
    fun clientOrNull(): St2GattClient? = client
}
