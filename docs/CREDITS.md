# Credits

This file documents project components and references used while building LibAuto. Hardware-specific behavior was tested locally, but no vendor firmware, proprietary binaries, or commercial app code is included in this repository.

## Core Components

- AASDK 2.1: vendored under `third_party/aasdk-2.1` and used for Android Auto protocol/channel handling. Keep its upstream license and attribution intact when publishing.
- The vendored AASDK snapshot includes upstream TLS/auth test material in its messenger cryptor implementation, including an RSA private-key block. That material is part of the upstream-derived AASDK code path and is not a LibAuto signing key, upload key, maintainer key, or personal secret.
- Android SDK, Android NDK, Gradle, Android Gradle Plugin, Kotlin, CMake, and Ninja: used for Android and native builds.
- AndroidX Core, AppCompat, Lifecycle Service, and Material Components: used by the Android application layer.
- vcpkg: used to build native dependencies for Android ABIs.
- Boost, OpenSSL, Protocol Buffers, Abseil, utf8-range, and libusb: native dependencies pulled through vcpkg/AASDK.
- Android platform media APIs: `MediaCodec`, `AudioTrack`, `AudioRecord`, `MediaSession`, and USB host APIs.

## Research References

- AASDK protocol structures and examples.
- OpenAuto-style Android Auto head-unit behavior.
- Crankshaft/OpenAuto Raspberry Pi deployment behavior.
- Android Open Accessory and Android USB host documentation.
- Google Play target API documentation for release flavor decisions.

Research references are credited for protocol and behavior comparison. Unless code is present in this repository, no source code should be assumed copied from those projects.

## Trademark Notice

Android, Android Auto, Google, and other product names are trademarks of their respective owners. LibAuto is an independent compatibility project and is not affiliated with or endorsed by Google or any vehicle/head-unit vendor.
