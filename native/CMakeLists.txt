cmake_minimum_required(VERSION 3.22.1)
project(aasdk_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(AASDK_ROOT "${CMAKE_CURRENT_LIST_DIR}/../third_party/aasdk-2.1")

if(NOT EXISTS "${AASDK_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "AASDK not found at ${AASDK_ROOT}")
endif()

if(DEFINED VCPKG_INSTALLED_DIR AND DEFINED VCPKG_TARGET_TRIPLET)
    set(Boost_ROOT "${VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}" CACHE PATH "" FORCE)
    set(BOOST_ROOT "${Boost_ROOT}" CACHE PATH "" FORCE)
    set(Boost_NO_SYSTEM_PATHS ON CACHE BOOL "" FORCE)
    set(Boost_DIR "Boost_DIR-NOTFOUND" CACHE PATH "" FORCE)
    set(CMAKE_FIND_PACKAGE_PREFER_CONFIG OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${AASDK_ROOT}" "${CMAKE_BINARY_DIR}/aasdk_build")

add_library(aasdk_android SHARED
    aasdk_jni.cpp
    AndroidUsbTransport.cpp
    NativeLog.cpp
    UsbTransport.cpp
)

target_include_directories(aasdk_android PRIVATE
    "${AASDK_ROOT}/include"
    "${CMAKE_BINARY_DIR}/aasdk_build"
)

target_link_libraries(aasdk_android
    aasdk
    log
)
