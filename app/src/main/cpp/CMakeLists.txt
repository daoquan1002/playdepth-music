cmake_minimum_required(VERSION 3.22.1)
project(playdepth_usbdac LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    playdepth_usbdac
    SHARED
    uac_descriptor.cpp
    usb_transport.cpp
    clock_sync.cpp
    audio_dsp_pipeline.cpp
    usb_audio_driver.cpp
    usb_audio_jni.cpp
)

target_include_directories(playdepth_usbdac PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(playdepth_usbdac PRIVATE
    -O3
    -fno-math-errno
    -fno-trapping-math
    -funroll-loops
    -ftree-vectorize
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -fstack-protector-strong
    -D_FORTIFY_SOURCE=2
    $<$<NOT:$<CONFIG:Debug>>:-DNDEBUG>
    $<$<NOT:$<CONFIG:Debug>>:-g0>
    $<$<NOT:$<CONFIG:Debug>>:-fomit-frame-pointer>
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(playdepth_usbdac PRIVATE -march=armv8-a+simd)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(playdepth_usbdac PRIVATE -mfpu=neon)
endif()

target_link_options(playdepth_usbdac PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    $<$<NOT:$<CONFIG:Debug>>:-Wl,-s>
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
)

find_library(log-lib log)

target_link_libraries(
    playdepth_usbdac
    ${log-lib}
)