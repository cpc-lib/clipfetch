cmake_minimum_required(VERSION 3.24)
project(libtorrent-worker-v2 CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})

find_package(PkgConfig REQUIRED)
find_package(Protobuf REQUIRED)
find_program(PROTOC protoc REQUIRED)
find_program(GRPC_CPP_PLUGIN grpc_cpp_plugin protoc-gen-grpc-cpp protoc-gen-grpc++)
pkg_check_modules(GRPC REQUIRED grpc++ grpc)
pkg_check_modules(LIBTORRENT REQUIRED libtorrent-rasterbar)

if(NOT GRPC_CPP_PLUGIN)
    message(FATAL_ERROR "gRPC C++ code generator not found (grpc_cpp_plugin)")
endif()

message(STATUS "protoc:          ${PROTOC}")
message(STATUS "grpc plugin:      ${GRPC_CPP_PLUGIN}")
message(STATUS "protobuf ver:     ${Protobuf_VERSION}")
message(STATUS "libtorrent flags: ${LIBTORRENT_CFLAGS_OTHER}")

set(PROTO_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../proto)
set(GEN_DIR       ${CMAKE_CURRENT_BINARY_DIR}/generated)
file(MAKE_DIRECTORY ${GEN_DIR})

set(DOWNLOAD_ENGINE_PROTO     ${PROTO_SRC_DIR}/download_engine_v2.proto)
set(DOWNLOAD_ENGINE_PB_H      ${GEN_DIR}/download_engine_v2.pb.h)
set(DOWNLOAD_ENGINE_PB_CC     ${GEN_DIR}/download_engine_v2.pb.cc)
set(DOWNLOAD_ENGINE_GRPC_H    ${GEN_DIR}/download_engine_v2.grpc.pb.h)
set(DOWNLOAD_ENGINE_GRPC_CC   ${GEN_DIR}/download_engine_v2.grpc.pb.cc)

add_custom_command(
    OUTPUT  ${DOWNLOAD_ENGINE_PB_H} ${DOWNLOAD_ENGINE_PB_CC}
            ${DOWNLOAD_ENGINE_GRPC_H} ${DOWNLOAD_ENGINE_GRPC_CC}
    COMMAND ${PROTOC}
            --proto_path=${PROTO_SRC_DIR}
            --cpp_out=${GEN_DIR}
            --grpc_out=${GEN_DIR}
            --plugin=protoc-gen-grpc=${GRPC_CPP_PLUGIN}
            ${DOWNLOAD_ENGINE_PROTO}
    DEPENDS ${DOWNLOAD_ENGINE_PROTO}
    COMMENT "Generate proto + gRPC C++ stubs"
    VERBATIM
)

add_executable(libtorrent-worker
    src/main.cpp
    ${DOWNLOAD_ENGINE_PB_CC}
    ${DOWNLOAD_ENGINE_GRPC_CC}
)

target_include_directories(libtorrent-worker PRIVATE
    ${GEN_DIR}
    src
    ${GRPC_INCLUDE_DIRS}
    ${LIBTORRENT_INCLUDE_DIRS}
)

target_link_directories(libtorrent-worker PRIVATE
    ${GRPC_LIBRARY_DIRS}
    ${LIBTORRENT_LIBRARY_DIRS}
)

target_link_libraries(libtorrent-worker PRIVATE
    protobuf::libprotobuf
    ${LIBTORRENT_LIBRARIES}
    ${GRPC_LIBRARIES}
)

target_compile_options(libtorrent-worker PRIVATE
    ${GRPC_CFLAGS_OTHER}
    ${LIBTORRENT_CFLAGS_OTHER}
)
