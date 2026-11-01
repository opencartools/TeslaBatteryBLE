# Car screen

## Sub-features

The car view shown after opening a car: the hero card (SOC, rated range, reading age), the status pill row, the battery health card, charge stats, parked drain, the history chart with timescale chips, and the driving screen with the range/consumption chart.

## How to get to it (user POV)

From the cars list, tap a car row. The screen switches from the list to the car view. Back returns to the list.

## Driving it with Paparazzi

- `CardSnapshots` — the hero and the cards, light and dark on the phone config, plus narrow (Jelly) variants where the class registers them.
- `HeroReadSnapshots` — the read ring under the status pill, in its states.
- `DrivingScreenSnapshots` — the full driving screen in light and dark.

Render one class at a time while iterating. Read the `runs/<latest>.js` map to find the PNG for a name.

## Gotchas

- The snapshots use fixtures, not a live car; a fixture change can change every card.
- The hero text is the alignment reference for anything placed under it (banners, hints, chips).
- Narrow layouts are separate snapshots; a phone-width pass does not prove the Jelly width.
