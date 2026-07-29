cmake_minimum_required(VERSION 3.22.1)
project(dumble C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static, no tests, no CLI tools — we link one shared object and ship only that.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/opus
                 ${CMAKE_BINARY_DIR}/opus)

find_package(oboe REQUIRED CONFIG)

add_library(dumble SHARED opus_jni.c)
target_link_libraries(dumble opus oboe::oboe)
set(DUMBLE_VERSION_SCRIPT ${CMAKE_CURRENT_SOURCE_DIR}/dumble.map)
target_link_options(dumble PRIVATE
    # Android 15+ ships 16 KB page-size devices; without this the loader rejects the library.
    "-Wl,-z,max-page-size=16384"
    # Export only the JNI entry points from the dynamic symbol table; see dumble.map.
    "-Wl,--version-script=${DUMBLE_VERSION_SCRIPT}")
set_target_properties(dumble PROPERTIES LINK_DEPENDS ${DUMBLE_VERSION_SCRIPT})
