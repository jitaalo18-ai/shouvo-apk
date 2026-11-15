# Dynamic captions (V0.22)

The screenshot is a visual reference; no proprietary Caption AI source was copied.
Word timing is obtained through the existing vendored whisper.cpp v1.8.7 (MIT),
using whisper_full_get_token_data and merging UTF-8 subword tokens into words.
Recognition and token alignment can be imperfect; this is not forced alignment.

Bundled unmodified fonts downloaded from google/fonts on 2026-11-15:
- Lilita One, Juan Montoreano: https://github.com/google/fonts/tree/main/ofl/lilitaone
- Noto Sans Bengali: https://github.com/google/fonts/tree/main/ofl/notosansbengali
Both SIL Open Font License 1.1; complete copyright and licenses are in assets/fonts/*-OFL.txt.
English uses Lilita One, Bengali uses Noto Sans Bengali weight 800.

Dynamic style uses two lines, white text, yellow current word and a translucent dark rectangle.
Preview and exported video share the same renderer. Timing is stored in database schema 7,
with additive migration preserving existing projects. Move, split, trim and compounds preserve
source-relative word timing. Existing captions must be regenerated to obtain word timing.
Correcting words preserves their timing slots. Adding/removing words clears the old timing; regenerate to refresh alignment.
All older static styles remain available. Bitmap cache is capped at 8 MiB.

Captions share one reusable full-frame texture, avoiding Media3 1.5.1's 15-overlay sampler limit. Extra visual overlays are composed in ordered batches of at most 15.
