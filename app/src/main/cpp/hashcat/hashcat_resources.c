/* SPDX-License-Identifier: MIT */
#include "hashcat_android.h"
#include "resources_identity.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

extern const unsigned char hashcat_resources_start[], hashcat_resources_end[];
static char resource_directory[PATH_MAX];

const char *hc_android_resource_dir(void) { return resource_directory; }

static int make_directories(const char *path)
{
    char copy[PATH_MAX];
    if (snprintf(copy, sizeof(copy), "%s", path) >= (int)sizeof(copy)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    for (char *p = copy + 1; ; ++p) {
        if (*p != '/' && *p != '\0') continue;
        char saved = *p;
        *p = '\0';
        if (mkdir(copy, 0700) != 0 && errno != EEXIST) return -1;
        struct stat st;
        if (lstat(copy, &st) != 0 || !S_ISDIR(st.st_mode)) {
            errno = ENOTDIR;
            return -1;
        }
        if (saved == '\0') return 0;
        *p = saved;
    }
}

/* Only called on the unique mkdtemp directory created by this invocation.
 * Never follows symlinks and never removes runtime/session/user input files. */
static void remove_temporary_directory(const char *path)
{
    DIR *dir = opendir(path);
    if (!dir) return;
    struct dirent *entry;
    while ((entry = readdir(dir))) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        char child[PATH_MAX];
        if (snprintf(child, sizeof(child), "%s/%s", path, entry->d_name) >= (int)sizeof(child)) continue;
        struct stat st;
        if (lstat(child, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) remove_temporary_directory(child);
        else unlink(child);
    }
    closedir(dir);
    rmdir(path);
}

static int read_inflated(z_stream *stream, void *output, unsigned int length)
{
    stream->next_out = output;
    stream->avail_out = length;
    while (stream->avail_out) {
        unsigned int before_in = stream->avail_in, before_out = stream->avail_out;
        int result = inflate(stream, Z_NO_FLUSH);
        if (result == Z_STREAM_END && stream->avail_out == 0) return 0;
        if (result != Z_OK || (before_in == stream->avail_in && before_out == stream->avail_out)) {
            errno = EINVAL;
            return -1;
        }
    }
    return 0;
}

static unsigned long long tar_octal(const unsigned char *field, size_t length)
{
    char value[24];
    if (length >= sizeof(value)) return 0;
    memcpy(value, field, length);
    value[length] = '\0';
    return strtoull(value, NULL, 8);
}

static int extract_archive(const char *destination)
{
    z_stream stream = {0};
    size_t compressed_length = (size_t)(hashcat_resources_end - hashcat_resources_start);
    if (compressed_length > UINT_MAX) { errno = EFBIG; return -1; }
    stream.next_in = (Bytef *)hashcat_resources_start;
    stream.avail_in = (unsigned int)compressed_length;
    if (inflateInit2(&stream, 15 + 16) != Z_OK) { errno = ENOMEM; return -1; }
    int result = -1;
    unsigned char header[512], buffer[65536];
    for (;;) {
        if (read_inflated(&stream, header, sizeof(header)) != 0) break;
        if (header[0] == 0) {
            // Drain the gzip trailer so its CRC and size are verified too.
            int status;
            do {
                stream.next_out = buffer;
                stream.avail_out = sizeof(buffer);
                status = inflate(&stream, Z_NO_FLUSH);
            } while (status == Z_OK);
            if (status == Z_STREAM_END) result = 0;
            else errno = EINVAL;
            break;
        }
        unsigned int checksum = 0;
        for (size_t i = 0; i < sizeof(header); ++i)
            checksum += (i >= 148 && i < 156) ? ' ' : header[i];
        if (checksum != tar_octal(header + 148, 8)) { errno = EINVAL; break; }
        char name[257];
        int name_length = header[345]
            ? snprintf(name, sizeof(name), "%.*s/%.*s", 155, header + 345, 100, header)
            : snprintf(name, sizeof(name), "%.*s", 100, header);
        if (name_length <= 0 || name_length >= (int)sizeof(name) || name[0] == '/') {
            errno = EINVAL; break;
        }
        // Reject special/traversal path components before opening any file.
        char components[sizeof(name)];
        memcpy(components, name, (size_t)name_length + 1);
        char *save = NULL, *part = strtok_r(components, "/", &save);
        bool valid = true;
        while (part) {
            if (!strcmp(part, ".") || !strcmp(part, "..")) valid = false;
            part = strtok_r(NULL, "/", &save);
        }
        if (!valid) { errno = EINVAL; break; }
        char path[PATH_MAX];
        if (snprintf(path, sizeof(path), "%s/%s", destination, name) >= (int)sizeof(path)) {
            errno = ENAMETOOLONG; break;
        }
        unsigned long long size = tar_octal(header + 124, 12);
        if (header[156] == '5') {
            if (size != 0 || make_directories(path) != 0) break;
            continue;
        }
        if (header[156] != '0' && header[156] != 0) { errno = EINVAL; break; }
        int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC,
            name_length >= 3 && !strcmp(name + name_length - 3, ".so") ? 0700 : 0600);
        if (fd < 0) break;
        bool written = true;
        for (unsigned long long remaining = size; remaining;) {
            unsigned int chunk = remaining > sizeof(buffer) ? sizeof(buffer) : (unsigned int)remaining;
            if (read_inflated(&stream, buffer, chunk) != 0) { written = false; break; }
            unsigned int position = 0;
            while (position < chunk) {
                ssize_t count = write(fd, buffer + position, chunk - position);
                if (count < 0 && errno == EINTR) continue;
                if (count <= 0) { written = false; break; }
                position += (unsigned int)count;
            }
            if (!written) break;
            remaining -= chunk;
        }
        int saved_errno = errno;
        if (close(fd) != 0 && written) { saved_errno = errno; written = false; }
        errno = saved_errno;
        if (!written) break;
        unsigned int padding = (unsigned int)((512 - size % 512) % 512);
        if (padding && read_inflated(&stream, buffer, padding) != 0) break;
    }
    int saved_errno = errno;
    inflateEnd(&stream);
    errno = saved_errno;
    return result;
}

