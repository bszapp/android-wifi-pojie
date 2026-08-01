#define _GNU_SOURCE

#if !defined(RFTOOL_CLI_BUILD) && !defined(TERMINAL_MULTICALL_BUILD)
#include <jni.h>
#endif

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <setjmp.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#ifndef TERMINAL_MULTICALL_BUILD
#include "zlib.h"
#endif

#ifdef TERMINAL_MULTICALL_BUILD
extern int proot_main(int argc, char *const argv[]);
#endif

#define DEFAULT_LINUX_PATH "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
#define DEFAULT_HOST_PATH "/system/bin:/system/xbin:/system_ext/bin:/product/bin:/vendor/bin:/odm/bin:/apex/com.android.runtime/bin"
#define DEFAULT_ANDROID_ROOT "/system"
#define DEFAULT_ANDROID_DATA "/data"
#define DEFAULT_ANDROID_ART_ROOT "/apex/com.android.art"
#define DEFAULT_ANDROID_I18N_ROOT "/apex/com.android.i18n"
#define DEFAULT_ANDROID_TZDATA_ROOT "/apex/com.android.tzdata"
#define DEFAULT_ANDROID_RUNTIME_ROOT "/apex/com.android.runtime"

#define MODE_PROOT 1
#define MODE_CHROOT 2

struct tar_header {
  char name[100];
  char mode[8];
  char uid[8];
  char gid[8];
  char size[12];
  char mtime[12];
  char chksum[8];
  char typeflag;
  char linkname[100];
  char magic[6];
  char version[2];
  char uname[32];
  char gname[32];
  char devmajor[8];
  char devminor[8];
  char prefix[155];
  char pad[12];
};

struct error_ctx {
  jmp_buf env;
  char message[512];
};

static __thread struct error_ctx *g_error_ctx = NULL;

static void dief(const char *fmt, ...) {
  va_list ap;

  va_start(ap, fmt);
  if (g_error_ctx != NULL) {
    vsnprintf(g_error_ctx->message, sizeof(g_error_ctx->message), fmt, ap);
    va_end(ap);
    longjmp(g_error_ctx->env, 1);
  }
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
  exit(1);
}

static void die_errno(const char *what, const char *arg) {
  if (arg != NULL) {
    dief("%s: %s: %s", what, arg, strerror(errno));
  } else {
    dief("%s: %s", what, strerror(errno));
  }
}

static void *xmalloc(size_t size) {
  void *ptr = malloc(size);
  if (ptr == NULL) {
    dief("out of memory");
  }
  return ptr;
}

static void *xcalloc(size_t n, size_t size) {
  void *ptr = calloc(n, size);
  if (ptr == NULL) {
    dief("out of memory");
  }
  return ptr;
}

static char *xstrdup(const char *s) {
  char *copy = strdup(s);
  if (copy == NULL) {
    dief("out of memory");
  }
  return copy;
}

static char *xasprintf(const char *fmt, ...) {
  va_list ap;
  va_list copy;
  int needed;
  char *out;

  va_start(ap, fmt);
  va_copy(copy, ap);
  needed = vsnprintf(NULL, 0, fmt, copy);
  va_end(copy);
  if (needed < 0) {
    va_end(ap);
    dief("vsnprintf failed");
  }

  out = xmalloc((size_t)needed + 1);
  vsnprintf(out, (size_t)needed + 1, fmt, ap);
  va_end(ap);
  return out;
}

struct string_list {
  char **items;
  size_t count;
  size_t capacity;
};

static void string_list_init(struct string_list *list) {
  list->items = NULL;
  list->count = 0;
  list->capacity = 0;
}

static void string_list_reserve(struct string_list *list, size_t min_capacity) {
  size_t new_capacity;
  char **new_items;

  if (list->capacity >= min_capacity) {
    return;
  }

  new_capacity = list->capacity == 0 ? 8 : list->capacity;
  while (new_capacity < min_capacity) {
    new_capacity *= 2;
  }

  new_items = realloc(list->items, new_capacity * sizeof(char *));
  if (new_items == NULL) {
    dief("out of memory");
  }
  list->items = new_items;
  list->capacity = new_capacity;
}

static bool string_list_contains(const struct string_list *list, const char *value) {
  size_t i;

  for (i = 0; i < list->count; i++) {
    if (strcmp(list->items[i], value) == 0) {
      return true;
    }
  }
  return false;
}

static void string_list_add_owned(struct string_list *list, char *value) {
  string_list_reserve(list, list->count + 1);
  list->items[list->count++] = value;
}

static void string_list_add_unique_owned(struct string_list *list, char *value) {
  if (value == NULL || value[0] == '\0') {
    free(value);
    return;
  }
  if (string_list_contains(list, value)) {
    free(value);
    return;
  }
  string_list_add_owned(list, value);
}

static void string_list_free(struct string_list *list) {
  size_t i;

  for (i = 0; i < list->count; i++) {
    free(list->items[i]);
  }
  free(list->items);
  string_list_init(list);
}

static bool is_all_zero(const unsigned char *buf, size_t size) {
  size_t i;
  for (i = 0; i < size; i++) {
    if (buf[i] != 0) {
      return false;
    }
  }
  return true;
}

static unsigned long long parse_octal(const char *buf, size_t size) {
  unsigned long long value = 0;
  size_t i = 0;

  while (i < size && (buf[i] == ' ' || buf[i] == '\0')) {
    i++;
  }
  for (; i < size; i++) {
    if (buf[i] == '\0' || buf[i] == ' ') {
      break;
    }
    if (buf[i] < '0' || buf[i] > '7') {
      break;
    }
    value = (value << 3) + (unsigned long long)(buf[i] - '0');
  }
  return value;
}

static char *path_join2(const char *left, const char *right) {
  size_t left_len = strlen(left);
  size_t right_len = strlen(right);
  bool need_slash = left_len > 0 && left[left_len - 1] != '/';
  char *out = xmalloc(left_len + right_len + (need_slash ? 2 : 1));

  memcpy(out, left, left_len);
  if (need_slash) {
    out[left_len++] = '/';
  }
  memcpy(out + left_len, right, right_len);
  out[left_len + right_len] = '\0';
  return out;
}

static int rmrf_path(const char *path) {
  struct stat st;
  DIR *dir;
  struct dirent *entry;

  if (lstat(path, &st) != 0) {
    if (errno == ENOENT) {
      return 0;
    }
    return -1;
  }

  if (!S_ISDIR(st.st_mode) || S_ISLNK(st.st_mode)) {
    return unlink(path);
  }

  dir = opendir(path);
  if (dir == NULL) {
    return -1;
  }

  while ((entry = readdir(dir)) != NULL) {
    char *child;
    if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
      continue;
    }
    child = path_join2(path, entry->d_name);
    if (rmrf_path(child) != 0) {
      int saved = errno;
      free(child);
      closedir(dir);
      errno = saved;
      return -1;
    }
    free(child);
  }

  if (closedir(dir) != 0) {
    return -1;
  }
  return rmdir(path);
}

struct rmrf_progress_ctx {
  const char *root_path;
  unsigned long long total_entries;
  unsigned long long deleted_entries;
  unsigned long long total_mounts;
  unsigned long long unmounted_mounts;
  bool enable_events;
};

static bool path_has_prefix(const char *path, const char *prefix);

static void json_print_escaped(FILE *stream, const char *value) {
  const unsigned char *p = (const unsigned char *)(value == NULL ? "" : value);

  while (*p != '\0') {
    switch (*p) {
      case '\\':
        fputs("\\\\", stream);
        break;
      case '"':
        fputs("\\\"", stream);
        break;
      case '\b':
        fputs("\\b", stream);
        break;
      case '\f':
        fputs("\\f", stream);
        break;
      case '\n':
        fputs("\\n", stream);
        break;
      case '\r':
        fputs("\\r", stream);
        break;
      case '\t':
        fputs("\\t", stream);
        break;
      default:
        if (*p < 0x20) {
          fprintf(stream, "\\u%04x", (unsigned int)*p);
        } else {
          fputc((int)*p, stream);
        }
        break;
    }
    p++;
  }
}

