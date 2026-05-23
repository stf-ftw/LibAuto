# Native Dependencies (vcpkg)

This project uses vcpkg in manifest mode to build native dependencies for Android.
All files are kept in-tree under `third_party/`.

## One-time setup

```
cd third_party/vcpkg
./bootstrap-vcpkg.sh
```

## Build

Gradle will invoke vcpkg automatically via the toolchain.
A binary cache is stored in `third_party/vcpkg-bincache`.

```
cd LibAuto
./gradlew assembleCompatibilityRelease assemblePlayRelease
```

## Notes

- Manifest: `native/vcpkg.json`
- Toolchain wrapper: `native/vcpkg-toolchain.cmake`
- Native ABIs: `armeabi-v7a` and `arm64-v8a`.
- Binary cache setting is passed via CMake arg `VCPKG_BINARY_SOURCES=clear;files,<cache>,readwrite`.
- If vcpkg bootstrap is missing, the CMake configure will fail.
