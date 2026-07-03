# TabSSH native library (SPICE client + JNI shim)
#
# This build unit is the entry point for the NDK toolchain when
# externalNativeBuild is enabled in app/build.gradle. Task #12 lands
# only the harness — the actual SPICE C sources land in tasks #13/#14.
#
# Prebuilt static dependencies (libspice-client, glib, pixman, openssl,
# spice-protocol, opus) live in ../../../libs/spice/${ANDROID_ABI}/lib
# and their headers in ../../../libs/spice/${ANDROID_ABI}/include. See
# ../../../libs/spice/README.md for the fetch + cross-compile workflow.
#
# When no prebuilts are present (the default state on a fresh clone),
# this file compiles a single stub source that exports a no-op JNI
# symbol so the AGP build stays green. The JNI layer refuses to load
# any SPICE symbol at runtime unless the prebuilts are actually there —
# see SpiceLoader.kt (task #13).

cmake_minimum_required(VERSION 3.22.1)
project(tabssh_native LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Absolute path to the per-ABI prebuilt root.
# Structure: libs/spice/<abi>/{include,lib}
set(SPICE_PREBUILT_ROOT "${CMAKE_SOURCE_DIR}/../../../../libs/spice/${ANDROID_ABI}")

set(SPICE_AVAILABLE FALSE)
if(EXISTS "${SPICE_PREBUILT_ROOT}/lib/libspice-client-glib-2.0.a")
    set(SPICE_AVAILABLE TRUE)
endif()

add_library(tabssh_native SHARED spice_stub.c)

target_compile_definitions(tabssh_native PRIVATE
    $<$<BOOL:${SPICE_AVAILABLE}>:TABSSH_SPICE_AVAILABLE=1>
)

if(SPICE_AVAILABLE)
    target_include_directories(tabssh_native PRIVATE
        "${SPICE_PREBUILT_ROOT}/include"
        "${SPICE_PREBUILT_ROOT}/include/spice-1"
        "${SPICE_PREBUILT_ROOT}/include/spice-client-glib-2.0"
        "${SPICE_PREBUILT_ROOT}/include/glib-2.0"
        "${SPICE_PREBUILT_ROOT}/lib/glib-2.0/include"
    )
    target_link_directories(tabssh_native PRIVATE
        "${SPICE_PREBUILT_ROOT}/lib"
    )
    # Static-link the SPICE + glib chain so the resulting .so is
    # self-contained and does not require any additional prebuilt .so
    # at runtime.
    target_link_libraries(tabssh_native PRIVATE
        -Wl,--start-group
        spice-client-glib-2.0
        spice-common-client
        gio-2.0
        gobject-2.0
        gmodule-2.0
        gthread-2.0
        glib-2.0
        pixman-1
        opus
        ssl
        crypto
        z
        -Wl,--end-group
        log
        android
    )
else()
    target_link_libraries(tabssh_native PRIVATE log android)
endif()
