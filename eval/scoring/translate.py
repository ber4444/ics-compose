"""DeepL translation with the harness's record/replay posture.

Live calls (record) hit DeepL and cost money; scoring replays from
fixtures/translations/{kind}/{clip_id}.{lang}.json and costs $0.
"""
import os
import json

import httpx

DEEPL_KEY_ENV = "DEEPL_API_KEY"


def _endpoint(key: str) -> str:
    # Free-tier keys end with ":fx" and use a different host.
    return "https://api-free.deepl.com/v2/translate" if key.endswith(":fx") else "https://api.deepl.com/v2/translate"


def deepl_translate(text: str, target_lang: str) -> str:
    key = os.environ.get(DEEPL_KEY_ENV)
    if not key:
        raise ValueError(f"{DEEPL_KEY_ENV} is not set")
    if not text.strip():
        return ""
    resp = httpx.post(
        _endpoint(key),
        headers={"Authorization": f"DeepL-Auth-Key {key}"},
        data={"text": text, "target_lang": target_lang},
        timeout=60.0,
    )
    resp.raise_for_status()
    return resp.json()["translations"][0]["text"]


def translate_cached(kind: str, clip_id: str, text: str, target_lang: str, translations_dir: str) -> str:
    """kind in {'ref', 'deepgram', 'soniox'}. Live-translates + records unless
    EVAL_LIVE=1 is unset, in which case it replays from the fixture."""
    is_live = os.environ.get("EVAL_LIVE", "0") == "1"
    kind_dir = os.path.join(translations_dir, kind)
    fix_path = os.path.join(kind_dir, f"{clip_id}.{target_lang}.json")

    if not is_live:
        if not os.path.exists(fix_path):
            raise FileNotFoundError(
                f"Translation fixture not found: {fix_path}. Run scripts/translate.py first"
            )
        with open(fix_path, "r") as f:
            return json.load(f)["text"]

    os.makedirs(kind_dir, exist_ok=True)
    translated = deepl_translate(text, target_lang)
    with open(fix_path, "w") as f:
        json.dump({"text": translated, "target_lang": target_lang, "source_chars": len(text)}, f, indent=2)
    return translated
