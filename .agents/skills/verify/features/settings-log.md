# Settings and log

## Sub-features

Settings rows (units, notifications, car/VIN editing, clear cache, About), the log card with its Share button, the empty and filled log states, and the mirrored (RTL) log card.

## How to get to it (user POV)

The gear icon in the top bar opens Settings. Back returns to the car view. The log card sits on the car screen; Share exports the log.

## Driving it with Paparazzi

- `LogCardSnapshots` — title row with Share, empty and filled, mirrored.
- Settings itself is captured by the emulator flow's **settings** screen; use it when the row layout or insets change.

## Gotchas

- The Share button is part of the title row; check its alignment and touch target separately from the log text.
- The mirrored snapshot is the RTL evidence; a left-aligned check does not prove it.
- The log text is monospace and wraps; long lines can push the card past the screen without an obvious error.
