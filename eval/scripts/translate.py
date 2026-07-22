"""Record DeepL translations of the verified reference and each provider's baseline
hypothesis into fixtures/translations/. This is a spending script (DeepL bills per
source character); scoring replays the fixtures for free.
"""
import os
import sys
import json
import argparse

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import TranscriptResult
from scoring.translate import translate_cached, DEEPL_KEY_ENV


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="Bypass the char cost cap")
    parser.add_argument("--target", default=config.TRANSLATE_TARGET_LANG, help="DeepL target lang (e.g. DE, ES, FR)")
    args = parser.parse_args()

    if not os.environ.get(DEEPL_KEY_ENV):
        print(f"Error: {DEEPL_KEY_ENV} is not set. Add it to eval/.env (export {DEEPL_KEY_ENV}=...).")
        sys.exit(1)

    os.environ["EVAL_LIVE"] = "1"
    target = args.target
    providers = ["deepgram", "soniox"]

    with open(config.MANIFEST_PATH, "r") as f:
        manifest = json.load(f)

    # Estimate char cost: ref + each provider baseline hyp, per verified clip.
    jobs = []  # (kind, clip_id, text)
    for entry in manifest["entries"]:
        if not entry.get("verified", False):
            continue
        clip_id = entry["id"]
        ref_path = os.path.join(config.GOLDEN_DIR, entry["ref"])
        if not os.path.exists(ref_path):
            continue
        with open(ref_path, "r") as f:
            jobs.append(("ref", clip_id, f.read()))
        for p in providers:
            fix = os.path.join(config.FIXTURES_DIR, p, f"{clip_id}.json")
            if os.path.exists(fix):
                with open(fix, "r") as f:
                    jobs.append((p, clip_id, TranscriptResult(**json.load(f)).text))

    total_chars = sum(len(t) for _, _, t in jobs)
    print(f"Translating {len(jobs)} texts → {target} (~{total_chars} source chars).")
    if total_chars > config.MAX_TRANSLATE_CHARS and not args.force:
        print(f"Error: {total_chars} chars exceeds cap {config.MAX_TRANSLATE_CHARS}. Use --force.")
        sys.exit(1)

    for kind, clip_id, text in jobs:
        out = os.path.join(config.TRANSLATIONS_DIR, kind, f"{clip_id}.{target}.json")
        if os.path.exists(out):
            print(f"Skipping {kind}/{clip_id} ({target}), fixture exists.")
            continue
        print(f"Translating {kind}/{clip_id} ({len(text)} chars)...")
        try:
            translate_cached(kind, clip_id, text, target, config.TRANSLATIONS_DIR)
        except Exception as e:
            print(f"  FAILED {kind}/{clip_id}: {e}")


if __name__ == "__main__":
    main()
