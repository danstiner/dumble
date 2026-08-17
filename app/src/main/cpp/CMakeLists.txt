cmake_minimum_required(VERSION 3.22.1)
project(dumble C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static, no tests, no CLI tools — we link one shared object and ship only that.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/opus
                 ${CMAKE_BINARY_DIR}/opus)

# Optimise libopus even in a debug build. It inherits CMAKE_BUILD_TYPE, so a debug variant
# compiles it at -O0, where encoding one 20 ms frame measured ~5 ms on a Pixel 7a against a 20 ms
# budget — a quarter of the budget spent in third-party code nobody sets a breakpoint in, and
# enough to make the capture instrumentation's timings say nothing about a release build.
# Debug-only via a generator expression rather than unconditionally: target_compile_options appends
# after the config's own flags and would win, so scoping it to Debug leaves every other
# configuration compiling opus exactly as that configuration chose. (Today the release variant is
# RelWithDebInfo, which already means -O2, so this changes nothing there — but it stays true if the
# release configuration ever changes.)
target_compile_options(opus PRIVATE $<$<CONFIG:Debug>:-O2>)

find_package(oboe REQUIRED CONFIG)

# CONFIGURE_DEPENDS for the same reason as the host harness: without it an existing .cxx build
# tree keeps linking libdumble.so without a newly added core source until something else happens
# to trigger a reconfigure.
file(GLOB CORE_SRC CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/core/*.cpp)
add_library(dumble SHARED
    ${CORE_SRC}
    android/OboeCapture.cpp
    android/capture_jni.cpp
    android/playout_jni.cpp)
target_include_directories(dumble PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(dumble opus oboe::oboe log)
set(DUMBLE_VERSION_SCRIPT ${CMAKE_CURRENT_SOURCE_DIR}/dumble.map)
target_link_options(dumble PRIVATE
    # Android 15+ ships 16 KB page-size devices; without this the loader rejects the library.
    "-Wl,-z,max-page-size=16384"
    # Export only the JNI entry points from the dynamic symbol table; see dumble.map.
    "-Wl,--version-script=${DUMBLE_VERSION_SCRIPT}")
set_target_properties(dumble PROPERTIES LINK_DEPENDS ${DUMBLE_VERSION_SCRIPT})
