/* SPDX-License-Identifier: MIT */
#include "common.h"
#include "types.h"
#include "hashcat.h"
#include "event.h"
#include "hashcat_android.h"
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define HC_ANDROID_LOG(priority, ...) __android_log_print(priority, "HashcatNative", __VA_ARGS__)

extern int hashcat_frontend_main(int argc, char **argv);
extern int hc_android_prepare_resources(const char *runtime);
static pthread_mutex_t run_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t context_mutex = PTHREAD_MUTEX_INITIALIZER;
static hashcat_ctx_t *executing_context;
static bool embedded;
static const char *opencl_library_path;
static wlantool_hashcat_event_callback event_callback;
static void *event_user;

const char *wlantool_hashcat_version(void) { return VERSION_TAG; }
bool hc_android_is_embedded(void) { return embedded; }
const char *hc_android_opencl_library(void) { return opencl_library_path; }

/* The WPA dictionary build has no bundled optional conversion/compression
 * libraries. Let the upstream loader resolve any system-provided libraries. */
void *hc_android_load_builtin(const char *filename)
{
    (void)filename;
    return NULL;
}

/* Use only namespaces the system already exports, as AOSP libvndksupport does.
 * The namespace lookup is an internal API, resolved optionally at runtime.
 * Do not create namespaces or change the application's linker configuration. */
void *hc_android_load_opencl(const char *filename, void *context)
{
    HC_ANDROID_LOG(ANDROID_LOG_INFO, "OpenCL dlopen attempt: %s", filename);
    void *library = dlopen(filename, RTLD_NOW);
    if (library) {
        HC_ANDROID_LOG(ANDROID_LOG_INFO, "OpenCL dlopen succeeded: %s", filename);
        return library;
    }
    HC_ANDROID_LOG(ANDROID_LOG_WARN, "OpenCL dlopen failed: %s: %s", filename, dlerror());
    typedef struct android_namespace_t *(*namespace_lookup)(const char *);
    namespace_lookup lookup = (namespace_lookup)dlsym(RTLD_DEFAULT, "android_get_exported_namespace");
    if (!lookup) {
        /* Recent Android exports the private namespace API from libdl_android,
         * which need not be present in the default symbol scope. Retain the
         * handle for the process lifetime while its function pointer is used. */
        static void *namespace_library;
        if (!namespace_library) namespace_library = dlopen("libdl_android.so", RTLD_NOW | RTLD_LOCAL);
        if (namespace_library) {
            lookup = (namespace_lookup)dlsym(namespace_library, "android_get_exported_namespace");
            if (lookup) HC_ANDROID_LOG(ANDROID_LOG_INFO, "Exported namespace API resolved from libdl_android.so");
        }
    }
    if (!lookup) {
        HC_ANDROID_LOG(ANDROID_LOG_WARN, "Exported namespace lookup unavailable: %s", dlerror());
        return NULL;
    }
    const char *names[] = {"sphal", "vendor", NULL};
    for (int i = 0; names[i]; ++i) {
        struct android_namespace_t *space = lookup(names[i]);
        if (!space) {
            HC_ANDROID_LOG(ANDROID_LOG_DEBUG, "Exported namespace unavailable: %s", names[i]);
            continue;
        }
        android_dlextinfo options = {
            .flags = ANDROID_DLEXT_USE_NAMESPACE,
            .library_namespace = space,
        };
        library = android_dlopen_ext(filename, RTLD_NOW, &options);
        if (library) {
            HC_ANDROID_LOG(ANDROID_LOG_INFO, "OpenCL loaded in namespace %s: %s", names[i], filename);
            event_log_info(context, "Android OpenCL driver loaded via exported %s namespace: %s", names[i], filename);
            return library;
        }
        HC_ANDROID_LOG(ANDROID_LOG_WARN, "OpenCL namespace %s load failed: %s: %s", names[i], filename, dlerror());
    }
    return NULL;
}

void hc_android_set_executing_context(void *context)
{
    pthread_mutex_lock(&context_mutex);
    executing_context = context;
    pthread_mutex_unlock(&context_mutex);
}

int wlantool_hashcat_quit(void)
{
    pthread_mutex_lock(&context_mutex);
    int result = executing_context ? hashcat_session_quit(executing_context) : -1;
    pthread_mutex_unlock(&context_mutex);
    return result;
}

