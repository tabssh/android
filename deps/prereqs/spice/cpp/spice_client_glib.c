/*
 * TabSSH SPICE client — libspice-client-glib backend.
 *
 * This translation unit is only added to the CMake target when
 * SPICE_AVAILABLE=TRUE (see CMakeLists.txt), i.e. when the per-ABI
 * libs/spice prebuilts are present. On a fresh clone with no
 * prebuilts, this file is *not* compiled at all and every SPICE JNI
 * entry point in spice_client.c takes the "unavailable" branch.
 *
 * Design summary:
 *
 *  - Each SpiceClient owns one `tabssh_spice_session` allocated by
 *    tabssh_spice_impl_create. The handle is the pointer, cast to
 *    jlong and stashed in SpiceClient.nativeHandle on the Kotlin
 *    side.
 *  - One process-wide GMainLoop drives the GLib default context on a
 *    dedicated worker. libspice attaches sources to that global context,
 *    so all sessions share the dispatcher and JNI callers queue work there.
 *  - JNI callbacks into Kotlin cache a JavaVM* at first attach; the
 *    dispatcher callback attaches its thread, resolves
 *    the cached jmethodIDs on the SpiceClient class, and invokes the
 *    matching internal `onNative*` method. The Kotlin object is kept
 *    alive by a global reference held in the session struct — it is
 *    released only from tabssh_spice_impl_destroy.
 *  - Display channel primary-create hands out a shared ARGB
 *    framebuffer to Kotlin as a fresh IntArray; invalidate signals
 *    become onNativeFramebufferUpdate calls with the dirty rect and
 *    a copy of the pixels for the affected region.
 *
 * This file is written against the documented public spice-client-glib
 * 0.42 API and has been cross-compiled and linked for Android arm64-v8a
 * against the pinned dependency sources. Live endpoint behavior still
 * needs device/server coverage.
 */

#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#include <glib.h>
#include <glib-object.h>
#include <spice-client.h>
#include <spice/vd_agent.h>

#define LOG_TAG "tabssh_spice"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

#define MAX_SPICE_DIMENSION 16384
#define MAX_SPICE_PIXELS (32LL * 1024LL * 1024LL)

/*
 * Cached JavaVM. Populated at JNI_OnLoad; never cleared. Every
 * worker-thread callback uses this to attach the current thread and
 * obtain a per-callback JNIEnv.
 */
static JavaVM *g_vm = NULL;

/*
 * Cached SpiceClient class + method IDs. Populated lazily on first
 * callback dispatch — we cannot resolve them at JNI_OnLoad because
 * the class may not have been loaded yet. Once populated they are
 * immutable for the process lifetime.
 */
static jclass g_client_cls = NULL;
static jmethodID g_mid_on_connected = NULL;
static jmethodID g_mid_on_framebuffer_update = NULL;
static jmethodID g_mid_on_desktop_resize = NULL;
static jmethodID g_mid_on_cursor_update = NULL;
static jmethodID g_mid_on_agent_connected = NULL;
static jmethodID g_mid_on_clipboard_text = NULL;
static jmethodID g_mid_on_error = NULL;
static jmethodID g_mid_on_disconnected = NULL;
static GMutex g_client_class_mutex;
static GMutex g_dispatcher_mutex;
static GMainContext *g_dispatcher_context = NULL;
static GMainLoop *g_dispatcher_loop = NULL;

