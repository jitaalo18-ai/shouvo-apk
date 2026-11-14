#!/bin/bash
# Material for the on-device Auto Captions tests (pushed to the emulator by run-emulator-tests.sh):
#  - the two speech models from the "models-v1" release
#  - English speech made with espeak-ng (1.5 s of silence first, to check the caption times)
#  - one real Bangla sentence from Google FLEURS with its transcript
set -u
OUT=${1:-ci-data}
mkdir -p "$OUT"
gh release download models-v1 -p 'ggml-small-q5_1.bin' -p 'ggml-turbo-bn-q5_0.bin' -p 'ggml-silero-v6.2.0.bin' -D "$OUT" --clobber || echo "models not available"
sudo apt-get update -qq > /dev/null 2>&1; sudo apt-get install -y -qq espeak-ng ffmpeg > /dev/null 2>&1 || true
TEXT="Hello everyone. Today I will show you how to make a video. First open the editor. Then add your clips and press export."
if command -v espeak-ng > /dev/null; then
  espeak-ng -v en-us -s 150 -w "$OUT/speech.wav" "$TEXT"
  ffmpeg -loglevel error -y -f lavfi -i testsrc=size=640x360:rate=30 -i "$OUT/speech.wav" \
    -filter_complex "[1:a]adelay=1500|1500,apad=pad_dur=1[a]" -map 0:v -map "[a]" -shortest \
    -c:v libx264 -pix_fmt yuv420p -c:a aac -ar 44100 -ac 2 "$OUT/speech_en.mp4"
  echo "$TEXT" > "$OUT/speech_en.txt"
  rm -f "$OUT/speech.wav"
fi
curl -sSLf -o /tmp/test.tsv https://huggingface.co/datasets/google/fleurs/resolve/main/data/bn_in/test.tsv && \
curl -sSLf -o /tmp/fleurs.tar.gz https://huggingface.co/datasets/google/fleurs/resolve/main/data/bn_in/audio/test.tar.gz && \
mkdir -p /tmp/fleurs && tar -xzf /tmp/fleurs.tar.gz -C /tmp/fleurs --strip-components=1 && \
python3 - "$OUT" <<'PY'
import csv, os, shutil, sys
out = sys.argv[1]
for r in csv.reader(open('/tmp/test.tsv', encoding='utf8'), delimiter='\t', quoting=csv.QUOTE_NONE):
    wav = os.path.join('/tmp/fleurs', r[1])
    if os.path.exists(wav) and 6 <= (os.path.getsize(wav) - 44) / 32000 <= 15:
        shutil.copy(wav, os.path.join(out, 'bn_clip.wav'))
        open(os.path.join(out, 'bn_clip.txt'), 'w', encoding='utf8').write(r[3])
        print('bangla clip', r[1], r[3])
        break
PY
# about 40 s of Bangla: several different FLEURS sentences one after the other (checks that long
# speech is captioned completely, not cut off)
python3 - "$OUT" <<'PY'
import csv, os, subprocess, sys
out = sys.argv[1]
seen, parts, texts = set(), [], []
for r in csv.reader(open('/tmp/test.tsv', encoding='utf8'), delimiter='\t', quoting=csv.QUOTE_NONE):
    wav = os.path.join('/tmp/fleurs', r[1])
    if r[3] in seen or not os.path.exists(wav):
        continue
    seen.add(r[3])
    parts.append(wav); texts.append(r[3])
    if sum(os.path.getsize(p) / 64000 for p in parts) > 38:     # float32 mono 16 kHz
        break
# FLEURS files are 32-bit float: join them with ffmpeg, half a second of silence after each
cmd = ['ffmpeg', '-loglevel', 'error', '-y']
for p in parts:
    cmd += ['-i', p]
graph = ''.join(f'[{i}:a]apad=pad_dur=0.5[a{i}];' for i in range(len(parts)))
graph += ''.join(f'[a{i}]' for i in range(len(parts))) + f'concat=n={len(parts)}:v=0:a=1[out]'
cmd += ['-filter_complex', graph, '-map', '[out]', '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', os.path.join(out, 'bn_long.wav')]
subprocess.run(cmd, check=True)
open(os.path.join(out, 'bn_long.txt'), 'w', encoding='utf8').write(' '.join(texts))
print('bangla long:', len(parts), 'sentences,', os.path.getsize(os.path.join(out, 'bn_long.wav')) // 32000, 's')
PY
# Isolate voice: real Bangla speech (first 14 s of bn_long) with traffic noise and laptop-like
# music mixed in, as videos at 48 kHz and 44.1 kHz, plus the clean speech to compare with
if [ -f "$OUT/bn_long.wav" ]; then
  python3 -c "import numpy" 2> /dev/null || pip3 install --quiet numpy
  ffmpeg -loglevel error -y -i "$OUT/bn_long.wav" -t 14 -ar 48000 -ac 1 -c:a pcm_s16le /tmp/iso_speech48.wav
  python3 - "$OUT" <<'PY'
import numpy as np, wave, sys, os
out = sys.argv[1]
sr = 48000
w = wave.open('/tmp/iso_speech48.wav'); speech = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
n = len(speech); t = np.arange(n) / sr
rng = np.random.RandomState(42)
# traffic: brown rumble, passing-car swell, two horn beeps
brown = np.cumsum(rng.randn(n)); brown -= np.convolve(brown, np.ones(4801) / 4801, 'same'); brown /= np.abs(brown).max()
swell = 0.6 + 0.4 * np.sin(2 * np.pi * t / 7.0)
horn = np.zeros(n)
for s0 in (3.2, 9.1):
    m = (t >= s0) & (t < s0 + 0.45)
    horn[m] = 0.5 * (np.sin(2 * np.pi * 440 * t[m]) + np.sin(2 * np.pi * 554 * t[m]))
traffic = brown * swell + horn
# laptop: chords that change every second and a beat
music = np.zeros(n)
for k, f in enumerate([220.0, 277.18, 329.63, 440.0]):
    music += 0.25 * np.sin(2 * np.pi * f * t * (1 + 0.25 * ((t // 1) % 3)))
music += ((t * 2) % 1 < 0.04) * rng.randn(n) * 0.8
def rms(x): return np.sqrt(np.mean(x ** 2))
def at_snr(noise, snr): return noise * (rms(speech) / rms(noise)) / (10 ** (snr / 20))
mix = speech + at_snr(traffic, 3) + at_snr(music, 6)
peak = np.abs(mix).max()
if peak > 0.95:
    mix *= 0.95 / peak; speech_out = speech * 0.95 / peak
else:
    speech_out = speech
def save(name, x):
    x = np.clip(x, -1, 1)
    w = wave.open(name, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes((x * 32767).astype(np.int16).tobytes()); w.close()
save(os.path.join(out, 'iso_clean.wav'), speech_out)
save('/tmp/iso_mix48.wav', mix)
print('isolate voice material: %.1f s, speech %.1f dBFS' % (n / sr, 20 * np.log10(rms(speech_out))))
PY
  for r in 48000 44100; do
    ffmpeg -loglevel error -y -f lavfi -i testsrc=size=640x360:rate=30 -i /tmp/iso_mix48.wav -map 0:v -map 1:a -shortest \
      -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 192k -ar $r -ac 2 "$OUT/iso_noisy_$r.mp4"
  done
fi
ls -la "$OUT"
