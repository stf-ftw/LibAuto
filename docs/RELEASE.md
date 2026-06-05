# Release

Current release build:

- Version name: `0.7`
- Version code: `7`
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

## Changes Since v0.6

- Fixed the drag and pinch-to-zoom smoothness regression by keeping media delivery off the AA protocol path and avoiding USB status work on packet hot paths.
- Fixed a session-freeze/exit bug caused by concurrent USB device status snapshots during active projection.
- Fixed fullscreen recovery after external overlays such as a reverse camera: LibAuto now reclaims immersive mode when focus returns.
- Media control key support is still experimental and may not work on every head unit.
- Wireless Android Auto remains in progress and is not exposed as a normal launcher flow yet.
- I am currently in an exam session, so development updates may be slower over the next four weeks.

## Notes

- Persistent logs are size-capped under `Android/data/ro.stf_ftw.libauto/files/LibAutoLogs/` to avoid unbounded NAND writes.
- LibAuto is not affiliated with or endorsed by Google, Android Auto, or any vehicle/head-unit vendor.
