/* SPDX-License-Identifier: MIT */
#ifndef WLANTOOL_HASHCAT_ANDROID_H
#define WLANTOOL_HASHCAT_ANDROID_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef void (*wlantool_hashcat_event_callback)(uint32_t event_id,
    const void *data, size_t length, void *user);

/* argv includes argv[0]. Calls are serialized because upstream getopt is global.
 * runtime_dir must be owned/writable by the caller. All input paths should be
 * absolute. The callback runs synchronously on hashcat's emitting thread. */
int wlantool_hashcat_run(int argc, char **argv, const char *runtime_dir,
    const char *opencl_library, wlantool_hashcat_event_callback callback, void *user);
const char *wlantool_hashcat_version(void);
int wlantool_hashcat_quit(void);

/* Integration hooks; not a second implementation of hashcat. */
const char *hc_android_resource_dir(void);
const char *hc_android_opencl_library(void);
void *hc_android_load_opencl(const char *filename, void *context);
void *hc_android_load_builtin(const char *filename);
bool hc_android_is_embedded(void);
void hc_android_event(uint32_t id, void *context, const void *data, size_t length);
void hc_android_set_executing_context(void *context);
int hc_android_run_command(int argc, char **argv, const char *runtime_dir,
    const char *opencl_library);

#endif
