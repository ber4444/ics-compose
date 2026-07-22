import os
import json
import httpx
from typing import List
from .base import Provider, TranscriptResult, WordInfo, StreamResult, StreamEvent, FinalWord
from .streaming import run_ws_stream

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

    def _transcribe_stream_live(self, wav_path: str) -> StreamResult:
        api_key = os.environ.get("DEEPGRAM_API_KEY")
        if not api_key:
            raise ValueError("DEEPGRAM_API_KEY is not set")

        params = (
            "model=nova-3&encoding=linear16&sample_rate=16000&channels=1"
            "&interim_results=true&punctuate=true&smart_format=true"
        )
        url = f"wss://api.deepgram.com/v1/listen?{params}"

        # Deepgram emits per-segment Results: interim (is_final=false) updates refine
        # the current segment; a final locks it and the next segment starts fresh.
        state = {"committed": "", "events": [], "final_words": []}

        def on_message(data, t):
            if data.get("type") != "Results":
                return
            try:
                alt = data["channel"]["alternatives"][0]
            except (KeyError, IndexError):
                return
            transcript = alt.get("transcript", "")
            is_final = bool(data.get("is_final", False))
            if is_final and transcript:
                state["committed"] = (state["committed"] + " " + transcript).strip()
                for w in alt.get("words", []):
                    state["final_words"].append(
                        FinalWord(word=w["word"], start_s=float(w["start"]), final_t_recv=t)
                    )
                display = state["committed"]
            else:
                display = (state["committed"] + " " + transcript).strip()
            state["events"].append(
                StreamEvent(t_recv=t, display_text=display, is_final_update=is_final)
            )

        duration_s = run_ws_stream(
            url, wav_path, on_message,
            headers={"Authorization": f"Token {api_key}"},
            close_message=json.dumps({"type": "CloseStream"}),
        )

        return StreamResult(
            final_text=state["committed"].strip(),
            events=state["events"],
            final_words=state["final_words"],
            audio_duration_s=duration_s,
            model="nova-3",
        )
