# Space Travel 2 notification protocol research

Research date: 2026-10-01. Source is the official Moondrop app's bundled
Qualcomm SDK, decompiled locally from the APK identified in
[battery-protocol.md](battery-protocol.md). No device commands were sent during
the initial research. SDK support is confirmed. Live tests on 2026-10-02
received successful subscription responses but no unsolicited notifications;
details and limitations are recorded below.

## Registration and transport

GAIA notifications require both the existing BLE notification CCCDs and a
GAIA feature subscription. Enabling the CCCDs alone does not request feature
events. Register each feature through BASIC feature `0`, command `7`, with one
feature-ID byte. Frames use MoonMode's existing BLE transport and vendor `001D`.

| Operation | Hex frame |
|---|---|
| Subscribe to audio-curation events | `00 1D 00 07 08` |
| Subscribe to battery events | `00 1D 00 07 0D` |
| Registration response | `00 1D 01 07 <data>` |
| Registration error | `00 1D 01 87 <error status>` |
| Cancel audio-curation subscription | `00 1D 00 08 08` |
| Cancel battery subscription | `00 1D 00 08 0D` |

`V3BasicPlugin.registerNotification` sends command `7` with a two-second SDK
timeout. `getFeatureFromNotificationPackets` reads the returned feature ID
from response data for BASIC version 2 or newer when a byte is present. It uses
the original request payload otherwise. A registration response is a GAIA
response, not a GATT write acknowledgement. `cancelNotification` sends command
`8` with the same feature-ID payload.

`QTILV3Vendor.startPlugin` adds and starts each discovered supported plugin,
then calls `basicPlugin.registerNotification` for that feature. On a successful
registration, `lambda$startPlugin$0` publishes the feature as supported. On
failure, `lambda$startPlugin$1` logs the failure. Plugin startup therefore
requests subscriptions automatically in the official SDK. The individual
battery and audio-curation `onStarted` methods register their local publishers;
they do not issue the GAIA registration commands themselves.

## ANC mode changes

MoonMode uses AUDIO_CURATION feature `8`. This is distinct from the SDK's older
ANC feature `2` and ANC_V2 feature `32`.

| Report | Hex frame | SDK command |
|---|---|---|
| Current-mode read request | `00 1D 10 03` | `3` |
| Current-mode read response | `00 1D 11 03 <mode data>` | `3` |
| Unsolicited mode change | `00 1D 10 81 <mode data>` | `1` |
| Toggle-configuration notification | `00 1D 10 83 <data>` | `3` |
| Scenario-configuration notification | `00 1D 10 84 <data>` | `4` |

`V3AudioCurationPlugin.NOTIFICATIONS.V1_MODE_CHANGE` is `1`.
`onNotification` command `1` and `onResponse` command `3` both call
`publishCurrentMode`, which constructs `data.audiocuration.Mode` from the
entire payload. `NotificationPacket.getData` returns the payload unchanged.
There is no leading success-status byte.

`Mode` reads:

| Payload offset | Meaning |
|---|---|
| `0` | Current mode value |
| `1` | Feature type |
| `2` | Adaptation-control support |
| `3` | Gain-control support |
| `4` | Howling-control support, audio-curation version 5 or newer |

The same parser handles read responses and mode-change notifications. The
first-byte mapping already captured for this device, `0` Off, `1` ANC, `2`
Transparency, is therefore the appropriate candidate for notification decoding.
The SDK does not define a Space Travel 2 mode enum or prove additional mappings.
Do not substitute SET codes `1`, `2`, `3`, `4` for read/report values. Confirm the
notification values with a capture before claiming hardware verification.

The existing code originally named `0x1083` a GET notification and `0x1084` a SET
notification. Those names do not match the SDK. The SDK routes `0x1083` through
`publishCurrentMode` despite defining it as toggle configuration, but this is
not evidence that the device sends its physical mode changes under that ID.
The explicit mode-change event is `0x1081`. `0x1084` is scenario configuration
and should not be treated as a SET acknowledgement.

## Battery changes

`V3BatteryPlugin.onNotification` accepts notification command `0` for supported
batteries and `1` for battery levels. Both use the same parsers as their read
responses.