static void clear_jni_exception(JNIEnv *env) {
    if (env && (*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

typedef struct tabssh_spice_session {
    SpiceSession *session;
    SpiceMainChannel *main_channel;
    SpiceDisplayChannel *display_channel;
    SpiceInputsChannel *inputs_channel;

    GMainContext *main_ctx;
    GMutex stop_mutex;
    GCond stop_cond;
    gboolean stop_requested;
    gboolean stop_complete;

    /* Global ref to the Kotlin SpiceClient. Released in destroy(). */
    jobject client_ref;

    /*
     * Shared framebuffer handed to Kotlin as a Java IntArray. The
     * underlying pixel buffer is owned by libspice (comes from
     * display-primary-create); we hold a GlobalRef to the IntArray so
     * we can update it via SetIntArrayRegion on each invalidate.
     */
    jintArray fb_array;
    int fb_width;
    int fb_height;
    int fb_stride_bytes;
    int fb_format;
    const uint32_t *fb_pixels;
} tabssh_spice_session;

/*
 * Attach the shared dispatcher thread to the JVM and return a JNIEnv*.
 * The dispatcher lives for the process lifetime, so it stays attached.
 */
static JNIEnv *attach_current_thread(void) {
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    jint rc = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            LOGE("AttachCurrentThread failed");
            return NULL;
        }
    } else if (rc != JNI_OK) {
        LOGE("GetEnv returned %d", rc);
        return NULL;
    }
    return env;
}

/*
 * Populate g_client_cls / g_mid_* on first use. Idempotent. Returns
 * false if any lookup failed — the caller should skip the callback
 * rather than crash.
 */
static gboolean ensure_client_class(JNIEnv *env) {
    g_mutex_lock(&g_client_class_mutex);
    if (g_client_cls != NULL) {
        g_mutex_unlock(&g_client_class_mutex);
        return TRUE;
    }
    jclass local = (*env)->FindClass(env, "io/github/tabssh/hypervisor/spice/SpiceClient");
    if (!local) {
        LOGE("FindClass(SpiceClient) failed");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        g_mutex_unlock(&g_client_class_mutex);
        return FALSE;
    }
    g_client_cls = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!g_client_cls) {
        LOGE("NewGlobalRef(SpiceClient) failed");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        g_mutex_unlock(&g_client_class_mutex);
        return FALSE;
    }
    g_mid_on_connected = (*env)->GetMethodID(env, g_client_cls,
        "onNativeConnected", "(IILjava/lang/String;[I)V");
    g_mid_on_framebuffer_update = (*env)->GetMethodID(env, g_client_cls,
        "onNativeFramebufferUpdate", "(IIII[I)V");
    g_mid_on_desktop_resize = (*env)->GetMethodID(env, g_client_cls,
        "onNativeDesktopResize", "(II[I)V");
    g_mid_on_cursor_update = (*env)->GetMethodID(env, g_client_cls,
        "onNativeCursorUpdate", "(IIII[I[B)V");
    g_mid_on_agent_connected = (*env)->GetMethodID(env, g_client_cls,
        "onNativeAgentConnected", "()V");
    g_mid_on_clipboard_text = (*env)->GetMethodID(env, g_client_cls,
        "onNativeClipboardText", "(Ljava/lang/String;)V");
    g_mid_on_error = (*env)->GetMethodID(env, g_client_cls,
        "onNativeError", "(Ljava/lang/String;)V");
    g_mid_on_disconnected = (*env)->GetMethodID(env, g_client_cls,
        "onNativeDisconnected", "(Ljava/lang/String;)V");
    if (!g_mid_on_connected || !g_mid_on_framebuffer_update ||
        !g_mid_on_desktop_resize || !g_mid_on_cursor_update ||
        !g_mid_on_agent_connected || !g_mid_on_clipboard_text ||
        !g_mid_on_error || !g_mid_on_disconnected) {
        LOGE("GetMethodID for one or more onNative* callbacks failed");
        (*env)->DeleteGlobalRef(env, g_client_cls);
        g_client_cls = NULL;
        g_mid_on_connected = NULL;
        g_mid_on_framebuffer_update = NULL;
        g_mid_on_desktop_resize = NULL;
        g_mid_on_cursor_update = NULL;
        g_mid_on_agent_connected = NULL;
        g_mid_on_clipboard_text = NULL;
        g_mid_on_error = NULL;
        g_mid_on_disconnected = NULL;
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        g_mutex_unlock(&g_client_class_mutex);
        return FALSE;
    }
    g_mutex_unlock(&g_client_class_mutex);
    return TRUE;
}

static gpointer dispatcher_thread_main(gpointer user_data) {
    GMainLoop *loop = (GMainLoop *)user_data;
    LOGI("SPICE shared GLib dispatcher starting");
    g_main_loop_run(loop);
    return NULL;
}

