# MoonMode header variants

Open `index.html` to compare three headers in context. State, width, text size, and theme controls update every preview. Choose a single variant for a larger inspection. Connect and mode buttons simulate local state.

## Recommendation

I prefer A, Compact. The current app leads with "MoonMode" and a status line, but omits the device name. A adds the selected device to that line and uses a light divider to close the header. Connect becomes a filled action when disconnected or after an error. Disconnect stays a text action while connected, which gives the mode pictogram more attention.

B, Connection card, puts the device name, connection state, and recovery together. It has a natural place for a future device picker. The extra background and height make connection more prominent during normal use.

C, Device first, makes the selected earbuds the main heading and gives connection a full-width target. It works for frequent reconnecting. The large Disconnect action takes more attention than I want in a mode controller.

## States

- Connected shows a live mode and enables the bottom chooser.
- Disconnected shows the cached mode as "Last known, not live" and enables Connect.
- Connecting shows "Waiting for a reading," offers Cancel to stop connecting, and disables the chooser. The spinner respects reduced motion.
- Error shows "Could not connect," a Retry action, and the existing Bluetooth/earbuds recovery advice.

ST2 is an example selected-device name. The production header should use the selected bonded device name and provide a fallback when no device is selected. These studies do not add a device picker, battery reading, menu, or new Bluetooth behavior.

The mode pictograms and light/dark tokens follow `design/ui-iteration-2`. The bottom chooser retains the production icon/label rail and 96 px targets, using monochrome preview colors. Production Material You colors continue to come from the device theme. The only new pictogram is a small earbud outline in B. All interactive targets are at least 48 px high. The chooser has no active selection while disconnected or connecting. Width controls cover 320, 360, and 412 px; 130% text helps expose tight layouts. The gallery is standalone and changes no Android code.

## Selected direction

C, Device first, is the selected layout. Remove the small app-name line above the device heading. The app is now named MoonMode in launcher, widget, and notification text. The device name remains the main heading; when no selected device name is available, show MoonMode.

## Typography

The app and preview bundle Adwaita Sans regular and medium. The device heading is 30 sp, medium weight, with 33 sp line height and -1.65 sp letter spacing, matching the selected preview proportions. Font license is bundled in app assets.
