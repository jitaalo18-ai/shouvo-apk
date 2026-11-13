package com.videoeditor.app.captions

/** Called with 0..100 while whisper.cpp works through the audio. */
fun interface WhisperProgress {
    fun onProgress(percent: Int)
}

/** whisper.cpp (speech recognition, runs on the phone) through JNI. See app/src/main/cpp. */
object WhisperNative {
    val available: Boolean = try {
        System.loadLibrary("nockwhisper")
        true
    } catch (e: Throwable) {
        false
    }

    /** Loads a ggml model file; 0 if it could not be loaded. */
    external fun load(path: String): Long
    external fun free(ctx: Long)

    /** Stops a running [transcribe] as soon as possible (it then returns null). */
    external fun cancel()
    external fun systemInfo(): String

    /**
     * 16 kHz mono samples -> "t0Ms\tt1Ms\ttext" lines. [maxLen] limits a piece's length (bytes of
     * UTF-8, so Bangla needs a larger value), [beamSize] > 1 uses beam search (more accurate).
     */
    external fun transcribe(
        ctx: Long,
        pcm: FloatArray,
        language: String,
        threads: Int,
        maxLen: Int,
        beamSize: Int,
        vadModel: String?,
        listener: WhisperProgress?,
    ): Array<String>?
}
