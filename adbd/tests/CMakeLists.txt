# Host unit tests for the shared adb protocol core.
add_subdirectory(${VENDOR_DIR}/googletest ${CMAKE_BINARY_DIR}/googletest EXCLUDE_FROM_ALL)

set(ADB_TEST_SOURCES
  ${ADB_DIR}/adb_io_test.cpp
  ${ADB_DIR}/adb_listeners_test.cpp
  ${ADB_DIR}/adb_utils_test.cpp
  ${ADB_DIR}/fdevent/fdevent_test.cpp
  ${ADB_DIR}/shell_service_protocol_test.cpp
  ${ADB_DIR}/socket_spec_test.cpp
  ${ADB_DIR}/socket_test.cpp
  ${ADB_DIR}/sysdeps_test.cpp
  ${ADB_DIR}/sysdeps/stat_test.cpp
  ${ADB_DIR}/transport_test.cpp
  ${ADB_DIR}/types_test.cpp
  ${ADB_DIR}/test_utils/test_utils.cpp
  ${VENDOR_DIR}/libbase/test_utils.cpp
)

add_executable(adbd_tests ${ADB_TEST_SOURCES})
target_link_libraries(adbd_tests PRIVATE adbd_core gtest gtest_main)
target_compile_options(adbd_tests PRIVATE -Wall -Wextra -Wno-unused-parameter)

include(CTest)
add_test(NAME adbd_tests COMMAND adbd_tests)
