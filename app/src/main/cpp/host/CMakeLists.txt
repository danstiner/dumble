cmake_minimum_required(VERSION 3.22)
project(dumble_core_host CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Claim the default before opus can. Its OpusBuildtype.cmake writes CMAKE_BUILD_TYPE into the
# cache whenever it finds it unset, and a build tree has one cache with no directory scoping, so
# vendoring opus otherwise lets a dependency choose our default — silently, and it chooses
# Release. Debug, because this tree exists to run tests and Release defines NDEBUG, which compiles
# the assertions some of them check straight out. An explicit -DCMAKE_BUILD_TYPE still wins: both
# this and opus only act when nothing was asked for.
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Debug CACHE STRING "Choose the type of build." FORCE)
endif()

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

# libopus's own CMakeLists is portable — plain project(Opus LANGUAGES C), no Android assumptions —
# so the same submodule that builds for arm64 builds for the host, in its own build directory.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
add_subdirectory(${REPO_ROOT}/third_party/opus ${CMAKE_BINARY_DIR}/opus)

set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
add_subdirectory(${REPO_ROOT}/third_party/googletest ${CMAKE_BINARY_DIR}/googletest)

file(GLOB CORE_SRC CONFIGURE_DEPENDS ${CORE_DIR}/core/*.cpp)
add_library(dumble_core STATIC ${CORE_SRC})
target_include_directories(dumble_core PUBLIC ${CORE_DIR})
# Compiles in the hooks a test needs to stage what only another thread can cause; the Android
# build never defines it.
target_compile_definitions(dumble_core PUBLIC DUMBLE_TESTING)
target_link_libraries(dumble_core PUBLIC opus)

enable_testing()
file(GLOB CORE_TESTS CONFIGURE_DEPENDS ${REPO_ROOT}/app/src/test/cpp/*.cpp)
add_executable(dumble_core_tests ${CORE_TESTS})
# The detector's weights and its reference traces are files, not synthetic fixtures, and ctest
# runs from the build tree — so the tests are told where the repo is rather than guessing from
# a relative path.
target_compile_definitions(dumble_core_tests PRIVATE
    DUMBLE_ASSETS_DIR="${REPO_ROOT}/app/src/main/assets"
    DUMBLE_TEST_DATA_DIR="${REPO_ROOT}/app/src/test/resources"
    DUMBLE_BUILD_TYPE="${CMAKE_BUILD_TYPE}")
target_link_libraries(dumble_core_tests dumble_core GTest::gtest_main)
include(GoogleTest)
gtest_discover_tests(dumble_core_tests)
