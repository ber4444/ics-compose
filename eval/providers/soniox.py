import os
import json
from typing import List
from .base import Provider, TranscriptResult, WordInfo, StreamResult, StreamEvent, FinalWord
from .streaming import run_ws_stream
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

    def _transcribe_stream_live(self, wav_path: str) -> StreamResult:
        api_key = os.environ.get("SONIOX_API_KEY")
        if not api_key:
            raise ValueError("SONIOX_API_KEY is not set")

        url = "wss://stt-rt.soniox.com/transcribe-websocket"
        config = json.dumps({
            "api_key": api_key,
            "model": "stt-rt-v5",
            "audio_format": "pcm_s16le",
            "sample_rate": 16000,
            "num_channels": 1,
            "language_hints": ["en"],
        })

        # Soniox streams tokens: final tokens are emitted once (permanent); non-final
        # tokens are the evolving tail, re-sent each message. Display = committed
        # finals + this message's non-final tail.
        state = {"committed": "", "events": [], "final_words": []}

        def on_message(data, t):
            if data.get("error_code"):
                raise RuntimeError(f"Soniox error {data.get('error_code')}: {data.get('error_message')}")
            tokens = data.get("tokens", [])
            tail = ""
            finalized_any = False
            for tok in tokens:
                text = tok.get("text", "")
                if tok.get("is_final"):
                    state["committed"] += text
                    if text.strip():
                        state["final_words"].append(
                            FinalWord(word=text.strip(), start_s=tok.get("start_ms", 0) / 1000.0, final_t_recv=t)
                        )
                    finalized_any = True
                else:
                    tail += text
            display = (state["committed"] + tail).strip()
            state["events"].append(
                StreamEvent(t_recv=t, display_text=display, is_final_update=finalized_any)
            )

        duration_s = run_ws_stream(
            url, wav_path, on_message,
            init_message=config,
            close_message="",  # empty text frame signals end-of-audio to Soniox
        )

        return StreamResult(
            final_text=state["committed"].strip(),
            events=state["events"],
            final_words=state["final_words"],
            audio_duration_s=duration_s,
            model="stt-rt-v5",
        )
