#!/bin/bash
# Material for the on-device Auto Captions tests (pushed to the emulator by run-emulator-tests.sh):
#  - the two speech models from the "models-v1" release
#  - English speech made with espeak-ng (1.5 s of silence first, to check the caption times)
#  - one real Bangla sentence from Google FLEURS with its transcript
set -u
OUT=${1:-ci-data}
mkdir -p "$OUT"
gh release download models-v1 -p 'ggml-small-q5_1.bin' -p 'ggml-turbo-bn-q5_0.bin' -D "$OUT" --clobber || echo "models not available"
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
ls -la "$OUT"
