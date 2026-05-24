# Release

Current release build:

- Version name: `0.3`
- Version code: `3`
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

## Notes

- Download `LibAuto-standard.apk` first.
- If it does not install or crashes immediately on an older/Chinese Android head unit, try `LibAuto-legacy-headunit.apk` instead.
- Persistent logs are size-capped under `Android/data/ro.stf_ftw.libauto/files/LibAutoLogs/` to avoid unbounded NAND writes.
- Play Store acceptance is not guaranteed; Android Auto/head-unit apps can be sensitive to policy, certification, and trademark review.
