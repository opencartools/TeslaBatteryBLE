# Third-party notices

TeslaBatteryBLE is licensed under the GNU Affero General Public License v3.0
only (AGPL-3.0-only). See [LICENSE](LICENSE).

## Tesla protocol definitions — Apache License 2.0

- `protocol/src/main/proto/*.proto` are copied from
  [teslamotors/vehicle-command](https://github.com/teslamotors/vehicle-command)
  (Apache-2.0). The pinned revision is recorded in
  `protocol/src/main/proto/TESLA_COMMIT`.
- The Kotlin protocol implementation in `core/` is a port of the Go
  implementation (Apache-2.0) and of the MIT-licensed Swift ports
  ([misakatao/TeslaBLEKeyKit](https://github.com/misakatao/TeslaBLEKeyKit),
  [shoujiaxin/swift-tesla-ble](https://github.com/shoujiaxin/swift-tesla-ble)).

## Runtime dependencies

- [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin)
  (`dev.whyoleg.cryptography`, Apache-2.0): `core` crypto primitives.
- [Vico](https://github.com/patrykandpatrick/vico)
  (`com.patrykandpatrick.vico`, Apache-2.0): the drive chart; no source is copied.

## Behavioral references only — no code copied

- The AGPL-licensed C++ implementations (pmdroid, yoziru/PedroKTFC lineage)
  were used to understand behavior only.

## Obtainium badge — GPL-3.0

- `website/images/obtainium-badge.png` is the "Get it on Obtainium" badge
  from [ImranR98/Obtainium](https://github.com/ImranR98/Obtainium)
  (GPL-3.0), self-hosted per their
  [deep-links doc](https://wiki.obtainium.imranr.dev/deep_links/).

## Car lock sound — CC BY 4.0

- `app/src/main/res/raw/car_lock.mp3` is "Car Lock Sound Effect" from
  [Freesoundslibrary](https://www.freesoundslibrary.com/car-lock-sound-effect/)
  (author: Freesoundslibrary), licensed under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). It plays on the
  "car left unlocked" notification.

Full license texts for bundled dependencies (Wire, AndroidX, Kotlin, etc.)
ship with release artifacts and appear in the app's open-source licenses
screen; tracking is in [docs/master-plan.md](docs/master-plan.md).