int hc_android_prepare_resources(const char *runtime)
{
    if (!runtime || !*runtime || make_directories(runtime) != 0) return -1;
    char canonical[PATH_MAX];
    if (!realpath(runtime, canonical)) return -1;
    char lock_path[PATH_MAX], marker[PATH_MAX], temporary[PATH_MAX];
    if (snprintf(lock_path, sizeof(lock_path), "%s/.resources.lock", canonical) >= (int)sizeof(lock_path)
        || snprintf(resource_directory, sizeof(resource_directory), "%s/resources-%s", canonical,
            HASHCAT_RESOURCE_ID) >= (int)sizeof(resource_directory)
        || snprintf(marker, sizeof(marker), "%s/.complete", resource_directory) >= (int)sizeof(marker)) {
        errno = ENAMETOOLONG; return -1;
    }
    int lock = open(lock_path, O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0) return -1;
    int result = -1;
    if (flock(lock, LOCK_EX) != 0) goto done;
    struct stat st;
    if (lstat(marker, &st) == 0 && S_ISREG(st.st_mode)) { result = 0; goto done; }
    if (snprintf(temporary, sizeof(temporary), "%s/.resources-XXXXXX", canonical) >= (int)sizeof(temporary)) {
        errno = ENAMETOOLONG; goto done;
    }
    if (!mkdtemp(temporary)) goto done;
    if (extract_archive(temporary) != 0) goto cleanup;
    char ready[PATH_MAX];
    if (snprintf(ready, sizeof(ready), "%s/.complete", temporary) >= (int)sizeof(ready)) {
        errno = ENAMETOOLONG; goto cleanup;
    }
    int fd = open(ready, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) goto cleanup;
    if (close(fd) != 0) goto cleanup;
    if (rename(temporary, resource_directory) != 0) goto cleanup;
    result = 0;
    goto done;
cleanup:;
    int saved_errno = errno;
    remove_temporary_directory(temporary);
    errno = saved_errno;
done:;
    int final_errno = errno;
    close(lock);
    errno = final_errno;
    return result;
}
