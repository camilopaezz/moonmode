package dev.camilo.st2mode

/** Owners keep the client alive; only resumed activities and widget commands block refresh. */
internal class SessionOwnership {
    private val owners = mutableMapOf<SessionOwner, Int>()
    private var foregroundActivities = 0
    private var autoConnectAttempted = false
    val references: Int get() = owners.values.sum()

    fun acquire(owner: SessionOwner) {
        owners[owner] = (owners[owner] ?: 0) + 1
    }

    fun release(owner: SessionOwner) {
        val count = owners[owner] ?: return
        if (count <= 1) owners.remove(owner) else owners[owner] = count - 1
        if (references == 0) autoConnectAttempted = false
    }

    fun activityResumed() { foregroundActivities++ }
    fun activityPaused() { foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0) }

    fun canContinueRefresh(): Boolean = foregroundActivities == 0 &&
        (owners[SessionOwner.WidgetCommand] ?: 0) == 0

    fun runRefreshCleanupIfIdle(close: () -> Unit) {
        if (canContinueRefresh()) close()
    }

    fun canRefresh(): Boolean = canContinueRefresh() &&
        (owners[SessionOwner.WidgetRefresh] ?: 0) == 0

    fun claimAutoConnect(enabled: Boolean, hasPermissions: Boolean, hasSelection: Boolean): Boolean {
        if (!enabled || !hasPermissions || !hasSelection || autoConnectAttempted) return false
        autoConnectAttempted = true
        return true
    }
}

enum class SessionOwner { Activity, WidgetCommand, WidgetRefresh }
