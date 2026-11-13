# Notes from two Edits tutorial videos (owner's upload, 2026-11-13)

Source: two screen-recorded tutorials the owner uploaded (12 min 54 s and 18 min 32 s, 480p).
Method: one frame every 7 seconds was extracted and read. The narration was NOT analysed (no speech
recognition here), and anything that happened between two sampled frames may be missing. Small
labels are hard to read at 480p, so a few names may be slightly off. Use as a guide, not as a spec.

Legend: [HAVE] in Nock Nock 0.10 - [PART] partly - [NO] not built.

## 1. App shell

- Five bottom tabs (icons only): Ideas, Inspiration feed, Projects, Camera, Insights.  [NO] by spec
- Projects tab: a row of quick actions on top (Captions, Teleprompter, Cut silences, Cutout ...),
  then a 3-column grid of project thumbnails, each with "age - size" under it (e.g. "43m - 229 MB").
  A white "+" button bottom-right opens a small menu: Camera / Gallery.            [PART] list, no grid/size
- Ideas tab: Stickies (yellow notes), Reels, Audio (All / Licensed music / Original audio),
  Collections.                                                                     [NO] by spec

## 2. Camera

- Left column: Music, Green screen ("Change background"), Timer (3-2-1 countdown), Speed, Teleprompter.
- Bottom: flash, zoom 1x / 2x / 5x, record, flip camera, quality button.
- Quality pop-up: Resolution HD / 2K / 4K, Frame rate 24 / 30 / 60, Color SDR / HDR.
- Records several clips in a row; small thumbnails of the takes with an "x" each, arrow to continue.
  [NO] - no camera in Nock Nock

## 3. Editor layout

- Top: X, project name with drop-down, quality label (4K), Export.               [HAVE] label is "FHD"
- Preview with a small "collapse" chevron at its bottom edge.                     [NO] chevron
- Transport row: play, (keyframe button appears only while a clip is selected), ruler labels around
  the stacked current/total time, undo, redo.                                     [PART] ruler is a separate row
- Tracks, top to bottom: captions (blue chips), text (purple), stickers, overlay (thumbnails),
  sound effects (pink chips), music ("+ Add audio" hint when empty, magenta when filled), main video.
                                                                                  [PART] overlay, sounds, music, main
- Left of every track, shown when scrolled to the start: an eye (hide) and/or speaker (mute) icon. [NO]
- Between two main clips: a small square button. Tapping it opens Transitions.     [NO]
- White "+" at the right end of the main track adds media.                         [HAVE]
- Main tool row (nothing selected): Audio, Text, Voice, Captions, Overlay, Sound FX, Cutout, Sticker.
                                                                                  [PART] Audio/Text/Voice/Links placeholders
- Toast after undo, e.g. "Undo split video".                                       [NO]

## 4. Selected main clip

- Yellow frame in the preview; in the timeline a yellow border with a handle at each end and the
  duration in the top-left corner.                                                [HAVE] in blue
- Tool row (scrolls): back, Split, Volume, Speed, Adjust, Delete, Restyle, Cut silences, Filters,
  Green screen, Effects, Voice FX, Voice enhance, Extract audio, Crop, Mirror, Replace, Slip, Duplicate.
  [PART] Split, Delete, Cut silences (AutoCut) work; the rest are missing or placeholders
- Crop opens a mode with the text "Drag to select the portion of the clip you want in your video".

## 5. Panels (all slide up over the timeline, with a tick to confirm)

- Transitions: None, Dissolve, Zoom, Fade, Reveal, Flicker, Pan down, Zoom out, Blur, Shake,
  Pan right, Flare; checkbox "Apply to all transitions".                           [NO]
- Adjust: Brightness, Contrast, Warmth, Saturation, Fade, Highlights, Shadows, Vignette, Sharpen
  (each -100..100 with the value at the right); "Apply adjustments to all clips"; Reset.   [NO]
- Filters: None, Simple, Boost, Summer, Latte, Vintage, Simple cool, Fade cool, Boost cool, Frosty,
  Indigo, Denim ...; tap a chosen filter again for its strength; "Apply filter to all clips". [NO]
- Effects: None, Zoom, Blur in, B&W fade, Echo, Fade out ...; "Apply effect to all clips".    [NO]
- Voice effects: None, Helium, Low, Intercom, Toy speaker, Microphone.             [NO]
- Restyle (server AI): For You, Styles, Characters, Locations, Lighting.           [NO] needs servers

## 6. Audio, sound effects, text, captions, overlay, stickers

- Audio: sheet with search, tabs For you / Saved / Trending / Original audio / Royalty-free, and an
  Import menu: "Extract from video" / "Import from files". Music clip tools: Split, Volume, Fade,
  Beats, Delete, Replace.          [PART] own Music library, import from files, extract (voice removed)
- Sound FX: sheet with tabs All / Transitions / Action / Comedy / Reactions; rows with name and
  length; tapping a row previews it in a player bar at the bottom; tick adds it. Clip tools: Split,
  Volume, Fade, Replace, Delete, Slip, Duplicate.     [PART] own library with search and preview
- Text: editor with tabs for typing, font (All / Recent / Simple / Bold / Retro / Playful /
  Handwritten), colour (swatches + Hue / Saturation / Brightness), shadow styles (Hard shadow, Soft
  shadow, Block offset, Glow, Halo), animations (Blur, Transparent, Pixel, Sparkle, 3D offset,
  Black offset, Knockout ...), background, alignment. Text clip tools: Split, Edit, Text to speech,
  Duplicate, Delete. A yellow centre line appears while dragging text in the preview.   [NO]
- Voice: a record button replaces the tool row; tick when done.                    [NO]
- Captions: generated automatically into chips; tools Split, Edit, Move, Delete.   [NO]
- Overlay: "Loading media" dialog, then the clip appears small in the preview and as a thumbnail
  track; tools like a main clip (Split, Volume, Speed, Adjust, Delete, Filters, Replace ...). [PART]
- Sticker: GIPHY search grid; tools Split, Replace, Duplicate, Mirror, Delete.     [NO]

## 7. Export

- Tapping the quality label opens: Resolution HD / 2K / 4K, Frame rate 24 / 30 / 60, Color SDR / HDR.
- Export shows a full screen with the percentage and "don't close the app or lock your screen".
- Then "Choose where to share": Instagram, Facebook, Message, Download, More.
  [PART] export to Gallery with a progress dialog; fixed resolution, no share sheet

## Biggest gaps for the owner's kind of editing (suggested, not decided)

1. Volume and Speed per clip (they sit right after Split in the reference tool row).
2. Transition button between clips with a few simple transitions.
3. Mute / hide icons at the start of each track.
4. Export options (resolution, frame rate) and a share step after export.
5. Adjust sliders and filters.
6. Text (the reference text editor is large; start with typing, font size, colour, position).