static void rmrf_emit_scan_event(struct rmrf_progress_ctx *ctx) {
  if (!ctx->enable_events) {
    return;
  }
  fputs("{\"event\":\"scan\",\"path\":\"", stdout);
  json_print_escaped(stdout, ctx->root_path);
  fprintf(stdout, "\",\"total\":%llu}\n", ctx->total_entries);
  fflush(stdout);
}

static void rmrf_emit_umount_event(struct rmrf_progress_ctx *ctx, const char *path) {
  if (!ctx->enable_events) {
    return;
  }
  fputs("{\"event\":\"umount\",\"path\":\"", stdout);
  json_print_escaped(stdout, path);
  fprintf(stdout,
          "\",\"index\":%llu,\"total\":%llu}\n",
          ctx->unmounted_mounts,
          ctx->total_mounts);
  fflush(stdout);
}

static void rmrf_emit_delete_event(struct rmrf_progress_ctx *ctx, const char *path) {
  if (!ctx->enable_events) {
    return;
  }
  fputs("{\"event\":\"delete\",\"path\":\"", stdout);
  json_print_escaped(stdout, path);
  fprintf(stdout,
          "\",\"index\":%llu,\"total\":%llu}\n",
          ctx->deleted_entries,
          ctx->total_entries);
  fflush(stdout);
}

static void rmrf_emit_done_event(struct rmrf_progress_ctx *ctx) {
  if (!ctx->enable_events) {
    return;
  }
  fputs("{\"event\":\"done\",\"path\":\"", stdout);
  json_print_escaped(stdout, ctx->root_path);
  fprintf(stdout,
          "\",\"deleted\":%llu,\"total\":%llu,\"unmounted\":%llu}\n",
          ctx->deleted_entries,
          ctx->total_entries,
          ctx->unmounted_mounts);
  fflush(stdout);
}

static int rmrf_emit_error_event(struct rmrf_progress_ctx *ctx, const char *phase,
                                 const char *operation, const char *path, int errnum) {
  unsigned long long index = ctx->deleted_entries;

  if (strcmp(phase, "umount") == 0) {
    index = ctx->unmounted_mounts;
  }

  if (ctx->enable_events) {
    fputs("{\"event\":\"error\",\"phase\":\"", stdout);
    json_print_escaped(stdout, phase);
    fputs("\",\"operation\":\"", stdout);
    json_print_escaped(stdout, operation);
    fputs("\",\"path\":\"", stdout);
    json_print_escaped(stdout, path);
    fprintf(stdout,
            "\",\"errno\":%d,\"index\":%llu,\"total\":%llu,\"reason\":\"",
            errnum,
            index,
            strcmp(phase, "umount") == 0 ? ctx->total_mounts : ctx->total_entries);
    json_print_escaped(stdout, strerror(errnum));
    fputs("\"}\n", stdout);
    fflush(stdout);
  }
  errno = errnum;
  return -1;
}

static char *decode_mountinfo_path(const char *value) {
  size_t len = strlen(value);
  char *decoded = xmalloc(len + 1);
  size_t in = 0;
  size_t out = 0;

  while (value[in] != '\0') {
    if (value[in] == '\\' &&
        value[in + 1] != '\0' &&
        value[in + 2] != '\0' &&
        value[in + 3] != '\0' &&
        value[in + 1] >= '0' && value[in + 1] <= '7' &&
        value[in + 2] >= '0' && value[in + 2] <= '7' &&
        value[in + 3] >= '0' && value[in + 3] <= '7') {
      decoded[out++] = (char)((value[in + 1] - '0') * 64 +
                              (value[in + 2] - '0') * 8 +
                              (value[in + 3] - '0'));
      in += 4;
      continue;
    }
    decoded[out++] = value[in++];
  }

  decoded[out] = '\0';
  return decoded;
}

static int compare_path_length_desc(const void *left, const void *right) {
  const char *const *lhs = left;
  const char *const *rhs = right;
  size_t lhs_len = strlen(*lhs);
  size_t rhs_len = strlen(*rhs);

  if (lhs_len < rhs_len) {
    return 1;
  }
  if (lhs_len > rhs_len) {
    return -1;
  }
  return strcmp(*lhs, *rhs);
}

static void collect_mount_points_under(const char *root_path, struct string_list *mount_points) {
  FILE *stream;
  char *line = NULL;
  size_t line_cap = 0;

  string_list_init(mount_points);
  stream = fopen("/proc/self/mountinfo", "r");
  if (stream == NULL) {
    return;
  }

  while (getline(&line, &line_cap, stream) >= 0) {
    char *separator = strstr(line, " - ");
    char *cursor = line;
    char *field = NULL;
    int field_index = 0;

    if (separator != NULL) {
      *separator = '\0';
    }

    while (*cursor != '\0') {
      char *start;

      while (*cursor == ' ') {
        cursor++;
      }
      if (*cursor == '\0') {
        break;
      }

      start = cursor;
      while (*cursor != '\0' && *cursor != ' ') {
        cursor++;
      }

      field_index++;
      if (field_index == 5) {
        field = xasprintf("%.*s", (int)(cursor - start), start);
        break;
      }
    }

    if (field != NULL) {
      char *decoded = decode_mountinfo_path(field);
      if (path_has_prefix(decoded, root_path)) {
        string_list_add_unique_owned(mount_points, decoded);
      } else {
        free(decoded);
      }
      free(field);
    }
  }

  free(line);
  fclose(stream);

  if (mount_points->count > 1) {
    qsort(mount_points->items,
          mount_points->count,
          sizeof(char *),
          compare_path_length_desc);
  }
}

static void rmrf_make_writable_best_effort(const char *path, const struct stat *st) {
  mode_t wanted = st->st_mode;

  wanted |= S_IRUSR | S_IWUSR;
  if (S_ISDIR(st->st_mode)) {
    wanted |= S_IXUSR;
  }
  if (wanted != st->st_mode) {
    (void)chmod(path, wanted);
  }
}

static int rmrf_count_entries(const char *path, struct rmrf_progress_ctx *ctx) {
  struct stat st;

  if (lstat(path, &st) != 0) {
    if (errno == ENOENT) {
      return 0;
    }
    return rmrf_emit_error_event(ctx, "scan", "lstat", path, errno);
  }

  if (!S_ISDIR(st.st_mode) || S_ISLNK(st.st_mode)) {
    ctx->total_entries++;
    return 0;
  }

  rmrf_make_writable_best_effort(path, &st);

  {
    DIR *dir = opendir(path);
    struct dirent *entry;

    if (dir == NULL) {
      return rmrf_emit_error_event(ctx, "scan", "opendir", path, errno);
    }

    while ((entry = readdir(dir)) != NULL) {
      char *child_path;
      int rc;

      if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
        continue;
      }

      child_path = path_join2(path, entry->d_name);
      rc = rmrf_count_entries(child_path, ctx);
      free(child_path);
      if (rc != 0) {
        int saved = errno;
        closedir(dir);
        errno = saved;
        return -1;
      }
    }

    if (closedir(dir) != 0) {
      return rmrf_emit_error_event(ctx, "scan", "closedir", path, errno);
    }
  }

  ctx->total_entries++;
  return 0;
}

