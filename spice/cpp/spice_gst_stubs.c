/*
 * spice_gst_stubs.c — GStreamer backend stubs for the Android SPICE build.
 *
 * spice-gtk 0.42 compiles channel-display-gst.c and spice-gstaudio.c
 * unconditionally and mandates the full gstreamer-1.0 stack. We do NOT
 * cross-build GStreamer for Android: statically linking the entire
 * GStreamer + plugin tree would defeat the single self-contained
 * libtabssh_native.so model (the whole point of the mosh-parity delivery).
 *
 * spice/build-android.sh instead patches spice-gtk to drop those two
 * source files and the gstreamer dependency, leaving two symbols
 * unresolved in libspice-client-glib-2.0.a:
 *
 *   create_gstreamer_decoder()  — the video-stream decoder factory
 *   gstvideo_has_codec()        — codec-capability probe (drives which
 *                                 SPICE_DISPLAY_CAP_CODEC_* caps we advertise)
 *   spice_gstaudio_new()        — the audio backend constructor
 *
 * Both are resolved here at the final link into libtabssh_native.so.
 * Returning NULL degrades gracefully, exactly as spice-gtk already
 * handles a decoder/backend it cannot create:
 *
 *   - Video: MJPEG streams still decode via the builtin libjpeg decoder
 *     (channel-display-mjpeg.c, enabled with -Dbuiltin-mjpeg=true). A
 *     non-MJPEG stream (VP8/VP9/H264) hits the default branch in
 *     channel-display.c, gets a NULL decoder, logs "could not create a
 *     video decoder", and drops that stream — the surface still receives
 *     ordinary draw/image commands.
 *   - Audio: spice_gstaudio_new() returning NULL means no SPICE audio
 *     playback/record, which is explicitly out of scope (PLAN.AI.md).
 *
 * Signatures are intentionally opaque (void*): C has no name mangling, so
 * the linker matches these by symbol name alone. The real callers compile
 * against spice-gtk's own prototypes; only the definitions live here.
 */

void *create_gstreamer_decoder(int codec_type, void *stream)
{
    (void)codec_type;
    (void)stream;
    return 0;
}

/* gboolean (== int); FALSE for every codec, so channel-display.c advertises
 * only the builtin MJPEG capability and never a GStreamer-backed codec. */
int gstvideo_has_codec(int codec_type)
{
    (void)codec_type;
    return 0;
}

void *spice_gstaudio_new(void *session, void *context, const char *name)
{
    (void)session;
    (void)context;
    (void)name;
    return 0;
}
