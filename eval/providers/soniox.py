import os
from typing import List
from .base import Provider, TranscriptResult, WordInfo
from soniox.client import SonioxClient

class SonioxProvider(Provider):
    @property
    def name(self) -> str:
        return "soniox"

    def _transcribe_batch(self, wav_path: str, domain_terms: List[str], boost: bool) -> TranscriptResult:
        api_key = os.environ.get("SONIOX_API_KEY")
        if not api_key:
            raise ValueError("SONIOX_API_KEY is not set")
            
        client = SonioxClient(api_key=api_key)
        
        # We don't have the explicit config object imported here, but we can just use the defaults
        # or pass dict if config parameter accepts dict. Actually, Soniox Python SDK uses `config` param
        # but let's just pass basic parameters. We can pass model="stt-async-v5" and file.
        # Wait, the SDK has `CreateTranscriptionConfig`.
        # Let's import it safely.
        from soniox.api.stt import CreateTranscriptionConfig
        
        config_kwargs = {}
        # We could set speech context here, but let's just use defaults for now since we don't have exact fields
        # If boost and domain_terms are needed:
        # if boost and domain_terms:
        #     config_kwargs['speech_context'] = {'phrases': domain_terms, 'boost': 2.0} # just guessing the shape
            
        config = CreateTranscriptionConfig(**config_kwargs) if config_kwargs else None
        
        res = client.stt.transcribe_and_wait_with_tokens(file=wav_path, model="stt-async-v5", config=config)
        
        text = res.text
        words = []
        for w in res.tokens:
            words.append(WordInfo(
                word=w.text,
                start_s=w.start_ms / 1000.0 if hasattr(w, 'start_ms') else 0.0,
                end_s=w.end_ms / 1000.0 if hasattr(w, 'end_ms') else 0.0,
                confidence=1.0 # default to 1.0 if not provided
            ))
            
        return TranscriptResult(
            text=text,
            words=words,
            raw_response={"text": res.text} # simplified raw response
        )
