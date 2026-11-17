# Speech evaluation corpus

Real speech with hand-drawn labels of where the speech is. The transmit gate's evaluation
(`app/src/test/cpp/EvalCorpus.h`, `VoiceActivityEvalTest.cpp`) scores against the labels, so when
the gate and a label disagree, the gate is wrong.

## Source and license

Excerpts of the **LibriSpeech ASR corpus** (`dev-other` subset), which derives from public-domain
LibriVox audiobook recordings.

> V. Panayotov, G. Chen, D. Povey, and S. Khudanpur,
> "LibriSpeech: an ASR corpus based on public domain audio books,"
> *ICASSP 2015*. https://www.openslr.org/12/

**License:** Creative Commons Attribution 4.0 International (CC BY 4.0),
https://creativecommons.org/licenses/by/4.0/. It permits redistribution and derivative works,
including in this public repository, provided attribution is given and changes are indicated.
Both are done on this page.

## Changes from the original

Each clip is a derivative of its LibriSpeech source:

- **Resampled** from 16 kHz to 48 kHz mono 16-bit PCM, the rate the transmit pipeline runs at.
- `dev-other-116-288045-0000-trim` is also **trimmed** in time, hence the `-trim` suffix.
- No spectral or level processing. Loudness and dynamics are as recorded.

File names keep LibriSpeech's `speaker-chapter-utterance` identifiers, so each clip can be traced
to its origin: `dev-other-700-122866-0000` is the `dev-other` split, speaker 700, chapter 122866,
utterance 0000.

## Clips

| file | length | speech level | speech regions | what it exercises |
|---|---|---|---|---|
| `dev-other-116-288045-0000-trim` | 3.39 s | −22 dBFS | 2, with one 0.20 s pause | onset, and a short pause inside an utterance |
| `dev-other-700-122866-0000` | 4.89 s | −31.5 dBFS | 1, continuous | sustained coverage of a quiet talker (a future loudness target for gain control) |
| `dev-other-1255-138279-0002` | 8.45 s | −24.5 dBFS | 4, with pauses of 0.23, 0.66 and 0.51 s | the hangover across several real pauses |

## Label files

Each `.wav` has two label files in Audacity's format: one speech region per line,
`startSeconds<TAB>endSeconds<TAB>speech`, on the clip's own timeline.

- **`.manual.txt`** is the human-verified ground truth, and the only one the evaluation reads.
- **`.silero.txt`** is a draft produced by Silero VAD, kept for reference.
