# RFCOMM transport

MoonMode's control session uses GAIA over RFCOMM on the earbuds' paired Classic
Bluetooth address, with BLE as the fallback. This page records the protocol and
the device measurements behind that choice.

Device: Xiaomi 21081111RG, Android 17, Space Travel 2 firmware 1.6.0, connected
for audio. Times come from the debuggable build's `st2-latency` and `st2-probe`
log tags.

## Protocol

The official Moondrop app (version `2.27.3c-261120ai`, code `100040`) includes a
Qualcomm GAIA SPP transport. It selects the standard SPP UUID
`00001101-0000-1000-8000-00805f9b34fb` when SDP lists it, then the Qualcomm GAIA
UUID `00001107-D102-11E1-9B23-00025B00A5A5`. Space Travel 2 lists standard SPP
and refuses a connection to the GAIA UUID.

RFCOMM frames wrap the same vendor, command, and payload bytes as BLE:

```text
[FF][version][flags][payload length][vendor 2][command 2][payload][checksum if flags & 1]
```

Flag `2` with version 4 or later means a 2-byte length. MoonMode sends version
`01` with no flags. The earbuds reply with version `04`.

| Exchange | Bytes |
|---|---|
| GET mode | `FF 01 00 00 00 1D 10 03` |
| GET reply, Off | `FF 04 00 04 00 1D 11 03 00 01 00 00` |
| SET ANC | `FF 01 00 01 00 1D 10 04 02` |

SET sends no reply. After a SET, GET kept reporting the previous mode for more
than 1.5 seconds while the transition played. A completed RFCOMM write is
therefore the success signal, matching the BLE write callback, and the shared
`ModePoll` waits 2 seconds after a SET before trusting GET again.

## How the session uses it

`St2GattClient.connect()` checks that the selected earbuds are connected for A2DP,
then opens the SPP socket. The session is ready as soon as the socket opens:
there is no service discovery or notification subscription. A widget command
registered before connecting is written first, then the initial GET and battery
read. Polling, battery reads, and the 100 ms write gap are the same code as BLE.

BLE is used when the earbuds are not connected for audio, or the socket cannot
open within 6 seconds. It uses the confirmed endpoint, or endpoint discovery when
the caller allows a scan. The widget and background refresh never scan.

If the socket closes or a write fails, the session reports **Connection lost**.
SETs that had not reached the earbuds move to the confirmed BLE endpoint when
there is one. A SET whose write had started is never repeated: when the socket
closes mid-write, or a write blocks for 3 seconds, the bytes may already have
arrived.

The widget command service and refresh worker share the app's client through
`St2Session`, so the app, widget, and refresh never open a second socket.

## Long session, 2026-11-27

One socket, held by the debug-only `RfcommProbeService`
(`app/src/debug/.../RfcommProbe.kt`; its header lists the plan steps), with the
app closed:

| Phase | Result |
|---|---|
| Connect | 890 ms (400–450 ms in shorter runs the same night) |
| Register mode and battery events (`00 1D 00 07 08`, `0D`) | `00 1D 01 07` for both |
| 10 minutes, GET mode every 2 s, battery every 30 s | 321 requests, 321 replies; median 65 ms, p95 79 ms, max 527 ms |
| 5 minutes idle, socket open, then GET and battery | Both answered; first reply 740 ms, then 30 ms |
| 10 minutes idle, then GET and battery | Both answered; first reply 509 ms, then 79 ms |
| Socket lifetime | 25 min 12 s, closed by the probe, never by the earbuds |

The first reply after an idle gap is slower, likely the link leaving a low-power
state; it stays well inside the 1.5-second GET timeout.

The socket survived the polling a resumed app does. During the polling phase
the mode was changed from the earbuds five times (ANC, Off, Transparency, ANC,
Off). Each change appeared in the next GET, within 2 seconds. The earbuds sent no
unsolicited frame, even with events registered, matching the earlier
[BLE notification probe](notification-protocol.md). RFCOMM sessions therefore
poll like BLE sessions.

## Official Moondrop app

The earbuds serve one SPP connection at a time.

