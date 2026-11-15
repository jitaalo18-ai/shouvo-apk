#!/bin/bash
set -euo pipefail
mkdir -p ci-data
# Valid H.264 access units, CBR filler included, >1 GiB. Small frame dimensions keep
# fixture generation cheap; this stresses encoded-byte buffering, not 4K decoding.
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=640x360:rate=30' \
  -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 66 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -b:v 136M -minrate 136M -maxrate 136M -bufsize 136M \
  -x264-params 'nal-hrd=cbr:filler=1' -c:a aac -b:a 128k -movflags +faststart ci-data/large_export.mp4
python3 - <<'PY'
from pathlib import Path
p=Path('ci-data/large_export.mp4')
assert p.stat().st_size >= 1024**3, p.stat().st_size
print('Large export input bytes:', p.stat().st_size)
PY
