/*
 * Copyright (C) 2012 The Android Open Source Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * ARM64 entry sequence adapted from bionic libc/arch-common/bionic/crtbegin.c.
 * The NDK shared-library CRT remains responsible for library constructors.
 * This entry is reached only by execve, never by System.loadLibrary/dlopen.
 */
#include "hashcat_android.h"
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int hashcat_command_main(int argc, char **argv, char **envp)
{
    (void)envp;
    const char *runtime = getenv("HASHCAT_HOME");
    char default_runtime[PATH_MAX];
    if (runtime == NULL || *runtime == '\0') {
        Dl_info location;
        if (!dladdr((void *)&wlantool_hashcat_run, &location) || !location.dli_fname) {
            fputs("hashcat: cannot locate library; set HASHCAT_HOME\n", stderr);
            return -1;
        }
        char library_path[PATH_MAX];
        if (realpath(location.dli_fname, library_path) == NULL) return -1;
        char *slash = strrchr(library_path, '/');
        if (slash == NULL) return -1;
        *slash = '\0';
        if (snprintf(default_runtime, sizeof(default_runtime), "%s/hashcat-data", library_path)
            >= (int)sizeof(default_runtime)) return -1;
        runtime = default_runtime;
    }
    return hc_android_run_command(argc, argv, runtime, getenv("HASHCAT_OPENCL_LIBRARY"));
}

/* __libc_init's dynamic variant reads fini_array from this zeroed structure.
 * No private bionic layout is accessed by this code; zero all reserved slots.
 * Linker/libc initialize TLS, environment, and constructors before entry. */
extern void __libc_init(void *, void (*)(void),
    int (*)(int, char **, char **), const void *) __attribute__((noreturn));

__attribute__((used, visibility("hidden"), noreturn))
void hashcat_start_main(void *raw_args)
{
    const void *structors[8] = {0};
    __libc_init(raw_args, NULL, hashcat_command_main, structors);
}

#if !defined(__aarch64__)
#error The executable shared-library entry currently supports arm64-v8a only.
#endif

__asm__(
    ".section .interp,\"a\",%progbits\n"
    ".global hashcat_android_interpreter\n"
    ".hidden hashcat_android_interpreter\n"
    "hashcat_android_interpreter:\n"
    ".asciz \"/system/bin/linker64\"\n"
    ".text\n"
    ".global hashcat_android_start\n"
    ".hidden hashcat_android_start\n"
    ".type hashcat_android_start,%function\n"
    "hashcat_android_start:\n"
    "bti j\n"
    "mov x29,#0\n"
    "mov x30,#0\n"
    "mov x0,sp\n"
    "b hashcat_start_main\n"
    ".size hashcat_android_start,.-hashcat_android_start\n");
