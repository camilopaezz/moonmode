# MoonMode

ANC mode control for SPACE TRAVEL 2 over GAIA V3 BLE.

[Battery protocol research](docs/battery-protocol.md) records the official app's
GAIA commands and device validation for battery readings.

## Build

Requires **JDK 17** and the Android SDK with platform 36. Set `JAVA_HOME` to your JDK 17 installation and set the SDK path in `local.properties` or `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug
```

## Home-screen widget

After pairing the earbuds, connect in the app once and confirm the BLE endpoint. Add **MoonMode** from your launcher's widget picker. The widget has Off, ANC, and Transparency buttons and uses the device selected in the app.

A tap connects to the saved endpoint, sends the mode command, then stops the short-lived Bluetooth foreground service. Rapid taps keep the latest pending mode while the current command finishes. Each command attempt times out after 30 seconds.

The widget footer shows the selected paired device. The highlighted mode is the last confirmed setting, not a live reading. Tap the title to open the app. If Bluetooth permissions or setup are missing, the mode buttons open the app instead. A failed command leaves a message asking you to open the app; endpoint discovery and confirmation stay in the app.

Left and right battery percentages appear beside the paired device when known,
for example `Paired: Space Travel 2 · L 80% · R 70%`. Tap the footer to open the app.
Battery is also queried on connection and after widget mode changes. The widget
shows the last successful reading, hides unknown buds, sides reporting zero, and readings older than
24 hours. Battery query failures do not turn
a successful mode change into an error.

While a widget exists, WorkManager schedules a mode and battery refresh every
15 minutes. Android may delay it during Doze or Battery Saver. Each refresh skips
Bluetooth when permissions/setup are missing or the selected earbuds are not
connected for audio. It reuses the shared BLE client, uses only the confirmed
endpoint, and limits the BLE session to 12 seconds. It never scans, starts a
foreground service, or retries immediately. Work pauses when the phone battery
is low and is canceled when the last widget is removed. A connection opened only
for a refresh closes when that worker releases it.

[Notification protocol research](docs/notification-protocol.md) records the SDK
subscriptions for immediate mode and battery changes. Those device events are
not yet verified or enabled by MoonMode.

## Tests

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Use the same JDK 17 setup as the build command.

## CI

GitHub Actions runs unit tests, Android lint, and a debug APK build on pushes to `main` and pull requests. You can also run it manually from the Actions tab. Each run uploads reports and, when successful, a `moonmode-debug` APK artifact, retained for seven days.

The CI APK uses a debug signing key. Installing it over a locally built APK may require uninstalling the local app first if their signing keys differ.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minSdk 31. Pair the buds in system Bluetooth settings first, then pick them from the bonded list. The first Connect scans for the separate BLE control endpoint and asks you to confirm its address. Once connected, the confirmed address is saved for that bonded audio device, so reconnecting (even after restarting the app) skips the scan. If the saved BLE address stops working, the app scans again and asks for confirmation. If multiple same-name earbuds are nearby, move away from the others and scan again; the advertised name alone cannot prove which audio device an endpoint belongs to.

## License

MoonMode is licensed under the [MIT License](LICENSE). Bundled fonts retain their own licenses in [`app/src/main/assets/licenses`](app/src/main/assets/licenses).
