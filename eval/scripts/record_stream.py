"""Record real-time streaming sessions to fixtures/{provider}-stream/.

This is a spending script: it opens live websocket sessions and streams each clip
at real time, so wall-clock cost ≈ audio duration × providers. Replay/scoring never
calls it. Guarded by the same billed-seconds cap as record.py.
"""
import os
import sys
import json
import argparse

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import DeepgramProvider, SonioxProvider


def ensure_stream_dir(provider_name: str):
    path = os.path.join(config.FIXTURES_DIR, f"{provider_name}-stream")
    os.makedirs(path, exist_ok=True)
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="Bypass cost checks")
    parser.add_argument("--max-clips", type=int, default=None,
                        help="Only stream the first N clips (plan requires >=10)")
    args = parser.parse_args()

    os.environ["EVAL_LIVE"] = "1"

    with open(config.MANIFEST_PATH, 'r') as f:
        manifest = json.load(f)

    entries = manifest["entries"]
    if args.max_clips is not None:
        entries = entries[:args.max_clips]

    providers = {"deepgram": DeepgramProvider(), "soniox": SonioxProvider()}

    total_audio_s = sum(e["source"]["dur_s"] for e in entries)
    total_billed = total_audio_s * len(providers)
    print(f"Streaming {len(entries)} clips × {len(providers)} providers.")
    print(f"Billed audio: {total_billed}s (~{total_billed/60:.0f} min wall-clock, real-time paced).")

    if total_billed > config.MAX_BILLED_AUDIO_SECONDS and not args.force:
        print(f"Error: billed {total_billed}s exceeds cap {config.MAX_BILLED_AUDIO_SECONDS}s. Use --force.")
        sys.exit(1)

    for entry in entries:
        clip_id = entry["id"]
        wav_path = os.path.join(config.CLIPS_DIR, f"{clip_id}.wav")
        if not os.path.exists(wav_path):
            print(f"Skipping {clip_id}, audio not found.")
            continue

        for name, provider in providers.items():
            fix_path = os.path.join(ensure_stream_dir(name), f"{clip_id}.json")
            if os.path.exists(fix_path):
                print(f"Skipping {name}-stream for {clip_id}, fixture exists.")
                continue

            print(f"Streaming {name} for {clip_id} (~{entry['source']['dur_s']}s real time)...")
            try:
                result = provider.transcribe_stream(wav_path)
                with open(fix_path, 'w') as f:
                    json.dump(result.model_dump(), f, indent=2)
                print(f"  -> {len(result.events)} events, {len(result.final_words)} final words")
            except Exception as e:
                print(f"  FAILED {name}-stream for {clip_id}: {e}")


if __name__ == "__main__":
    main()
