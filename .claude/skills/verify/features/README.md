# Feature map

The user-facing surfaces this repo can render and verify, and how to drive each one.

| Feature | Snapshot class(es) | Full-screen path |
| --- | --- | --- |
| [Car screen](car-screen.md) | `CardSnapshots`, `HeroReadSnapshots`, `DrivingScreenSnapshots` | capture script: car |
| [Cars list and scan](cars-list.md) | `SetupBannerSnapshots` | capture script: scan |
| [VIN prompt](vin-prompt.md) | `VinPromptSnapshots` | manual, emulator |
| [Trips and notifications](trips.md) | `TripSnapshots` | notification screenshot workflow |
| [Settings and log](settings-log.md) | `LogCardSnapshots` | capture script: settings |

Start with the class that owns the feature. Render it, read the PNGs, and review. Only fall back to the emulator flow when the feature depends on the activity, insets, navigation or a system notification.
