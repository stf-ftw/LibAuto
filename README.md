
# LibAuto

**An experimental Android Auto head-unit receiver for Android tablets and aftermarket Android head units.**

This repository is a customized fork of the original [stf-ftw/LibAuto](https://github.com/stf-ftw/LibAuto) project, featuring additional modifications, experimentation, and wireless Android Auto connectivity implementations.

Customized and developed by **[AggarwalVansh](https://github.com/AggarwalVansh)**, with assistance from **AntiGravity and REA**.

LibAuto brings Android Auto projection to Android devices through USB and experimental wireless connectivity. It handles phone connectivity, projection decoding, audio playback, touch input, media controls, and vehicle sensor data locally.

This project is not affiliated with, endorsed by, sponsored by, or connected to Google, Android, Android Auto, or any vehicle/head-unit vendor. Android, Android Auto, Google, and other product names are trademarks of their respective owners.

---

## Project Status

| Component | Status |
|---|---|
| USB Android Auto projection | Functional |
| Automatic AOAP initialization | Implemented |
| Video decoding and rendering | Implemented |
| Audio playback | Implemented |
| Touch and gesture forwarding | Implemented |
| Hardware/media key forwarding | Implemented |
| Microphone channel | Implemented |
| GPS-backed vehicle speed | Implemented |
| Basic vehicle sensors | Implemented |
| Bluetooth wireless connectivity | Implemented, testing |
| Headunit Server wireless method | Implemented, testing |

The USB projection implementation is functional, while wireless connectivity is currently undergoing testing.

This fork builds upon the original LibAuto implementation and introduces additional development focused on expanding connectivity options and improving the overall head-unit experience.

### Wireless Android Auto Development

Wireless connectivity has been experimentally implemented using two approaches:

**1. Bluetooth-based connection**

An experimental wireless connectivity implementation using Bluetooth.

**2. Headunit Server method**

An alternative wireless connection approach based on the Headunit Server method.

Both implementations are currently undergoing testing. Reliability, connection stability, and compatibility across different Android phones and head-unit devices are still being evaluated.

Wireless functionality should be considered experimental and may not work consistently across all devices.

---

## Original Project & Credits

This repository is a fork and customized continuation of:

**[stf-ftw/LibAuto](https://github.com/stf-ftw/LibAuto)**

All original project work, existing implementations, and contributions from the upstream repository remain credited to their respective original authors and contributors.

A huge thank you to **[@stf-ftw](https://github.com/stf-ftw)** for creating and maintaining the original LibAuto project and making this fork possible.

This fork builds upon that foundation with additional modifications and experimental development, particularly around wireless Android Auto connectivity through Bluetooth and the Headunit Server method.

Additional development and customization have been carried out with assistance from **AntiGravity and REA**, guided by my own ideas and experimentation.

Please visit the original repository for upstream development, original documentation, and project history:

[**Original LibAuto Repository →**](https://github.com/stf-ftw/LibAuto)

---

## Compatible Platforms

### Known Working

- HarmonyOS 4.2 / Android 12 tablet, Snapdragon 865, USB host mode.
- Android aftermarket head units that support normal APK installation and expose USB host devices to applications.

### Designed For

- Android 8.0+ (`minSdk 26`).
- Android 15 target (`targetSdk 35`).
- `armeabi-v7a` and `arm64-v8a` devices.
- Displays around 800x480, 1024x600, 1280x720, 1920x1080, and native-aspect scaled modes.

### Requirements

- USB host support for wired projection.
- A compatible USB cable and port.
- Runtime permissions for USB, microphone, and location where required.
- A phone with Android Auto installed and enabled for wired projection.

Wireless requirements depend on the selected connection method and device compatibility.

---

## Features

- One-tap session start from a connected USB device list.
- Optional autoconnect when exactly one USB device is present.
- Projection quality options: 480p, 720p, and 1080p.
- Native-aspect option for non-16:9 displays.
- Local video rendering with letterbox/fill handling.
- Touch and two-pointer gesture forwarding.
- Learnable hardware/media key forwarding for play, pause, stop, next, previous, rewind, fast-forward, voice, call, and related controls.
- Audio playback through Android `AudioTrack`.
- Microphone channel support for Android Auto voice input.
- Sensor channel support for driving status, night mode, GPS speed, gear, and parking brake.
- Conservative file logging to reduce unnecessary NAND writes.
- Session reset on disconnect/replug to prevent stale decoder/framebuffer states.
- Experimental wireless connectivity through Bluetooth and the Headunit Server method.

---

## Download & Installation

**Download the latest available APK from GitHub Releases:**

[**Download LibAuto →**](https://github.com/AggarwalVansh/LibAuto/releases)

### Standard APK

`LibAuto-standard.apk`

Recommended for normal Android tablets and modern Android head units.

Installation:

```bash
adb install -r LibAuto-standard.apk
```

**Android application ID:**

```text
ro.stf_ftw.libauto
```

---

## Usage

1. Install and open LibAuto on your Android tablet or head unit.
2. Select the desired projection quality.
3. Configure native screen aspect if required.
4. Connect your Android phone using the supported connection method.
5. Accept Android Auto prompts on the phone if shown.
6. Start the projection session.
7. Use the fullscreen projection interface.

If a session ends unexpectedly, reconnect the phone or return to the device list and start a new session.

Wireless connection methods are experimental and may require additional device-specific setup.

---

## Building from Source

### Prerequisites

- Android SDK with API 35 platform and build-tools.
- Android NDK 25.1.8937393.
- JDK 17.
- Network access for initial vcpkg/native dependency setup unless the local cache is already populated.

### Build Commands

```bash
./gradlew assemblePlayRelease bundlePlayRelease
```

### Release Outputs

```text
app/build/outputs/apk/play/release/LibAuto-play-release-unsigned.apk

app/build/outputs/bundle/playRelease/LibAuto-play-release.aab
```

The standard APK is distributed as:

```text
LibAuto-standard.apk
```

For current Google Play submissions, Google's [target API requirements](https://developer.android.com/google/play/requirements/target-sdk) apply. LibAuto's standard/Play flavor targets API 35.

---

## Repository Structure

```text
LibAuto/
├── app/                    # Android application and projection implementation
├── native/                 # JNI bridge and Android USB transport
├── third_party/
│   └── aasdk-2.1/           # Vendored AASDK source
├── docs/                   # Documentation, release notes and credits
├── scripts/                # Release packaging helpers
└── README.md
```

---

## Important Limitations

- Android Auto is not a public receiver protocol. Compatibility may change with phone-side Android Auto updates.
- LibAuto is not a certified Android Auto receiver.
- Wireless connectivity is experimental and currently undergoing testing.
- Bluetooth and Headunit Server connection methods may exhibit device-specific compatibility issues.
- Some aftermarket head units restrict or virtualize USB devices, preventing third-party applications from accessing them.

---

## Development Philosophy & Support Expectations

This fork is a personal, experimental customization of the original LibAuto project.

Development and customization have been carried out with assistance from **AntiGravity and REA**, alongside my own ideas, direction, and experimentation.

The goal is to explore possibilities, experiment with new approaches, and help bring ideas to life that could be useful to the wider Android Auto developer community.

Some implementations, including the experimental wireless connectivity methods, are driven by personal ideas and are intended to help developers explore possibilities and get their own implementations working.

### A Note on Support

Please understand that this is **not a full-time maintained project or a commercial product**.

- I cannot guarantee continuous maintenance or immediate issue resolution.
- Extended support, frequent updates, and guaranteed compatibility are not promised.
- Features may be experimental, incomplete, or device-dependent.
- Development happens as time and availability permit.
- Bug fixes and feature requests may not always receive immediate attention.
- Pull requests, suggestions, testing, and community contributions are welcome.

The intention is to share ideas, experiments, and useful implementations with other developers rather than promise a fully supported standalone product.

**Think of this repository as a contribution to experimentation and development, not a commitment to long-term support.**

---

## Contributing & Upstream Development

Development on this fork is ongoing.

The primary focus of my additional development work has been implementing experimental wireless Android Auto connectivity through:

- Bluetooth-based connectivity.
- The Headunit Server method.

Both implementations are currently being tested for reliability and device compatibility.

I am looking forward to contributing these improvements back to the original project and collaborating with the upstream maintainer to get the changes reviewed and, hopefully, merged into the official LibAuto codebase.

The broader goal is to explore ideas, implement useful improvements, and make them available to other developers working on similar projects.

Feedback, testing, bug reports, and contributions are welcome.

When reporting issues, please include:

- Android phone model and Android version.
- Head-unit/tablet model and Android version.
- Connection method (USB / Bluetooth / Headunit Server).
- Relevant logs and reproduction steps.

---

## Developer & Maintainer of This Fork

**[AggarwalVansh](https://github.com/AggarwalVansh)**

Working on additional development, customizations, and experimental wireless Android Auto connectivity implementations.

- GitHub: [@AggarwalVansh](https://github.com/AggarwalVansh)
- Fork: [AggarwalVansh/LibAuto](https://github.com/AggarwalVansh/LibAuto)
- Upstream: [stf-ftw/LibAuto](https://github.com/stf-ftw/LibAuto)

---

## License

LibAuto project code is intended to be released under the **GNU General Public License v3.0 (GPLv3)**.

See [LICENSE](LICENSE) for details.

This repository is based on the original LibAuto project. Original code and third-party dependencies retain their applicable licenses and copyright notices.

Please review [Credits](docs/CREDITS.md) and dependency license files before redistribution.
