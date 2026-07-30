cmake_minimum_required(VERSION 3.22)
project(dumble_core_host CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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
target_link_libraries(dumble_core PUBLIC opus)

enable_testing()
file(GLOB CORE_TESTS CONFIGURE_DEPENDS ${REPO_ROOT}/app/src/test/cpp/*.cpp)
add_executable(dumble_core_tests ${CORE_TESTS})
target_link_libraries(dumble_core_tests dumble_core GTest::gtest_main)
include(GoogleTest)
gtest_discover_tests(dumble_core_tests)
