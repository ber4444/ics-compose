"""Shared websocket streaming harness for the STT providers.

Feeds a 16 kHz mono s16le WAV to a provider websocket in 200 ms chunks paced at
real time (burst-feeding would invalidate the latency numbers) and hands every
inbound message to a provider-specific callback, timestamped on receipt.
"""
import asyncio
import json
import time
import wave
from typing import Callable, Optional

import websockets

CHUNK_MS = 200
SAMPLE_RATE = 16000


def read_pcm_chunks(wav_path: str, chunk_ms: int = CHUNK_MS):
    """Return (list[bytes] of ~chunk_ms PCM, audio_duration_s)."""
    with wave.open(wav_path, "rb") as w:
        assert w.getframerate() == SAMPLE_RATE, f"expected {SAMPLE_RATE} Hz, got {w.getframerate()}"
        assert w.getnchannels() == 1, "expected mono"
        assert w.getsampwidth() == 2, "expected s16le"
        frames_per_chunk = int(SAMPLE_RATE * chunk_ms / 1000)
        duration_s = w.getnframes() / float(SAMPLE_RATE)
        chunks = []
        while True:
            data = w.readframes(frames_per_chunk)
            if not data:
                break
            chunks.append(data)
    return chunks, duration_s


async def _run(url, wav_path, on_message, headers, init_message, close_message, recv_grace_s):
    chunks, duration_s = read_pcm_chunks(wav_path)
    async with websockets.connect(url, additional_headers=headers or {}, max_size=None) as ws:
        if init_message is not None:
            await ws.send(init_message)

        t0 = time.monotonic()

        async def sender():
            for ch in chunks:
                await ws.send(ch)
                await asyncio.sleep(CHUNK_MS / 1000.0)
            if close_message is not None:
                await ws.send(close_message)

        async def receiver():
            try:
                async for raw in ws:
                    t_recv = time.monotonic() - t0
                    try:
                        data = json.loads(raw)
                    except (json.JSONDecodeError, TypeError):
                        continue
                    on_message(data, t_recv)
            except websockets.ConnectionClosed:
                pass

        await asyncio.wait_for(
            asyncio.gather(sender(), receiver()),
            timeout=duration_s * 2 + recv_grace_s + 30,
        )
    return duration_s


def run_ws_stream(
    url: str,
    wav_path: str,
    on_message: Callable[[dict, float], None],
    *,
    headers: Optional[dict] = None,
    init_message: Optional[str] = None,
    close_message: Optional[str] = None,
    recv_grace_s: float = 10.0,
) -> float:
    """Synchronous entry point; returns the audio duration in seconds."""
    return asyncio.run(
        _run(url, wav_path, on_message, headers, init_message, close_message, recv_grace_s)
    )
