# Technical Notes

This document records the major changes made while turning the original Android Auto Display skeleton into LibAuto.

## USB And Session Flow

- Replaced manual staged testing with a connected-device list.
- Added one-tap start that requests USB permission, enables AOAP/accessory mode, claims bulk endpoints, and starts the Android Auto transport.
- Added optional autoconnect when exactly one USB device is available.
- Reset session, decoder, counters, and framebuffer state on disconnect/replug so old video frames do not block new sessions.
- Suppressed expected disconnect read/write failures in the main UI because unplugging a phone is normal operation.

## Android Auto Identity

- Changed application name and Android Auto head-unit identity from generic/OpenAuto-style names to `LibAuto`.
- Set Android application id to `ro.stf_ftw.libauto`.
- Kept USB host intent filters so Android can offer LibAuto for attached USB devices.

## Projection And Rendering

- Implemented Android Auto media channel handling through AASDK/JNI.
- Added local video decode/rendering with Android `MediaCodec`.
- Added audio playback with Android `AudioTrack`.
- Added 480p, 720p, and 1080p projection modes.
- Changed 16:9 480p from 800x480 to 854x480.
- Added native-aspect sizing for non-16:9 screens and separate touch-coordinate mapping.
- Restored fitted fullscreen rendering with letterbox/pillarbox behavior instead of stretching native-aspect video incorrectly.

## Input

- Implemented touch forwarding to the phone.
- Fixed coordinate mapping by separating screen viewport coordinates from negotiated projection coordinates.
- Limited active pointers to two to keep pinch/zoom usable without flooding the protocol.
- Added two-finger gesture support.
- Added hardware/media-key forwarding through Android `MediaSession`.

## Sensors

- Implemented basic fake-car sensor responses requested by Android Auto apps:
- Driving status: unrestricted.
- Night data: follows local/night configuration where available.
- Speed: GPS-backed tablet/head-unit speed when available, otherwise safe fallback.
- Gear: drive.
- Parking brake: released.

These keep navigation apps from waiting forever for vehicle data while avoiding unnecessary high-rate sensor spam.

## Stability And Performance

- Reduced excessive logging to protect head-unit NAND and avoid log-driven stalls.
- Avoided UI spam from normal USB read/write failures after unplug.
- Added disconnect handling for missing traffic/closed USB endpoints.
- Tuned audio/video handling after observed stalls where navigation app lifecycle changes affected audio smoothness.

## Build And Release

- Added release flavors:
- `play`: targetSdk 35 for current Google Play API-level requirements; released as `LibAuto-standard.apk`.
- `compatibility`: targetSdk 28 for sideloaded old/forked head-unit firmware; released as `LibAuto-legacy-headunit.apk`.
- Added API 35 compile support.
- Fixed the arm64 Android vcpkg triplet so libusb/autotools cross-build receives the correct host triplet.
- Built and signed release APK artifacts outside the repository.

## Known Risks

- Android Auto receiver behavior is not public API and may break with phone-side updates.
- Some head units expose misleading Android versions or block USB host APIs.
- The `compatibility`/legacy head-unit flavor is for sideloading only, not Google Play.
- Native dependency licenses must be reviewed before public binary redistribution.
