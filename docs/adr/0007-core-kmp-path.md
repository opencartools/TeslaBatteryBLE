# ADR-0007: Keep `core` on a KMP migration path

Status: Accepted
Date: 2026-10-07

## Context

- `core` claims to be multiplatform-ready, but it is built with the
  `kotlin.jvm` plugin and its crypto goes straight to JCA/JCE
  (`KeyAgreement` ECDH, `Cipher` AES/GCM, `Mac` HmacSHA256, `MessageDigest`
  SHA-1/SHA-256, `SecureRandom`, EC key specs, PKCS8).
- A file-by-file audit (2026-10-07) found 14 of 20 main-source files are
  already portable pure Kotlin (framing, history, health math,
  `AntiReplayWindow`, command/session encoding). The 6 non-portable files
  cluster into one seam: ECDH P-256, AES-GCM, HMAC-SHA256, SHA-1/SHA-256,
  secure RNG, UUID (`TeslaCrypto`, `TeslaKeys`, `Metadata`, `TeslaNames`,
  `TeslaGatt`, `TeslaVcsec`). No file is inherently platform-bound.
- Wire is not a blocker: the Wire runtime has published multiplatform
  artifacts since 4.9.0 and okio 3.x is KMP, so the same protos and plugin
  carry over.

## Decision

- Do not migrate the build to KMP now. Keep `core` on the migration path with
  guardrails so no new change makes the move harder.
- Swap the crypto layer to `dev.whyoleg.cryptography`
  (cryptography-kotlin) now, while there are no users to migrate. It is the
  only single KMP library covering the full seam (SHA-1/SHA-256, HMAC,
  AES-GCM, ECDH P-256, EC keygen, SecureRandom) behind one common API, with
  platform backends (OpenSSL/native, JCA on JVM, WebCrypto on JS). The
  build stays `kotlin.jvm` until the plugin switch; this removes the JCA
  blocker first.
- When the build migration happens: apply the `multiplatform` plugin with a
  `jvm()` target first, keep the same protos, move tests to `kotlin.test` in
  `commonTest` (JVM-only vectors stay in `jvmTest`).
- Fallback if cryptography-kotlin is unmaintained at build-migration time:
  an `expect`/`actual` seam keeping JCA on JVM, one actual per target per
  primitive. BouncyCastle alone is rejected (JVM-only).

## Guardrails (binding until migration)

1. No `java.*`/`javax.*` imports in new portable `core` code. Platform APIs
   appear only in `expect` declarations or single-purpose JVM impl files.
