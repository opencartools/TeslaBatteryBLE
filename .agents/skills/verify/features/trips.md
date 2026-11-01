# Trips and notifications

## Sub-features

The trip card, the trip heads-up notification with its tracker/arrow, the group summary for multiple cars, and the trip bar that survives the next notification update.

## How to get to it (user POV)

A drive starts while the app is tracking. The app posts a trip notification; the card appears on the car screen. The heads-up only makes sense with the app in the background.

## Driving it with Paparazzi

- `TripSnapshots` — the trip card and its variants, light and dark.
- For the system notification, use the repo's notification screenshot workflow rather than a Paparazzi render; the shade is system UI.

## Gotchas

- A heads-up posted while the app is foreground is suppressed by Android; verify with the app backgrounded or from a notification screenshot.
- The notification is a one-shot alert per trip; re-testing needs a new trip or a cleared seen state.
- The arrow/tracker rendering is part of the notification image, not the card; do not approve one from the other.
