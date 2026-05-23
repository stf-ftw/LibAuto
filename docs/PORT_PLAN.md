# Port Plan

## Module boundaries

- Kotlin app (`app`)
  - UI, service lifecycle, and control surfaces.
  - JNI bridge to the native AASDK wrapper (`AasdkNative`).

- Native AASDK wrapper (`native`)
  - CMake build producing `libaasdk_android.so`.
  - JNI entry points: `nativeInit`, `nativeStart`, `nativeStop`, `nativeGetLastError`.
  - Owns AASDK lifecycle and logs to Android logcat.

- Transport layer (future)
  - USB host bulk I/O implemented in Kotlin or native.
  - Feeds raw streams into the AASDK transport abstraction.

## Step order

1) Build AASDK + wrapper as a shared library with Android NDK.
2) Verify JNI load + start/stop loop works in APK.
3) Add USB transport plumbing and connect to AASDK I/O abstraction.
4) Integrate video/audio sinks with decoded AA output.

## Notes

- AASDK upstream depends on Boost, Protobuf, OpenSSL, and libusb.
- These dependencies must be built for Android and linked into `libaasdk_android.so`.