static int rmrf_unmount_submounts(struct rmrf_progress_ctx *ctx) {
  struct string_list mount_points;
  size_t i;

  collect_mount_points_under(ctx->root_path, &mount_points);
  ctx->total_mounts = (unsigned long long)mount_points.count;

  for (i = 0; i < mount_points.count; i++) {
    if (umount2(mount_points.items[i], MNT_DETACH) != 0) {
      int errnum = errno;
      char *mount_path = xstrdup(mount_points.items[i]);
      int rc;
      string_list_free(&mount_points);
      rc = rmrf_emit_error_event(ctx, "umount", "umount2", mount_path, errnum);
      free(mount_path);
      return rc;
    }

    ctx->unmounted_mounts++;
    rmrf_emit_umount_event(ctx, mount_points.items[i]);
  }

  string_list_free(&mount_points);
  return 0;
}

static int rmrf_delete_entries(const char *path, struct rmrf_progress_ctx *ctx) {
  struct stat st;

  if (lstat(path, &st) != 0) {
    if (errno == ENOENT) {
      return 0;
    }
    return rmrf_emit_error_event(ctx, "delete", "lstat", path, errno);
  }

  if (!S_ISDIR(st.st_mode) || S_ISLNK(st.st_mode)) {
    rmrf_make_writable_best_effort(path, &st);
    if (unlink(path) != 0) {
      return rmrf_emit_error_event(ctx, "delete", "unlink", path, errno);
    }
    ctx->deleted_entries++;
    rmrf_emit_delete_event(ctx, path);
    return 0;
  }

  rmrf_make_writable_best_effort(path, &st);

  {
    DIR *dir = opendir(path);
    struct dirent *entry;

    if (dir == NULL) {
      return rmrf_emit_error_event(ctx, "delete", "opendir", path, errno);
    }

    while ((entry = readdir(dir)) != NULL) {
      char *child_path;
      int rc;

      if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
        continue;
      }

      child_path = path_join2(path, entry->d_name);
      rc = rmrf_delete_entries(child_path, ctx);
      free(child_path);
      if (rc != 0) {
        int saved = errno;
        closedir(dir);
        errno = saved;
        return -1;
      }
    }

    if (closedir(dir) != 0) {
      return rmrf_emit_error_event(ctx, "delete", "closedir", path, errno);
    }
  }

  if (rmdir(path) != 0) {
    return rmrf_emit_error_event(ctx, "delete", "rmdir", path, errno);
  }
  ctx->deleted_entries++;
  rmrf_emit_delete_event(ctx, path);
  return 0;
}

static int cmd_rmrf_with_progress(const char *path) {
  struct rmrf_progress_ctx ctx;
  struct stat st;

  memset(&ctx, 0, sizeof(ctx));
  ctx.root_path = path;
  ctx.enable_events = true;

  if (lstat(path, &st) != 0) {
    if (errno == ENOENT) {
      rmrf_emit_scan_event(&ctx);
      rmrf_emit_done_event(&ctx);
      return 0;
    }
    return rmrf_emit_error_event(&ctx, "scan", "lstat", path, errno);
  }

  if (geteuid() == 0) {
    if (rmrf_unmount_submounts(&ctx) != 0) {
      return -1;
    }
  }

  if (rmrf_count_entries(path, &ctx) != 0) {
    return -1;
  }
  rmrf_emit_scan_event(&ctx);

  if (rmrf_delete_entries(path, &ctx) != 0) {
    return -1;
  }

  rmrf_emit_done_event(&ctx);
  return 0;
}

static bool path_has_prefix(const char *path, const char *prefix) {
  size_t prefix_len = strlen(prefix);
  return strncmp(path, prefix, prefix_len) == 0 &&
         (path[prefix_len] == '\0' || path[prefix_len] == '/');
}

static bool path_is_dir(const char *path) {
  struct stat st;

  if (path == NULL || path[0] == '\0') {
    return false;
  }
  if (stat(path, &st) != 0) {
    return false;
  }
  return S_ISDIR(st.st_mode);
}

