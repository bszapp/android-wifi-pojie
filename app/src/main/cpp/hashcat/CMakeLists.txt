# SPDX-License-Identifier: MIT
set(HASHCAT_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}")
set(HASHCAT_VERSION "v7.1.2-mainline-5108769476e1")
set(HASHCAT_WORK "${CMAKE_BINARY_DIR}/hashcat-wpa-build")
set(HASHCAT_CORE "${HASHCAT_WORK}/hashcat/obj/combined.NATIVE.a")
set(HASHCAT_EMBED "${HASHCAT_WORK}/embedded")
file(GLOB_RECURSE HASHCAT_INPUTS CONFIGURE_DEPENDS "${HASHCAT_SOURCE}/*")
add_custom_command(
    OUTPUT "${HASHCAT_CORE}" "${HASHCAT_EMBED}/resources.S" "${HASHCAT_EMBED}/resources_identity.h" "${HASHCAT_EMBED}/exports.map"
    COMMAND sh "${CMAKE_CURRENT_SOURCE_DIR}/build-core.sh"
        "${HASHCAT_SOURCE}" "${HASHCAT_WORK}" "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_C_COMPILER}" "${CMAKE_CXX_COMPILER}" "${LLVM_AR}"
        "${ANDROID_PLATFORM_LEVEL}" "${GNU_MAKE}"
        "${HASHCAT_VERSION}" "${LLVM_STRIP}"
    DEPENDS ${HASHCAT_INPUTS} "${CMAKE_CURRENT_SOURCE_DIR}/build-core.sh"
        "${CMAKE_CURRENT_SOURCE_DIR}/pack_resources.py" "${CMAKE_CURRENT_SOURCE_DIR}/hashcat_android.h"
    VERBATIM
    COMMENT "Compiling pinned Android WPA/WPA2 hashcat and wordlist plugin from source")
add_custom_target(hashcat_core_build DEPENDS "${HASHCAT_CORE}" "${HASHCAT_EMBED}/resources.S")
add_library(hashcat_core STATIC IMPORTED)
set_target_properties(hashcat_core PROPERTIES IMPORTED_LOCATION "${HASHCAT_CORE}")

set(HASHCAT_FRONTEND "${HASHCAT_SOURCE}/src/main.c")
set_source_files_properties("${HASHCAT_FRONTEND}" PROPERTIES COMPILE_DEFINITIONS "main=hashcat_frontend_main")
set_source_files_properties("${HASHCAT_EMBED}/resources.S" PROPERTIES GENERATED TRUE)
add_library(hashcat SHARED
    hashcat_android.c hashcat_entry.c hashcat_resources.c
    "${HASHCAT_FRONTEND}" "${HASHCAT_EMBED}/resources.S")
add_dependencies(hashcat hashcat_core_build)
target_compile_definitions(hashcat PRIVATE _GNU_SOURCE WLANTOOL_ANDROID_HASHCAT
    WITH_CUBIN COMPTIME=0 VERSION_TAG="${HASHCAT_VERSION}")
target_compile_options(hashcat PRIVATE -O2 -ffunction-sections -fdata-sections)
target_include_directories(hashcat PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}" "${HASHCAT_EMBED}"
    "${HASHCAT_SOURCE}/include" "${HASHCAT_SOURCE}/OpenCL" "${HASHCAT_SOURCE}/deps/OpenCL-Headers"
    "${HASHCAT_SOURCE}/deps/sse2neon")
target_link_libraries(hashcat PRIVATE
    -Wl,--whole-archive hashcat_core -Wl,--no-whole-archive dl m log z)
target_link_options(hashcat PRIVATE
    -Wl,--undefined=hashcat_android_interpreter
    -Wl,-e,hashcat_android_start -Wl,--dynamic-linker=/system/bin/linker64
    -Wl,-Bsymbolic-functions -Wl,-z,defs -Wl,--gc-sections
    "-Wl,--version-script=${HASHCAT_EMBED}/exports.map"
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
set_target_properties(hashcat PROPERTIES
    LINKER_LANGUAGE CXX LIBRARY_OUTPUT_DIRECTORY "${OUTPUT_ABI_DIR}"
    LINK_DEPENDS "${HASHCAT_EMBED}/exports.map")
