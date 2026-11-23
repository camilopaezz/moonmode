package dev.camilo.st2mode.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaiaClassicTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun framesMatchBytesAcceptedByTheEarbuds() {
        assertArrayEquals(bytes("FF 01 00 00 00 1D 10 03"), GaiaClassic.frame(Gaia.getCurrentMode()))
        assertArrayEquals(
            bytes("FF 01 00 01 00 1D 10 04 02"),
            GaiaClassic.frame(Gaia.setMode(AncMode.Anc.setCode)),
        )
    }

    @Test
    fun capturedModeReplyDecodesWithTheBleDecoder() {
        val packets = GaiaClassic.Reader().feed(bytes("FF 04 00 04 00 1D 11 03 01 01 00 00"))
        assertEquals(1, packets.size)
        assertEquals(AncMode.Anc, decodeModeReport(packets[0]))
    }

    @Test
    fun reassemblesSplitAndJoinedFrames() {
        val reader = GaiaClassic.Reader()
        assertTrue(reader.feed(bytes("FF 04 00 04 00 1D")).isEmpty())
        val packets = reader.feed(bytes("11 03 00 01 00 00 FF 04 00 04 00 1D 1B 01 01 50 02 46"))
        assertEquals(2, packets.size)
        assertEquals(AncMode.Off, decodeModeReport(packets[0]))
        val battery = decodeBatteryReport(packets[1], measuredAt = 0L)
        assertEquals(80, battery?.left)
        assertEquals(70, battery?.right)
    }

    @Test
    fun skipsNoiseAndChecksumAndHandlesExtendedLength() {
        val reader = GaiaClassic.Reader()
        // Leading noise, then a checksummed frame (flags 1), then a v4 extended-length frame (flags 2).
        val packets = reader.feed(
            bytes("00 12 FF 04 01 01 00 1D 11 03 02 AA FF 04 02 00 01 00 1D 11 03 01"),
        )
        assertEquals(2, packets.size)
        assertEquals(AncMode.Transparency, decodeModeReport(packets[0]))
        assertEquals(AncMode.Anc, decodeModeReport(packets[1]))
    }
}
