cmake_minimum_required(VERSION 3.22.1)
project(v2rayng_root_tun)

add_library(root_tun SHARED root_tun_jni.cpp)
find_library(log-lib log)
target_link_libraries(root_tun ${log-lib})

# Packaged as libroot_tun_helper.so so Android extracts it under nativeLibraryDir.
add_executable(root_tun_helper root_tun_helper.cpp)
set_target_properties(root_tun_helper PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    OUTPUT_NAME "root_tun_helper"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
