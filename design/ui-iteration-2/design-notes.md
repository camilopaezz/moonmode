# ST2 Mode iteration 2

App from Word. Center is a mode pictogram, not a large word. Widgets from Latch, full width, no empty right panel.

## Critique

Default: recolor Word, keep a tiny latch plus a vacant status column. That was the complaint.

Changed: one icon language for app and widgets. Three widget densities of the same three-stop, each using the full 4x1. Off is unprocessed sound, not power or mute.

## Sign (app)

Tokens: paper `#F7F8FA` ink `#191C20` line `#D5D8DE` mute `#5F6570` fail `#B3261E` recess `#E4E6EA`
Dark: paper `#141518` ink `#E8EAED` line `#3C4048` mute `#9AA0A8` fail `#F2B8B5` recess `#2A2D32`

Type: title 20/500, label 15/500, caption 13/400, chooser 12/500. System UI / Roboto.

```
ST2 Mode                 Disconnect
Connected

         [ pictogram ]
            ANC

   [ Off ] [ ANC ] [ Transparency ]
```

## Icons (stroke, currentColor)

- Off: three open waveforms. Processing absent. Sound still exists.
- ANC: waveforms stopped by a solid block. Outside noise blocked.
- Transparency: slotted block with waveforms continuing through. Outside sound admitted.

Shape carries the meaning. Color is secondary.

## Widgets

Same track + sliding block. No right-hand panel. Caption is a full-width Last known line.

1. Segment: three cells, icon plus label in each.
2. Stops: three large icons, one caption with mode and Last known.
3. Rail: one contiguous label track, sliding indicator, small icons in the labels.

## Shared

48px targets. Widget taps connect then set if the app is disconnected. Reduced motion skips delays. Mocks only.
