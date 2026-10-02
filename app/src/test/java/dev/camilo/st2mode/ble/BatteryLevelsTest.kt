package dev.camilo.st2mode.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLevelsTest {
    @Test
    fun queryMatchesOfficialSdkAndReportsAreIdentifiedById() {
        assertArrayEquals(byteArrayOf(0, 0x1D, 0x1A, 1, 1, 2), Gaia.getBatteryLevels())
        val report = Gaia.frame(Gaia.GET_BATTERY_LEVELS_RESPONSE, byteArrayOf(2, 70, 1, 80))
        assertEquals(BatteryLevels(80, 70, 123), decodeBatteryReport(report, 123))
        assertNull(decodeModeReport(report))
    }

    @Test
    fun oneBudAndUnknownLevelsRemainDistinctFromZeroPercent() {
        fun report(vararg bytes: Int) = decodeBatteryReport(
            Gaia.frame(Gaia.GET_BATTERY_LEVELS_NOTIFICATION, bytes.map { it.toByte() }.toByteArray()), 123,
        )
        assertEquals(BatteryLevels(0, null, 123), report(1, 0))
        assertEquals(BatteryLevels(null, 80, 123), report(1, 255, 2, 80))
        assertEquals(BatteryLevels(null, null, 123), report(1, 255, 2, 255))
        assertEquals(BatteryLevels(80, null, 123), report(3, 50, 1, 80))
        assertNull(report(3, 50))
    }

    @Test
    fun malformedReportsErrorsAndUnrelatedPacketsDoNotChangeBattery() {
        assertNull(decodeBatteryReport(byteArrayOf(0, 0x1D, 0x1B), 123))
        for (payload in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(1, 101), byteArrayOf(1, 50, 1, 60))) {
            assertNull(decodeBatteryReport(Gaia.frame(Gaia.GET_BATTERY_LEVELS_RESPONSE, payload), 123))
        }
        assertNull(decodeBatteryReport(Gaia.frame(Gaia.GET_BATTERY_LEVELS_ERROR, byteArrayOf(1, 50)), 123))
        assertNull(decodeBatteryReport(Gaia.frame(Gaia.GET_CURRENT_MODE_RESPONSE, byteArrayOf(1, 50)), 123))
        assertNull(decodeBatteryReport(byteArrayOf(0, 0, 0x1B, 1, 1, 50), 123))
    }

    @Test
    fun modeChangesDropOnlyAncReadsAndKeepBatteryQuery() {
        val writes = ModeWrites()
        writes.enqueue(GattOp.WriteCommand(Gaia.getCurrentMode(), sending = false))
        val battery = GattOp.WriteCommand(Gaia.getBatteryLevels(), sending = false, kind = CommandKind.BATTERY_GET)
        writes.enqueue(battery)
        assertFalse(battery.isGet)
        assertFalse(battery.isSet)
        assertTrue(writes.dropQueuedGets())
        assertEquals(battery, writes.startNext())
        assertNull(writes.finish(true))
        assertNull(writes.startNext())
    }
}
