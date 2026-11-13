# Changes to the vendored whisper.cpp (v1.8.7)

- Only the CPU backend of ggml is included (other backends removed).
- src/whisper.cpp, decoding loop: the output of one 30 s window may use all of the text context
  the prompt leaves free (448 tokens minus the prompt) instead of a fixed half (224). Bangla text
  needs many tokens per word, and the fixed limit cut long sentences off in the middle.
