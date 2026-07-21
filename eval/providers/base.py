from typing import List, Optional, Any, Dict
from pydantic import BaseModel
from abc import ABC, abstractmethod

class WordInfo(BaseModel):
    word: str
    start_s: float
    end_s: float
    confidence: float

class TranscriptResult(BaseModel):
    text: str
    words: List[WordInfo]
    raw_response: Dict[str, Any]

class Provider(ABC):
    @property
    @abstractmethod
    def name(self) -> str:
        pass
        
    def transcribe(self, wav_path: str, domain_terms: List[str], boost: bool) -> TranscriptResult:
        import os
        import json
        from pathlib import Path
        
        is_live = os.environ.get("EVAL_LIVE", "0") == "1"
        clip_id = Path(wav_path).stem
        fix_dir = os.path.join(os.path.dirname(__file__), "..", "fixtures", f"{self.name}-boost" if boost else self.name)
        fix_path = os.path.join(fix_dir, f"{clip_id}.json")
        
        if not is_live:
            if not os.path.exists(fix_path):
                raise FileNotFoundError(f"Fixture not found: {fix_path}. Run record.py first or use --live")
            with open(fix_path, 'r') as f:
                return TranscriptResult(**json.load(f))
                
        result = self._transcribe_batch(wav_path, domain_terms, boost)
        return result

    @abstractmethod
    def _transcribe_batch(self, wav_path: str, domain_terms: List[str], boost: bool) -> TranscriptResult:
        """Transcribe a clip synchronously via API."""
        pass
