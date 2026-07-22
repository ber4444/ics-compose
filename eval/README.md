# STT Provider Eval Harness

A reproducible, pytest-driven harness that scores **Deepgram** and **Soniox** against
a domain-specific golden set of event-stream audio, producing a per-provider
[`reports/scorecard.md`](reports/scorecard.md): batch accuracy (WER/CER), domain-term
recall (Entity F1), keyterm-boosting impact, and real-time streaming realism (flicker
+ finalization latency).

## Setup

1. Create and activate a virtual environment, then install deps:
   ```bash
   python3 -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```
2. Provide API keys via a gitignored `.env` (sourced by the scripts — use `export` so
   they reach the Python process):
   ```bash
   export DEEPGRAM_API_KEY=your_key
   export SONIOX_API_KEY=your_key
   ```

## Running the pipeline

Everything except the two spending scripts runs offline from `fixtures/` and costs $0.

| Step | Command | Cost | Notes |
|---|---|---|---|
| Fetch clips | `python scripts/fetch_clips.py` | free | pulls the manifest's segments from Wowza, decodes to 16 kHz mono WAV |
| Bootstrap refs | `python scripts/bootstrap_refs.py` | free | 2-of-3 consensus draft refs + disagreement report (human verifies) |
| Record **batch** | `python scripts/record.py` | 💲 live | writes `fixtures/{provider}[-boost]/` |
| Record **streaming** | `python scripts/record_stream.py [--max-clips N]` | 💲 live | real-time paced websocket sessions → `fixtures/{provider}-stream/` |
| Score | `python scoring/scorecard.py` | free | regenerates `reports/scorecard.md` from fixtures |

`./run_eval.sh` chains the free steps + batch record + score, skipping work whose
fixtures already exist.

## Metric definitions

**Batch**
- **WER (Norm)** — word error rate after the Whisper English text normalizer (casing,
  punctuation, number forms); comparable to the HF Open ASR Leaderboard.
- **WER (Fmt)** — word error rate on the raw formatted text (punctuation/casing count).
- **CER (Norm)** — character error rate on normalized text.
- **Entity F1** — precision/recall/F1 over each clip's `domain_terms` (esoteric event
  vocabulary), reported alongside a baseline-vs-**keyterm-boosted** comparison.

**Streaming (live-caption realism)**
- **Streaming WER** — WER of the final streamed transcript (vs the batch WER).
- **Flicker** — fraction of already-shown caption characters that were later rewritten
  or retracted across successive partials. `0` = captions only ever append.
- **Finalization latency (med / p95)** — per word, wall-clock time from spoken to
  finalized. Measured against each provider's **self-reported** word timestamps;
  forced-alignment ground truth (plan Phase 5) is intentionally **not wired yet**.

## Record/replay & cost

- **Record/replay is the default posture.** Scoring, tests, and CI read only from
  `fixtures/` and never spend. Only `record.py` / `record_stream.py` (and `pytest -m
  live`) hit provider APIs.
- Hard cap of **90 minutes** of billed audio per invocation without `--force`
  (`config.MAX_BILLED_AUDIO_SECONDS`). Streaming is real-time paced, so wall-clock ≈
  billed audio ≈ audio duration × providers.
- Never commit `clips/`, `refs/`, `fixtures/`, `align/`, or `.env` (all gitignored).

## Honesty convention

Only report numbers from runs that actually executed. If a step could not run (missing
key, missing clip), the scorecard cell reads `n/a (not run)` — never an estimate.
Anything aspirational in docs gets one explicit "not wired yet" sentence.

## Current headline (real run)

Deepgram is **meaningfully worse than Soniox** on this material — ~45% higher normalized
WER (0.350 vs 0.242) and much lower Entity F1 (0.49 vs 0.77), i.e. it misses the domain
vocabulary far more often. Keyterm boosting narrows the gap on individual clips but does
not close it. See [`reports/scorecard.md`](reports/scorecard.md) for the full breakdown.
