package dev.camilo.st2mode.ble

enum class AncMode(val setCode: Int, val label: String) {
    Off(1, "Off"),
    Anc(2, "ANC"),
    Wind(3, "Wind"),
    Transparency(4, "Transparency");

    companion object {
        /**
         * ST2 GET/notify payload byte to the UI enum.
         * Never feed this value into SET — use [setCode] instead.
         *
         * Captured GET `001d1103XX010000`: 0 Off, 1 ANC, 2 Transparency.
         * Unknown bytes are ignored. SET codes 1/2/4 are a different table.
         */
        fun fromGetPayload(byte: Int): AncMode? =
            when (byte and 0xFF) {
                0 -> Off
                1 -> Anc
                2 -> Transparency
                else -> null
            }
    }
}
