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

# --- Streaming (Phase 4) ---

class StreamEvent(BaseModel):
    """One interim/final update from the provider, timestamped on receipt."""
    t_recv: float          # wall-clock seconds since the first audio chunk was sent
    display_text: str      # full caption shown at this instant (committed finals + interim tail)
    is_final_update: bool  # whether this message finalized any text

class FinalWord(BaseModel):
    """A word once the provider marked it final, with its self-reported spoken time."""
    word: str
    start_s: float         # provider's own word start time (audio timeline)
    final_t_recv: float    # wall-clock time (since audio start) the word was finalized

class StreamResult(BaseModel):
    final_text: str
    events: List[StreamEvent]
    final_words: List[FinalWord]
    audio_duration_s: float
    model: str = ""

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

    def transcribe_stream(self, wav_path: str) -> StreamResult:
        """Real-time streaming transcription. Replays from
        fixtures/{name}-stream/{clip_id}.json unless EVAL_LIVE=1."""
        import os
        import json
        from pathlib import Path

        is_live = os.environ.get("EVAL_LIVE", "0") == "1"
        clip_id = Path(wav_path).stem
        fix_dir = os.path.join(os.path.dirname(__file__), "..", "fixtures", f"{self.name}-stream")
        fix_path = os.path.join(fix_dir, f"{clip_id}.json")

        if not is_live:
            if not os.path.exists(fix_path):
                raise FileNotFoundError(f"Stream fixture not found: {fix_path}. Run record_stream.py --live first")
            with open(fix_path, 'r') as f:
                return StreamResult(**json.load(f))

        return self._transcribe_stream_live(wav_path)

    @abstractmethod
    def _transcribe_stream_live(self, wav_path: str) -> StreamResult:
        """Open a websocket, feed 200 ms PCM chunks paced at real time, and record
        every interim/final message with its wall-clock receipt time."""
        pass
