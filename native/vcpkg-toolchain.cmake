# vcpkg toolchain wrapper for Android builds.

if(DEFINED CMAKE_ANDROID_NDK)
    set(ANDROID_NDK "${CMAKE_ANDROID_NDK}" CACHE PATH "" FORCE)
endif()

if(DEFINED ANDROID_NDK)
    set(ANDROID_NDK_HOME "${ANDROID_NDK}" CACHE PATH "" FORCE)
    set(ENV{ANDROID_NDK} "${ANDROID_NDK}")
    set(ENV{ANDROID_NDK_HOME} "${ANDROID_NDK}")
    set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${ANDROID_NDK}/build/cmake/android.toolchain.cmake")
endif()

if(NOT DEFINED ANDROID_ABI)
    set(ANDROID_ABI "arm64-v8a" CACHE STRING "" FORCE)
endif()

if(NOT DEFINED VCPKG_TARGET_TRIPLET)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(VCPKG_TARGET_TRIPLET "arm64-android-api26")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(VCPKG_TARGET_TRIPLET "arm-neon-android-api26")
    else()
        message(FATAL_ERROR "Unsupported Android ABI for vcpkg: ${ANDROID_ABI}")
    endif()
endif()

if(NOT DEFINED VCPKG_OVERLAY_TRIPLETS)
    get_filename_component(_vcpkg_overlay_triplets "${CMAKE_CURRENT_LIST_DIR}/vcpkg-triplets" ABSOLUTE)
    set(VCPKG_OVERLAY_TRIPLETS "${_vcpkg_overlay_triplets}")
endif()

if(NOT DEFINED VCPKG_FEATURE_FLAGS)
    set(VCPKG_FEATURE_FLAGS "manifests")
endif()

if(NOT DEFINED VCPKG_ROOT)
    get_filename_component(_vcpkg_root "${CMAKE_CURRENT_LIST_DIR}/../third_party/vcpkg" ABSOLUTE)
    set(VCPKG_ROOT "${_vcpkg_root}")
endif()

include("${VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")
