cmake_minimum_required(VERSION 3.22.1)
project(personalmedia LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_library(personalmedia SHARED
    jni_bridge.cpp
    portable/streaming_torrent_session.cpp
    portable/mpv_range_server.cpp
    portable/native_live_tv.cpp
    portable/matroska_probe.cpp
    portable/matroska_clusters.cpp
    portable/matroska_seek.cpp
    portable/matroska_timeline.cpp
    portable/matroska_cues.cpp
    portable/matroska_mp4.cpp)
target_include_directories(personalmedia PRIVATE portable)
if(ANDROID)
    # NDK r27 and older need explicit 16 KB LOAD/RELRO page alignment.
    # Required for Google Play's 16 KB memory-page devices; this does not
    # repair separately staged third-party .so files such as libmpv.so.
    target_link_options(personalmedia PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
endif()
find_library(log-lib log)
target_link_libraries(personalmedia PRIVATE ${log-lib})
# STR-5B: explicit, ABI-matched Android libtorrent dependency integration.
# Plain :app:assembleDebug remains a safe baseline and never claims P2P support.
# To build real P2P: -PpmTorrent=true and supply PM_TORRENT_DEPS_ROOT
# as an explicit CMake definition (preferred) or a process environment
# variable (legacy), pointing at the installed folder of pm-* triplets.
option(PM_REQUIRE_LIBTORRENT "Require a real libtorrent for this build" OFF)
if(PM_REQUIRE_LIBTORRENT)
    if(NOT ANDROID)
        message(FATAL_ERROR "STR-5B torrent link mode requires the Android NDK")
    endif()
    if(DEFINED PM_TORRENT_DEPS_ROOT AND NOT "${PM_TORRENT_DEPS_ROOT}" STREQUAL "")
        set(_pm_requested_deps_root "${PM_TORRENT_DEPS_ROOT}")
    elseif(DEFINED ENV{PM_TORRENT_DEPS_ROOT} AND NOT "$ENV{PM_TORRENT_DEPS_ROOT}" STREQUAL "")
        set(_pm_requested_deps_root "$ENV{PM_TORRENT_DEPS_ROOT}")
    else()
        message(FATAL_ERROR "STR-5B: PM_TORRENT_DEPS_ROOT missing. Pass -PpmTorrentDepsRoot=<installed directory> to Gradle or set PM_TORRENT_DEPS_ROOT.")
    endif()

    # Gradle/NDK generates one CMake build per Android ABI.
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(_pm_triplet "pm-arm64-android")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(_pm_triplet "pm-arm-neon-android")
    elseif(ANDROID_ABI STREQUAL "x86")
        set(_pm_triplet "pm-x86-android")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(_pm_triplet "pm-x64-android")
    else()
        message(FATAL_ERROR "STR-5B: unsupported Android ABI '${ANDROID_ABI}'")
    endif()

    file(TO_CMAKE_PATH "${_pm_requested_deps_root}" _pm_deps_root)
    set(_pm_prefix "${_pm_deps_root}/${_pm_triplet}")
    if(NOT EXISTS "${_pm_prefix}/include/libtorrent/session.hpp")
        message(FATAL_ERROR "STR-5B: real Android ${ANDROID_ABI} libtorrent not installed at ${_pm_prefix}; do not generate a disabled APK")
    endif()
    file(GLOB _pm_lt_archives "${_pm_prefix}/lib/*torrent*.a")
    if(NOT _pm_lt_archives OR NOT EXISTS "${_pm_prefix}/lib/libssl.a" OR
       NOT EXISTS "${_pm_prefix}/lib/libcrypto.a")
        message(FATAL_ERROR "STR-5B: missing real ABI-specific static libtorrent/OpenSSL archives in ${_pm_prefix}/lib")
    endif()

    # Search the specific ABI prefix; do not accidentally link the Windows
    # host's OpenSSL or Boost. NDK toolchain package search is otherwise rooted
    # in the Android sysroot and may not find vcpkg package configs.
    list(PREPEND CMAKE_PREFIX_PATH "${_pm_prefix}")
    list(PREPEND CMAKE_FIND_ROOT_PATH "${_pm_prefix}")
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
    find_package(LibtorrentRasterbar CONFIG REQUIRED
        HINTS "${_pm_prefix}" "${_pm_prefix}/share/LibtorrentRasterbar"
        NO_DEFAULT_PATH)
    if(NOT TARGET LibtorrentRasterbar::torrent-rasterbar)
        message(FATAL_ERROR "STR-5B: LibtorrentRasterbar config did not export the torrent-rasterbar target")
    endif()
    target_link_libraries(personalmedia PRIVATE LibtorrentRasterbar::torrent-rasterbar)
    target_compile_definitions(personalmedia PRIVATE PM_WITH_LIBTORRENT=1)
    message(STATUS "STR-5B: ABI-specific libtorrent link configured for ${ANDROID_ABI} (${_pm_triplet}); actual compilation still required")
else()
    message(STATUS "PersonalMedia: torrent engine disabled (use -PpmTorrent=true with NDK libtorrent dependencies)")
endif()

if(ANDROID)
    target_sources(personalmedia PRIVATE mpv_surface.cpp)
    target_link_libraries(personalmedia PRIVATE EGL GLESv2 android dl)
endif()
