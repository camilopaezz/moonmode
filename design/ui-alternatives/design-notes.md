# ST2 Mode UI alternatives

Mocks only. Off / ANC / Transparency. Connection is secondary. No Wind, EQ, battery, device list, or addresses.

## Critique (before tokens)

Default I would ship: Material purple, three pill buttons, dual Connect/Disconnect, empty column, 4x2 widget. That is the current app and a SaaS-card recolor.

Changed:
- Personality lives in how you pick a mode, not in chrome.
- One contextual Connect or Disconnect, not two primary actions.
- Space is given to the mode control, not to filler copy.
- System sans only. No eyebrows, gradients, cards-with-shadows, or marketing.
- Widget is 4x1 and always reads Last known.

## Latch

Three-stop panel filling leftover height. Tap a stop; a sliding block snaps.

Tokens: paper `#E6E5E0` ink `#1B1B18` recess `#D0CFC9` thumb `#2A2A26` mute `#6A6964` fail `#A33A32`
Dark: paper `#1C1C1A` ink `#ECEBE6` recess `#2E2E2A` thumb `#F0EFEA` mute `#A8A79F` fail `#E07A72`

Type: title 20/500, status 14/400, stop label 15/500, pending 13/400. System UI / Roboto.

```
ST2 Mode                 Disconnect
Connected

 [ Off |  ANC  | Transparency ]
 [     | block |              ]
 [     |       |              ]

 Last known only if disconnected
```

Left-aligned header. Three equal columns, labels at the bottom. The selected column is a sliding block.

## Stack

Three exclusive rows share leftover height. Selected row inverts. No switch chrome.

Tokens: paper `#F4F4F4` ink `#121212` selected `#1A1A1A` onSel `#F4F4F4` mute `#5C5C5C` fail `#B42318`
Dark: paper `#121212` ink `#F0F0F0` selected `#EDEDED` onSel `#121212` mute `#A0A0A0` fail `#FF8A80`

Type: title 20/500, row label 22/500, status 14/400.

```
ST2 Mode                 Disconnect
Connected
-----------------------------
| Off                       |
| ANC                       |  <- inverted when live
| Transparency              |
-----------------------------
```

Rows are the page. Header is one line. Cached row is named, not inverted, while disconnected.

## Word

Live mode is a display word. Options are a compact exclusive row under it.

Tokens: paper `#F7F8FA` ink `#191C20` line `#D5D8DE` mute `#5F6570` fail `#B3261E`
Dark: paper `#141518` ink `#E8EAED` line `#3C4048` mute `#9AA0A8` fail `#F2B8B5`

Type: title 20/500, display 42/500, option 12/500, caption 13/400.

```
ST2 Mode                 Disconnect
Connected

          ANC

   [ Off ] [ ANC ] [ Transparency ]
```

Centered display, left-aligned header. Disconnected: word is last known, caption says so, options disabled.

## Why they differ

Latch is three columns with a sliding block. Stack is three full-height rows. Word is typography plus a compact chooser. Not three palettes of the same grid.

## Shared

48px targets. Keyboard focus. Reduced motion: skip delays. Reviewer strip outside the phone: Connected / Disconnected / Failed. Widget 4x1 matches the concept and says Last known.