static bool is_supported_host_tool_path(const char *path) {
  static const char *const prefixes[] = {
      "/system",
      "/system_ext",
      "/product",
      "/vendor",
      "/odm",
      "/apex",
  };
  size_t i;

  for (i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {
    if (path_has_prefix(path, prefixes[i])) {
      return true;
    }
  }
  return false;
}

static char *normalize_path_segment(const char *start, size_t len) {
  const char *end = start + len;
  char *out;
  size_t out_len;

  while (start < end && (*start == ' ' || *start == '\t' || *start == '\n' || *start == '\r')) {
    start++;
  }
  while (end > start &&
         (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\n' || end[-1] == '\r')) {
    end--;
  }
  while (end > start + 1 && end[-1] == '/') {
    end--;
  }
  if (start >= end) {
    return NULL;
  }

  out_len = (size_t)(end - start);
  out = xmalloc(out_len + 1);
  memcpy(out, start, out_len);
  out[out_len] = '\0';
  return out;
}

static char *mount_root_for_path(const char *path) {
  const char *slash;

  if (path == NULL || path[0] != '/') {
    return NULL;
  }
  slash = strchr(path + 1, '/');
  if (slash == NULL) {
    return xstrdup(path);
  }
  return xasprintf("%.*s", (int)(slash - path), path);
}

static void add_default_host_paths(struct string_list *host_paths) {
  static const char *const defaults[] = {
      "/system/bin",
      "/system/xbin",
      "/system_ext/bin",
      "/product/bin",
      "/vendor/bin",
      "/odm/bin",
      "/apex/com.android.runtime/bin",
  };
  size_t i;

  for (i = 0; i < sizeof(defaults) / sizeof(defaults[0]); i++) {
    if (path_is_dir(defaults[i])) {
      string_list_add_unique_owned(host_paths, xstrdup(defaults[i]));
    }
  }
}

static void add_support_mount_roots(struct string_list *mount_roots) {
  static const char *const roots[] = {
      "/system",
      "/system_ext",
      "/product",
      "/vendor",
      "/odm",
      "/apex",
      "/linkerconfig",
  };
  size_t i;

  for (i = 0; i < sizeof(roots) / sizeof(roots[0]); i++) {
    if (path_is_dir(roots[i]) || access(roots[i], F_OK) == 0) {
      string_list_add_unique_owned(mount_roots, xstrdup(roots[i]));
    }
  }
}

static void collect_host_tool_paths(const char *raw_path, struct string_list *host_paths,
                                    struct string_list *mount_roots) {
  const char *cursor;

  string_list_init(host_paths);
  string_list_init(mount_roots);

  if (raw_path == NULL) {
    add_default_host_paths(host_paths);
    add_support_mount_roots(mount_roots);
    return;
  }

  cursor = raw_path;
  while (true) {
    const char *segment_end = strchr(cursor, ':');
    size_t len = segment_end == NULL ? strlen(cursor) : (size_t)(segment_end - cursor);
    char *entry = normalize_path_segment(cursor, len);

    if (entry != NULL && entry[0] == '/' && is_supported_host_tool_path(entry) &&
        path_is_dir(entry)) {
      char *root = mount_root_for_path(entry);
      string_list_add_unique_owned(host_paths, entry);
      if (root != NULL && path_is_dir(root)) {
        string_list_add_unique_owned(mount_roots, root);
      } else {
        free(root);
      }
    } else {
      free(entry);
    }

    if (segment_end == NULL) {
      break;
    }
    cursor = segment_end + 1;
  }

  add_default_host_paths(host_paths);
  add_support_mount_roots(mount_roots);
}

static char *join_paths(const char *base, const struct string_list *extra_paths) {
  size_t len = strlen(base);
  size_t i;
  char *out;
  char *p;

  for (i = 0; i < extra_paths->count; i++) {
    len += 1 + strlen(extra_paths->items[i]);
  }

  out = xmalloc(len + 1);
  p = out;
  memcpy(p, base, strlen(base));
  p += strlen(base);
  for (i = 0; i < extra_paths->count; i++) {
    *p++ = ':';
    memcpy(p, extra_paths->items[i], strlen(extra_paths->items[i]));
    p += strlen(extra_paths->items[i]);
  }
  *p = '\0';
  return out;
}

static char *build_guest_path(const struct string_list *host_paths) {
  return join_paths(DEFAULT_LINUX_PATH, host_paths);
}

static char *build_host_exec_path(const struct string_list *host_paths) {
  struct string_list tail;

  if (host_paths->count == 0) {
    return xstrdup(DEFAULT_HOST_PATH);
  }

  string_list_init(&tail);
  if (host_paths->count > 1) {
    tail.items = host_paths->items + 1;
    tail.count = host_paths->count - 1;
  }
  return join_paths(host_paths->items[0], &tail);
}

static void ensure_parent_dirs(const char *path, mode_t mode) {
  char *copy = xstrdup(path);
  char *p = copy;

  if (copy[0] == '/') {
    p++;
  }
  for (; *p != '\0'; p++) {
    if (*p != '/') {
      continue;
    }
    *p = '\0';
    if (copy[0] != '\0' && mkdir(copy, mode) != 0 && errno != EEXIST) {
      die_errno("mkdir", copy);
    }
    *p = '/';
  }
  free(copy);
}

static void ensure_dir(const char *path, mode_t mode) {
  struct stat st;
  if (lstat(path, &st) == 0) {
    if (S_ISDIR(st.st_mode)) {
      return;
    }
    if (unlink(path) != 0) {
      die_errno("unlink", path);
    }
  } else if (errno != ENOENT) {
    die_errno("lstat", path);
  }
  ensure_parent_dirs(path, 0755);
  if (mkdir(path, mode) != 0 && errno != EEXIST) {
    die_errno("mkdir", path);
  }
}

static bool path_is_safe(const char *path) {
  const char *p = path;

  if (path == NULL || *path == '\0') {
    return false;
  }
  if (*p == '/') {
    return false;
  }
  while (*p != '\0') {
    const char *start;
    size_t len;

    while (*p == '/') {
      p++;
    }
    start = p;
    while (*p != '\0' && *p != '/') {
      p++;
    }
    len = (size_t)(p - start);
    if (len == 0) {
      continue;
    }
    if (len == 1 && start[0] == '.') {
      continue;
    }
    if (len == 2 && start[0] == '.' && start[1] == '.') {
      return false;
    }
  }
  return true;
}

static char *join_under_root(const char *root, const char *rel) {
  if (strcmp(rel, ".") == 0 || strcmp(rel, "./") == 0) {
    return xstrdup(root);
  }
  if (!path_is_safe(rel)) {
    dief("unsafe archive path: %s", rel);
  }
  while (rel[0] == '.' && rel[1] == '/') {
    rel += 2;
  }
  return path_join2(root, rel);
}

#ifndef TERMINAL_MULTICALL_BUILD
static void gz_read_or_die(gzFile gz, void *buf, unsigned len) {
  unsigned char *p = buf;
  unsigned remaining = len;

  while (remaining > 0) {
    int rv = gzread(gz, p, remaining);
    if (rv <= 0) {
      dief("unexpected end of gzip stream");
    }
    p += rv;
    remaining -= (unsigned)rv;
  }
}

static void gz_skip_or_die(gzFile gz, unsigned long long len) {
  unsigned char buf[8192];
  while (len > 0) {
    unsigned chunk = len > sizeof(buf) ? sizeof(buf) : (unsigned)len;
    gz_read_or_die(gz, buf, chunk);
    len -= chunk;
  }
}

static void gz_skip_padding(gzFile gz, unsigned long long size) {
  unsigned long long padding = (512 - (size % 512)) % 512;
  if (padding != 0) {
    gz_skip_or_die(gz, padding);
  }
}

static char *tar_name_from_header(const struct tar_header *hdr) {
  size_t name_len = strnlen(hdr->name, sizeof(hdr->name));
  size_t prefix_len = strnlen(hdr->prefix, sizeof(hdr->prefix));
  char *name;

  if (prefix_len == 0) {
    name = xmalloc(name_len + 1);
    memcpy(name, hdr->name, name_len);
    name[name_len] = '\0';
    return name;
  }

  name = xmalloc(prefix_len + 1 + name_len + 1);
  memcpy(name, hdr->prefix, prefix_len);
  name[prefix_len] = '/';
  memcpy(name + prefix_len + 1, hdr->name, name_len);
  name[prefix_len + 1 + name_len] = '\0';
  return name;
}

static char *read_long_string(gzFile gz, unsigned long long size) {
  char *buf = xcalloc((size_t)size + 1, 1);
  gz_read_or_die(gz, buf, (unsigned)size);
  gz_skip_padding(gz, size);
  buf[size] = '\0';
  while (size > 0 && (buf[size - 1] == '\0' || buf[size - 1] == '\n')) {
    buf[size - 1] = '\0';
    size--;
  }
  return buf;
}

static void write_file_from_gz(gzFile gz, const char *path, mode_t mode,
                               unsigned long long size) {
  int fd;
  unsigned char buf[32768];

  ensure_parent_dirs(path, 0755);
  fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, mode ? mode : 0644);
  if (fd < 0) {
    die_errno("open", path);
  }

  while (size > 0) {
    unsigned chunk = size > sizeof(buf) ? sizeof(buf) : (unsigned)size;
    ssize_t written_total = 0;
    gz_read_or_die(gz, buf, chunk);
    while (written_total < (ssize_t)chunk) {
      ssize_t written = write(fd, buf + written_total, chunk - (unsigned)written_total);
      if (written < 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        die_errno("write", path);
      }
      written_total += written;
    }
    size -= chunk;
  }

  if (fchmod(fd, mode ? mode : 0644) != 0) {
    int saved = errno;
    close(fd);
    errno = saved;
    die_errno("fchmod", path);
  }
  if (close(fd) != 0) {
    die_errno("close", path);
  }
}

static int cmd_extract(const char *archive, const char *dest) {
  gzFile gz;
  char *pending_long_name = NULL;
  char *pending_long_link = NULL;

  ensure_dir(dest, 0755);
  gz = gzopen(archive, "rb");
  if (gz == NULL) {
    dief("cannot open gzip archive: %s", archive);
  }

  for (;;) {
    struct tar_header hdr;
    unsigned long long size;
    mode_t mode;
    char typeflag;
    char *name = NULL;
    char *linkname = NULL;
    char *out_path = NULL;

    gz_read_or_die(gz, &hdr, sizeof(hdr));
    if (is_all_zero((const unsigned char *)&hdr, sizeof(hdr))) {
      break;
    }

    size = parse_octal(hdr.size, sizeof(hdr.size));
    mode = (mode_t)parse_octal(hdr.mode, sizeof(hdr.mode));
    typeflag = hdr.typeflag == '\0' ? '0' : hdr.typeflag;

    if (typeflag == 'L') {
      free(pending_long_name);
      pending_long_name = read_long_string(gz, size);
      continue;
    }
    if (typeflag == 'K') {
      free(pending_long_link);
      pending_long_link = read_long_string(gz, size);
      continue;
    }
    if (typeflag == 'x' || typeflag == 'g') {
      gz_skip_or_die(gz, size);
      gz_skip_padding(gz, size);
      continue;
    }

    if (pending_long_name != NULL) {
      name = pending_long_name;
      pending_long_name = NULL;
    } else {
      name = tar_name_from_header(&hdr);
    }

    if (pending_long_link != NULL) {
      linkname = pending_long_link;
      pending_long_link = NULL;
    } else {
      linkname = xstrdup(hdr.linkname);
    }

    out_path = join_under_root(dest, name);

    switch (typeflag) {
      case '5':
        ensure_dir(out_path, mode ? mode : 0755);
        break;
      case '2':
        ensure_parent_dirs(out_path, 0755);
        unlink(out_path);
        if (symlink(linkname, out_path) != 0) {
          die_errno("symlink", out_path);
        }
        break;
      case '1': {
        char *target = join_under_root(dest, linkname);
        ensure_parent_dirs(out_path, 0755);
        unlink(out_path);
        if (link(target, out_path) != 0) {
          free(target);
          die_errno("link", out_path);
        }
        free(target);
        break;
      }
      case '0':
      case '7':
        write_file_from_gz(gz, out_path, mode, size);
        gz_skip_padding(gz, size);
        size = 0;
        break;
      default:
        gz_skip_or_die(gz, size);
        gz_skip_padding(gz, size);
        size = 0;
        break;
    }

    if (size != 0) {
      gz_skip_or_die(gz, size);
      gz_skip_padding(gz, size);
    }

    free(name);
    free(linkname);
    free(out_path);
  }

  free(pending_long_name);
  free(pending_long_link);
  if (gzclose(gz) != Z_OK) {
    dief("failed to close archive: %s", archive);
  }
  return 0;
}
#endif

static void set_path_env(const char *path) {
  if (path == NULL || path[0] == '\0') {
    return;
  }
  if (setenv("PATH", path, 1) != 0) {
    die_errno("setenv", "PATH");
  }
}

static char *shell_quote(const char *value) {
  size_t i;
  size_t len = 2;
  char *out;
  char *p;

  for (i = 0; value[i] != '\0'; i++) {
    len += (value[i] == '\'') ? 4 : 1;
  }

  out = xmalloc(len + 1);
  p = out;
  *p++ = '\'';
  for (i = 0; value[i] != '\0'; i++) {
    if (value[i] == '\'') {
      memcpy(p, "'\\''", 4);
      p += 4;
    } else {
      *p++ = value[i];
    }
  }
  *p++ = '\'';
  *p = '\0';
  return out;
}

typedef void (*pre_exec_fn)(void *);

static void appendf(char **dst, const char *fmt, ...) {
  va_list ap;
  va_list copy;
  int needed;
  size_t prefix_len;
  char *combined;

  va_start(ap, fmt);
  va_copy(copy, ap);
  needed = vsnprintf(NULL, 0, fmt, copy);
  va_end(copy);
  if (needed < 0) {
    va_end(ap);
    dief("vsnprintf failed");
  }

  prefix_len = *dst == NULL ? 0 : strlen(*dst);
  combined = xmalloc(prefix_len + (size_t)needed + 1);
  if (*dst != NULL) {
    memcpy(combined, *dst, prefix_len);
  }
  vsnprintf(combined + prefix_len, (size_t)needed + 1, fmt, ap);
  va_end(ap);
  free(*dst);
  *dst = combined;
}

static void spawn_pty_process(char *const *argv, pre_exec_fn pre_exec, void *opaque,
                              int *read_fd, int *write_fd, pid_t *pid_out) {
  int master_fd;
  char slave_name[PATH_MAX];
  pid_t pid;

  master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (master_fd < 0) {
    die_errno("posix_openpt", NULL);
  }
  if (grantpt(master_fd) != 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("grantpt", NULL);
  }
  if (unlockpt(master_fd) != 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("unlockpt", NULL);
  }
  if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("ptsname_r", NULL);
  }

  pid = fork();
  if (pid < 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("fork", NULL);
  }

  if (pid == 0) {
    int slave_fd;
    g_error_ctx = NULL;
    if (setsid() < 0) {
      fprintf(stderr, "setsid: %s\n", strerror(errno));
      _exit(127);
    }
    slave_fd = open(slave_name, O_RDWR);
    if (slave_fd < 0) {
      fprintf(stderr, "open pty slave: %s\n", strerror(errno));
      _exit(127);
    }
    (void)ioctl(slave_fd, TIOCSCTTY, 0);
    if (dup2(slave_fd, STDIN_FILENO) < 0 ||
        dup2(slave_fd, STDOUT_FILENO) < 0 ||
        dup2(slave_fd, STDERR_FILENO) < 0) {
      fprintf(stderr, "dup2: %s\n", strerror(errno));
      _exit(127);
    }
    if (slave_fd > STDERR_FILENO) {
      close(slave_fd);
    }
    close(master_fd);
    if (pre_exec != NULL) {
      pre_exec(opaque);
    }
    execvp(argv[0], argv);
    fprintf(stderr, "execvp %s: %s\n", argv[0], strerror(errno));
    _exit(127);
  }

  *read_fd = master_fd;
  *write_fd = dup(master_fd);
  if (*write_fd < 0) {
    int saved = errno;
    close(master_fd);
    kill(pid, SIGKILL);
    errno = saved;
    die_errno("dup", NULL);
  }
  *pid_out = pid;
}

