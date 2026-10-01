# MoonMode

ANC mode control for SPACE TRAVEL 2 over GAIA V3 BLE.

## Build

Requires **JDK 17** and the Android SDK with platform 36. Set `JAVA_HOME` to your JDK 17 installation and set the SDK path in `local.properties` or `ANDROID_HOME`.

```sh
./gradlew :app:assembleDebug
```

## Home-screen widget

After pairing the earbuds, connect in the app once and confirm the BLE endpoint. Add **MoonMode** from your launcher's widget picker. The widget has Off, ANC, and Transparency buttons and uses the device selected in the app.

A tap connects to the saved endpoint, sends the mode command, then stops the short-lived Bluetooth foreground service. It doesn't poll in the background. Rapid taps keep the latest pending mode while the current command finishes. Each command attempt times out after 30 seconds.

The widget footer shows the selected paired device. The highlighted mode is the last confirmed setting, not a live reading. Tap the title to open the app. If Bluetooth permissions or setup are missing, the mode buttons open the app instead. A failed command leaves a message asking you to open the app; endpoint discovery and confirmation stay in the app.

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
