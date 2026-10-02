package dev.camilo.st2mode

import org.junit.Assert.*
import org.junit.Test

class SessionOwnershipTest {
    @Test fun pressingHomeAllowsRefreshWithoutDestroyingTheClient() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        session.activityResumed()
        assertFalse(session.canRefresh())
        session.activityPaused()
        assertEquals(1, session.references)
        assertTrue(session.canRefresh())
        session.acquire(SessionOwner.WidgetRefresh)
        assertFalse(session.canRefresh())
        session.release(SessionOwner.WidgetRefresh)
        assertEquals(1, session.references)
        assertTrue(session.canRefresh())
    }

    @Test fun queuedCleanupCannotDisconnectAWidgetCommandThatTakesOver() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        session.acquire(SessionOwner.WidgetRefresh)
        var connected = true
        val queuedCleanup = { session.runRefreshCleanupIfIdle { connected = false } }
        session.release(SessionOwner.WidgetRefresh)
        session.acquire(SessionOwner.WidgetCommand)
        queuedCleanup()
        assertTrue(connected)
    }

    @Test fun queuedCleanupCannotDisconnectAnActivityThatResumes() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        session.acquire(SessionOwner.WidgetRefresh)
        var connected = true
        val queuedCleanup = { session.runRefreshCleanupIfIdle { connected = false } }
        session.release(SessionOwner.WidgetRefresh)
        session.activityResumed()
        queuedCleanup()
        assertTrue(connected)
    }

    @Test fun refreshClosesItsConnectionWhilePausedActivityKeepsTheClient() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        session.acquire(SessionOwner.WidgetRefresh)
        var connected = true
        session.release(SessionOwner.WidgetRefresh)
        session.runRefreshCleanupIfIdle { connected = false }
        assertFalse(connected)
        assertEquals(1, session.references)
    }

    @Test fun foregroundUseTakesPriorityAfterRefreshAcquiresTheClient() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        session.acquire(SessionOwner.WidgetRefresh)
        assertTrue(session.canContinueRefresh())
        session.activityResumed()
        assertFalse(session.canContinueRefresh())
        session.activityPaused()
        assertTrue(session.canContinueRefresh())
        session.acquire(SessionOwner.WidgetCommand)
        assertFalse(session.canContinueRefresh())
        session.release(SessionOwner.WidgetCommand)
        assertTrue(session.canContinueRefresh())
    }

    @Test fun widgetCommandsBlockRefreshUntilTheirOwnerReleases() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        session.acquire(SessionOwner.WidgetCommand)
        assertFalse(session.canRefresh())
        session.release(SessionOwner.WidgetCommand)
        assertTrue(session.canRefresh())
    }

    @Test fun recreatedActivityWithNewClientMayAutoConnectAgain() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        assertTrue(session.claimAutoConnect(true, true, true))
        assertFalse(session.claimAutoConnect(true, true, true))
        session.release(SessionOwner.Activity)
        session.acquire(SessionOwner.Activity)
        assertTrue(session.claimAutoConnect(true, true, true))
    }

    @Test fun recreatedActivityDoesNotRepeatAutoConnectWhenAnotherOwnerKeptTheClient() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        session.acquire(SessionOwner.WidgetCommand)
        assertTrue(session.claimAutoConnect(true, true, true))
        session.release(SessionOwner.Activity)
        session.acquire(SessionOwner.Activity)
        assertFalse(session.claimAutoConnect(true, true, true))
    }

    @Test fun missingSetupDoesNotConsumeTheLaunchAttempt() {
        val session = SessionOwnership()
        session.acquire(SessionOwner.Activity)
        assertFalse(session.claimAutoConnect(false, true, true))
        assertFalse(session.claimAutoConnect(true, false, true))
        assertFalse(session.claimAutoConnect(true, true, false))
        assertTrue(session.claimAutoConnect(true, true, true))
    }
}