void hc_android_event(uint32_t id, void *context, const void *data, size_t length)
{
    if (!event_callback) return;
    hashcat_ctx_t *ctx = context;
    if (id == EVENT_LOG_INFO || id == EVENT_LOG_WARNING || id == EVENT_LOG_ERROR || id == EVENT_LOG_ADVICE) {
        if (id == EVENT_LOG_ADVICE && !ctx->user_options->advice) return;
        const event_ctx_t *log = ctx->event_ctx;
        size_t size = log->msg_len + (log->msg_newline ? 1 : 0);
        char *text = malloc(size ? size : 1);
        if (!text) return;
        memcpy(text, log->msg_buf, log->msg_len);
        if (log->msg_newline) text[size - 1] = '\n';
        event_callback(id, text, size, event_user);
        free(text);
    } else {
        event_callback(id, data, data ? length : 0, event_user);
    }
}

static int run_frontend(int argc, char **argv, const char *runtime,
    const char *opencl_library, bool library_mode,
    wlantool_hashcat_event_callback callback, void *user)
{
    if (argc < 1 || !argv || !runtime || !*runtime) { errno = EINVAL; return -1; }
    bool version_only = argc == 2 && argv[1] && (!strcmp(argv[1], "--version") || !strcmp(argv[1], "-V"));
    bool explicit_mode = false;
    for (int i = 1; i < argc; ++i) {
        if (argv[i] && (!strncmp(argv[i], "-m", 2) || !strcmp(argv[i], "--hash-type") ||
            !strncmp(argv[i], "--hash-type=", 12))) explicit_mode = true;
    }
    bool default_wpa = !version_only && !explicit_mode;
    char **arguments = calloc((size_t)argc + 3, sizeof(char *));
    if (!arguments) return -1;
    for (int i = 0; i < argc; ++i) {
        int destination = i == 0 ? 0 : i + (default_wpa ? 2 : 0);
        if (!argv[i] || !(arguments[destination] = strdup(argv[i]))) {
            for (int j = 0; j < argc + 2; ++j) free(arguments[j]);
            free(arguments);
            errno = argv[i] ? ENOMEM : EINVAL;
            return -1;
        }
    }
    // Backend information also initializes a module. Use the bundled WPA mode
    // when no mode was supplied, without retaining a default MD5 module.
    if (default_wpa) {
        arguments[1] = strdup("-m");
        arguments[2] = strdup("22000");
        if (!arguments[1] || !arguments[2]) {
            for (int i = 0; i < argc + 2; ++i) free(arguments[i]);
            free(arguments);
            return -1;
        }
        argc += 2;
    }
    pthread_mutex_lock(&run_mutex);
    int result = -1;
    HC_ANDROID_LOG(ANDROID_LOG_INFO, "Native invocation: version=%s embedded=%d argc=%d uid=%u runtime=%s",
        VERSION_TAG, library_mode, argc, (unsigned)getuid(), runtime);
    const char *resource_home = library_mode ? NULL : getenv("HASHCAT_RESOURCE_HOME");
    if (!resource_home || !*resource_home) resource_home = runtime;
    if (!version_only && hc_android_prepare_resources(resource_home) != 0) {
        HC_ANDROID_LOG(ANDROID_LOG_ERROR, "Resource preparation failed: %s: %s", resource_home, strerror(errno));
        fprintf(stderr, "hashcat: cannot prepare resources in %s: %s\n", resource_home, strerror(errno));
        goto finished;
    }
    if (!version_only) HC_ANDROID_LOG(ANDROID_LOG_INFO, "Resources ready: %s", hc_android_resource_dir());
    embedded = library_mode;
    event_callback = callback;
    event_user = user;
    opencl_library_path = opencl_library;
    result = hashcat_frontend_main(argc, arguments);
    HC_ANDROID_LOG(ANDROID_LOG_INFO, "Native frontend returned: exitCode=%d embedded=%d", result, library_mode);
    hc_android_set_executing_context(NULL);
    event_callback = NULL;
    event_user = NULL;
    opencl_library_path = NULL;
    embedded = false;
finished:;
    int saved_errno = errno;
    pthread_mutex_unlock(&run_mutex);
    // getopt can permute pointers, but does not change their allocations.
    for (int i = 0; i < argc; ++i) free(arguments[i]);
    free(arguments);
    errno = saved_errno;
    return result;
}

int wlantool_hashcat_run(int argc, char **argv, const char *runtime,
    const char *opencl_library, wlantool_hashcat_event_callback callback, void *user)
{
    return run_frontend(argc, argv, runtime, opencl_library, true, callback, user);
}

int hc_android_run_command(int argc, char **argv, const char *runtime, const char *opencl_library)
{
    return run_frontend(argc, argv, runtime, opencl_library, false, NULL, NULL);
}

typedef struct {
    JavaVM *vm;
    jobject listener;
    jmethodID method;
    jthrowable failure;
    pthread_mutex_t mutex;
} jni_listener;

