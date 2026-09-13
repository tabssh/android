/*
 * TabSSH SPICE JNI symbol table.
 *
 * This translation unit exports every native symbol the Kotlin
 * SpiceClient / SpiceLoader classes reference. It is always compiled,
 * regardless of whether libspice-client-glib prebuilts are present.
 * When TABSSH_SPICE_AVAILABLE is defined (see CMakeLists.txt), the
 * heavy lifting is delegated to spice_client_glib.c via the
 * tabssh_spice_impl_* forward declarations at the bottom of this
 * file. When it is not defined, every entry point returns a clean
 * failure so callers see a well-defined "SPICE unavailable" error
 * instead of an UnsatisfiedLinkError storm.
 *
 * Why split rather than #ifdef the real implementation inline: the
 * libspice-glib code pulls in glib headers with GObject
 * introspection macros that expand into a *lot* of code. Keeping
 * them behind a CMake-conditional source file means fresh clones
 * (no prebuilts) never even parse those headers — the file is
 * simply not added to the target sources.
 */

#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <string.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>

#define LOG_TAG "tabssh_spice"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

/*
 * SpiceLoader.nativeIsSpiceAvailable — returns 1 when the .so was
 * compiled with real libspice-client-glib prebuilts linked in, 0 for
 * the scaffold-only build.
 */
JNIEXPORT jint JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceLoader_nativeIsSpiceAvailable(
    JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
#ifdef TABSSH_SPICE_AVAILABLE
    LOGI("SPICE prebuilts detected at build time");
    return 1;
#else
    LOGI("SPICE prebuilts absent — scaffold-only build");
    return 0;
#endif
}

/*
 * Forward declarations for the real-implementation entry points.
 * These live in spice_client_glib.c and are only linked when
 * TABSSH_SPICE_AVAILABLE. They take/return the same values the JNI
 * signatures below expose; the JNI wrappers are thin thunks so the
 * glib code can be JNI-agnostic.
 */
#ifdef TABSSH_SPICE_AVAILABLE
jlong tabssh_spice_impl_create(JNIEnv *env, jstring host, jint port, jint tls_port,
                                jstring password, jbyteArray ca_cert, jstring host_subject,
                                jboolean tls_verify);
jboolean tabssh_spice_impl_start(JNIEnv *env, jlong handle, jobject self);
void tabssh_spice_impl_stop(JNIEnv *env, jlong handle);
void tabssh_spice_impl_destroy(JNIEnv *env, jlong handle);
void tabssh_spice_impl_send_key(JNIEnv *env, jlong handle, jint scancode, jboolean down);
void tabssh_spice_impl_send_pointer_move(JNIEnv *env, jlong handle, jint x, jint y, jint mask);
void tabssh_spice_impl_send_pointer_button(JNIEnv *env, jlong handle, jint button, jint button_state, jboolean down);
void tabssh_spice_impl_send_clipboard(JNIEnv *env, jlong handle, jstring text);
#endif

/*
 * Marker that gets logged the first time any nativeXxx entry point is
 * called on a scaffold-only build, so a bug report attaching logcat
 * makes it immediately obvious the .so has no SPICE support baked in.
 * The Kotlin SpiceLoader gate already blocks the code path — this is
 * a belt-and-braces log line, not a functional check.
 */
#ifndef TABSSH_SPICE_AVAILABLE
static void log_unavailable_once(const char *entrypoint) {
    static int logged = 0;
    if (!logged) {
        LOGW("SPICE entrypoint %s called on scaffold-only build — returning failure",
             entrypoint);
        logged = 1;
    }
}
#endif

/*
 * Native crash handler — SpiceLoader.nativeInstallCrashHandler.
 *
 * The SPICE native stack (spice_client_glib.c + the static
 * glib/spice-gtk/openssl link) is the only in-process native code
 * this app loads: Tor and Mosh run as separate subprocesses via
 * ProcessBuilder and cannot bring this process down. A fault inside
 * that stack (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE) currently kills
 * the process with nothing written anywhere Kotlin's Logger can
 * reach, so the Debug Log stays silent even though the app crashed.
 *
 * This installs a minimal signal handler that writes a one-line
 * marker (signal number + faulting address) to a file path supplied
 * by Kotlin, then re-raises the original signal so Android's normal
 * crash handling (debuggerd tombstone, process death) proceeds
 * exactly as before. TabSSHApplication checks for this marker file on
 * the next launch and folds it into the Debug Log.
 *
 * Every function the handler itself calls (write, close, sigaction,
 * raise) is async-signal-safe per POSIX — no libc allocation, no
 * printf-family calls, no JNI, no glib.
 */
static int g_crash_fd = -1;
static struct sigaction g_prev_sigsegv;
static struct sigaction g_prev_sigabrt;
static struct sigaction g_prev_sigbus;
static struct sigaction g_prev_sigill;
static struct sigaction g_prev_sigfpe;

static void tabssh_append_hex(char *buf, int *len, int cap, unsigned long value) {
    char hex[2 * sizeof(unsigned long)];
    int hi = 0;
    if (value == 0) {
        hex[hi++] = '0';
    } else {
        while (value > 0 && hi < (int) sizeof(hex)) {
            unsigned long nibble = value & 0xFUL;
            hex[hi++] = (char) (nibble < 10 ? ('0' + nibble) : ('a' + (nibble - 10)));
            value >>= 4;
        }
    }
    for (int i = hi - 1; i >= 0 && *len < cap - 1; i--) {
        buf[(*len)++] = hex[i];
    }
}

