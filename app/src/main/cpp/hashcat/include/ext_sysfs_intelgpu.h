/**
 * Author......: See docs/credits.txt
 * License.....: MIT
 */

#ifndef HC_EXT_SYSFS_INTELGPU_H
#define HC_EXT_SYSFS_INTELGPU_H

#include <stdbool.h>

static const char SYS_BUS_PCI_DEVICES_INTEL[] = "/sys/bus/pci/devices";

typedef int HM_ADAPTER_SYSFS_INTELGPU;

typedef void *SYSFS_INTELGPU_LIB;

typedef struct hm_sysfs_intelgpu_lib
{
  // currently not using libudev, because it can only read values, not set them, so using /sys instead

  SYSFS_INTELGPU_LIB lib;

} hm_sysfs_intelgpu_lib_t;

typedef hm_sysfs_intelgpu_lib_t SYSFS_INTELGPU_PTR;

bool sysfs_intelgpu_init (void *hashcat_ctx);
void sysfs_intelgpu_close (void *hashcat_ctx);
char *hm_SYSFS_INTELGPU_get_syspath_device (void *hashcat_ctx, const int backend_device_idx);
char *hm_SYSFS_INTELGPU_get_syspath_hwmon (void *hashcat_ctx, const int backend_device_idx);
int hm_SYSFS_INTELGPU_get_fan_speed_current (void *hashcat_ctx, const int backend_device_idx, int *val);
int hm_SYSFS_INTELGPU_get_temperature_current (void *hashcat_ctx, const int backend_device_idx, int *val);
//int hm_SYSFS_INTELGPU_get_pp_dpm_sclk (void *hashcat_ctx, const int backend_device_idx, int *val);
//int hm_SYSFS_INTELGPU_get_pp_dpm_mclk (void *hashcat_ctx, const int backend_device_idx, int *val);
//int hm_SYSFS_INTELGPU_get_pp_dpm_pcie (void *hashcat_ctx, const int backend_device_idx, int *val);
//int hm_SYSFS_INTELGPU_get_gpu_busy_percent (void *hashcat_ctx, const int backend_device_idx, int *val);
//int hm_SYSFS_INTELGPU_get_mem_info_vram_used (void *hashcat_ctx, const int backend_device_idx, u64 *val);

#endif // HC_EXT_SYSFS_INTELGPU_H
