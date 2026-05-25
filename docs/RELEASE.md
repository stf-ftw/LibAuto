# Release

Current release build:

- Version name: `0.5`
- Version code: `5`
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

## Changes Since v0.4

- Restored reliable two-finger gesture forwarding by preserving Android Auto's raw multi-touch pointer actions.
- Fixed intermittent green/macroblock video corruption by feeding H.264 frames to the decoder in reference order and waiting for a clean keyframe after queue recovery.
- Added the missing stop, rewind, and fast-forward actions to the media-key learner and Android media-session bridge.
- Shortened the native USB idle fallback so missed unplug events return to the launcher faster.
- Hid the experimental wireless Android Auto controls from the launcher while wireless support remains under development.

## Notes

- Persistent logs are size-capped under `Android/data/ro.stf_ftw.libauto/files/LibAutoLogs/` to avoid unbounded NAND writes.
- LibAuto is not affiliated with or endorsed by Google, Android Auto, or any vehicle/head-unit vendor.
