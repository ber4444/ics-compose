import os
from typing import List, Literal, Optional
from pydantic import BaseModel

class Source(BaseModel):
    event: str
    rendition: str
    start_s: int
    dur_s: int

class ManifestEntry(BaseModel):
    id: str
    source: Source
    conditions: List[Literal["clean", "music_bed", "multi_speaker", "crowd_noise", "poor_mic"]]
    domain_terms: List[str]
    ref: str
    verified: bool

class Manifest(BaseModel):
    entries: List[ManifestEntry]

# Config / Constants
GOLDEN_DIR = os.path.join(os.path.dirname(__file__), "golden")
MANIFEST_PATH = os.path.join(GOLDEN_DIR, "manifest.json")
CLIPS_DIR = os.path.join(GOLDEN_DIR, "clips")
REFS_DIR = os.path.join(GOLDEN_DIR, "refs")
ALIGN_DIR = os.path.join(GOLDEN_DIR, "align")

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")
REPORTS_DIR = os.path.join(os.path.dirname(__file__), "reports")

# Cost caps (in seconds of billed audio)
MAX_BILLED_AUDIO_SECONDS = 90 * 60

# Translation-fidelity check (Deepgram/Soniox → DeepL). Measures how much ASR error
# survives machine translation: translate(hypothesis) vs translate(verified reference).
TRANSLATE_TARGET_LANG = os.environ.get("EVAL_TRANSLATE_TARGET", "DE")
# DeepL bills per source character; cap chars per invocation (free tier = 500k/month).
MAX_TRANSLATE_CHARS = 300_000
TRANSLATIONS_DIR = os.path.join(FIXTURES_DIR, "translations")