static void send_jni_event(uint32_t event_id, const void *data, size_t length, void *user)
{
    jni_listener *listener = user;
    if (length > INT_MAX) return;
    JNIEnv *env = NULL;
    bool attached = false;
    if ((*listener->vm)->GetEnv(listener->vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*listener->vm)->AttachCurrentThread(listener->vm, &env, NULL) != JNI_OK) return;
        attached = true;
    }
    if (!env) return;
    pthread_mutex_lock(&listener->mutex);
    if (!listener->failure) {
        jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
        if (bytes && length) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, data);
        if (!(*env)->ExceptionCheck(env))
            (*env)->CallVoidMethod(env, listener->listener, listener->method, (jint)event_id, bytes);
        if (bytes) (*env)->DeleteLocalRef(env, bytes);
        if ((*env)->ExceptionCheck(env)) {
            jthrowable error = (*env)->ExceptionOccurred(env);
            (*env)->ExceptionClear(env);
            listener->failure = (*env)->NewGlobalRef(env, error);
            (*env)->DeleteLocalRef(env, error);
        }
    }
    if (listener->failure) wlantool_hashcat_quit();
    pthread_mutex_unlock(&listener->mutex);
    if (attached) (*listener->vm)->DetachCurrentThread(listener->vm);
}

JNIEXPORT jstring JNICALL
Java_io_github_bszapp_wifitoolbox_hashcat_NativeHashcat_nativeVersion(JNIEnv *env, jobject self)
{
    (void)self;
    return (*env)->NewStringUTF(env, wlantool_hashcat_version());
}

JNIEXPORT jboolean JNICALL
Java_io_github_bszapp_wifitoolbox_hashcat_NativeHashcat_nativeQuit(JNIEnv *env, jobject self)
{
    (void)env; (void)self;
    return wlantool_hashcat_quit() == 0;
}

/* Android JNI's modified UTF-8 encodes supplementary characters differently
 * from normal UTF-8. Keep command arguments/paths as real UTF-8 byte arrays. */
static char *copy_utf8_bytes(JNIEnv *env, jbyteArray value)
{
    if (!value) return NULL;
    jsize length = (*env)->GetArrayLength(env, value);
    char *text = malloc((size_t)length + 1);
    if (!text) return NULL;
    (*env)->GetByteArrayRegion(env, value, 0, length, (jbyte *)text);
    if ((*env)->ExceptionCheck(env)) { free(text); return NULL; }
    text[length] = '\0';
    return text;
}

JNIEXPORT jint JNICALL
Java_io_github_bszapp_wifitoolbox_hashcat_NativeHashcat_nativeRun(JNIEnv *env, jobject self,
    jobjectArray args, jbyteArray runtime, jbyteArray driver, jobject callback)
{
    (void)self;
    jsize count = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t)count + 2, sizeof(char *));
    if (!argv) return -1;
    argv[0] = strdup("hashcat");
    char *runtime_chars = NULL, *driver_chars = NULL;
    jni_listener listener = {.mutex = PTHREAD_MUTEX_INITIALIZER};
    int result = -1;
    if (!argv[0]) goto cleanup;
    for (jsize i = 0; i < count; ++i) {
        jbyteArray value = (*env)->GetObjectArrayElement(env, args, i);
        if (!value) goto cleanup;
        argv[i + 1] = copy_utf8_bytes(env, value);
        (*env)->DeleteLocalRef(env, value);
        if (!argv[i + 1]) goto cleanup;
    }
    runtime_chars = copy_utf8_bytes(env, runtime);
    if (!runtime_chars) goto cleanup;
    if (driver) {
        driver_chars = copy_utf8_bytes(env, driver);
        if (!driver_chars) goto cleanup;
    }
    if (callback) {
        (*env)->GetJavaVM(env, &listener.vm);
        listener.listener = (*env)->NewGlobalRef(env, callback);
        jclass type = (*env)->GetObjectClass(env, callback);
        listener.method = (*env)->GetMethodID(env, type, "onEvent", "(I[B)V");
        (*env)->DeleteLocalRef(env, type);
        if (!listener.listener || !listener.method) goto cleanup;
    }
    result = wlantool_hashcat_run(count + 1, argv, runtime_chars, driver_chars,
        callback ? send_jni_event : NULL, &listener);
cleanup:
    if (listener.failure) {
        (*env)->Throw(env, listener.failure);
        (*env)->DeleteGlobalRef(env, listener.failure);
    }
    if (listener.listener) (*env)->DeleteGlobalRef(env, listener.listener);
    pthread_mutex_destroy(&listener.mutex);
    free(runtime_chars);
    free(driver_chars);
    for (jsize i = 0; i <= count; ++i) free(argv[i]);
    free(argv);
    return result;
}