2. Seam pattern for crypto/RNG/UUID: capability interfaces live with the
   common logic; the JVM implementation holds the JCA calls; callers receive
   the seam by constructor injection (pattern already proven by
   `HashContext`/`Metadata` and `TeslaSession`'s `nonceGenerator`).
3. New `core` files compile against common stdlib only; if a file needs a
   platform capability it extends a seam instead of importing `java.*`.
4. New `core` runtime dependencies must publish KMP artifacts covering all
   future `core` targets; JVM-only libraries are test-only or hidden behind
   a seam.
5. New tests use `kotlin.test` and avoid JVM-only assertions so they can move
   to `commonTest` unchanged.

## Consequences

- The crypto swap landed in #236 (cryptography-kotlin 0.6.0, JDK provider)
  under the pre-1.0 policy: no data migration, no in-app migration code.
  Existing PKCS8 keys must import
  through the new library; if they do not, the owner re-pairs (acceptable at
  this stage).
- Contributors get a failing-lint-free rule set today: keep `java.*` out of
  new logic, extend the seam when a capability is missing.
- Build-plugin migration cost stays bounded: one seam to re-target, protos
  and fixtures unchanged, Go-vector cross-checks preserved via `jvmTest`.
- Revisit the crypto-library choice at build-migration time; if
  cryptography-kotlin is unmaintained then, take the `expect`/`actual`
  fallback.
- Decided 2026-11-01 (#119, taken up in the owner's 2026-11-01 sweep): the
  seam takes a key handle, not key bytes. `VehicleKey` exposes `publicKeyRaw`
  and a final `sessionKeyWith(peerPublicRaw)`; an implementation supplies only
  `sharedSecretWith` (raw ECDH), so the Tesla derivation `SHA-1(shared)[:16]`
  stays in `:protocol` and cannot drift per platform. `TeslaKeyPair` (PKCS#8
  plus the uncompressed point) is the software handle and the stored record;
  `TeslaSession.import(key, …)` and `SessionKeys.load()` take the handle, and
  the bytes-taking `TeslaCrypto.sessionKey(privateKeyPkcs8, peer)` is gone.
- Hardware-backed handles (Android Keystore `PURPOSE_AGREE_KEY` on API 31+,
  StrongBox, Secure Enclave) implement `sharedSecretWith` and never export a
  private key. They are follow-up work; the app still builds a software
  handle from the Keystore-wrapped PKCS#8 (ADR-0005) until then.

## Update 2026-10-20: build-migration plan

Status of this section: Deferred (2026-10-21; proposed 2026-10-16, accepted
2026-10-20). It plans the plugin switch the Decision section deferred.

Deferred because Swift implementations of the Tesla BLE protocol already
serve iOS, so a multiplatform `:protocol` would mostly duplicate them. A
JVM library already serves Android, desktop and server Kotlin. The plan
comes back if an iOS version of this app becomes a goal: then the shared
value is mostly `:core` (history, health, charge sessions), not only the
protocol. Until then the guardrails above and the detekt `ForbiddenImport`
check stay, so the switch remains about one PR.

Preconditions, in order:

1. Crypto on cryptography-kotlin (done, #236).
2. The move-only split of the protocol code into `:protocol` (#251).
3. Step 1 (#264): main code in `:protocol` and `:core` free of `java.*` and
   `UUID`, every test on `kotlin.test`, and detekt `ForbiddenImport` on both
   modules' main code.

The plugin switch then happens once, on that final layout, for `:protocol`
and `:core` together. `:core` depends on `:protocol`, so both need the same
targets.

### Leftovers from step 1, done in the step-2 PR

- `TeslaCrypto`'s `label.toByteArray(Charsets.US_ASCII)`: `Charsets` is not
  in the common stdlib. It becomes `encodeToByteArray()`, since the labels
  are ASCII literals. The crypto owner reviews that line.
- VIN validation: step 1 switched VIN bytes to `encodeToByteArray()`
  (UTF-8), where `Charsets.US_ASCII` used to map non-ASCII characters to
  `?`. The two are identical for any valid VIN, but nothing checks that a
  VIN is ASCII. Add one `requireValidVin` in `:protocol` (17 characters,
  `[A-HJ-NPR-Z0-9]`), with a test, and call it from the `TeslaSession`
  constructors and `TeslaNames.bleName`. Every connection, including one
  using a VIN loaded from storage, goes through those entry points, so
  stored VINs are checked on use as well as at entry.

### Targets

- First PR: `jvm()` only. `:app` keeps consuming the JVM artifact, so
  Android needs no `androidTarget()` while the Keystore seam stays in `app`.
  The source sets become `commonMain`, `commonTest` and `jvmTest`. Wire
  generates into `commonMain` once the multiplatform plugin is applied.
- Second PR: `iosArm64()` and `iosSimulatorArm64()`, plus `macosArm64()` so
  `commonTest` runs natively on a macOS CI runner. `iosX64` is skipped
  (Intel simulators are on their way out). Apple klibs only build on macOS,
  so CI gains one `macos-latest` job. Linux jobs stay JVM-only.
- No JS/Wasm target: WebCrypto has no blocking API, and the protocol uses
  the `*Blocking` calls.

### Crypto provider per platform

- `commonMain` depends on `cryptography-core` only.
- JVM uses `cryptography-provider-jdk`, as today.
- Apple uses `cryptography-provider-optimal`. Checked against the 0.6.0
  sources: on Apple targets it bundles CryptoKit (every Apple target except
  `watchosArm32`) and CommonCrypto, and the docs describe CryptoKit first
  with CommonCrypto as the fallback. Neither provider needs an opt-in
  annotation; the only opt-in the protocol uses is the existing
  `DelicateCryptographyApi` for SHA-1.
- CryptoKit 0.6.0 registers SHA-1, SHA-256, HMAC, AES-GCM, ECDH and ECDSA.
  Its ECDH decoders take the formats the protocol uses: a `RAW` public key
  (the uncompressed point Tesla sends) and a PKCS8 `DER` private key.
- Known CommonCrypto limits, only relevant if CommonCrypto ends up serving a
  call: it cannot decode a `RAW` EC private key, and it decodes a DER EC
  private key only when the optional `publicKey` field is present
  (RFC 5915). Keys this library generates carry that field. A Secure
  Enclave key handle (#119) makes the limit moot.
- To be confirmed by the macOS vector run: which provider actually serves
  each call under `optimal` (CryptoKit is expected for all of them), and
  that every Go vector passes on Apple byte for byte.
- The provider is a dependency of the library, not something consumers have
  to choose. Consumers who want another provider can exclude it.

### Go vectors on every target

- `GoVectorTest` reads `expected.txt` through the JVM class loader, which is
  JVM-only.
- Replacement: a Gradle task generates a `commonTest` Kotlin file holding
  `expected.txt` as a string constant, from the same CI-diffed file. The
  vector test then runs unchanged on every target.
- `expected.txt` stays generated by `tools/go-fixtures`, never hand-edited.
  Only its delivery into tests changes.
- Tests that need JVM types (for example `InvalidKeySpecException` in
  `TeslaCryptoTest`) go to `jvmTest`.

### Wire

- Wire 7.1.0's Gradle plugin generates Kotlin into `commonMain` when the
  `multiplatform` plugin is applied. Generated code uses Wire's own
  `Instant`/`ByteString` (okio) types, which are KMP.
- `:core`'s `protoPath` points at `../protocol/src/main/proto`. It follows
  the protos when `:protocol`'s source root moves (it can stay a plain
  directory outside `commonMain`, since only Wire reads it).
- To be confirmed in the first step-2 commit: the generated sources land in
  `commonMain` for both modules, and `:core`'s generated code still
  references `:protocol`'s Tesla types instead of regenerating them.

### Lint and baselines after the plugin switch

- `config/detekt/portable.yml` excludes `**/test/**`, which matches neither
  `commonTest` nor `jvmTest`. The switch changes it to
  `['**/*Test/**', '**/test/**']`, so `ForbiddenImport` stays off every test
  source set.
- `ForbiddenImport` also stays off `jvmMain`, whose job is to hold JVM
  implementations behind seams: the rule applies to `commonMain` only.
- The multiplatform detekt tasks are per source set (`detektMetadataMain`,
  `detektJvmMain`, ...). Each module's baselines move to the new task names
  in the same PR, with no new entries admitted.
- ktlint already runs per source set and needs no change.

### R8 consumer rules

- cryptography-kotlin's JDK provider references optional BouncyCastle
  classes, so a minified Android build warns. Today the four
  `-dontwarn org.bouncycastle...` lines (#236) live in
  `app/proguard-rules.pro`.
- The published JVM artifact ships them as
  `META-INF/proguard/teslable-protocol.pro`, so minified consumers need no
  rules of their own. The app then drops its copy.

### Publishing `:protocol` as KMP

- Use `com.vanniktech.maven.publish` against the Central Portal. It
  publishes the root module with Gradle module metadata plus one artifact
  per target (`teslable-protocol-jvm`, `-iosarm64`, ...), and Gradle
  consumers resolve the right one automatically.
- Signing keys and the Central token live in CI secrets. Publishing runs
  only from a tag and needs the owner's go-ahead (store/account rule).
- ABI: the Kotlin Gradle plugin's built-in ABI validation covers the KMP
  klib ABI and the JVM ABI, with a dump checked in. Explicit API mode goes
  on for `:protocol` in the same PR.
- The ABI dumps will churn until the public API settles (#118) and the #119
  key seam lands. That churn is expected, and the dumps are not a
  compatibility promise before the first publish.
- The first publish waits for the Apple targets, so the first release is
  already multiplatform and no consumer starts on a JVM-only coordinate.
- Docs: Dokka generates the API reference. The library gets its own README
  with a short sample (pair, open a session, read the charge state), per
  the library plan's documentation step.
- Swift consumers get an XCFramework through the `XCFramework` Gradle task.
  SKIE/SPM packaging is a later, separate decision.

### Not in scope

- Keystore and Secure Enclave `VehicleKey` implementations, and what portable
  (backup) mode means for a key that cannot be exported; the seam itself
  landed in #119.
- Moving app-level code into the library.
