# Release

Current release build:

- Version name: `0.4`
- Version code: `4`
- Application id: `ro.stf_ftw.libauto`
- Minimum SDK: 26
- ABIs: `armeabi-v7a`, `arm64-v8a`

## Flavors

- `play`: targetSdk 35, packaged publicly as `LibAuto-standard.apk`.
- `compatibility`: targetSdk 28, packaged publicly as `LibAuto-legacy-headunit.apk` for sideloading on old/forked Android head units.

## Build Command

```bash
./gradlew assembleCompatibilityRelease bundlePlayRelease assemblePlayRelease
```

## Release Outputs

Release artifacts should be generated outside the source tree:

```text
LibAuto-standard.apk
LibAuto-legacy-headunit.apk
```

Verification reports:

```text
LibAuto-standard.apk.verify.txt
LibAuto-legacy-headunit.apk.verify.txt
```

## Signing

Do not commit signing keys or generated release artifacts. Use your own upload key for distribution and keep it backed up securely.

## Verify APKs

```bash
apksigner verify --verbose --print-certs LibAuto-standard.apk
```

## Which APK Should I Install?

Download `LibAuto-standard.apk` first.

If it does not install or crashes immediately on an older/Chinese Android head unit, try `LibAuto-legacy-headunit.apk` instead.

## Changes Since v0.3

- Added a dedicated media-key mapping screen opened from the launcher gear button.
- Expanded learnable head-unit controls to include previous, next, play/pause, play, pause, stop, rewind, fast-forward, voice, call, and end-call.
- Kept the launcher layout fullscreen while moving media-key setup out of the bottom of the main screen.
- Routed learned hard-key actions through the Android Auto button/input path used by the active projection session.
- Kept the standard APK on current Android/Play target requirements and the legacy APK on older target behavior for problematic sideloaded head units.

## Notes

- Persistent logs are size-capped under `Android/data/ro.stf_ftw.libauto/files/LibAutoLogs/` to avoid unbounded NAND writes.
- Play Store acceptance is not guaranteed; Android Auto/head-unit apps can be sensitive to policy, certification, and trademark review.
- LibAuto is not affiliated with or endorsed by Google, Android Auto, or any vehicle/head-unit vendor.
