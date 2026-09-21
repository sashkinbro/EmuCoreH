cmake_minimum_required(VERSION 3.18.1)
project(memory-advice C CXX)
set(CMAKE_CXX_STANDARD 14)
set(IgnoreOldToolchainWarning "${ANDROID_UNIFIED_HEADERS}")

set( THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party")
set( TENSORFLOW_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../external/tensorflow")
set( EXTERNAL_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../external")
set( TFLITE_FLATBUFFERS_SCHEMA_DIR "${EXTERNAL_SOURCE_DIR}/flatbuffers/include")

set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Werror -Wthread-safety" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D _LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -O3 -fPIC" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-rtti" )
set( CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections" )
set( CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -g0")

if (${CMAKE_BUILD_TYPE} STREQUAL "Release")
set( CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections,-s")
endif (${CMAKE_BUILD_TYPE} STREQUAL "Release")
set( CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--hash-style=both" )

file(READ "${CMAKE_CURRENT_SOURCE_DIR}/default.json" PARAMS_FILE)
set(PARAMS_STRING "namespace memory_advice {\nconst char* parameters_string = R\"PARAMS(\n")
string(APPEND PARAMS_STRING "${PARAMS_FILE}")
string(APPEND PARAMS_STRING "\n )PARAMS\";\n}\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/advisor_parameters.cpp" "${PARAMS_STRING}")

include_directories( . )
include_directories( ../include )
include_directories( ../src/common )
include_directories( ${CMAKE_CURRENT_BINARY_DIR} )
include_directories( ${THIRD_PARTY_DIR} )

set(TFLITE_INCLUDE_DIRS
  "${TENSORFLOW_SOURCE_DIR}"
  "${TFLITE_FLATBUFFERS_SCHEMA_DIR}"
)
include_directories(
  BEFORE
  ${TFLITE_INCLUDE_DIRS}
)

set(MEMORY_ADVICE_SRCS
  c_header_check.c
  core/memory_advice.cpp
  core/memory_advice_impl.cpp
  core/memory_advice_c.cpp
  core/memory_advice_utils.cpp
  core/metrics_provider.cpp
  core/state_watcher.cpp
  core/predictor.cpp
  test/basic.cpp
  ../src/common/jni/jni_helper.cpp
  ../src/common/jni/jni_wrap.cpp
  ../src/common/jni/jnictx.cpp
  ../src/common/apk_utils.cpp
  ../src/common/system_utils.cpp
  ${THIRD_PARTY_DIR}/json11/json11.cpp
  advisor_parameters.cpp
)

add_library( tflite SHARED IMPORTED )
set_target_properties( tflite PROPERTIES IMPORTED_LOCATION ${THIRD_PARTY_DIR}/tensorflow-lite/res/${CMAKE_ANDROID_ARCH_ABI}/libtensorflowlite_jni.so )

add_library( tflite_static STATIC IMPORTED )
set_target_properties( tflite_static PROPERTIES IMPORTED_LOCATION ${THIRD_PARTY_DIR}/tensorflow-lite/res/${CMAKE_ANDROID_ARCH_ABI}/libtensorflow-lite.a )


add_library(memory_advice_static_pre STATIC ${MEMORY_ADVICE_SRCS})
add_library(memory_advice SHARED ${MEMORY_ADVICE_SRCS})
target_link_libraries(memory_advice
  PUBLIC
  android
  log
  tflite_static
)

target_link_libraries(memory_advice_static_pre
  PUBLIC
  android
  log
  tflite_static
)

#use the ar command to bundle the tensorflow lite static library into memory advice
set(mri_file ${CMAKE_CURRENT_BINARY_DIR}/memory_advice_static.ar)

file(WRITE ${mri_file} "create ${CMAKE_CURRENT_BINARY_DIR}/libmemory_advice_static.a\n")
file(APPEND ${mri_file} "addlib ${CMAKE_CURRENT_BINARY_DIR}/libmemory_advice_static_pre.a\n")
file(APPEND ${mri_file} "addlib ${THIRD_PARTY_DIR}/tensorflow-lite/res/${CMAKE_ANDROID_ARCH_ABI}/libtensorflow-lite.a\n")

file(APPEND ${mri_file} "save\n")
file(APPEND ${mri_file} "end\n")

set(output_archive_placeholder_file ${CMAKE_CURRENT_BINARY_DIR}/${output_archive}.placeholder.cpp)
add_custom_command(OUTPUT ${output_archive_placeholder_file}
                   COMMAND echo  > ${output_archive_placeholder_file}
                   DEPENDS memory_advice_static_pre)


add_library(memory_advice_static STATIC ${output_archive_placeholder_file})
add_custom_command(TARGET memory_advice_static
                   POST_BUILD
                   COMMAND ${CMAKE_AR} -M < ${mri_file})
