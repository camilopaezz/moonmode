# RFCOMM widget transport

Measured on 2026-11-22 with the Xiaomi 21081111RG, Android 17, and Space Travel 2
connected for audio. Times are from the debuggable build's `st2-latency` log tag.

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
than 1.5 seconds while the transition played. Waiting for GET confirmation
timed out, the BLE fallback repeated the command, and the earbuds played the ANC
prompt twice. A completed RFCOMM write is therefore the success signal, matching
the BLE write callback. The widget falls back to BLE only when the socket cannot
connect or the write fails, so nothing is sent twice. The battery read that
follows travels on the same in-order channel.

## Widget timing

| Path | Mode | Socket or BLE connected | Command written | Command total |
|---|---|---:|---:|---:|
| RFCOMM | Transparency | 731 ms | 733 ms | 737 ms |
| RFCOMM | Off | 539 ms | 541 ms | 544 ms |
| RFCOMM | ANC | 409 ms | 411 ms | 412 ms |
| BLE fallback | ANC | 138 ms | 1825 ms | 4311 ms |

RFCOMM rows start at the A2DP connection check. That check plus the socket
connect is the whole cost. BLE rows start at `openGatt`, and their total
includes notification setup and the battery read. A read-only GET after the ANC
run reported ANC.

## App

The app's mode buttons use the same sender when no BLE session is ready. Both
the app and the widget share one socket, serialized by a lock, and close it when
the widget service finishes or the activity pauses. In a check on the same phone,
an app tap opened the socket in 709 ms, a second tap reused it, and a later BLE
connection read the mode the app had sent.

A GET made up to 1.5 seconds or more after an RFCOMM SET still reports the previous
mode, so a BLE reading taken right after a direct change can briefly lag.

## Not covered

- Earbuds that are not connected for audio use the BLE path.
- A ready BLE session, such as a resumed app, keeps using BLE.
- Audible transition timing was not measured.
