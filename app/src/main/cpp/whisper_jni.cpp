// JNI bridge between the app (com.videoeditor.app.captions.WhisperNative) and whisper.cpp.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "NockWhisper"

static std::atomic<bool> g_cancel{false};

struct ProgressSink {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

static void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

static void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *sink = static_cast<ProgressSink *>(user);
    if (sink && sink->listener) sink->env->CallVoidMethod(sink->listener, sink->method, (jint) progress);
}

static bool abort_cb(void *) { return g_cancel.load(); }

/** Length of the longest prefix of [s] that ends on a complete UTF-8 character. */
static size_t complete_utf8(const std::string &s) {
    size_t n = s.size();
    // look at most 3 bytes back for the start of the last character
    for (size_t back = 1; back <= 3 && back <= n; back++) {
        unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;           // continuation byte
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return back >= need ? n : n - back;
    }
    return n;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_videoeditor_app_captions_WhisperNative_load(JNIEnv *env, jobject, jstring path) {
    whisper_log_set(log_cb, nullptr);
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_videoeditor_app_captions_WhisperNative_free(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

extern "C" JNIEXPORT void JNICALL
Java_com_videoeditor_app_captions_WhisperNative_cancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_videoeditor_app_captions_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

/**
 * Transcribes 16 kHz mono audio. Returns one string per caption piece: "t0Ms\tt1Ms\ttext",
 * or null when it failed / was cancelled.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_videoeditor_app_captions_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ctxPtr, jfloatArray pcm, jstring language, jint threads,
        jint maxLen, jint beamSize, jstring vadModel, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return nullptr;
    g_cancel.store(false);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string langStr(lang);
    env->ReleaseStringUTFChars(language, lang);
    std::string vadPath;
    if (vadModel) {
        const char *v = env->GetStringUTFChars(vadModel, nullptr);
        vadPath = v;
        env->ReleaseStringUTFChars(vadModel, v);
    }

    whisper_full_params p = whisper_full_default_params(
            beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = langStr.c_str();
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;            // fewer repeated / invented lines
    p.single_segment = false;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.token_timestamps = true;
    p.max_len = maxLen;             // short pieces that fit on screen
    p.split_on_word = true;
    p.suppress_blank = true;
    p.suppress_nst = true;
    if (beamSize > 1) p.beam_search.beam_size = beamSize;
    if (!vadPath.empty()) {
        p.vad = true;
        p.vad_model_path = vadPath.c_str();
    }

    ProgressSink sink{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        sink.method = env->GetMethodID(cls, "onProgress", "(I)V");
        p.progress_callback = progress_cb;
        p.progress_callback_user_data = &sink;
    }
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    int rc = whisper_full(ctx, p, samples.data(), (int) samples.size());
    if (rc != 0 || g_cancel.load()) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full returned %d (cancel=%d)", rc, (int) g_cancel.load());
        return nullptr;
    }

    int segments = whisper_full_n_segments(ctx);
    // A Bangla letter is 3 bytes and a piece may end in the middle of one: carry the incomplete
    // bytes over to the next piece so no letter is lost or broken.
    std::string carry;
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(segments, stringClass, nullptr);
    for (int i = 0; i < segments; i++) {
        long long t0 = whisper_full_get_segment_t0(ctx, i) * 10;   // centiseconds -> ms
        long long t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        std::string text = carry + whisper_full_get_segment_text(ctx, i);
        size_t ok = (i == segments - 1) ? text.size() : complete_utf8(text);
        carry = text.substr(ok);
        text.resize(ok);
        std::string line = std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + text;
        // the text is UTF-8; NewStringUTF wants modified UTF-8, so go through a byte array
        jbyteArray bytes = env->NewByteArray((jsize) line.size());
        env->SetByteArrayRegion(bytes, 0, (jsize) line.size(), reinterpret_cast<const jbyte *>(line.data()));
        jclass charsetCls = env->FindClass("java/nio/charset/StandardCharsets");
        jfieldID utf8Field = env->GetStaticFieldID(charsetCls, "UTF_8", "Ljava/nio/charset/Charset;");
        jobject utf8 = env->GetStaticObjectField(charsetCls, utf8Field);
        jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/nio/charset/Charset;)V");
        jobject str = env->NewObject(stringClass, ctor, bytes, utf8);
        env->SetObjectArrayElement(out, i, str);
        env->DeleteLocalRef(str);
        env->DeleteLocalRef(utf8);
        env->DeleteLocalRef(charsetCls);
        env->DeleteLocalRef(bytes);
    }
    return out;
}