| Report | Hex frame |
|---|---|
| Supported batteries changed | `00 1D 1A 80 <battery IDs>` |
| Battery levels changed | `00 1D 1A 81 <ID level pairs>` |

IDs, unknown levels, and read commands are documented in
[battery-protocol.md](battery-protocol.md). A subscription may report only a
subset of batteries; the SDK pair format does not require a complete snapshot.
The SDK defines no charging bit in the battery level pairs.

## Bounded live probe

This probe reads state and registers reports. It does not send ANC SET commands,
change touch configuration, or hold a permanent background connection.

1. Use one MoonMode BLE session with both existing notification CCCDs enabled.
   Keep the official app closed so it does not compete for the control session.
2. Read current mode and battery once and record the full incoming frame and
   characteristic UUID. Record firmware and audio-curation/BASIC feature
   versions when available.
3. Register feature `08` and `0D`, allowing at most two seconds per registration.
   Record the GAIA responses or errors. Do not count write callbacks as success.
4. Disable periodic mode reads for the observation window, retaining raw receive
   logging. Ask the user to hold an earbud to change mode twice. Observe for at
   most 60 seconds and look specifically for `0x1081`. Compare its first byte
   with one subsequent current-mode read.
5. For battery events, observe any natural change during that same window.
   A later user-controlled one-bud case transition can test side availability.
   No battery event within 60 seconds does not disprove support because battery
   values may not have changed.
6. Cancel the subscriptions or close the session. Record registration and event
   support separately. An accepted registration alone does not prove events.

Push reports can update app and widget state while a control session is alive.
They cannot arrive after MoonMode disconnects BLE. Reliable immediate updates
with the app closed would also need a deliberate connection-lifetime policy.
The scheduled 15-minute reads remain useful when no session is held.

## Xiaomi live probe, 2026-10-02

The retained [probe log excerpt](notification-probe-2026-10-02.log) records
the end of the second run, including its final reads and cancellation attempts.
Its timestamps are the phone's logcat clock. The observations below summarize
both runs; the excerpt does not contain their full traffic.

Two separate 60-second observation windows used the selected, previously
confirmed BLE endpoint on Xiaomi 21081111RG, Android 14. Both response and data
CCCDs were enabled. The debug-only probe made baseline reads, subscribed to
features `08` and `0D`, stopped sending commands for 60 seconds, read again,
attempted cancellation, and closed the connection. It never sent mode SETs.
The ordinary app polling loop was not active, and widget refresh was excluded
while the probe held its connection.

Both registrations returned `00 1D 01 07` with no payload in each run.
These are successful GAIA responses, not just GATT write callbacks, but they
do not establish that this firmware actually sends events.

| Check | First run | Second run |
|---|---|---|
| Initial mode read | Off, `00 1D 11 03 00 01 00 00` | Off, same frame |
| Physical changes | User reported Transparency, ANC, Off | User confirmed Off to Transparency, left there |
| Final mode read | Off, same frame | Transparency, `00 1D 11 03 02 01 00 00` |
| Unsolicited packets during observation | None on either characteristic | None on either characteristic |
| Battery before and after | `00 1D 1B 01 01 00 02 3C` | Same frame |

The second run establishes that the probed endpoint observes the changed mode
through GET, despite sending no mode-change notification during observation.
No `0x1081`, `0x1083`, or other unsolicited frame appeared. Push mode updates
are therefore **not demonstrated with this subscription sequence**. This does
not prove that notifications are impossible: an additional initialization step,
a different event registration mechanism, or firmware differences could matter.
The next useful investigation is an official-app Bluetooth trace to compare its
initialization and physical-change traffic with this sequence.

Battery values did not change, so the absence of `0x1A81` is inconclusive.
Left reported zero and right reported 60%; zero does not establish whether the
left bud was empty or unavailable. This experiment does not verify battery
subscriptions or side-availability events.

The BASIC supported-feature query `00 1D 00 01` timed out in both runs, so
firmware and feature versions were not established. Cancellation commands
`00 1D 00 08 08` and `00 1D 00 08 0D` also received no responses within two
seconds each. Closing GATT ended each test regardless of cancellation support.

The probe was a temporary diagnostic tool and is not included in the app.
Production subscriptions and the widget's 15-minute refresh policy remain unchanged.