struct proot_env {
  const char *tmp_dir;
  const char *loader_path;
  const char *host_path;
};

static void proot_pre_exec(void *opaque) {
  struct proot_env *env = opaque;
#ifdef TERMINAL_MULTICALL_BUILD
  char self_path[PATH_MAX];
  ssize_t self_path_length;
#endif
  set_path_env(env->host_path);
#ifdef PR_SET_DUMPABLE
  (void)prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
#endif
  if (setenv("PROOT_TMP_DIR", env->tmp_dir, 1) != 0) {
    die_errno("setenv", "PROOT_TMP_DIR");
  }
#ifdef TERMINAL_MULTICALL_BUILD
  self_path_length = readlink("/proc/self/exe", self_path,
                              sizeof(self_path) - 1);
  if (self_path_length < 0) {
    die_errno("readlink", "/proc/self/exe");
  }
  self_path[self_path_length] = '\0';
  if (setenv("PROOT_LOADER", self_path, 1) != 0) {
    die_errno("setenv", "PROOT_LOADER");
  }
#else
  if (env->loader_path != NULL && env->loader_path[0] != '\0') {
    if (setenv("PROOT_LOADER", env->loader_path, 1) != 0) {
      die_errno("setenv", "PROOT_LOADER");
    }
  }
#endif
}

#ifdef TERMINAL_MULTICALL_BUILD
static void spawn_pty_proot(char *const *argv, int argc, struct proot_env *env,
                            int *read_fd, int *write_fd, pid_t *pid_out) {
  int master_fd;
  char slave_name[PATH_MAX];
  pid_t pid;

  master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (master_fd < 0) {
    die_errno("posix_openpt", NULL);
  }
  if (grantpt(master_fd) != 0 || unlockpt(master_fd) != 0 ||
      ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("prepare pty", NULL);
  }

  pid = fork();
  if (pid < 0) {
    int saved = errno;
    close(master_fd);
    errno = saved;
    die_errno("fork", NULL);
  }

  if (pid == 0) {
    int slave_fd;
    g_error_ctx = NULL;
    if (setsid() < 0) {
      fprintf(stderr, "setsid: %s\n", strerror(errno));
      _exit(127);
    }
    slave_fd = open(slave_name, O_RDWR);
    if (slave_fd < 0) {
      fprintf(stderr, "open pty slave: %s\n", strerror(errno));
      _exit(127);
    }
    (void)ioctl(slave_fd, TIOCSCTTY, 0);
    if (dup2(slave_fd, STDIN_FILENO) < 0 ||
        dup2(slave_fd, STDOUT_FILENO) < 0 ||
        dup2(slave_fd, STDERR_FILENO) < 0) {
      fprintf(stderr, "dup2: %s\n", strerror(errno));
      _exit(127);
    }
    if (slave_fd > STDERR_FILENO) {
      close(slave_fd);
    }
    close(master_fd);
    proot_pre_exec(env);
    _exit(proot_main(argc, argv));
  }

  *read_fd = master_fd;
  *write_fd = dup(master_fd);
  if (*write_fd < 0) {
    int saved = errno;
    close(master_fd);
    kill(pid, SIGKILL);
    errno = saved;
    die_errno("dup", NULL);
  }
  *pid_out = pid;
}
#endif

