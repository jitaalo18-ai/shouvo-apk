"""Accuracy check of the caption models on real Bangla speech (Google FLEURS, bn_in test set).

Usage: eval_captions.py <fleurs_dir> <whisper-cli> <report.md> name=model.bin[:lang] ...
Every model transcribes the same clips; we report CER / WER against FLEURS' transcription and
the time it took on this (CI) machine.
"""
import csv, os, re, subprocess, sys, time, unicodedata

fleurs, cli, report = sys.argv[1], sys.argv[2], sys.argv[3]
models = [a.split("=", 1) for a in sys.argv[4:]]
N = int(os.environ.get("EVAL_CLIPS", "15"))

rows = []
with open(os.path.join(fleurs, "test.tsv"), encoding="utf8") as f:
    for r in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
        rows.append(r)
clips = []
seen = set()
for r in rows:
    wav = os.path.join(fleurs, "audio", r[1])
    if r[1] in seen or not os.path.exists(wav):
        continue
    seen.add(r[1])
    clips.append((wav, r[3] if len(r) > 3 else r[2]))
    if len(clips) >= N:
        break

def norm(s):
    s = unicodedata.normalize("NFC", s.lower())
    s = re.sub(r"[।॥.,!?;:\"'()\[\]\-–—…]", " ", s)
    return re.sub(r"\s+", " ", s).strip()

def edits(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]

def audio_seconds(wav):
    return (os.path.getsize(wav) - 44) / 32000.0

lines = ["| Model | CER | WER | Time / audio (CI CPU) |", "|---|---|---|---|"]
samples = []
for name, spec in models:
    path, _, lang = spec.partition(":")
    lang = lang or "bn"
    ce = cl = we = wl = 0
    spent = audio = 0.0
    for i, (wav, ref) in enumerate(clips):
        t0 = time.time()
        out = subprocess.run([cli, "-m", path, "-l", lang, "-f", wav, "-nt", "-t", str(os.cpu_count() or 4)],
                             capture_output=True, text=True).stdout
        spent += time.time() - t0
        audio += audio_seconds(wav)
        hyp, r = norm(out), norm(ref)
        ce += edits(hyp.replace(" ", ""), r.replace(" ", "")); cl += len(r.replace(" ", ""))
        we += edits(hyp.split(), r.split()); wl += len(r.split())
        if i < 3:
            samples.append(f"- **{name}** clip {i+1}\n  - heard: {hyp}\n  - truth: {r}")
    lines.append(f"| {name} | {100*ce/max(cl,1):.1f}% | {100*we/max(wl,1):.1f}% | {spent/max(audio,1e-6):.2f}x |")
    print(lines[-1], flush=True)

with open(report, "w", encoding="utf8") as f:
    f.write(f"# Caption model accuracy on Bangla (FLEURS bn_in test, {len(clips)} clips)\n\n")
    f.write("\n".join(lines) + "\n\nLower is better. Time is on the GitHub Actions CPU, not a phone.\n\n## Samples\n\n")
    f.write("\n".join(samples) + "\n")
