# MoonMode

ANC mode control for SPACE TRAVEL 2 over GAIA V3. MoonMode talks to the earbuds
over RFCOMM on their paired Classic Bluetooth link and uses BLE only as a fallback.

[Battery protocol research](docs/battery-protocol.md) records the official app's
GAIA commands and device validation for battery readings.

[Android UI](docs/android-design.md) describes the glass control layer, screen
behaviour, design overrides, and the debug sample screens for visual review.

## Build

Requires **JDK 17** and the Android SDK with platform 36. Set `JAVA_HOME` to your JDK 17 installation and set the SDK path in `local.properties` or `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug
```

## Connection

The app, the widget, and background refresh share one control session for the
selected earbuds.

When the earbuds are connected for audio, Connect opens GAIA over RFCOMM on their
paired Classic Bluetooth address. There is no scan or endpoint confirmation, and
the session is ready in about half a second instead of about 2.4 seconds over BLE.
The same socket carries mode changes, the 2-second mode polling while the app is
open, and battery reads. See [RFCOMM transport](docs/rfcomm-transport.md).

BLE is the fallback when the earbuds are not connected for audio or the RFCOMM
socket cannot open. The first BLE connection scans for the separate control
endpoint and asks you to confirm it. After that the confirmed address is reused.

The earbuds accept one RFCOMM control connection at a time. While the official
Moondrop app is open, even in the background, MoonMode's RFCOMM attempt fails within
about half a second and MoonMode uses BLE instead. The attempt briefly interrupts
Moondrop, which reconnects by itself. If Moondrop connects while MoonMode is
connected, MoonMode shows **Connection lost**. Close Moondrop, then reconnect.

## Home-screen widget

After pairing the earbuds, open the app once so it saves the selected earbuds. Add **MoonMode** from your launcher's widget picker. The widget has Off, ANC, and Transparency buttons and uses the device selected in the app.

A tap uses the app's session when one is ready. Otherwise it connects as described
above and sends the mode before anything else: over RFCOMM it is written as soon as
the socket opens, about half a second after the tap. Then the widget shows the mode
and reads the battery before the short-lived Bluetooth foreground service releases
the connection. Rapid taps keep the latest pending mode while the current command
finishes. Each command attempt times out after 30 seconds.

If the RFCOMM socket drops or a write fails before the mode is sent, the command
moves to the saved BLE endpoint, so it is never sent twice. Without a saved endpoint,
the widget asks you to open the app; the widget never scans.

For a cold BLE connection, the requested mode is sent as soon as service
discovery completes, before notification subscriptions and battery queries.
Commands arriving during notification setup run ahead of queued subscriptions and
reads while preserving earlier mode-command order.

The widget footer shows the selected paired device. The highlighted mode is the last confirmed setting, not a live reading. Tap the title to open the app. If Bluetooth permissions are missing or no paired earbuds are selected, the mode buttons open the app instead. A failed command leaves a message asking you to open the app; endpoint discovery and confirmation stay in the app.

Left and right battery percentages appear beside the paired device when known,
for example `Paired: Space Travel 2 · L 80% · R 70%`. Tap the footer to open the app.
Battery is also queried on connection and after widget mode changes. The widget
shows the last successful reading, hides unknown buds, sides reporting zero, and readings older than
24 hours. Battery query failures do not turn
a successful mode change into an error.

[Notification protocol research](docs/notification-protocol.md) records the SDK
subscriptions for immediate mode and battery changes. Those device events are
not yet verified or enabled by MoonMode.

## Settings

Use the gear button to switch paired earbuds, reset the selected earbuds’ saved BLE
endpoint, and configure connection, widget refresh, and appearance. Reset disconnects
the control session and repeats endpoint discovery the next time the BLE fallback is
needed. It does not remove Android Bluetooth pairing.

Connect when opening the app is off by default. When enabled, MoonMode tries once
per shared client session after Bluetooth access and a selected device are available.
Activity recreation tries again if the previous client was destroyed. A failed
attempt leaves the normal Retry button available.

Background widget refresh is on by default and runs only while a widget exists.
WorkManager schedules it about every 15 minutes; Android may delay it during Doze
or Battery Saver. Refresh pauses when the phone battery is low, skips earbuds that
are not connected for audio, and never scans or sends mode changes. A refresh opens
a control connection for at most 12 seconds, reads the mode and battery, and releases it. Resumed
activities and widget commands take priority. A paused activity shares its idle client
with background refresh. Background connection failures preserve the saved endpoint. Turning refresh off cancels the schedule;
app usage and widget commands still update the cached mode and battery.

Appearance supports System, Light, and Dark, with either Android wallpaper colors
or MoonMode’s purple palette. These preferences apply to the app; the widget follows
Android’s theme. Settings also provides permission status, Android settings shortcuts,
widget setup instructions, the app version, and bundled licenses.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Use the same JDK 17 setup as the build command.

`ProfileConnectionTest` covers Bluetooth proxy cleanup triggering a disconnect
callback before the connection check returns. The worker now completes the check
before closing the proxy, so cleanup cannot make connected earbuds appear
disconnected and skip a widget refresh.

## CI

GitHub Actions runs unit tests, Android lint, and a debug APK build on pushes to `main` and pull requests. You can also run it manually from the Actions tab. Each run uploads reports and, when successful, a `moonmode-debug` APK artifact, retained for seven days.

The CI APK uses a debug signing key. Installing it over a locally built APK may require uninstalling the local app first if their signing keys differ.

## Releases

Publishing a GitHub release runs `.github/workflows/release.yml`. It checks out the release tag, runs the unit tests, builds a signed release APK, and attaches `moonmode-<version>.apk` and its SHA-256 checksum to the release.

The tag must be a semantic version such as `v1.2.3` (a `-beta1` style suffix is allowed). The workflow sets `versionName` to the tag without the `v` and `versionCode` to `major * 10000 + minor * 100 + patch`, so each release must have a higher version than the last.

```sh
git tag v1.0.0 && git push origin v1.0.0
gh release create v1.0.0 --generate-notes
```

Signing needs four repository secrets. Create a keystore once and keep a backup: Android refuses updates signed with a different key.

```sh
keytool -genkeypair -v -keystore moonmode-release.jks -alias moonmode \
  -keyalg RSA -keysize 4096 -validity 10000
gh secret set MOONMODE_KEYSTORE_BASE64 < <(base64 -w0 moonmode-release.jks)
gh secret set MOONMODE_KEYSTORE_PASSWORD
gh secret set MOONMODE_KEY_ALIAS --body moonmode
gh secret set MOONMODE_KEY_PASSWORD
```

Release APKs use a different key from debug APKs, so uninstall a debug build before installing a release one.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 31. Pair the buds in system Bluetooth settings first, then pick them from the bonded list. While they are connected for audio, Connect uses RFCOMM and needs no further setup.

Only the BLE fallback scans for the separate control endpoint and asks you to confirm its address. The confirmed address is saved for that bonded audio device, so later BLE connections (even after restarting the app) skip the scan. If the saved BLE address stops working, the app scans again and asks for confirmation. If multiple same-name earbuds are nearby, move away from the others and scan again; the advertised name alone cannot prove which audio device an endpoint belongs to.

To test a debug build on a phone that has a release build, install it beside the release app:

```sh
./gradlew :app:assembleDebug -Pmoonmode.debugSuffix=.dev
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

MoonMode is licensed under the [MIT License](LICENSE). Bundled fonts retain their own licenses in [`app/src/main/assets/licenses`](app/src/main/assets/licenses).
