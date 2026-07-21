# STT Provider Eval Harness

A reproducible, pytest-driven harness that scores deepgram, soniox, and assemblyai against a domain-specific golden set of event-stream audio.

## Setup

1. Create a Python virtual environment:
   ```bash
   python3 -m venv venv
   source venv/bin/activate
   ```
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Copy `.env.example` to `.env` and fill in your API keys (DO NOT commit this file):
   ```
   DEEPGRAM_API_KEY=your_key
   SONIOX_API_KEY=your_key
   ASSEMBLYAI_API_KEY=your_key
   WOWZA_USER=your_user
   WOWZA_PASS=your_password
   ```

## Honesty Convention
Only report numbers from runs that actually executed. If a step could not run (missing key, missing clip), the scorecard cell reads `n/a (not run)` — never an estimate. Anything aspirational in docs gets one explicit "not wired yet" sentence.

## Cost Notes & Record/Replay
- **Record/replay is the default posture**: scoring and CI run entirely from `fixtures/` and cost $0.
- Only `scripts/record.py` (or `pytest -m live`, disabled in CI) hits provider APIs.
- There is a hard cap of 90 minutes of billed audio per invocation without `--force`. Expected full-matrix record cost is ~$1-2.
