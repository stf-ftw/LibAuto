# Build (Fedora, CLI)

This project uses the Gradle wrapper and does not require Android Studio.

## Prereqs

1) Install JDK 17

```
sudo dnf install java-17-openjdk-devel
```

2) Install Android SDK command line tools

- If you already have an Android SDK, set `ANDROID_SDK_ROOT` to it and skip downloads.
- Otherwise:
  - Download the command line tools zip from https://developer.android.com/studio
  - Extract it to a directory such as `~/Android/Sdk/cmdline-tools/latest`

3) Install SDK packages

```
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## Build

From the project root:

```
cd LibAuto
./gradlew assembleCompatibilityRelease assemblePlayRelease
```

The unsigned release APKs will be at:

- `app/build/outputs/apk/compatibility/release/LibAuto-compatibility-release-unsigned.apk`
- `app/build/outputs/apk/play/release/LibAuto-play-release-unsigned.apk`

Use the compatibility flavor for older or forked Android head units. Use the play flavor for modern Android devices and Play-targeted builds.
