import os
import httpx
from typing import List
from .base import Provider, TranscriptResult, WordInfo

class DeepgramProvider(Provider):
    @property
    def name(self) -> str:
        return "deepgram"

    def _transcribe_batch(self, wav_path: str, domain_terms: List[str], boost: bool) -> TranscriptResult:
        api_key = os.environ.get("DEEPGRAM_API_KEY")
        if not api_key:
            raise ValueError("DEEPGRAM_API_KEY is not set")
            
        url = "https://api.deepgram.com/v1/listen"
        params = {
            "model": "nova-3",
            "smart_format": "true",
            "punctuate": "true"
        }
        
        if boost and domain_terms:
            # nova-3 replaced the old `keywords=term:intensifier` boosting with
            # keyterm prompting: one `keyterm=<phrase>` per term (no numeric boost).
            # httpx encodes a list value as repeated query params.
            params["keyterm"] = domain_terms
            
        with open(wav_path, "rb") as f:
            audio_data = f.read()
            
        headers = {
            "Authorization": f"Token {api_key}",
            "Content-Type": "audio/wav"
        }
        
        response = httpx.post(url, headers=headers, params=params, content=audio_data, timeout=60.0)
        response.raise_for_status()
        raw = response.json()
        
        # Parse Deepgram response
        try:
            channel = raw["results"]["channels"][0]
            alt = channel["alternatives"][0]
            text = alt["transcript"]
            words = []
            for w in alt.get("words", []):
                words.append(WordInfo(
                    word=w["word"],
                    start_s=w["start"],
                    end_s=w["end"],
                    confidence=w["confidence"]
                ))
        except (KeyError, IndexError):
            text = ""
            words = []
            
        return TranscriptResult(
            text=text,
            words=words,
            raw_response=raw
        )
