cmake_minimum_required(VERSION 3.22.1)

project(wlantool_native C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    message(FATAL_ERROR "This native build is intended for the Android NDK toolchain.")
endif()

if(NOT DEFINED APP_GENERATED_JNI_DIR OR APP_GENERATED_JNI_DIR STREQUAL "")
    message(FATAL_ERROR "APP_GENERATED_JNI_DIR must be provided by Gradle.")
endif()

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../" ABSOLUTE)
set(ROOTFS_BUILDER_DIR "${PROJECT_ROOT}/rftoolbuilder")
set(DIST_DIR "${ROOTFS_BUILDER_DIR}/distfiles")
set(PROOT_REV "58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939")
set(PROOT_ARCHIVE "${DIST_DIR}/proot-android-compat-${PROOT_REV}.tar.gz")
set(MUSL_ARCHIVE "${DIST_DIR}/musl-1.2.6.tar.gz")
set(TALLOC_ARCHIVE "${DIST_DIR}/talloc-2.4.3.tar.gz")
set(OUTPUT_ABI_DIR "${APP_GENERATED_JNI_DIR}/${ANDROID_ABI}")
set(PROOT_WORK_DIR "${CMAKE_BINARY_DIR}/native-work/${ANDROID_ABI}")

foreach(required_path
        "${MUSL_ARCHIVE}"
        "${TALLOC_ARCHIVE}"
        "${PROOT_ARCHIVE}")
    if(NOT EXISTS "${required_path}")
        message(FATAL_ERROR "Missing ${required_path}. Run rftoolbuilder/downloads.sh first.")
    endif()
endforeach()

file(MAKE_DIRECTORY "${OUTPUT_ABI_DIR}")

get_filename_component(TOOLCHAIN_BIN_DIR "${CMAKE_C_COMPILER}" DIRECTORY)
find_program(GNU_MAKE NAMES make gmake REQUIRED)
find_program(LLVM_AR NAMES llvm-ar REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_RANLIB NAMES llvm-ranlib REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_STRIP NAMES llvm-strip REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_OBJCOPY NAMES llvm-objcopy REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)
find_program(LLVM_OBJDUMP NAMES llvm-objdump REQUIRED PATHS "${TOOLCHAIN_BIN_DIR}" NO_DEFAULT_PATH)

set(TERMINAL_OUTPUT "${OUTPUT_ABI_DIR}/libterminal.so")
set(PROOT_PATCHES
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/extension/ashmem_memfd/ashmem_memfd.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/extension/sysvipc/sysvipc_msg.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/extension/sysvipc/sysvipc_shm.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/syscall/rlimit.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/patches/proot/src/tracee/seccomp.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/shims/linux/net.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/shims/sys/queue.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/talloc-config.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/terminal-crt-arch-aarch64.h"
        "${CMAKE_CURRENT_SOURCE_DIR}/librftool.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/build-proot.sh")

add_custom_command(
        OUTPUT "${TERMINAL_OUTPUT}"
        COMMAND "${CMAKE_COMMAND}" -E env
            "ABI=${ANDROID_ABI}"
            "DIST_DIR=${DIST_DIR}"
            "WORK_DIR=${PROOT_WORK_DIR}"
            "OUTPUT_DIR=${OUTPUT_ABI_DIR}"
            "CC=${CMAKE_C_COMPILER}"
            "AR_TOOL=${LLVM_AR}"
            "RANLIB_TOOL=${LLVM_RANLIB}"
            "STRIP_TOOL=${LLVM_STRIP}"
            "OBJCOPY_TOOL=${LLVM_OBJCOPY}"
            "OBJDUMP_TOOL=${LLVM_OBJDUMP}"
            "SYSROOT=${CMAKE_SYSROOT}"
            "MAKE_TOOL=${GNU_MAKE}"
            "HOST_PREFIX=$ENV{PREFIX}"
            sh "${CMAKE_CURRENT_SOURCE_DIR}/build-proot.sh"
        DEPENDS
            "${MUSL_ARCHIVE}"
            "${TALLOC_ARCHIVE}"
            "${PROOT_ARCHIVE}"
            ${PROOT_PATCHES}
        VERBATIM
        COMMENT "Building the unified libterminal.so executable from source")

add_custom_target(terminal ALL DEPENDS "${TERMINAL_OUTPUT}")