static char *build_tool_shell(const char *guest_path) {
  char *quoted_path = shell_quote(guest_path);
  char *command = xasprintf(
      "PATH=%s; export PATH; "
      "ANDROID_ROOT=%s; export ANDROID_ROOT; "
      "ANDROID_DATA=%s; export ANDROID_DATA; "
      "ANDROID_ART_ROOT=%s; export ANDROID_ART_ROOT; "
      "ANDROID_I18N_ROOT=%s; export ANDROID_I18N_ROOT; "
      "ANDROID_TZDATA_ROOT=%s; export ANDROID_TZDATA_ROOT; "
      "ANDROID_RUNTIME_ROOT=%s; export ANDROID_RUNTIME_ROOT; "
      "cd /wlantool && exec /bin/sh -i",
      quoted_path,
      DEFAULT_ANDROID_ROOT,
      DEFAULT_ANDROID_DATA,
      DEFAULT_ANDROID_ART_ROOT,
      DEFAULT_ANDROID_I18N_ROOT,
      DEFAULT_ANDROID_TZDATA_ROOT,
      DEFAULT_ANDROID_RUNTIME_ROOT);
  free(quoted_path);
  return command;
}

static char *build_chroot_command(const char *root, const char *host_path,
                                  const char *guest_path,
                                  const struct string_list *mount_roots) {
  char *quoted_root = shell_quote(root);
  char *quoted_host_path = shell_quote(host_path);
  char *tool_shell = build_tool_shell(guest_path);
  char *quoted_tool_shell = shell_quote(tool_shell);
  char *inner = NULL;
  char *quoted_inner = NULL;
  char *command = NULL;
  size_t i;

  appendf(&inner,
          "PATH=%s; export PATH; "
          "ROOT=%s; "
          "mkdir -p \"$ROOT/proc\" \"$ROOT/sys\" \"$ROOT/dev\"; ",
          quoted_host_path,
          quoted_root);
  for (i = 0; i < mount_roots->count; i++) {
    appendf(&inner,
            "if [ -d %1$s ]; then "
            "rm -f \"$ROOT%1$s\" 2>/dev/null || true; "
            "mkdir -p \"$ROOT%1$s\"; "
            "mount --rbind %1$s \"$ROOT%1$s\" 2>/dev/null || true; "
            "else "
            "rm -rf \"$ROOT%1$s\" 2>/dev/null || true; "
            "mkdir -p \"$(dirname \"$ROOT%1$s\")\"; "
            ": > \"$ROOT%1$s\"; "
            "mount -o bind %1$s \"$ROOT%1$s\" 2>/dev/null || true; "
            "fi; ",
            mount_roots->items[i]);
  }
  appendf(&inner,
          "mount -t proc proc \"$ROOT/proc\" 2>/dev/null || true; "
          "mount --rbind /sys \"$ROOT/sys\" 2>/dev/null || true; "
          "mount --rbind /dev \"$ROOT/dev\" 2>/dev/null || true; ");
  appendf(&inner,
          "cd \"$ROOT\"; "
          "chroot . /bin/sh -lc %s; "
          "RC=$?; ",
          quoted_tool_shell);
  for (i = mount_roots->count; i > 0; i--) {
    appendf(&inner, "umount -l \"$ROOT%s\" 2>/dev/null || true; ",
            mount_roots->items[i - 1]);
  }
  appendf(&inner,
          "umount -l \"$ROOT/dev\" 2>/dev/null || true; "
          "umount -l \"$ROOT/sys\" 2>/dev/null || true; "
          "umount -l \"$ROOT/proc\" 2>/dev/null || true; "
          "exit $RC");
  quoted_inner = shell_quote(inner);
  appendf(&command,
          "PATH=%s; export PATH; "
          "ROOT=%s; "
          "exec /system/bin/unshare -m /system/bin/sh -c %s",
          quoted_host_path,
          quoted_root,
          quoted_inner);

  free(quoted_root);
  free(quoted_host_path);
  free(tool_shell);
  free(quoted_tool_shell);
  free(inner);
  free(quoted_inner);
  return command;
}

static int start_proot_session_impl(const char *root, const char *runtime_root,
                                    const char *proot_path,
                                    const char *loader_path,
                                    const char *host_tool_path,
                                    int *read_fd, int *write_fd, pid_t *pid_out) {
  struct string_list host_paths;
  struct string_list mount_roots;
  char *tmp_dir = path_join2(runtime_root, "proot-tmp");
  struct proot_env env;
  char *guest_path;
  char *host_exec_path;
  char *tool_shell;
  char **argv;
  size_t argc = 0;
  size_t i;

#ifndef TERMINAL_MULTICALL_BUILD
  if (proot_path == NULL || proot_path[0] == '\0') {
    dief("proot path is empty");
  }
  if (access(proot_path, X_OK) != 0) {
    die_errno("access", proot_path);
  }
#else
  (void)proot_path;
  (void)loader_path;
#endif

  collect_host_tool_paths(host_tool_path, &host_paths, &mount_roots);
  guest_path = build_guest_path(&host_paths);
  host_exec_path = build_host_exec_path(&host_paths);
  tool_shell = build_tool_shell(guest_path);
  argv = xcalloc(16 + mount_roots.count * 2, sizeof(char *));
#ifdef TERMINAL_MULTICALL_BUILD
  argv[argc++] = "proot";
#else
  argv[argc++] = (char *)proot_path;
#endif
  argv[argc++] = "--kill-on-exit";
  argv[argc++] = "-r";
  argv[argc++] = (char *)root;
  argv[argc++] = "-w";
  argv[argc++] = "/";
  argv[argc++] = "-b";
  argv[argc++] = "/dev";
  argv[argc++] = "-b";
  argv[argc++] = "/proc";
  argv[argc++] = "-b";
  argv[argc++] = "/sys";
  for (i = 0; i < mount_roots.count; i++) {
    argv[argc++] = "-b";
    argv[argc++] = mount_roots.items[i];
  }
  argv[argc++] = "/bin/sh";
  argv[argc++] = "-lc";
  argv[argc++] = tool_shell;
  argv[argc] = NULL;

  ensure_dir(runtime_root, 0700);
  if (rmrf_path(tmp_dir) != 0 && errno != ENOENT) {
    die_errno("rmrf", tmp_dir);
  }
  ensure_dir(tmp_dir, 0700);
  env.tmp_dir = tmp_dir;
  env.loader_path = NULL;
  env.host_path = host_exec_path;
#ifdef TERMINAL_MULTICALL_BUILD
  spawn_pty_proot(argv, (int)argc, &env, read_fd, write_fd, pid_out);
#else
  env.loader_path = loader_path;
  spawn_pty_process(argv, proot_pre_exec, &env, read_fd, write_fd, pid_out);
#endif

  free(argv);
  free(tool_shell);
  free(host_exec_path);
  free(guest_path);
  string_list_free(&mount_roots);
  string_list_free(&host_paths);
  free(tmp_dir);
  return 0;
}

static int start_chroot_session_impl(const char *root, int *read_fd, int *write_fd,
                                     pid_t *pid_out,
                                     const char *host_tool_path) {
  struct string_list host_paths;
  struct string_list mount_roots;
  char *guest_path;
  char *host_exec_path;
  char *command;
  char *argv[] = {
      "/system/bin/sh",
      "-c",
      NULL,
      NULL,
  };

  if (geteuid() != 0) {
    dief("chroot session requires uid 0");
  }

  collect_host_tool_paths(host_tool_path, &host_paths, &mount_roots);
  guest_path = build_guest_path(&host_paths);
  host_exec_path = build_host_exec_path(&host_paths);
  command = build_chroot_command(root, host_exec_path, guest_path, &mount_roots);
  argv[2] = command;

  spawn_pty_process(argv, NULL, NULL, read_fd, write_fd, pid_out);
  free(command);
  free(host_exec_path);
  free(guest_path);
  string_list_free(&mount_roots);
  string_list_free(&host_paths);
  return 0;
}

