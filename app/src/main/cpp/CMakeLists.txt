cmake_minimum_required(VERSION 3.22.1)
project(dumbleopus C)

include(FetchContent)
FetchContent_Declare(
    opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG        v1.5.2
)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opus)

add_library(dumbleopus SHARED opus_jni.c)
target_link_libraries(dumbleopus opus)
