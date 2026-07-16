# VAD/AGC evaluation corpus

Real human speech with human-verified speech-region labels, used by the transmit-gate
evaluation (`app/src/test/java/me/danielstiner/dumble/mumble/voice/eval/`). Ground truth is
the hand-drawn `.manual.txt` labels — not an energy heuristic — so when the gate disagrees
with a label, the gate is what's wrong.

## Source & attribution

These clips are excerpts of the **LibriSpeech ASR corpus** (`dev-other` subset), derived from
public-domain LibriVox audiobook recordings.

> V. Panayotov, G. Chen, D. Povey, and S. Khudanpur,
> "LibriSpeech: an ASR corpus based on public domain audio books,"
> *ICASSP 2015*. https://www.openslr.org/12/

**License:** Creative Commons Attribution 4.0 International (CC BY 4.0) —
https://creativecommons.org/licenses/by/4.0/

LibriSpeech is licensed CC BY 4.0, which permits redistribution and derivative works
(including this project's public repository) provided attribution is given and changes are
indicated. Both are done here.

## Modifications from the original

Each clip is a **derivative** of its LibriSpeech source:

- **Resampled** from 16 kHz to **48 kHz mono PCM16** (the transmit pipeline runs at 48 kHz).
- Clip `dev-other-116-288045-0000-trim` is additionally **trimmed** in time (the `-trim` suffix).
- No spectral/level processing was applied; loudness and dynamics are as recorded.

Filenames preserve the LibriSpeech `speaker-chapter-utterance` identifiers so each clip is
traceable to its origin (e.g. `dev-other-700-122866-0000` = `dev-other` split, speaker 700,
chapter 122866, utterance 0000).

## Clips & their role in the eval

| file | dur | speech loudness | regions | exercises |
|------|-----|-----------------|---------|-----------|
| `dev-other-116-288045-0000-trim` | 3.39s | −22 dBFS | 2 (one 0.20s pause) | onset + a short intra-utterance pause |
| `dev-other-700-122866-0000`      | 4.89s | −31.5 dBFS | 1 continuous | sustained coverage; quiet talker (future AGC loudness target) |
| `dev-other-1255-138279-0002`     | 8.45s | −24.5 dBFS | 4 (pauses 0.23/0.66/0.51s) | hangover across several real pauses |

## Label files

Two Audacity-format label files accompany each `.wav` (tab-separated
`startSeconds<TAB>endSeconds<TAB>speech`, one speech region per line, times on the clip's own
timeline):

- **`.manual.txt`** — human-verified ground truth. Authoritative.
- **`.silero.txt`** — a Silero VAD draft, kept only as a machine reference/benchmark. Not used
  for scoring.

The evaluation reads `.manual.txt` only.
