cmake_minimum_required(VERSION 3.4.1)
project(hogger)

find_package(games-memory-advice REQUIRED CONFIG)

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++14 -Werror -Wthread-safety" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D _LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -Os -fPIC" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGOOGLE_PROTOBUF_NO_RTTI -DHAVE_PTHREAD")

include_directories(../../../../include)
include_directories(../../../common/include ) # Samples Includes


add_library(${CMAKE_PROJECT_NAME}
        SHARED
        src/main/cpp/hogger.cpp
        )

target_link_libraries(${CMAKE_PROJECT_NAME}
        games-memory-advice::memory_advice
        android
        GLESv2
        log
        )
