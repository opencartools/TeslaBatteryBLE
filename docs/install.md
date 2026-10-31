# Installing

The README has the short version. Stable releases are not out yet; signed stable releases and F-Droid are on the [roadmap](master-plan.md).

## GitHub Releases

Install the APK from the latest [release](https://github.com/opencartools/TeslaBatteryBLE/releases).
Releases are signed with the owner key and include prereleases such as betas.

## PR builds

Every pull request that compiles has its debug APK in the rolling
[**PR builds**](https://github.com/opencartools/TeslaBatteryBLE/releases/tag/pr-builds)
prerelease: one asset per open PR, named after the PR number and commit
(`TeslaBatteryBLE-pr417-a1b2c3d.apk`). It is replaced on every push and deleted
when the PR closes, and the link needs no GitHub login and no unzipping.

These are test builds of `com.dzid26.teslable.pr`, signed with the shared CI
debug key instead of the release key, so they install next to the app rather
than over it. They are not app releases: do not point Obtainium at them.

## Obtainium (auto-updates from GitHub)

1. Open [Obtainium](https://github.com/ImranR98/Obtainium) → **Add App**
2. URL: `https://github.com/opencartools/TeslaBatteryBLE`
3. Obtainium tracks stable releases by default. To also follow tagged
   pre-releases (for example betas), enable **Include prereleases**.
4. Install and let Obtainium keep it updated

To follow `main` instead of tagged releases, every push to `main` that passes CI
updates one pre-release titled **Preview of main**: its tag is renamed to
`main-<sha7>` and its APK replaced, signed with the release key. In the app's
Obtainium settings:

1. Turn on **Include prereleases**.
2. Set the release-title filter to the regex `^Preview` so this app ignores
   every other release.
3. Turn on **Fall back to older releases**; without it Obtainium finds nothing
   whenever a newer tagged release exists above the preview.
4. Allow background updates and, on Android 12+, let Obtainium install silently
   (Settings -> Apps -> Obtainium -> Install unknown apps).

Users who follow tagged betas with **Include prereleases** and no title filter
are not affected as long as a tagged pre-release is newer than the preview; to be
sure, set the title filter to the inverted regex `^(Preview|PR builds)`
(Obtainium's invert switch), which also keeps the PR builds release out of the
update check.

The preview carries two APKs of the same commit, both minified with R8 and signed
with the release key: `...-release.apk` (not debuggable, like a tagged release) and
`...-debuggable.apk` (`adb run-as` can read its logs). They share package and
version, so either installs over the other without uninstalling. Obtainium asks
which APK to use; to pin it, set the APK-filter regex to `release\.apk$` or
`debuggable\.apk$`.

The preview is signed with the release key, so it only updates an install signed
with that key. A debug build you installed yourself uses the same package name
but a different key; Android then offers to uninstall first. Do not accept that:
uninstalling wipes the car pairing key. Install the preview next to a debug
build only after the debug build is gone from the phone on purpose.
