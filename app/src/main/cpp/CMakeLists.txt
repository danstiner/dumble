cmake_minimum_required(VERSION 3.22.1)
project(dumble C)

include(FetchContent)

# RNNoise: portable C — built on BOTH Android and the host eval build.
FetchContent_Declare(
    rnnoise
    GIT_REPOSITORY https://github.com/xiph/rnnoise.git
    GIT_TAG        6cbfd53eb348a8d394e0757b4025c6ded34eb2b6
)
FetchContent_MakeAvailable(rnnoise)
file(GLOB RNNOISE_SRC ${rnnoise_SOURCE_DIR}/src/*.c)

if(ANDROID)
    FetchContent_Declare(
        opus
        GIT_REPOSITORY https://github.com/xiph/opus.git
        GIT_TAG        v1.5.2
    )
    set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(opus)

    add_library(dumble SHARED opus_jni.c rnnoise_jni.c ${RNNOISE_SRC})
    target_include_directories(dumble PRIVATE ${rnnoise_SOURCE_DIR}/include)
    target_link_libraries(dumble opus)
    target_link_options(dumble PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    add_library(dumble SHARED rnnoise_jni.c ${RNNOISE_SRC})
    target_include_directories(dumble PRIVATE ${rnnoise_SOURCE_DIR}/include ${JNI_INCLUDE_DIRS})
    set_target_properties(dumble PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
endif()
