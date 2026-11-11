# Standalone adbd: AOSP packages/modules/adb daemon sources with focused patches.
#
# The vendored trees under vendor/ are pristine upstream copies. The patches in
# patches/ are applied to a build copy by build.sh, so the vendor tree stays
# untouched and updates stay a straight re-vendor.
cmake_minimum_required(VERSION 3.22)
project(adbd C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED ADBD_SRC)
  message(FATAL_ERROR "ADBD_SRC must point at the patched source copy")
endif()

set(ADB_DIR ${ADBD_SRC})
set(VENDOR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor)
set(STUB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/stubs)

# BoringSSL is the crypto library AOSP adbd links against. libcrypto backs the
# RSA key auth; libssl backs the --tls-listen tunnel the app's own channel uses.
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
add_subdirectory(${VENDOR_DIR}/boringssl ${CMAKE_BINARY_DIR}/boringssl)

# zstd backs the sendrecv_v2 compression that adb 36 negotiates by default.
enable_language(ASM)
file(GLOB ZSTD_SOURCES CONFIGURE_DEPENDS
  ${VENDOR_DIR}/zstd/lib/common/*.c
  ${VENDOR_DIR}/zstd/lib/compress/*.c
  ${VENDOR_DIR}/zstd/lib/decompress/*.c
)
file(GLOB ZSTD_ASM ${VENDOR_DIR}/zstd/lib/decompress/*.S)
add_library(zstd STATIC ${ZSTD_SOURCES} ${ZSTD_ASM})
target_include_directories(zstd PUBLIC ${VENDOR_DIR}/zstd/lib)
target_compile_definitions(zstd PRIVATE ZSTD_MULTITHREAD)

set(LIBBASE_SOURCES
  ${VENDOR_DIR}/libbase/abi_compatibility.cpp
  ${VENDOR_DIR}/libbase/chrono_utils.cpp
  ${VENDOR_DIR}/libbase/cmsg.cpp
  ${VENDOR_DIR}/libbase/errors_unix.cpp
  ${VENDOR_DIR}/libbase/file.cpp
  ${VENDOR_DIR}/libbase/hex.cpp
  ${VENDOR_DIR}/libbase/logging.cpp
  ${VENDOR_DIR}/libbase/mapped_file.cpp
  ${VENDOR_DIR}/libbase/parsebool.cpp
  ${VENDOR_DIR}/libbase/parsenetaddress.cpp
  ${VENDOR_DIR}/libbase/posix_strerror_r.cpp
  ${VENDOR_DIR}/libbase/process.cpp
  ${VENDOR_DIR}/libbase/properties.cpp
  ${VENDOR_DIR}/libbase/stringprintf.cpp
  ${VENDOR_DIR}/libbase/strings.cpp
  ${VENDOR_DIR}/libbase/threads.cpp
)

set(LIBCUTILS_SOURCES
  ${VENDOR_DIR}/libcutils/socket_inaddr_any_server_unix.cpp
  ${VENDOR_DIR}/libcutils/socket_local_client_unix.cpp
  ${VENDOR_DIR}/libcutils/socket_local_server_unix.cpp
  ${VENDOR_DIR}/libcutils/socket_network_client_unix.cpp
  ${VENDOR_DIR}/libcutils/sockets.cpp
  ${VENDOR_DIR}/libcutils/sockets_unix.cpp
  ${VENDOR_DIR}/libcutils/fs_config.cpp
)

set(ADB_CORE_SOURCES
  ${ADB_DIR}/adb.cpp
  ${ADB_DIR}/adb_io.cpp
  ${ADB_DIR}/adb_listeners.cpp
  ${ADB_DIR}/adb_mdns.cpp
  ${ADB_DIR}/adb_trace.cpp
  ${ADB_DIR}/adb_unique_fd.cpp
  ${ADB_DIR}/adb_utils.cpp
  ${ADB_DIR}/apacket_reader.cpp
  ${ADB_DIR}/fdevent/fdevent.cpp
  ${ADB_DIR}/fdevent/fdevent_epoll.cpp
  ${ADB_DIR}/services.cpp
  ${ADB_DIR}/socket_spec.cpp
  ${ADB_DIR}/sockets.cpp
  ${ADB_DIR}/sysdeps/env.cpp
  ${ADB_DIR}/sysdeps/errno.cpp
  ${ADB_DIR}/sysdeps/posix/network.cpp
  ${ADB_DIR}/sysdeps_unix.cpp
  ${ADB_DIR}/transport.cpp
  ${ADB_DIR}/transport_fd.cpp
  ${ADB_DIR}/types.cpp
  ${ADB_DIR}/daemon/auth.cpp
  ${ADB_DIR}/shell_service_protocol.cpp
  ${ADB_DIR}/crypto/x509_generator.cpp
  ${ADB_DIR}/tls/adb_ca_list.cpp
  ${ADB_DIR}/tls/tls_connection.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/stubs/diagnose_usb.cpp
)

set(ADBD_DAEMON_SOURCES
  ${ADB_DIR}/daemon/file_sync_service.cpp
  ${ADB_DIR}/daemon/framebuffer_service.cpp
  ${ADB_DIR}/daemon/logging.cpp
  ${ADB_DIR}/daemon/main.cpp
  ${ADB_DIR}/daemon/services.cpp
  ${ADB_DIR}/daemon/shell_service.cpp
  ${ADB_DIR}/daemon/watchdog.cpp
  ${ADB_DIR}/daemon/property_monitor.cpp
  ${ADB_DIR}/daemon/transport_socket_server.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/src/adbd_auth.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/stubs/libc_private.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/stubs/libselinux.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/stubs/build/version.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/stubs/log/log_stub.cpp
)

if(NOT ANDROID)
  # The host build (compile check and smoke test) has no bionic properties; the
  # watchdog and the bionic property stubs are Android-only.
  list(REMOVE_ITEM ADBD_DAEMON_SOURCES
    ${ADB_DIR}/daemon/watchdog.cpp
    ${ADB_DIR}/daemon/property_monitor.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/stubs/libc_private.cpp
  )
endif()

add_library(adbd_core STATIC
  ${LIBBASE_SOURCES}
  ${LIBCUTILS_SOURCES}
  ${ADB_CORE_SOURCES}
  ${VENDOR_DIR}/libcrypto_utils/android_pubkey.cpp
  ${ADB_DIR}/libs/libadbd_fs/adbd_fs.cpp
  ${VENDOR_DIR}/libasyncio/AsyncIO.cpp
)

target_include_directories(adbd_core BEFORE PUBLIC ${STUB_DIR})
if(NOT ANDROID)
  # The host compile check and smoke test need a few headers bionic provides.
  target_include_directories(adbd_core BEFORE PUBLIC ${STUB_DIR}/host)
endif()
target_include_directories(adbd_core PUBLIC
  ${ADB_DIR}
  ${ADB_DIR}/crypto/include
  ${ADB_DIR}/tls/include
  ${VENDOR_DIR}/libbase/include
  ${VENDOR_DIR}/libcutils/include
  ${VENDOR_DIR}/libcrypto_utils/include
  ${VENDOR_DIR}/libasyncio/include
  ${VENDOR_DIR}/liblog/include
  ${VENDOR_DIR}/adbd_auth/include
  ${ADB_DIR}/libs/libadbd_fs/include
  ${STUB_DIR}
)

target_compile_definitions(adbd_core PUBLIC
  ADB_HOST=0
  ADB_VERSION_MAJOR=1
  ADB_VERSION_MINOR=0
  ADB_SERVER_VERSION=41
  PLATFORM_TOOLS_VERSION="35.0.2"
)

target_compile_options(adbd_core PRIVATE
  -Wall -Wextra -Wno-unused-parameter -Wno-deprecated-declarations
)

target_link_libraries(adbd_core PUBLIC ssl crypto dl zstd)
if(ANDROID)
  target_link_libraries(adbd_core PUBLIC log)
endif()

add_executable(adbd ${ADBD_DAEMON_SOURCES})
target_link_libraries(adbd PRIVATE adbd_core)
target_compile_options(adbd PRIVATE -Wall -Wextra -Wno-unused-parameter)

install(TARGETS adbd RUNTIME DESTINATION bin)

option(ADBD_BUILD_TESTS "Build the host adb unit tests" OFF)
if(ADBD_BUILD_TESTS)
  add_subdirectory(tests)
endif()