static gboolean ensure_dispatcher(void) {
    g_mutex_lock(&g_dispatcher_mutex);
    if (g_dispatcher_loop) {
        g_mutex_unlock(&g_dispatcher_mutex);
        return TRUE;
    }

    g_dispatcher_context = g_main_context_ref(g_main_context_default());
    g_dispatcher_loop = g_main_loop_new(g_dispatcher_context, FALSE);
    GError *err = NULL;
    GThread *thread = g_thread_try_new("tabssh-spice", dispatcher_thread_main,
                                       g_dispatcher_loop, &err);
    if (!thread) {
        LOGE("Could not start SPICE GLib dispatcher: %s", err ? err->message : "unknown error");
        if (err) g_error_free(err);
        g_main_loop_unref(g_dispatcher_loop);
        g_dispatcher_loop = NULL;
        g_main_context_unref(g_dispatcher_context);
        g_dispatcher_context = NULL;
        g_mutex_unlock(&g_dispatcher_mutex);
        return FALSE;
    }
    g_thread_unref(thread);
    g_mutex_unlock(&g_dispatcher_mutex);
    return TRUE;
}

static void queue_on_dispatcher(GMainContext *context, GSourceFunc callback,
                                gpointer user_data) {
    GSource *source = g_idle_source_new();
    if (!source) return;
    g_source_set_priority(source, G_PRIORITY_DEFAULT);
    g_source_set_callback(source, callback, user_data, NULL);
    g_source_attach(source, context);
    g_source_unref(source);
    g_main_context_wakeup(context);
}

/*
 * Convert a nullable UTF-8 jstring to a heap-allocated C string. The
 * caller must g_free() the result. Returns NULL for a null jstring.
 */
static char *jstring_to_utf8(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *raw = (*env)->GetStringUTFChars(env, s, NULL);
    if (!raw) return NULL;
    char *dup = g_strdup(raw);
    (*env)->ReleaseStringUTFChars(env, s, raw);
    return dup;
}

