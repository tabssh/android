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
 *  - A dedicated GMainContext + GMainLoop drives libspice signal
 *    dispatch on a worker GThread. All libspice calls happen on that
 *    thread; JNI callers post through g_main_context_invoke when they
 *    need to touch session state (e.g. sending input events after
 *    connect).
 *  - JNI callbacks into Kotlin cache a JavaVM* at first attach; each
 *    worker-thread callback attaches the current thread, resolves
 *    the cached jmethodIDs on the SpiceClient class, and invokes the
 *    matching internal `onNative*` method. The Kotlin object is kept
 *    alive by a global reference held in the session struct — it is
 *    released only from tabssh_spice_impl_destroy.
 *  - Display channel primary-create hands out a shared ARGB
 *    framebuffer to Kotlin as a fresh IntArray; invalidate signals
 *    become onNativeFramebufferUpdate calls with the dirty rect and
 *    a copy of the pixels for the affected region.
 *
 * IMPORTANT: This file is written against the documented public
 * spice-client-glib 0.42 API — see
 * https://gitlab.freedesktop.org/spice/spice-gtk — but it has NOT been
 * compile-verified inside TabSSH because the libs/spice prebuilts are
 * not yet in tree. First real verification will happen when task #14
 * or #15 exercises this against a live SPICE endpoint. Any deviations
 * from the documented API will surface as build failures at that
 * point; the JNI symbol table in spice_client.c is stable regardless.
 */

#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#include <glib.h>
#include <glib-object.h>
#include <spice-client.h>

#define LOG_TAG "tabssh_spice"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

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

typedef struct tabssh_spice_session {
    SpiceSession *session;
    SpiceMainChannel *main_channel;
    SpiceDisplayChannel *display_channel;
    SpiceInputsChannel *inputs_channel;

    GMainContext *main_ctx;
    GMainLoop *main_loop;
    GThread *loop_thread;

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
    const uint32_t *fb_pixels;
} tabssh_spice_session;

/*
 * Attach the current worker thread to the JVM and return a JNIEnv*.
 * Every callback path uses this. The corresponding detach happens
 * when the worker thread exits (loop_thread_main returns) — we never
 * detach mid-callback because the worker only exists for the session
 * lifetime.
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
    if (g_client_cls != NULL) return TRUE;
    jclass local = (*env)->FindClass(env, "io/github/tabssh/hypervisor/spice/SpiceClient");
    if (!local) {
        LOGE("FindClass(SpiceClient) failed");
        return FALSE;
    }
    g_client_cls = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (!g_client_cls) {
        LOGE("NewGlobalRef(SpiceClient) failed");
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
        return FALSE;
    }
    return TRUE;
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
    (void) channel; (void) format; (void) stride; (void) shmid;
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    JNIEnv *env = attach_current_thread();
    if (!env || !ensure_client_class(env) || !sess->client_ref) return;

    if (sess->fb_array) {
        (*env)->DeleteGlobalRef(env, sess->fb_array);
        sess->fb_array = NULL;
    }
    jintArray local_fb = (*env)->NewIntArray(env, width * height);
    if (!local_fb) {
        emit_error(sess, "NewIntArray(primary surface) failed");
        return;
    }
    sess->fb_array = (jintArray)(*env)->NewGlobalRef(env, local_fb);
    (*env)->DeleteLocalRef(env, local_fb);
    sess->fb_width = width;
    sess->fb_height = height;
    sess->fb_pixels = (const uint32_t *)imgdata;
    if (imgdata) {
        (*env)->SetIntArrayRegion(env, sess->fb_array, 0, width * height,
                                  (const jint *)imgdata);
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
    JNIEnv *env = attach_current_thread();
    if (!env || !ensure_client_class(env) || !sess->client_ref) return;

    /*
     * SPICE hands the primary surface as a contiguous ARGB buffer with
     * stride = width * 4. Update the affected rows one span at a time
     * to keep the JNI copy tight.
     */
    for (gint row = 0; row < h; row++) {
        gint offset = (y + row) * sess->fb_width + x;
        (*env)->SetIntArrayRegion(env, sess->fb_array, offset, w,
                                   (const jint *)(sess->fb_pixels + offset));
    }
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
    if (sess->main_loop) g_main_loop_quit(sess->main_loop);
}

/*
 * Worker thread body — runs the GMainLoop that drives libspice's
 * signal dispatch. Started from tabssh_spice_impl_start and exits
 * when g_main_loop_quit fires (either from disconnect or from
 * tabssh_spice_impl_stop).
 */
