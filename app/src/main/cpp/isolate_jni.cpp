// Isolate voice: JNI bridge to DeepFilterNet3 (libdf.so, built from third_party/deepfilternet).
// One state per processing job; frames of 480 samples, 48 kHz mono.
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

#ifdef NOCK_HAVE_DF
extern "C" {
struct DFState;
DFState *df_create(const char *path, float atten_lim, const char *log_level);
size_t df_get_frame_length(DFState *st);
float df_process_frame(DFState *st, float *input, float *output);
void df_set_atten_lim(DFState *st, float lim_db);
void df_free(DFState *st);
}
#endif

#define TAG "IsolateJni"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_videoeditor_app_media_VoiceIsolationNative_available(JNIEnv *, jclass) {
#ifdef NOCK_HAVE_DF
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_videoeditor_app_media_VoiceIsolationNative_create(JNIEnv *env, jclass, jstring modelPath, jfloat attenLimDb) {
#ifdef NOCK_HAVE_DF
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    DFState *st = df_create(path, attenLimDb, nullptr);
    env->ReleaseStringUTFChars(modelPath, path);
    __android_log_print(ANDROID_LOG_INFO, TAG, "DeepFilterNet3 ready, frame %zu", st ? df_get_frame_length(st) : 0);
    return reinterpret_cast<jlong>(st);
#else
    return 0;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_videoeditor_app_media_VoiceIsolationNative_frameLength(JNIEnv *, jclass, jlong handle) {
#ifdef NOCK_HAVE_DF
    return handle ? (jint) df_get_frame_length(reinterpret_cast<DFState *>(handle)) : 0;
#else
    return 0;
#endif
}

// Processes `frames` whole frames: input[frames * frameLength] -> output (same size).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_videoeditor_app_media_VoiceIsolationNative_process(JNIEnv *env, jclass, jlong handle,
                                                            jfloatArray input, jfloatArray output, jint frames) {
#ifdef NOCK_HAVE_DF
    if (!handle || frames <= 0) return JNI_FALSE;
    auto *st = reinterpret_cast<DFState *>(handle);
    const jint hop = (jint) df_get_frame_length(st);
    const jsize need = (jsize) frames * hop;
    if (env->GetArrayLength(input) < need || env->GetArrayLength(output) < need) return JNI_FALSE;
    std::vector<float> in((size_t) need), out((size_t) need);
    env->GetFloatArrayRegion(input, 0, need, in.data());
    for (jint f = 0; f < frames; f++) {
        df_process_frame(st, in.data() + (size_t) f * hop, out.data() + (size_t) f * hop);
    }
    env->SetFloatArrayRegion(output, 0, need, out.data());
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_videoeditor_app_media_VoiceIsolationNative_free(JNIEnv *, jclass, jlong handle) {
#ifdef NOCK_HAVE_DF
    if (handle) df_free(reinterpret_cast<DFState *>(handle));
#endif
}