static void terminate_process_group(pid_t pid) {
  if (pid <= 0) {
    return;
  }
  kill(-pid, SIGTERM);
  usleep(250000);
  kill(-pid, SIGKILL);
}

typedef int (*protected_fn)(void *);

static int run_protected(protected_fn fn, void *opaque, char **error_out) {
  struct error_ctx ctx;

  ctx.message[0] = '\0';
  g_error_ctx = &ctx;
  if (setjmp(ctx.env) != 0) {
    g_error_ctx = NULL;
    if (error_out != NULL) {
      *error_out = xstrdup(ctx.message[0] == '\0' ? "unknown native error" : ctx.message);
    }
    return -1;
  }

  if (error_out != NULL) {
    *error_out = NULL;
  }
  if (fn(opaque) != 0) {
    g_error_ctx = NULL;
    if (error_out != NULL) {
      *error_out = xstrdup(ctx.message[0] == '\0' ? "native call failed" : ctx.message);
    }
    return -1;
  }
  g_error_ctx = NULL;
  return 0;
}

#ifndef TERMINAL_MULTICALL_BUILD
struct extract_args {
  const char *archive;
  const char *destination;
};

static int run_extract(void *opaque) {
  struct extract_args *args = opaque;
  return cmd_extract(args->archive, args->destination);
}
#endif

struct session_args {
  int mode;
  const char *root;
  const char *runtime;
  const char *proot_path;
  const char *proot_loader_path;
  const char *host_tool_path;
  int *read_fd;
  int *write_fd;
  pid_t *pid_out;
};

static int run_session(void *opaque) {
  struct session_args *args = opaque;

  if (args->mode == MODE_PROOT) {
    return start_proot_session_impl(
        args->root,
        args->runtime,
        args->proot_path,
        args->proot_loader_path,
        args->host_tool_path,
        args->read_fd,
        args->write_fd,
        args->pid_out);
  }
  if (args->mode == MODE_CHROOT) {
    return start_chroot_session_impl(
        args->root,
        args->read_fd,
        args->write_fd,
        args->pid_out,
        args->host_tool_path);
  }
  dief("unsupported mode: %d", args->mode);
  return -1;
}

#if !defined(RFTOOL_CLI_BUILD) && !defined(TERMINAL_MULTICALL_BUILD)
static char *jstring_to_cstring(JNIEnv *env, jstring value) {
  const char *chars;
  char *copy;

  if (value == NULL) {
    return xstrdup("");
  }

  chars = (*env)->GetStringUTFChars(env, value, NULL);
  if (chars == NULL) {
    return NULL;
  }
  copy = xstrdup(chars);
  (*env)->ReleaseStringUTFChars(env, value, chars);
  return copy;
}

static void throw_io_exception(JNIEnv *env, const char *message) {
  jclass cls = (*env)->FindClass(env, "java/io/IOException");
  if (cls != NULL) {
    (*env)->ThrowNew(env, cls, message);
  }
}

JNIEXPORT void JNICALL
Java_io_github_bszapp_wlantool_bridge_RftoolBridge_extractRootfs(
    JNIEnv *env, jclass clazz, jstring archivePath, jstring destinationPath) {
  struct extract_args args;
  char *archive = NULL;
  char *destination = NULL;
  char *error = NULL;

  (void)clazz;
  archive = jstring_to_cstring(env, archivePath);
  destination = jstring_to_cstring(env, destinationPath);
  if (archive == NULL || destination == NULL) {
    free(archive);
    free(destination);
    return;
  }

  args.archive = archive;
  args.destination = destination;
  if (run_protected(run_extract, &args, &error) != 0) {
    throw_io_exception(env, error != NULL ? error : "rootfs extraction failed");
  }

  free(error);
  free(archive);
  free(destination);
}

JNIEXPORT jlongArray JNICALL
Java_io_github_bszapp_wlantool_bridge_RftoolBridge_startSession(
    JNIEnv *env, jclass clazz, jint mode, jstring rootfsPath, jstring runtimePath,
    jstring prootPath, jstring prootLoaderPath, jstring hostToolPath) {
  struct session_args args;
  char *root = NULL;
  char *runtime = NULL;
  char *proot = NULL;
  char *loader = NULL;
  char *host_tools = NULL;
  char *error = NULL;
  pid_t pid = -1;
  int read_fd = -1;
  int write_fd = -1;
  jlongArray out;
  jlong values[3];

  (void)clazz;
  root = jstring_to_cstring(env, rootfsPath);
  runtime = jstring_to_cstring(env, runtimePath);
  proot = jstring_to_cstring(env, prootPath);
  loader = jstring_to_cstring(env, prootLoaderPath);
  host_tools = jstring_to_cstring(env, hostToolPath);
  if (root == NULL || runtime == NULL || proot == NULL || loader == NULL || host_tools == NULL) {
    free(root);
    free(runtime);
    free(proot);
    free(loader);
    free(host_tools);
    return NULL;
  }

  args.mode = (int)mode;
  args.root = root;
  args.runtime = runtime;
  args.proot_path = proot;
  args.proot_loader_path = loader;
  args.host_tool_path = host_tools;
  args.read_fd = &read_fd;
  args.write_fd = &write_fd;
  args.pid_out = &pid;

  if (run_protected(run_session, &args, &error) != 0) {
    throw_io_exception(env, error != NULL ? error : "session start failed");
    free(error);
    free(root);
    free(runtime);
    free(proot);
    free(loader);
    free(host_tools);
    return NULL;
  }

  values[0] = (jlong)pid;
  values[1] = (jlong)read_fd;
  values[2] = (jlong)write_fd;
  out = (*env)->NewLongArray(env, 3);
  if (out != NULL) {
    (*env)->SetLongArrayRegion(env, out, 0, 3, values);
  }

  free(root);
  free(runtime);
  free(proot);
  free(loader);
  free(host_tools);
  return out;
}

JNIEXPORT void JNICALL
Java_io_github_bszapp_wlantool_bridge_RftoolBridge_terminateProcess(
    JNIEnv *env, jclass clazz, jlong pid) {
  (void)env;
  (void)clazz;
  terminate_process_group((pid_t)pid);
}
#endif

#ifdef TERMINAL_MULTICALL_BUILD
static volatile sig_atomic_t g_session_child = -1;
static volatile sig_atomic_t g_session_stop_requested = 0;

static void forward_terminal_signal(int signal_number) {
  pid_t child = (pid_t)g_session_child;
  g_session_stop_requested = signal_number;
  if (child > 0) {
    (void)kill(-child, signal_number);
  }
}

static void prepare_terminal_signal_state(void) {
  g_session_child = -1;
  g_session_stop_requested = 0;
  signal(SIGTERM, forward_terminal_signal);
  signal(SIGINT, forward_terminal_signal);
  signal(SIGHUP, forward_terminal_signal);
}

static int write_all_fd(int fd, const unsigned char *buffer, size_t size) {
  size_t offset = 0;
  while (offset < size) {
    ssize_t written = write(fd, buffer + offset, size - offset);
    if (written < 0) {
      if (errno == EINTR) {
        continue;
      }
      return -1;
    }
    offset += (size_t)written;
  }
  return 0;
}