static gpointer loop_thread_main(gpointer user_data) {
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    g_main_context_push_thread_default(sess->main_ctx);
    g_main_loop_run(sess->main_loop);
    g_main_context_pop_thread_default(sess->main_ctx);

    /* Detach so the JVM does not leak per-worker thread refs. */
    if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

jlong tabssh_spice_impl_create(JNIEnv *env, jstring host, jint port, jint tls_port,
                                jstring password, jbyteArray ca_cert, jstring host_subject,
                                jboolean tls_verify) {
    tabssh_spice_session *sess = g_new0(tabssh_spice_session, 1);
    if (!sess) return 0;

    sess->main_ctx = g_main_context_new();
    sess->main_loop = g_main_loop_new(sess->main_ctx, FALSE);
    sess->session = spice_session_new();

    char *c_host = jstring_to_utf8(env, host);
    char *c_pw = jstring_to_utf8(env, password);
    char *c_subj = jstring_to_utf8(env, host_subject);
    char port_str[8] = {0};
    char tls_port_str[8] = {0};
    if (port > 0) g_snprintf(port_str, sizeof(port_str), "%d", port);
    if (tls_port > 0) g_snprintf(tls_port_str, sizeof(tls_port_str), "%d", tls_port);

    g_object_set(sess->session,
                  "host", c_host,
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

    g_free(c_host); g_free(c_pw); g_free(c_subj);
    return (jlong)(uintptr_t)sess;
}

/*
 * Invoked on the worker thread once the main loop is up. Fires
 * spice_session_connect so libspice starts the transport handshake
 * from the correct GMainContext.
 */
static gboolean start_session_on_worker(gpointer user_data) {
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    if (!spice_session_connect(sess->session)) {
        emit_error(sess, "spice_session_connect failed");
        if (sess->main_loop) g_main_loop_quit(sess->main_loop);
    }
    return G_SOURCE_REMOVE;
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

    GError *err = NULL;
    sess->loop_thread = g_thread_try_new("tabssh-spice", loop_thread_main, sess, &err);
    if (!sess->loop_thread) {
        LOGE("g_thread_try_new failed: %s", err ? err->message : "?");
        if (err) g_error_free(err);
        return JNI_FALSE;
    }

    /*
     * Post the connect onto the worker context. The idle source runs
     * as soon as the loop starts iterating, which will be nearly
     * immediately after g_main_loop_run enters its poll.
     */
    g_main_context_invoke(sess->main_ctx, start_session_on_worker, sess);
    return JNI_TRUE;
}

static gboolean stop_session_on_worker(gpointer user_data) {
    tabssh_spice_session *sess = (tabssh_spice_session *)user_data;
    if (sess->session) spice_session_disconnect(sess->session);
    if (sess->main_loop) g_main_loop_quit(sess->main_loop);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_stop(JNIEnv *env, jlong handle) {
    (void) env;
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess) return;
    if (sess->main_ctx) g_main_context_invoke(sess->main_ctx, stop_session_on_worker, sess);
    if (sess->loop_thread) {
        g_thread_join(sess->loop_thread);
        sess->loop_thread = NULL;
    }
}

void tabssh_spice_impl_destroy(JNIEnv *env, jlong handle) {
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess) return;
    if (sess->loop_thread) {
        /* stop() should have been called first; belt-and-braces. */
        tabssh_spice_impl_stop(env, handle);
    }
    if (sess->session) {
        g_object_unref(sess->session);
        sess->session = NULL;
    }
    if (sess->main_loop) {
        g_main_loop_unref(sess->main_loop);
        sess->main_loop = NULL;
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
    g_free(sess);
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
    input_dispatch *d = g_new0(input_dispatch, 1);
    d->sess = sess; d->a = scancode; d->flag = down;
    g_main_context_invoke(sess->main_ctx, dispatch_key, d);
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
    input_dispatch *d = g_new0(input_dispatch, 1);
    d->sess = sess; d->a = x; d->b = y; d->c = mask;
    g_main_context_invoke(sess->main_ctx, dispatch_pointer_move, d);
}

static gboolean dispatch_pointer_button(gpointer user_data) {
    input_dispatch *d = (input_dispatch *)user_data;
    if (d->sess->inputs_channel) {
        if (d->flag) spice_inputs_channel_button_press(d->sess->inputs_channel,
                                                        (gint)d->a, 0);
        else spice_inputs_channel_button_release(d->sess->inputs_channel,
                                                   (gint)d->a, 0);
    }
    g_free(d);
    return G_SOURCE_REMOVE;
}

void tabssh_spice_impl_send_pointer_button(JNIEnv *env, jlong handle, jint mask, jboolean down) {
    (void) env;
    tabssh_spice_session *sess = (tabssh_spice_session *)(uintptr_t)handle;
    if (!sess || !sess->main_ctx) return;
    input_dispatch *d = g_new0(input_dispatch, 1);
    d->sess = sess; d->a = mask; d->flag = down;
    g_main_context_invoke(sess->main_ctx, dispatch_pointer_button, d);
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
    clipboard_dispatch *d = g_new0(clipboard_dispatch, 1);
    d->sess = sess;
    d->text = jstring_to_utf8(env, text);
    g_main_context_invoke(sess->main_ctx, dispatch_clipboard, d);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}
