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

# RNNoise has no upstream CMake; fetch source and compile src/*.c directly.
FetchContent_Declare(
    rnnoise
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG        6cbfd53eb348a8d394e0757b4025c6ded34eb2b6
)
FetchContent_MakeAvailable(rnnoise)

file(GLOB RNNOISE_SRC ${rnnoise_SOURCE_DIR}/src/*.c)

add_library(dumbleopus SHARED opus_jni.c rnnoise_jni.c ${RNNOISE_SRC})
target_include_directories(dumbleopus PRIVATE ${rnnoise_SOURCE_DIR}/include)
target_link_libraries(dumbleopus opus)
target_link_options(dumbleopus PRIVATE "-Wl,-z,max-page-size=16384")
