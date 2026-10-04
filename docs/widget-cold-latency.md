# Cold widget command timing

Measured on 2026-10-03 with the Xiaomi 21081111RG, Android 14, and Space Travel 2.
Each trial used a fresh BLE control connection after the previous widget service
released its session. Bluetooth audio remained connected.

The baseline was committed HEAD with timing instrumentation only, built in an
isolated temporary directory. Both versions used the same Android debug signing
key and retained the phone's pairing, selected device, and confirmed BLE endpoint.

Times below are milliseconds from entering `openGatt`, not from the launcher tap.
Write completion is Android's successful GATT write callback. It measures command
delivery, not the moment the audio processing changes.

| Version and requested mode | BLE connected | Services discovered | Mode write started | Mode write completed |
| --- | ---: | ---: | ---: | ---: |
| Baseline, ANC | 1070 | 1863 | 2409 | 2491 |
| Baseline, Transparency | 908 | 1935 | 2411 | 2521 |
| Optimized, ANC | 1228 | 2341 | 2342 | 2371 |
| Optimized, Transparency | 1000 | 2013 | 2014 | 2047 |

The baseline waited 476–546 ms between service discovery and starting the mode
write. The optimized path started within 1 ms of discovery, before either CCCD
subscription. This removes actual pre-command work rather than just changing
the widget's completion display. Connection and discovery variation still
dominates total latency; these four trials do not establish a stable end-to-end
percentage improvement or a subsecond cold-connect guarantee.

After the optimized Transparency widget command completed and its connection
closed, a fresh normal app connection read Transparency from the firmware.
The accepted mode report arrived 2468 ms after that verification connection
started. This confirms that the earbuds applied the command sent before
notification setup. Audible transition timing was not measured.

The ordinary app connection still subscribes before its initial mode read.
Existing 100 ms write gaps remain. The widget still waits for notification
readiness and its battery refresh before releasing the session. Debuggable builds
emit the `st2-latency` tag for connection, discovery, mode write, and accepted mode
reports without logging Bluetooth addresses.

Validation passed with 78 unit tests, Android lint, and a debug APK build. The
optimized APK was installed on the Xiaomi after the baseline measurements.
