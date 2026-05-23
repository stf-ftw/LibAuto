# Release

Current release build:

- Version name: `0.1`
- Version code: `1`
- Application id: `ro.stf_ftw.libauto`
- Minimum SDK: 26
- ABIs: `armeabi-v7a`, `arm64-v8a`

## Flavors

- `compatibility`: targetSdk 28, intended for sideloading on old/forked Android head units.
- `play`: targetSdk 35, intended for Google Play-style release validation and AAB upload.

## Build Command

```bash
./gradlew assembleCompatibilityRelease bundlePlayRelease assemblePlayRelease
```

## Release Outputs

Release artifacts should be generated outside the source tree:

```text
LibAuto-compatibility-release-signed.apk
LibAuto-play-release-signed.apk
LibAuto-play-release-signed.aab
```

Verification reports:

```text
LibAuto-compatibility-release-signed.apk.verify.txt
LibAuto-play-release-signed.apk.verify.txt
LibAuto-play-release-signed.aab.verify.txt
```

## Signing

Do not commit signing keys or generated release artifacts. Use your own upload key for distribution and keep it backed up securely.

## Verify APKs

```bash
apksigner verify --verbose --print-certs LibAuto-play-release-signed.apk
```

## Verify AAB

```bash
jarsigner -verify -verbose -certs LibAuto-play-release-signed.aab
```

## Notes

- Use `LibAuto-play-release-signed.aab` for Play upload checks.
- Use `LibAuto-compatibility-release-signed.apk` for sideload testing on problematic head units.
- Play Store acceptance is not guaranteed; Android Auto/head-unit apps can be sensitive to policy, certification, and trademark review.
