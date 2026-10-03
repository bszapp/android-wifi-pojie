/**
 * Author......: See docs/credits.txt
 * License.....: MIT
 */

#define _FILE_OFFSET_BITS 64

#include <io.h>

#ifndef MAP_FAILED
#define MAP_FAILED ((void*) -1)
#endif

#ifndef PROT_READ
#define PROT_READ 0x1
#endif

#ifndef MAP_PRIVATE
#define MAP_PRIVATE 0x02
#endif

void *mmap (MAYBE_UNUSED void *addr, size_t length, MAYBE_UNUSED int prot, MAYBE_UNUSED int flags, int fd, off_t offset)
{
  HANDLE hFile = (HANDLE) _get_osfhandle (fd);

  if (hFile == INVALID_HANDLE_VALUE || hFile == NULL) return MAP_FAILED;

  SYSTEM_INFO si;

  GetSystemInfo (&si);

  DWORD gran = si.dwAllocationGranularity;

  if ((uint64_t) offset % gran != 0) return MAP_FAILED;

  LARGE_INTEGER fsz;

  if (!GetFileSizeEx (hFile, &fsz)) return MAP_FAILED;

  uint64_t uoff = (uint64_t) offset;
  uint64_t ulen = (uint64_t) length;

  if (uoff > (uint64_t) fsz.QuadPart || ulen > (uint64_t) fsz.QuadPart - uoff) return MAP_FAILED;

  HANDLE hMap = CreateFileMappingW (hFile, NULL, PAGE_READONLY, 0, 0, NULL);

  if (!hMap) return MAP_FAILED;

  DWORD offLow  = (DWORD) (uoff & 0xFFFFFFFFULL);
  DWORD offHigh = (DWORD) (uoff >> 32);

  SIZE_T bytesToMap = (SIZE_T) ulen;

  void *base = MapViewOfFile (hMap, FILE_MAP_READ, offHigh, offLow, bytesToMap);

  CloseHandle (hMap);

  if (!base) return MAP_FAILED;

  return base;
}

int munmap (void *addr, MAYBE_UNUSED size_t length)
{
  if (UnmapViewOfFile (addr)) return 0;

  return -1;
}
