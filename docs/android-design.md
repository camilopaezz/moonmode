# Android UI

MoonMode follows the android-glass-ui system: Material 3 Expressive content with a floating Liquid Glass control layer. Bundled Adwaita Sans is the interface font, and layouts are edge to edge. This document records behaviour and MoonMode's overrides of that system.

## Primitives

The shared primitives live in `app/src/main/java/dev/camilo/st2mode/ui/common`.

- `FloatingControls.kt`: `FloatingScaffold` (the content slot is the only `hazeSource`), `ScrollEdgeBand`, `GlassSurface` (the single glass surface for every control), `FloatingScreenHeader` (split header), layout and motion constants, `rememberCompactWindow`, and `rememberReducedMotion`.
- `SegmentedGroup.kt`: `SectionLabel`, `SegmentedGroup`, `SegmentedRow`, and `segmentShape`.
- `Transitions.kt`: `screenTransform` (Home ↔ Settings), `valueSwap`, `heroSwap`, `contentEnter`/`contentExit`, and `predictiveBackPreview`, with their named constants.

Blur uses `dev.chrisbanes.haze` 1.6.10. Because minSdk is 31, backdrop blur is always available. `MaterialExpressiveTheme` (material3 1.5.0-alpha02) supplies the motion scheme. Theme shapes range from 12 to 32 dp.

## Theme

System, Light, and Dark are inline radio rows in Settings. Wallpaper colors are on by default. With them off, the app uses a complete tonal-spot fallback scheme generated from the brand purple `#5B4B8A`, with every container role defined. The widget follows Android's theme and wallpaper colors, not the in-app preferences.

## Home

The header uses the split shape. A glass brand capsule sits at the start and a 52 dp settings circle at the end. The selected device name is the expanded large title (`displaySmall`). It scrolls with the content and fades out by 45% collapse. After 60% collapse, the brand capsule crossfades to the device name. Below the title are the connection status (dot plus `bodyLarge`), and either Connect (filled) or Disconnect/Cancel (outlined).

Connection failures show a `tertiaryContainer` callout card with an icon, a `titleMedium` heading, recovery advice, and a Retry or Allow Bluetooth action. The mode pictogram is the hero: 120 dp, with the mode name in `headlineLarge`. A cached reading is dimmed and labelled "Last known, not live". Endpoint confirmation is a segmented group of clickable rows.

The three modes sit in a centered glass bottom dock: a pill track with 6 dp padding, 4 dp gaps, and pill segments. The live mode is a `secondaryContainer` segment with radio-button semantics. Cached modes never select a segment. Labels shrink to fit, like the widget, so all three icons stay aligned. Scrolling down past 10 dp hides the labels; scrolling up or reaching either end shows them again. Touch exploration and disabled animations keep labels visible and stop the status-dot pulse. In windows shorter than 480 dp, or with less than 360 dp of effective height after font scaling, the dock joins the end of the content with a solid tonal fill, 32 dp outer corners, and 28 dp segment corners. Content scrolls behind the header and dock with scrollable padding, and scroll-edge bands soften it when it can scroll past either edge.

## Settings

Settings uses the split floating header (back circle and title capsule) over a list that scrolls behind it. Every section is a segmented group of `surfaceContainer` rows with 24 dp leading icons. Sections are earbuds (radio rows plus reset), connection, widget (refresh toggle plus setup help), appearance (theme radios, then the wallpaper-colors toggle as its own group), and help (permission, Bluetooth settings, licenses, version). Selected radio rows use `secondaryContainer`. Reset confirmation, widget help, and licenses open Material dialogs.

## Motion

Every transition takes its specs from `MaterialTheme.motionScheme`: spatial specs for size and position, effects specs for alpha and color. The transforms live in `Transitions.kt`.

- **Screens**: Home ↔ Settings slide along a shared X axis by an eighth of the width while crossfading. Leaving Settings, it also recedes to 94 %. The screens sit on a `surfaceContainerHighest` backdrop, so the light window background never shows through.
- **Predictive back**: while the back swipe is in progress, Settings scales down by up to 10 %, moves away from the swiped edge, and rounds its corners to 28 dp. Releasing the swipe plays the backward screen transition from that point; cancelling it springs back. `android:enableOnBackInvokedCallback` is on.
- **Values**: the device name, connection status, button label, and hero caption rise in by a third of their height as the old value fades. The container resizes on the spatial spec.
- **Hero**: a new mode's pictogram and name grow in from 85 % scale. Going from live to cached dims the pictogram without swapping it.
- **Content**: the error callout, the Connect/Disconnect button, and the endpoint group expand and fade in, and shrink and fade out.
- **Selection**: one `secondaryContainer` pill slides between dock segments on the spatial spec. It appears in place when a live reading arrives and fades out when the reading is lost. Settings rows animate a selection fraction rather than their colors, so a theme change recolors them in step with the page. The status dot fills when connected and empties when disconnected.
- **Theme**: changing the theme or wallpaper colors blends the whole color scheme over the slow effects spec instead of cutting to the new colors.

Android's animator duration scale governs all of these, so setting it to off removes them.

## Widget

Launcher widgets cannot blur, so the widget is the solid glass state: an opaque `surfaceContainerHigh` panel inside a 1 dp lit rim (highlight at the top, outline-variant at 25% at the bottom), with no shadow. The three modes are pill segments 4 dp apart, as in the app's dock. The last confirmed mode is a `secondaryContainer` pill. Labels use Adwaita Sans Medium and the caption uses Adwaita Sans Regular in `onSurfaceVariant`. Android 14+ uses the system's container roles; Android 12–13 uses the nearest system palette tones. Colors live in the `widget_colors.xml` resources. `scripts/check-material-you.py` compares the panel fill against the system palette.

## Overrides

- **Brand wordmark**: keeps the Doto ExtraBold dot-matrix face at the system's 22/28 sp size, with 0 sp tracking, because negative tracking merges the dots. All other text uses Adwaita Sans.
- **Status pulse**: `StatusPulseSpec`, a 1200 ms reversing tween for the connecting dot. The motion scheme has no repeating spec.
- **Widget corners**: follow the launcher's `system_app_widget_background_radius` instead of the 32 dp panel corner, so the widget matches neighbouring widgets.
- **Widget rim**: XML gradients cannot mix system colors, so the rim uses fixed translucent colors in `widget_colors.xml`.
- **Predictive back preview**: follows Material's in-app back guidance (90 % scale, edge drift, 28 dp corners), which the glass system does not cover.
- **Mode dock labels**: shrink from `labelLarge` down to 8 sp so the three labels fit on one line, matching the widget.

## Review

Debug builds include `SampleActivity`, which renders labelled sample states without Bluetooth:

```sh
adb shell am start -n dev.camilo.st2mode/.SampleActivity \
  --es screen home --es state connected --es theme dark
```

Or open `moonmode-sample://sample?screen=home&state=connected&theme=dark`. `screen` is `home` or `settings`; `state` is `connected`, `cached`, `error`, or `setup`; `theme` is `light` or `dark`. Adding `widget=off|anc|transparency` saves that mode as the widget's last-known mode and refreshes widgets.