static int proxy_pty_session(int read_fd, int write_fd, pid_t child) {
  unsigned char buffer[32768];
  bool stdin_open = true;
  bool pty_open = true;
  int status = 0;
  bool child_reaped = false;

  g_session_child = child;

  while (pty_open || !child_reaped) {
    struct pollfd fds[2];
    nfds_t count = 0;
    int poll_result;

    if (g_session_stop_requested != 0) {
      terminate_process_group(child);
      g_session_stop_requested = 0;
      stdin_open = false;
      pty_open = false;
    }

    if (stdin_open) {
      fds[count].fd = STDIN_FILENO;
      fds[count].events = POLLIN | POLLHUP;
      fds[count].revents = 0;
      count++;
    }
    if (pty_open) {
      fds[count].fd = read_fd;
      fds[count].events = POLLIN | POLLHUP;
      fds[count].revents = 0;
      count++;
    }

    poll_result = poll(fds, count, 100);
    if (poll_result < 0 && errno != EINTR) {
      terminate_process_group(child);
      break;
    }

    for (nfds_t i = 0; poll_result > 0 && i < count; i++) {
      if ((fds[i].revents & (POLLIN | POLLHUP | POLLERR)) == 0) {
        continue;
      }
      if (fds[i].fd == STDIN_FILENO) {
        ssize_t n = read(STDIN_FILENO, buffer, sizeof(buffer));
        if (n > 0) {
          if (write_all_fd(write_fd, buffer, (size_t)n) != 0) {
            terminate_process_group(child);
            stdin_open = false;
            pty_open = false;
          }
        } else {
          terminate_process_group(child);
          stdin_open = false;
          pty_open = false;
        }
      } else {
        ssize_t n = read(read_fd, buffer, sizeof(buffer));
        if (n > 0) {
          if (write_all_fd(STDOUT_FILENO, buffer, (size_t)n) != 0) {
            terminate_process_group(child);
            pty_open = false;
          }
        } else if (n == 0 || errno == EIO) {
          pty_open = false;
        }
      }
    }

    if (!child_reaped) {
      pid_t waited = waitpid(child, &status, WNOHANG);
      if (waited == child) {
        child_reaped = true;
      }
    }
  }

  if (!child_reaped) {
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {
    }
  }
  g_session_child = -1;
  g_session_stop_requested = 0;
  close(read_fd);
  close(write_fd);

  if (WIFEXITED(status)) {
    return WEXITSTATUS(status);
  }
  if (WIFSIGNALED(status)) {
    return 128 + WTERMSIG(status);
  }
  return 1;
}

static bool path_contains_parent_reference(const char *path) {
  return strcmp(path, "..") == 0 || strncmp(path, "../", 3) == 0 ||
         strstr(path, "/../") != NULL ||
         (strlen(path) >= 3 && strcmp(path + strlen(path) - 3, "/..") == 0);
}

static int validate_delete_target(const char *path, const char *allowed_root) {
  char resolved_path[PATH_MAX];
  char resolved_root[PATH_MAX];
  struct stat st;

  if (path == NULL || allowed_root == NULL || path[0] != '/' ||
      allowed_root[0] != '/' || path_contains_parent_reference(path) ||
      path_contains_parent_reference(allowed_root)) {
    fprintf(stderr, "unsafe delete request\n");
    return -1;
  }
  if (realpath(allowed_root, resolved_root) == NULL) {
    fprintf(stderr, "cannot resolve allowed root: %s\n", strerror(errno));
    return -1;
  }
  if (strcmp(resolved_root, "/") == 0 || strcmp(resolved_root, "/data") == 0 ||
      strcmp(resolved_root, "/system") == 0) {
    fprintf(stderr, "refusing broad allowed root: %s\n", resolved_root);
    return -1;
  }
  if (lstat(path, &st) != 0) {
    if (errno == ENOENT && path_has_prefix(path, resolved_root) &&
        strcmp(path, resolved_root) != 0) {
      return 0;
    }
    fprintf(stderr, "cannot inspect delete target: %s\n", strerror(errno));
    return -1;
  }
  if (S_ISLNK(st.st_mode)) {
    fprintf(stderr, "refusing symlink delete target: %s\n", path);
    return -1;
  }
  if (realpath(path, resolved_path) == NULL) {
    fprintf(stderr, "cannot resolve delete target: %s\n", strerror(errno));
    return -1;
  }
  if (strcmp(resolved_path, resolved_root) == 0 ||
      !path_has_prefix(resolved_path, resolved_root)) {
    fprintf(stderr, "delete target is outside allowed root: %s\n", resolved_path);
    return -1;
  }
  return 0;
}

static const char *option_value(int argc, char **argv, const char *name) {
  int i;
  for (i = 0; i + 1 < argc; i++) {
    if (strcmp(argv[i], name) == 0) {
      return argv[i + 1];
    }
  }
  return NULL;
}

static void usage(const char *argv0) {
  fprintf(stderr,
          "usage:\n"
          "  %s version\n"
          "  %s session proot --rootfs PATH --runtime PATH --host-path PATH\n"
          "  %s session chroot --rootfs PATH --runtime PATH --host-path PATH\n"
          "  %s container delete --path PATH --allowed-root PATH\n"
          "  %s process terminate --pid PID\n",
          argv0, argv0, argv0, argv0, argv0);
}

int main(int argc, char **argv) {
  if (argc == 2 && strcmp(argv[1], "version") == 0) {
    puts("libterminal 1");
    return 0;
  }

  if (argc >= 3 && strcmp(argv[1], "session") == 0) {
    const char *root = option_value(argc, argv, "--rootfs");
    const char *runtime = option_value(argc, argv, "--runtime");
    const char *host_path = option_value(argc, argv, "--host-path");
    int read_fd = -1;
    int write_fd = -1;
    pid_t child = -1;

    if (root == NULL || runtime == NULL || host_path == NULL) {
      usage(argv[0]);
      return 2;
    }
    if (strcmp(argv[2], "proot") == 0) {
      prepare_terminal_signal_state();
      start_proot_session_impl(root, runtime, NULL, NULL, host_path,
                               &read_fd, &write_fd, &child);
      return proxy_pty_session(read_fd, write_fd, child);
    }
    if (strcmp(argv[2], "chroot") == 0) {
      prepare_terminal_signal_state();
      if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0) {
        dief("cannot configure chroot parent-death signal: %s", strerror(errno));
      }
      if (getppid() == 1) {
        raise(SIGTERM);
      }
      start_chroot_session_impl(root, &read_fd, &write_fd, &child, host_path);
      return proxy_pty_session(read_fd, write_fd, child);
    }
    usage(argv[0]);
    return 2;
  }

  if (argc >= 3 && strcmp(argv[1], "container") == 0 &&
      strcmp(argv[2], "delete") == 0) {
    const char *path = option_value(argc, argv, "--path");
    const char *allowed_root = option_value(argc, argv, "--allowed-root");
    if (validate_delete_target(path, allowed_root) != 0) {
      return 1;
    }
    return cmd_rmrf_with_progress(path) == 0 ? 0 : 1;
  }

  if (argc >= 3 && strcmp(argv[1], "process") == 0 &&
      strcmp(argv[2], "terminate") == 0) {
    const char *pid_text = option_value(argc, argv, "--pid");
    char *end = NULL;
    long pid;
    if (pid_text == NULL) {
      usage(argv[0]);
      return 2;
    }
    errno = 0;
    pid = strtol(pid_text, &end, 10);
    if (errno != 0 || end == pid_text || *end != '\0' || pid <= 0 || pid > INT_MAX) {
      fprintf(stderr, "invalid pid: %s\n", pid_text);
      return 2;
    }
    terminate_process_group((pid_t)pid);
    return 0;
  }

  usage(argv[0]);
  return 2;
}
#elif defined(RFTOOL_CLI_BUILD)
static void usage(const char *argv0) {
  fprintf(stderr,
          "usage:\n"
          "  %s rmrf PATH\n",
          argv0);
}

int main(int argc, char **argv) {
  if (argc != 3 || strcmp(argv[1], "rmrf") != 0) {
    usage(argv[0]);
    return 2;
  }
  return cmd_rmrf_with_progress(argv[2]) == 0 ? 0 : 1;
}
#endif