static void tabssh_append_dec(char *buf, int *len, int cap, unsigned long value) {
    char dec[3 * sizeof(unsigned long)];
    int di = 0;
    if (value == 0) {
        dec[di++] = '0';
    } else {
        while (value > 0 && di < (int) sizeof(dec)) {
            dec[di++] = (char) ('0' + (value % 10));
            value /= 10;
        }
    }
    for (int i = di - 1; i >= 0 && *len < cap - 1; i--) {
        buf[(*len)++] = dec[i];
    }
}

static void tabssh_crash_handler(int sig, siginfo_t *info, void *ucontext) {
    (void) ucontext;
    if (g_crash_fd >= 0) {
        char buf[256];
        int len = 0;
        static const char header[] = "=== NATIVE CRASH === signal=";
        static const char addrtag[] = " fault_addr=0x";

        for (unsigned i = 0; i < sizeof(header) - 1 && len < (int) sizeof(buf) - 1; i++) {
            buf[len++] = header[i];
        }
        tabssh_append_dec(buf, &len, (int) sizeof(buf), (unsigned long) sig);
        for (unsigned i = 0; i < sizeof(addrtag) - 1 && len < (int) sizeof(buf) - 1; i++) {
            buf[len++] = addrtag[i];
        }
        unsigned long addr = (info != NULL) ? (unsigned long) info->si_addr : 0UL;
        tabssh_append_hex(buf, &len, (int) sizeof(buf), addr);
        if (len < (int) sizeof(buf) - 1) {
            buf[len++] = '\n';
        }
        ssize_t written = write(g_crash_fd, buf, (size_t) len);
        (void) written;
        close(g_crash_fd);
        g_crash_fd = -1;
    }

    struct sigaction *prev = NULL;
    switch (sig) {
        case SIGSEGV: prev = &g_prev_sigsegv; break;
        case SIGABRT: prev = &g_prev_sigabrt; break;
        case SIGBUS:  prev = &g_prev_sigbus;  break;
        case SIGILL:  prev = &g_prev_sigill;  break;
        case SIGFPE:  prev = &g_prev_sigfpe;  break;
        default: break;
    }
    if (prev != NULL) {
        sigaction(sig, prev, NULL);
    }
    raise(sig);
}

JNIEXPORT jint JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceLoader_nativeInstallCrashHandler(
    JNIEnv *env, jclass clazz, jstring path) {
    (void) clazz;
    if (path == NULL) {
        return 0;
    }
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) {
        return 0;
    }

    int fd = open(cpath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
    if (fd < 0) {
        LOGE("Failed to open native crash marker file");
        return 0;
    }
    g_crash_fd = fd;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = tabssh_crash_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, &g_prev_sigsegv);
    sigaction(SIGABRT, &sa, &g_prev_sigabrt);
    sigaction(SIGBUS,  &sa, &g_prev_sigbus);
    sigaction(SIGILL,  &sa, &g_prev_sigill);
    sigaction(SIGFPE,  &sa, &g_prev_sigfpe);

    LOGI("Native crash handler installed");
    return 1;
}

JNIEXPORT jlong JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeCreateSession(
    JNIEnv *env, jobject thiz,
    jstring host, jint port, jint tls_port,
    jstring password, jbyteArray ca_cert, jstring host_subject,
    jboolean tls_verify) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    return tabssh_spice_impl_create(env, host, port, tls_port, password,
                                    ca_cert, host_subject, tls_verify);
#else
    (void) env; (void) host; (void) port; (void) tls_port;
    (void) password; (void) ca_cert; (void) host_subject; (void) tls_verify;
    log_unavailable_once("nativeCreateSession");
    return 0;
#endif
}

JNIEXPORT jboolean JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeStartSession(
    JNIEnv *env, jobject thiz, jlong handle, jobject self) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    return tabssh_spice_impl_start(env, handle, self);
#else
    (void) env; (void) handle; (void) self;
    log_unavailable_once("nativeStartSession");
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeStopSession(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    tabssh_spice_impl_stop(env, handle);
#else
    (void) env; (void) handle;
    log_unavailable_once("nativeStopSession");
#endif
}

JNIEXPORT void JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeDestroySession(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    tabssh_spice_impl_destroy(env, handle);
#else
    (void) env; (void) handle;
    log_unavailable_once("nativeDestroySession");
#endif
}

JNIEXPORT void JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeSendKeyEvent(
    JNIEnv *env, jobject thiz, jlong handle, jint scancode, jboolean down) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    tabssh_spice_impl_send_key(env, handle, scancode, down);
#else
    (void) env; (void) handle; (void) scancode; (void) down;
    log_unavailable_once("nativeSendKeyEvent");
#endif
}

JNIEXPORT void JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeSendPointerMove(
    JNIEnv *env, jobject thiz, jlong handle, jint x, jint y, jint mask) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    tabssh_spice_impl_send_pointer_move(env, handle, x, y, mask);
#else
    (void) env; (void) handle; (void) x; (void) y; (void) mask;
    log_unavailable_once("nativeSendPointerMove");
#endif
}

JNIEXPORT void JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeSendPointerButton(
    JNIEnv *env, jobject thiz, jlong handle, jint button, jint button_state, jboolean down) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    tabssh_spice_impl_send_pointer_button(env, handle, button, button_state, down);
#else
    (void) env; (void) handle; (void) button; (void) button_state; (void) down;
    log_unavailable_once("nativeSendPointerButton");
#endif
}

JNIEXPORT void JNICALL
Java_io_github_tabssh_hypervisor_spice_SpiceClient_nativeSendClipboardText(
    JNIEnv *env, jobject thiz, jlong handle, jstring text) {
    (void) thiz;
#ifdef TABSSH_SPICE_AVAILABLE
    tabssh_spice_impl_send_clipboard(env, handle, text);
#else
    (void) env; (void) handle; (void) text;
    log_unavailable_once("nativeSendClipboardText");
#endif
}
