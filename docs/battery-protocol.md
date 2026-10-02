# Space Travel 2 battery protocol research

Research date: 2026-10-01.

The battery protocol implemented by the official Moondrop app is GAIA V3,
feature `0x0D`. It fits MoonMode's existing GAIA BLE transport. The command and
payload definitions below are confirmed from the official app's bundled SDK.
The first research pass inspected the SDK without sending battery commands.
During widget implementation, live queries on the Xiaomi 21081111RG returned
left and right levels of 100%. A subsequent query returned left 0% and right
100% with one bud disconnected, as confirmed by the user. The widget therefore
hides sides reporting zero. Raw GAIA frames were not captured in that check.

## Evidence

The official app installed on the attached phone was inspected locally:

- Package: `com.moondroplab.moondrop.moondrop_app`
- Version: `2.26.0c-260916ai`, version code `100035`
- Base APK SHA-256:
  `96c2054f14b234269b7af2047e94a9ecc3673c2511bc7cb439e236fbb394e45c`
- JADX version: `1.5.6`

Relevant classes under `com.qualcomm.qti.gaiaclient`:

| Class below that package | Evidence |
|---|---|
| `core.gaia.qtil.data.QTILFeature` | `BATTERY = 13` |
| `core.gaia.qtil.plugins.v3.V3BatteryPlugin` | Supported-battery command `0`; battery-level command `1`; request payload is a list of battery IDs; responses and notifications use the same data parsers |
| `core.gaia.qtil.data.battery.Battery` | Battery ID meanings |
| `core.gaia.qtil.data.battery.SupportedBatteries` | Supported batteries are individual ID bytes |
| `core.gaia.qtil.data.battery.BatteryLevel` | Response consists of ID/level pairs; `LEVEL_UNKNOWN = 255` |
| `core.gaia.core.v3.packets.V3Command` | Command bit layout |
| `core.gaia.core.v3.packets.ResponsePacket` | Data is the entire payload, with no leading success-status byte |
| `core.gaia.core.v3.packets.V3PacketType` | Command `0`, notification `1`, response `2`, error `3` |
| `core.gaia.qtil.plugins.v3.V3BasicPlugin` | Notification registration uses BASIC command `7`, payload is the feature ID |

Moondrop's `native.handlers.DeviceInfoHandler` first asks for supported batteries
when they are not cached, then requests levels for that set. It forwards separate
single-device, left, right, and case values to the app.

The public [FxxkMoondrop adaptation notes](https://github.com/bqj6666/FxxkMoondrop/blob/7fab965ed81a99c23be41f8c9e33657f854d4677/ADAPTATION.md)
report Space Travel 2 device testing and describe GAIA battery queries.
Its [request implementation](https://github.com/bqj6666/FxxkMoondrop/blob/7fab965ed81a99c23be41f8c9e33657f854d4677/src/com/fxxkmoondrop/secret/GaiaBleClient.kt)
requests IDs `1` and `2`.
Its [battery parser](https://github.com/bqj6666/FxxkMoondrop/blob/7fab965ed81a99c23be41f8c9e33657f854d4677/src/com/fxxkmoondrop/secret/GaiaPacketHandler.kt)
reads ID/level pairs, matching the official SDK. The adaptation notes' statement
about each byte being a percentage is imprecise; use the pair format.
The notes' explicit battery example is for Golden Ages 2, so it is not a battery
capture proving support on our Space Travel 2 firmware.

## Transport and framing

Use the same endpoint and characteristics as ANC:

| Purpose | UUID |
|---|---|
| Service | `00001100-d102-11e1-9b23-00025b00a5a5` |
| Command write | `00001101-d102-11e1-9b23-00025b00a5a5` |
| Response notifications | `00001102-d102-11e1-9b23-00025b00a5a5` |
| Data notifications | `00001103-d102-11e1-9b23-00025b00a5a5` |

MoonMode already enables the response and data CCCDs. BLE frames have no Classic
Bluetooth `FF` header:

```text
[vendor: 2 bytes, big endian][commandValue: 2 bytes, big endian][payload...]
vendor = 0x001D
commandValue = (feature << 9) | (type << 7) | command
```

## Commands

All bytes below are hexadecimal. Examples are constructed from SDK definitions,
not captured from the earbuds.

| Operation | Frame |
|---|---|
| Discover supported batteries | `00 1D 1A 00` |
| Supported batteries response | `00 1D 1B 00 <IDs...>` |
| Read left and right levels | `00 1D 1A 01 01 02` |
| Battery levels response | `00 1D 1B 01 <ID level pairs...>` |
| Battery levels notification | `00 1D 1A 81 <ID level pairs...>` |
| Register battery notifications | `00 1D 00 07 0D` |
| Battery-level error | `00 1D 1B 81 <error status...>` |

Battery IDs:

| ID | Meaning |
|---|---|
| `00` | Single device |
| `01` | Left earbud |
| `02` | Right earbud |
| `03` | Charging case |

For example, supported-battery payload `01 02` means left and right are
supported. Query just the IDs actually reported. Do not assume the case is
available because the SDK defines an ID for it.

Example level response:

```text
00 1D 1B 01 01 50 02 46
            |-----| |-----|
            left 80 right 70
```

The level payload has no count byte and no leading success byte. Each pair
identifies its component, so the parser must not depend on pair order.
Treat `FF` as unknown, not 255%. For MoonMode, accept percentages `0..100`,
preserve explicit unknowns separately, and reject malformed odd-length payloads
and out-of-range levels. Ignore unknown IDs without guessing a component.

Errors use packet type `3`; they are not percentage reports. SDK error statuses
include feature unsupported `00`, command unsupported `01`, and invalid parameter
`05`. A GATT write acknowledgement only confirms delivery. It does not confirm
that the firmware supports the battery query.

## Device validation procedure

1. Connect the earbuds through MoonMode's already confirmed GAIA endpoint. Enable
   both existing notification characteristics before sending the query.
2. Send `00 1D 1A 00` and record the full response and characteristic UUID.
3. Request levels for the reported IDs. If discovery is unsupported or times out,
   make one bounded left/right query, `00 1D 1A 01 01 02`, and inspect the response.
4. Compare the returned levels with the official app, using the apps sequentially
   so they do not compete for the BLE connection.
5. Repeat with only one bud available to establish whether an unavailable bud is
   omitted, reported as `FF`, or retains a cached level. Record firmware version.
6. Try notification registration separately and observe whether level changes
   arrive without polling. A successful read does not establish push support.

Left/right query support is verified on the user's earbuds. Case battery,
charging flags, reading precision, and notification behavior remain
unverified. The inspected level payload defines an ID and a level, with no
separate charging flag.

## App and widget integration

Battery commands reuse `St2Session` and its single `St2GattClient`, sharing the
serialized GATT write queue. Queued writes have an explicit operation kind so
battery queries do not start or settle ANC polling state or its response timeout.

`handleRx` dispatches battery reports and battery errors separately from ANC.
Battery reads have a three-second timeout and failures leave ANC controls usable.

Battery snapshots are cached per selected device with a reading timestamp and
distinct unknown values. Reads run on connection and after a widget mode command,
before releasing the session. The footer shows known nonzero left/right levels
beside the paired device and opens the app when tapped. Cached readings older
than 24 hours are hidden. A single WorkManager job refreshes mode and battery
every 15 minutes while widgets exist, skipping Bluetooth reads unless the audio
device is connected and the phone battery is not low. The BLE session is bounded
to 12 seconds, with no discovery or immediate retries. Android can delay the
schedule for power management. The main app does not yet display battery readings.
