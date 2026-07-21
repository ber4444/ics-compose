import os
import httpx
import time
from typing import List
from .base import Provider, TranscriptResult, WordInfo

class AssemblyAIProvider(Provider):
    @property
    def name(self) -> str:
        return "assemblyai"

    def _transcribe_batch(self, wav_path: str, domain_terms: List[str], boost: bool) -> TranscriptResult:
        api_key = os.environ.get("ASSEMBLYAI_API_KEY")
        if not api_key:
            raise ValueError("ASSEMBLYAI_API_KEY is not set")
            
        headers = {
            "Authorization": api_key
        }
        
        # 1. Upload audio
        with open(wav_path, "rb") as f:
            audio_data = f.read()
            
        upload_resp = httpx.post(
            "https://api.assemblyai.com/v2/upload",
            headers=headers,
            content=audio_data,
            timeout=60.0
        )
        upload_resp.raise_for_status()
        upload_url = upload_resp.json()["upload_url"]
        
        # 2. Request transcription
        json_payload = {
            "audio_url": upload_url,
            "speech_model": "best"
        }
        
        if boost and domain_terms:
            json_payload["word_boost"] = domain_terms
            json_payload["boost_param"] = "high"
            
        tx_resp = httpx.post(
            "https://api.assemblyai.com/v2/transcript",
            headers=headers,
            json=json_payload,
            timeout=30.0
        )
        tx_resp.raise_for_status()
        tx_id = tx_resp.json()["id"]
        
        # 3. Poll for completion
        while True:
            poll_resp = httpx.get(
                f"https://api.assemblyai.com/v2/transcript/{tx_id}",
                headers=headers,
                timeout=30.0
            )
            poll_resp.raise_for_status()
            raw = poll_resp.json()
            
            status = raw["status"]
            if status == "completed":
                break
            elif status == "error":
                raise RuntimeError(f"AssemblyAI transcription failed: {raw.get('error')}")
                
            time.sleep(2)
            
        text = raw.get("text", "")
        words = []
        for w in raw.get("words", []):
            words.append(WordInfo(
                word=w["text"],
                start_s=w["start"] / 1000.0,
                end_s=w["end"] / 1000.0,
                confidence=w["confidence"]
            ))
            
        return TranscriptResult(
            text=text,
            words=words,
            raw_response=raw
        )
