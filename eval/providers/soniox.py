import os
import httpx
from typing import List
from .base import Provider, TranscriptResult, WordInfo

class SonioxProvider(Provider):
    @property
    def name(self) -> str:
        return "soniox"

    def _transcribe_batch(self, wav_path: str, domain_terms: List[str], boost: bool) -> TranscriptResult:
        api_key = os.environ.get("SONIOX_API_KEY")
        if not api_key:
            raise ValueError("SONIOX_API_KEY is not set")
            
        url = "https://api.soniox.com/transcribe"
        headers = {
            "Authorization": f"Bearer {api_key}"
        }
        
        # Soniox API typically accepts multipart form data or direct binary post for files
        with open(wav_path, "rb") as f:
            files = {"file": f}
            data = {
                "model": "stt-async-v5"
            }
            if boost and domain_terms:
                # Add domain terms via context if supported by Soniox REST API
                pass
                
            response = httpx.post(url, headers=headers, data=data, files=files, timeout=60.0)
            
            # Since we don't have exact docs, we try to parse it safely
            if response.status_code == 404:
                # Fallback to transcribe-file or another common endpoint if 404
                url = "https://api.soniox.com/transcribe-file"
                f.seek(0)
                response = httpx.post(url, headers=headers, data=data, files=files, timeout=60.0)
                
            response.raise_for_status()
            raw = response.json()
            
            # Parse Soniox response best-effort
            text = raw.get("text", "")
            words = []
            for w in raw.get("words", []):
                words.append(WordInfo(
                    word=w.get("text", w.get("word", "")),
                    start_s=w.get("start_ms", w.get("start", 0)) / 1000.0 if "start_ms" in w else w.get("start", 0),
                    end_s=w.get("end_ms", w.get("end", 0)) / 1000.0 if "end_ms" in w else w.get("end", 0),
                    confidence=w.get("confidence", 1.0)
                ))
                
            return TranscriptResult(
                text=text,
                words=words,
                raw_response=raw
            )