static void emit_error(tabssh_spice_session *sess, const char *msg) {
    JNIEnv *env = attach_current_thread();
    if (!env || !ensure_client_class(env) || !sess->client_ref) return;
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "unknown error");
    (*env)->CallVoidMethod(env, sess->client_ref, g_mid_on_error, jmsg);
    (*env)->DeleteLocalRef(env, jmsg);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void emit_disconnected(tabssh_spice_session *sess, const char *reason) {
    JNIEnv *env = attach_current_thread();
    if (!env || !ensure_client_class(env) || !sess->client_ref) return;
    jstring jreason = (*env)->NewStringUTF(env, reason ? reason : "session ended");
    (*env)->CallVoidMethod(env, sess->client_ref, g_mid_on_disconnected, jreason);
    (*env)->DeleteLocalRef(env, jreason);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/*
 * display-primary-create — SPICE has given us the primary surface.
 * Allocate a Java IntArray of matching size, cache it, and notify
 * Kotlin so the display view can start rendering.
 */
static void on_display_primary_create(SpiceChannel *channel, gint format, gint width, gint height,
                                       gint stride, gint shmid, gpointer imgdata,
                                       gpointer user_data) {
    (void) channel; (void) shmid;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    JNIEnv *env = attach_current_thread();
    if (!env || !ensure_client_class(env) || !sess->client_ref) return;

    int64_t pixels = (int64_t)width * (int64_t)height;
    int bytes_per_pixel = format == SPICE_SURFACE_FMT_32_xRGB ? 4 :
        format == SPICE_SURFACE_FMT_16_555 ? 2 : 0;
    if (bytes_per_pixel == 0 || width <= 0 || height <= 0 || width > MAX_SPICE_DIMENSION ||
        height > MAX_SPICE_DIMENSION || pixels > MAX_SPICE_PIXELS ||
        stride < width * bytes_per_pixel || stride % bytes_per_pixel != 0) {
        emit_error(sess, "SPICE server announced an invalid framebuffer");
        return;
    }

    if (sess->fb_array) {
        (*env)->DeleteGlobalRef(env, sess->fb_array);
        sess->fb_array = NULL;
    }
    jintArray local_fb = (*env)->NewIntArray(env, (jsize)pixels);
    if (!local_fb) {
        clear_jni_exception(env);
        emit_error(sess, "NewIntArray(primary surface) failed");
        return;
    }
    sess->fb_array = (jintArray)(*env)->NewGlobalRef(env, local_fb);
    (*env)->DeleteLocalRef(env, local_fb);
    if (!sess->fb_array) {
        clear_jni_exception(env);
        emit_error(sess, "Could not allocate SPICE framebuffer reference");
        return;
    }
    sess->fb_width = width;
    sess->fb_height = height;
    sess->fb_stride_bytes = stride;
    sess->fb_format = format;
    sess->fb_pixels = (const uint32_t *)imgdata;
    if (imgdata) {
        for (gint row = 0; row < height; row++) {
            const uint8_t *src = (const uint8_t *)imgdata + (size_t)row * (size_t)stride;
            jint *converted = g_try_new(jint, width);
            if (!converted) {
                emit_error(sess, "Could not allocate SPICE framebuffer row");
                return;
            }
            for (gint col = 0; col < width; col++) {
                if (format == SPICE_SURFACE_FMT_32_xRGB) {
                    converted[col] = ((const uint32_t *)src)[col] | (jint)0xff000000;
                } else {
                    guint16 pixel = ((const guint16 *)src)[col];
                    guint red = (pixel >> 10) & 0x1f;
                    guint green = (pixel >> 5) & 0x1f;
                    guint blue = pixel & 0x1f;
                    converted[col] = (jint)(0xff000000 | ((red << 3 | red >> 2) << 16) |
                        ((green << 3 | green >> 2) << 8) | (blue << 3 | blue >> 2));
                }
            }
            (*env)->SetIntArrayRegion(env, sess->fb_array, row * width, width, converted);
            g_free(converted);
            if ((*env)->ExceptionCheck(env)) {
                clear_jni_exception(env);
                emit_error(sess, "Could not copy SPICE framebuffer");
                return;
            }
        }
    }

    /*
     * SPICE does not expose a display name in the same shape as VNC's
     * DesktopName — most guests do not send one. Pass an empty string
     * so the Kotlin side has a well-defined value.
     */
    jstring jname = (*env)->NewStringUTF(env, "");
    (*env)->CallVoidMethod(env, sess->client_ref, g_mid_on_connected,
                            (jint)width, (jint)height, jname, sess->fb_array);
    (*env)->DeleteLocalRef(env, jname);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/*
 * display-invalidate — a rectangular region of the primary surface
 * has been repainted. Copy the affected row spans into the Kotlin
 * IntArray and notify.
 */
static void on_display_invalidate(SpiceChannel *channel, gint x, gint y, gint w, gint h,
                                   gpointer user_data) {
    (void) channel;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    if (!sess->fb_array || !sess->fb_pixels) return;
    if (x < 0 || y < 0 || w <= 0 || h <= 0 ||
        x > sess->fb_width || y > sess->fb_height ||
        w > sess->fb_width - x || h > sess->fb_height - y) {
        LOGW("Dropping out-of-bounds SPICE update rect %dx%d+%d+%d on %dx%d",
             w, h, x, y, sess->fb_width, sess->fb_height);
        return;
    }
    JNIEnv *env = attach_current_thread();
    if (!env || !ensure_client_class(env) || !sess->client_ref) return;

    /*
     * Honor the server-provided stride and update the affected rows one
     * span at a time to keep the JNI copy tight.
     */
    gint *converted = g_try_new(gint, w);
    if (!converted) return;
    for (gint row = 0; row < h; row++) {
        jint dst_offset = (y + row) * sess->fb_width + x;
        const uint8_t *src = (const uint8_t *)sess->fb_pixels +
            (size_t)(y + row) * (size_t)sess->fb_stride_bytes +
            (size_t)x * (size_t)(sess->fb_format == SPICE_SURFACE_FMT_32_xRGB ? 4 : 2);
        for (gint col = 0; col < w; col++) {
            if (sess->fb_format == SPICE_SURFACE_FMT_32_xRGB) {
                converted[col] = ((const uint32_t *)src)[col] | (jint)0xff000000;
            } else {
                guint16 pixel = ((const guint16 *)src)[col];
                guint red = (pixel >> 10) & 0x1f;
                guint green = (pixel >> 5) & 0x1f;
                guint blue = pixel & 0x1f;
                converted[col] = (jint)(0xff000000 | ((red << 3 | red >> 2) << 16) |
                    ((green << 3 | green >> 2) << 8) | (blue << 3 | blue >> 2));
            }
        }
        (*env)->SetIntArrayRegion(env, sess->fb_array, dst_offset, w, converted);
        if ((*env)->ExceptionCheck(env)) {
            g_free(converted);
            clear_jni_exception(env);
            emit_error(sess, "Could not copy SPICE framebuffer update");
            return;
        }
    }
    g_free(converted);
    (*env)->CallVoidMethod(env, sess->client_ref, g_mid_on_framebuffer_update,
                            (jint)x, (jint)y, (jint)w, (jint)h, sess->fb_array);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void on_display_primary_destroy(SpiceChannel *channel, gpointer user_data) {
    (void) channel;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    sess->fb_pixels = NULL;
}

/*
 * main-channel notify::agent-connected — the SPICE agent is available
 * on the guest side. Clipboard sync, dynamic resize, and shared
 * folders now work; forward the event so the display view can flip
 * the "agent up" indicator.
 */
static void on_main_agent_notify(SpiceMainChannel *main_channel, GParamSpec *pspec,
                                  gpointer user_data) {
    (void) main_channel; (void) pspec;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    JNIEnv *env = attach_current_thread();
    if (!env || !ensure_client_class(env) || !sess->client_ref) return;
    (*env)->CallVoidMethod(env, sess->client_ref, g_mid_on_agent_connected);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

/*
 * channel-event — libspice reports each channel's transport state here:
 * OPENED / SWITCHING / CLOSED plus the ERROR_* family (connect, TLS, link,
 * auth, IO). Without handling it a failed link closes the socket silently
 * and the Kotlin side never learns why — the console just stays black. Map
 * every error to emit_error with an actionable message, a clean main-channel
 * close to emit_disconnected, and log every transition so debug logs show
 * exactly where a handshake stalls.
 */
static void on_channel_event(SpiceChannel *channel, SpiceChannelEvent event,
                              gpointer user_data) {
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    int type = -1;
    g_object_get(channel, "channel-type", &type, NULL);

    switch (event) {
        case SPICE_CHANNEL_OPENED:
            LOGI("SPICE channel type=%d opened", type);
            break;
        case SPICE_CHANNEL_SWITCHING:
            LOGI("SPICE channel type=%d switching host", type);
            break;
        case SPICE_CHANNEL_CLOSED:
            LOGI("SPICE channel type=%d closed", type);
            if (SPICE_IS_MAIN_CHANNEL(channel))
                emit_disconnected(sess, "spice main channel closed");
            break;
        case SPICE_CHANNEL_ERROR_CONNECT:
            LOGE("SPICE channel type=%d connect error", type);
            emit_error(sess, "connection refused or host unreachable");
            break;
        case SPICE_CHANNEL_ERROR_TLS:
            LOGE("SPICE channel type=%d TLS error", type);
            emit_error(sess, "TLS handshake failed");
            break;
        case SPICE_CHANNEL_ERROR_LINK:
            LOGE("SPICE channel type=%d link error", type);
            emit_error(sess, "protocol link failed (version/capability mismatch)");
            break;
        case SPICE_CHANNEL_ERROR_AUTH:
            LOGE("SPICE channel type=%d auth error", type);
            emit_error(sess, "authentication failed (wrong or missing password)");
            break;
        case SPICE_CHANNEL_ERROR_IO:
            LOGE("SPICE channel type=%d I/O error", type);
            emit_error(sess, "connection lost");
            break;
        default:
            break;
    }
}

/*
 * Fired when SpiceSession creates a new channel. We wire up the ones
 * we care about (main, display, inputs) and connect them; libspice
 * emits channel-new on the same GMainContext we drive from the
 * worker thread, so signal handlers run there too.
 */
static void on_channel_new(SpiceSession *session, SpiceChannel *channel, gpointer user_data) {
    (void) session;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    int type = -1;
    g_object_get(channel, "channel-type", &type, NULL);

    g_signal_connect(channel, "channel-event",
                      G_CALLBACK(on_channel_event), sess);

    if (SPICE_IS_MAIN_CHANNEL(channel)) {
        sess->main_channel = SPICE_MAIN_CHANNEL(channel);
        g_signal_connect(channel, "notify::agent-connected",
                          G_CALLBACK(on_main_agent_notify), sess);
        spice_channel_connect(channel);
    } else if (SPICE_IS_DISPLAY_CHANNEL(channel)) {
        sess->display_channel = SPICE_DISPLAY_CHANNEL(channel);
        g_signal_connect(channel, "display-primary-create",
                          G_CALLBACK(on_display_primary_create), sess);
        g_signal_connect(channel, "display-primary-destroy",
                          G_CALLBACK(on_display_primary_destroy), sess);
        g_signal_connect(channel, "display-invalidate",
                          G_CALLBACK(on_display_invalidate), sess);
        spice_channel_connect(channel);
    } else if (SPICE_IS_INPUTS_CHANNEL(channel)) {
        sess->inputs_channel = SPICE_INPUTS_CHANNEL(channel);
        spice_channel_connect(channel);
    } else {
        LOGI("SPICE channel type=%d ignored", type);
    }
}

static void on_channel_destroy(SpiceSession *session, SpiceChannel *channel, gpointer user_data) {
    (void) session; (void) channel;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    if (SPICE_IS_MAIN_CHANNEL(channel)) sess->main_channel = NULL;
    else if (SPICE_IS_DISPLAY_CHANNEL(channel)) sess->display_channel = NULL;
    else if (SPICE_IS_INPUTS_CHANNEL(channel)) sess->inputs_channel = NULL;
}

static void on_session_disconnected(SpiceSession *session, gpointer user_data) {
    (void) session;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    emit_disconnected(sess, "spice session disconnected");
}

static gboolean connect_session_on_dispatcher(gpointer user_data) {
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    g_mutex_lock(&sess->stop_mutex);
    gboolean stopped = sess->stop_requested;
    g_mutex_unlock(&sess->stop_mutex);
    if (stopped) return G_SOURCE_REMOVE;

    LOGI("Starting SPICE session on shared GLib dispatcher");
    if (!spice_session_connect(sess->session)) {
        LOGE("spice_session_connect failed");
        emit_error(sess, "spice_session_connect failed");
    }
    return G_SOURCE_REMOVE;
}

jlong tabssh_spice_impl_create(JNIEnv *env, jstring host, jstring proxy, jint port, jint tls_port,
                                jstring password, jbyteArray ca_cert, jstring host_subject,
                                jboolean tls_verify) {
    tabssh_spice_session *sess = g_try_new0(tabssh_spice_session, 1);
    if (!sess) return 0;

    if (!ensure_dispatcher()) {
        g_free(sess);
        return 0;
    }
    sess->main_ctx = g_main_context_ref(g_main_context_default());
    g_mutex_init(&sess->stop_mutex);
    g_cond_init(&sess->stop_cond);
    sess->session = spice_session_new();
    if (!sess->session) {
        g_cond_clear(&sess->stop_cond);
        g_mutex_clear(&sess->stop_mutex);
        g_main_context_unref(sess->main_ctx);
        g_free(sess);
        return 0;
    }

    char *c_host = jstring_to_utf8(env, host);
    char *c_proxy = jstring_to_utf8(env, proxy);
    char *c_pw = jstring_to_utf8(env, password);
    char *c_subj = jstring_to_utf8(env, host_subject);
    char port_str[8] = {0};
    char tls_port_str[8] = {0};
    if (port > 0) g_snprintf(port_str, sizeof(port_str), "%d", port);
    if (tls_port > 0) g_snprintf(tls_port_str, sizeof(tls_port_str), "%d", tls_port);

    g_object_set(sess->session,
                  "host", c_host,
                  "proxy", c_proxy,
                  "port", port > 0 ? port_str : NULL,
                  "tls-port", tls_port > 0 ? tls_port_str : NULL,
                  "password", c_pw,
                  "cert-subject", c_subj,
                  "verify", tls_verify ? SPICE_SESSION_VERIFY_PUBKEY : 0,
                  NULL);

    if (ca_cert) {
        jsize len = (*env)->GetArrayLength(env, ca_cert);
        jbyte *bytes = (*env)->GetByteArrayElements(env, ca_cert, NULL);
        if (bytes && len > 0) {
            GByteArray *ba = g_byte_array_sized_new((guint)len);
            g_byte_array_append(ba, (const guint8 *)bytes, (guint)len);
            g_object_set(sess->session, "ca", ba, NULL);
            g_byte_array_unref(ba);
        }
        if (bytes) (*env)->ReleaseByteArrayElements(env, ca_cert, bytes, JNI_ABORT);
    }

    g_signal_connect(sess->session, "channel-new",
                      G_CALLBACK(on_channel_new), sess);
    g_signal_connect(sess->session, "channel-destroy",
                      G_CALLBACK(on_channel_destroy), sess);
    g_signal_connect(sess->session, "disconnected",
                      G_CALLBACK(on_session_disconnected), sess);

    g_free(c_host); g_free(c_proxy); g_free(c_pw); g_free(c_subj);
    return (jlong)(uintptr_t)sess;
}

jboolean tabssh_spice_impl_start(JNIEnv *env, jlong handle, jobject self) {
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess) return JNI_FALSE;
    if (!ensure_client_class(env)) return JNI_FALSE;
    if (sess->client_ref) {
        (*env)->DeleteGlobalRef(env, sess->client_ref);
    }
    sess->client_ref = (*env)->NewGlobalRef(env, self);
    if (!sess->client_ref) return JNI_FALSE;

    queue_on_dispatcher(sess->main_ctx, connect_session_on_dispatcher, sess);
    return JNI_TRUE;
}

static gboolean stop_session_on_dispatcher(gpointer user_data) {
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    g_mutex_lock(&sess->stop_mutex);
    gboolean already_stopped = sess->stop_complete;
    g_mutex_unlock(&sess->stop_mutex);
    if (!already_stopped && sess->session) spice_session_disconnect(sess->session);
    g_mutex_lock(&sess->stop_mutex);
    sess->stop_complete = TRUE;
    g_cond_broadcast(&sess->stop_cond);
    g_mutex_unlock(&sess->stop_mutex);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_stop(JNIEnv *env, jlong handle) {
    (void) env;
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess) return;
    g_mutex_lock(&sess->stop_mutex);
    sess->stop_requested = TRUE;
    gboolean already_stopped = sess->stop_complete;
    g_mutex_unlock(&sess->stop_mutex);
    if (already_stopped) return;
    if (g_main_context_is_owner(sess->main_ctx)) {
        stop_session_on_dispatcher(sess);
        return;
    }
    queue_on_dispatcher(sess->main_ctx, stop_session_on_dispatcher, sess);
    g_mutex_lock(&sess->stop_mutex);
    while (!sess->stop_complete) g_cond_wait(&sess->stop_cond, &sess->stop_mutex);
    g_mutex_unlock(&sess->stop_mutex);
}

static void destroy_session_resources(JNIEnv *env, tabssh_spice_session *sess) {
    if (sess->session) {
        g_object_unref(sess->session);
        sess->session = NULL;
    }
    if (sess->main_ctx) {
        g_main_context_unref(sess->main_ctx);
        sess->main_ctx = NULL;
    }
    if (sess->fb_array && env) {
        (*env)->DeleteGlobalRef(env, sess->fb_array);
        sess->fb_array = NULL;
    }
    if (sess->client_ref && env) {
        (*env)->DeleteGlobalRef(env, sess->client_ref);
        sess->client_ref = NULL;
    }
    g_cond_clear(&sess->stop_cond);
    g_mutex_clear(&sess->stop_mutex);
    g_free(sess);
}

typedef struct {
    tabssh_spice_session *sess;
    GMutex mutex;
    GCond cond;
    gboolean done;
} destroy_waiter;

static gboolean destroy_session_on_dispatcher(gpointer user_data) {
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    stop_session_on_dispatcher(sess);
    JNIEnv *env = attach_current_thread();
    destroy_session_resources(env, sess);
    return G_SOURCE_REMOVE;
}

static gboolean destroy_session_and_signal(gpointer user_data) {
    destroy_waiter *waiter = (destroy_waiter *)user_data;
    tabssh_spice_session *sess = waiter->sess;
    stop_session_on_dispatcher(sess);
    JNIEnv *env = attach_current_thread();
    destroy_session_resources(env, sess);
    g_mutex_lock(&waiter->mutex);
    waiter->done = TRUE;
    g_cond_signal(&waiter->cond);
    g_mutex_unlock(&waiter->mutex);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_destroy(JNIEnv *env, jlong handle) {
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess) return;
    if (g_main_context_is_owner(sess->main_ctx)) {
        g_mutex_lock(&sess->stop_mutex);
        sess->stop_requested = TRUE;
        g_mutex_unlock(&sess->stop_mutex);
        queue_on_dispatcher(sess->main_ctx, destroy_session_on_dispatcher, sess);
        return;
    }
    tabssh_spice_impl_stop(env, handle);
    destroy_waiter waiter = { .sess = sess };
    g_mutex_init(&waiter.mutex);
    g_cond_init(&waiter.cond);
    queue_on_dispatcher(sess->main_ctx, destroy_session_and_signal, &waiter);
    g_mutex_lock(&waiter.mutex);
    while (!waiter.done) g_cond_wait(&waiter.cond, &waiter.mutex);
    g_mutex_unlock(&waiter.mutex);
    g_cond_clear(&waiter.cond);
    g_mutex_clear(&waiter.mutex);
}

typedef struct {
    tabssh_spice_session *sess;
    int a;
    int b;
    int c;
    gboolean flag;
} input_dispatch;

static gboolean dispatch_key(gpointer user_data) {
    input_dispatch *d = (input_dispatch *)user_data;
    if (d->sess->inputs_channel) {
        if (d->flag) spice_inputs_channel_key_press(d->sess->inputs_channel, (guint)d->a);
        else spice_inputs_channel_key_release(d->sess->inputs_channel, (guint)d->a);
    }
    g_free(d);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_send_key(JNIEnv *env, jlong handle, jint scancode, jboolean down) {
    (void) env;
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess || !sess->main_ctx) return;
    input_dispatch *d = g_try_new0(input_dispatch, 1);
    if (!d) return;
    d->sess = sess; d->a = scancode; d->flag = down;
    queue_on_dispatcher(sess->main_ctx, dispatch_key, d);
}

static gboolean dispatch_pointer_move(gpointer user_data) {
    input_dispatch *d = (input_dispatch *)user_data;
    if (d->sess->inputs_channel && d->sess->display_channel) {
        spice_inputs_channel_position(d->sess->inputs_channel,
                                       (gint)d->a, (gint)d->b, 0, (gint)d->c);
    }
    g_free(d);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_send_pointer_move(JNIEnv *env, jlong handle, jint x, jint y, jint mask) {
    (void) env;
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess || !sess->main_ctx) return;
    input_dispatch *d = g_try_new0(input_dispatch, 1);
    if (!d) return;
    d->sess = sess; d->a = x; d->b = y; d->c = mask;
    queue_on_dispatcher(sess->main_ctx, dispatch_pointer_move, d);
}

static gboolean dispatch_pointer_button(gpointer user_data) {
    input_dispatch *d = (input_dispatch *)user_data;
    if (d->sess->inputs_channel) {
        if (d->flag) spice_inputs_channel_button_press(d->sess->inputs_channel,
                                                        (gint)d->a, (gint)d->b);
        else spice_inputs_channel_button_release(d->sess->inputs_channel,
                                                   (gint)d->a, (gint)d->b);
    }
    g_free(d);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_send_pointer_button(JNIEnv *env, jlong handle, jint button,
                                             jint button_state, jboolean down) {
    (void) env;
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess || !sess->main_ctx) return;
    input_dispatch *d = g_try_new0(input_dispatch, 1);
    if (!d) return;
    d->sess = sess; d->a = button; d->b = button_state; d->flag = down;
    queue_on_dispatcher(sess->main_ctx, dispatch_pointer_button, d);
}

typedef struct {
    tabssh_spice_session *sess;
    char *text;
} clipboard_dispatch;

static gboolean dispatch_clipboard(gpointer user_data) {
    clipboard_dispatch *d = (clipboard_dispatch *)user_data;
    if (d->sess->main_channel && d->text) {
        gboolean agent_connected = FALSE;
        g_object_get(d->sess->main_channel, "agent-connected", &agent_connected, NULL);
        if (agent_connected) {
            spice_main_channel_clipboard_selection_notify(
                d->sess->main_channel,
                VD_AGENT_CLIPBOARD_SELECTION_CLIPBOARD,
                VD_AGENT_CLIPBOARD_UTF8_TEXT,
                (const guchar *)d->text,
                (guint32)strlen(d->text));
        } else {
            LOGI("clipboard write dropped — agent not connected");
        }
    }
    g_free(d->text);
    g_free(d);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_send_clipboard(JNIEnv *env, jlong handle, jstring text) {
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess || !sess->main_ctx) return;
    clipboard_dispatch *d = g_try_new0(clipboard_dispatch, 1);
    if (!d) return;
    d->sess = sess;
    d->text = jstring_to_utf8(env, text);
    queue_on_dispatcher(sess->main_ctx, dispatch_clipboard, d);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}