| Situation | Result |
|---|---|
| MoonMode connected, Moondrop app opened | Moondrop connects; MoonMode's socket breaks (`Broken pipe`) within seconds |
| Moondrop open in the foreground, MoonMode connects | MoonMode's connect fails in 0.3–0.5 s (3 of 3 tries); Moondrop's socket drops and it reconnects about 1.3 s later |
| Moondrop sent to the background for 30 s, MoonMode connects | Connect still fails in about 1 s |
| Moondrop force-stopped, MoonMode connects | Connects in 447 ms and polls normally |

A fast connect failure leaves time for the BLE fallback, so MoonMode still works
while Moondrop holds the channel. Each attempt briefly interrupts Moondrop. An
app session that Moondrop takes over shows **Connection lost** and stays
disconnected until the user reconnects; MoonMode never retries RFCOMM on its own,
so the two apps do not fight over the channel.

## Widget timing, 2026-11-22

Measured before the session change, when only the widget used RFCOMM. RFCOMM rows
start at the A2DP connection check. That check plus the socket connect is the
whole cost. BLE rows start at `openGatt`, and their total includes notification
setup and the battery read.

| Path | Mode | Socket or BLE connected | Command written | Command total |
|---|---|---:|---:|---:|
| RFCOMM | Transparency | 731 ms | 733 ms | 737 ms |
| RFCOMM | Off | 539 ms | 541 ms | 544 ms |
| RFCOMM | ANC | 409 ms | 411 ms | 412 ms |
| BLE fallback | ANC | 138 ms | 1825 ms | 4311 ms |

## Shared-session timing, 2026-11-27

Widget commands after the session change, fired through the debug
`WidgetCommandProbeActivity`, which starts the same command service as a widget
tap. Times are from `connect-start` (after the service has started and checked
the selection), with the phone locked and dozing.

| Session | Mode | RFCOMM connected | Command written |
|---|---|---:|---:|
| Cold, fresh process | Transparency | 1024 ms | 1029 ms |
| Cold | Transparency | 539 ms | 542 ms |
| Cold | Off | 487 ms | 488 ms |
| App session already open | Off | — | 85 ms after the tap reached the service |
| App session already open | ANC | — | 164 ms after the tap reached the service |

A probe read after each run returned the requested mode. After a cold command
the service released the socket, and the next connection opened normally.

## App and fallback, 2026-11-27

The debug build, driven through its UI with the phone unlocked:

| Step | Result |
|---|---|
| Connect, Moondrop closed | RFCOMM connected in 1020 ms; first GET 190 ms later; polling every 2 s |
| ANC, Transparency, Off buttons over RFCOMM | Each write completed within 1 ms of starting; the next poll, about 2 s later, reported the new mode |
| Moondrop opened while connected | Moondrop took the channel; the session logged `rfcomm-lost` and the app showed **Connection lost** with Retry |
| Retry, Moondrop still open | RFCOMM failed in 750 ms; BLE endpoint discovery offered the earbuds; after the endpoint was chosen, GATT connected in 221 ms and polled |
| ANC, Off buttons over BLE | Writes completed in about 70 ms; polling confirmed both |
| Widget, Moondrop in the foreground, app process stopped | RFCOMM failed in 600–870 ms, then the confirmed endpoint; command written 2.9–4.0 s after the tap. A later RFCOMM read returned the requested mode |
| Widget, Moondrop process alive in the background | RFCOMM connected in 430–650 ms: Moondrop had released the channel |
| Widget, app session open over BLE | Reused the session; command written 345 ms after the tap |
| Cancel during a BLE connect, then Connect and Disconnect while GATT was still closing | RFCOMM connected 500 ms after GATT `disconnect()`; Disconnect closed it (a probe then got the channel); GATT `close()` still ran at its 2 s timeout, and the next BLE connection worked |

## Not covered

- Moving an unsent SET from a failed RFCOMM write to BLE is covered only by unit
  tests; a mid-write socket failure could not be forced on demand.
- Audible transition timing was not measured.
- Earbuds that are not connected for audio use BLE.
- Behaviour with other phones, or other Space Travel 2 firmware, is untested.
