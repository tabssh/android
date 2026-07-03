/*
 * TabSSH native stub.
 *
 * Task #12 lands the NDK harness; the real SPICE client JNI bindings
 * land in tasks #13/#14. This translation unit exports a single JNI
 * symbol that reports whether the shared object was built against a
 * real prebuilt SPICE stack or the empty scaffold. The Kotlin loader
 * (io.github.tabssh.hypervisor.spice.SpiceLoader) calls this before
 * attempting any other SPICE JNI call; when it returns 0 the SPICE
 * code paths are disabled at runtime.
 *
 * Keeping the JNI surface at exactly one symbol during the scaffold
 * phase avoids UnsatisfiedLinkError storms on devices that load the
 * library before the real bindings ship.
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "tabssh_native"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)

/*
 * Returns 1 if this .so was linked against a real libspice-client-glib
 * static build (TABSSH_SPICE_AVAILABLE=1 at compile time), 0 otherwise.
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
