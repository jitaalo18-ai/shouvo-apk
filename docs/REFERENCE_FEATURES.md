# Reference apps - feature notes for Nock Nock

Research notes (2026-11-13) from public articles and store listings, not from hands-on use.
Purpose: a shared memory for whoever continues this project, so features are chosen deliberately.
Nothing here is a commitment; the owner decides what gets built, one version at a time.

Legend: [HAVE] in Nock Nock 0.2 - [PART] partly - [NO] not built.

## Instagram "Edits: Video Editor" (primary UI reference)

App structure: five tabs - Ideas, Trending Audio, Projects, Recording (camera), Insights.

| Area | What Edits offers | Nock Nock |
|---|---|---|
| Projects | drafts and finished videos in one place | [HAVE] |
| Timeline | frame-accurate, stacked layers: video, audio, text, captions, overlays | [PART] video, overlay, sound, music |
| Basic cuts | trim, split, rearrange clips | [PART] split only; no trim handles on main clips, no reorder, no delete |
| Cut silences | removes pauses from voiceovers | [HAVE] AutoCut |
| Overlays | video/image overlay; clip can be converted to an overlay; colour, opacity, filters on overlays | [PART] move/resize/timing only |
| Tracks longer than video | audio, voiceover and overlay may extend past the main track | [NO] clipped to main track |
| Keyframes | position, rotation, size over time; also on text, overlays, cutouts | [NO] |
| Text | 30+ text effects, font categories, animated text, title presets | [NO] button only |
| Captions | automatic from speech, styled, per-word styling, caption animations | [NO] |
| Audio | voiceover recording, 15+ voice effects, Voice Enhance (background-noise removal), saved sounds, 400+ sound effects, import audio from files, beat markers | [PART] own Sound FX and Music libraries, import from files |
| Background | green screen, AI cutout (subject isolation) | [NO] |
| Effects/colour | 25+ video effects with intensity and timing, filters, colour correction, transitions, "apply to all" | [NO] |
| Speed/time | speed control, freeze frame, reverse | [NO] |
| Camera | up to 10 min recording, resolution/frame rate, green screen, touch-up, timer, teleprompter | [NO] and outside the current spec |
| AI | image animation, Restyle (clips up to 10 s), suggested sound effects | [NO] needs servers |
| Planning | Ideas notes, storyboards, templates | [NO] excluded by spec |
| Links | tappable links inside videos | [NO] button only |
| Insights | Reels analytics inside the app | [NO] excluded by spec |
| Export | no watermark, direct publish to Instagram/Facebook | [PART] export to Gallery, no watermark |

Reported weak points of Edits (worth avoiding): audio cut off at clip boundaries, preview freezing
while the timeline keeps moving, captions not saving, crashes and lag, slow exports.

## CapCut desktop (reference for Split, Remove Silence, Overlay)

- Multi-track timeline, keyframes (position, scale, opacity), masking, motion tracking.
- "Track Magnet" and "Auto Snapping" keep clips aligned without gaps.
- Audio: loudness normalisation, sound-effect library, Audio Enhancer (hum/echo/noise),
  Vocal Isolation (AI), auto-cut of silent segments.
- Captions in 16+ languages, keyword highlight, text that tracks a subject.
- Chroma key, one-click background removal, HSL colour correction, filters, transitions,
  optical-flow slow motion.
- Proxy mode and hardware acceleration for performance; AI tools need internet.

## Suggested order for future versions (proposal only)

1. 0.3 - editing basics: undo/redo, delete any clip, trim handles and reorder on the main track,
   clip volume/mute, snapping, tracks allowed to run past the main video.
2. 0.4 - audio: loudness normalisation, music ducking under speech, voice enhance (noise reduction).
3. 0.5 - Text button: manual text overlays with a few styles.
4. 0.6 - automatic captions (needs an on-device speech model; Bangla accuracy must be tested first).
5. Later - speed, freeze frame, transitions, keyframes, filters/colour, green screen.

Not planned (need servers/accounts or excluded by the master spec): cloud, templates, analytics,
social publishing, generative AI.
