import jiwer
from typing import Dict, Any, Tuple
from .normalize import normalize_text
import os
import json
import re

def calculate_metrics(ref_text: str, hyp_text: str) -> Dict[str, float]:
    """
    Calculates WER and CER for normalized and formatted text.
    """
    norm_ref = normalize_text(ref_text)
    norm_hyp = normalize_text(hyp_text)
    
    # Avoid div by zero
    if not norm_ref.strip():
        norm_ref = "<empty>"
        
    if not ref_text.strip():
        ref_text = "<empty>"
        
    metrics = {
        "wer_norm": jiwer.wer(norm_ref, norm_hyp),
        "wer_fmt": jiwer.wer(ref_text, hyp_text),
        "cer_norm": jiwer.cer(norm_ref, norm_hyp)
    }
    return metrics

def calculate_entity_metrics(ref_text: str, hyp_text: str, domain_terms: list) -> Dict[str, float]:
    norm_ref = normalize_text(ref_text).lower()
    norm_hyp = normalize_text(hyp_text).lower()
    
    true_positives = 0
    false_positives = 0
    false_negatives = 0
    
    for term in domain_terms:
        term_norm = normalize_text(term).lower()
        if not term_norm:
            continue
            
        pattern = r'\b' + re.escape(term_norm) + r'\b'
        ref_count = len(re.findall(pattern, norm_ref))
        hyp_count = len(re.findall(pattern, norm_hyp))
        
        matched = min(ref_count, hyp_count)
        true_positives += matched
        false_negatives += max(0, ref_count - hyp_count)
        false_positives += max(0, hyp_count - ref_count)
        
    precision = true_positives / (true_positives + false_positives) if (true_positives + false_positives) > 0 else 1.0
    recall = true_positives / (true_positives + false_negatives) if (true_positives + false_negatives) > 0 else 1.0
    
    if (precision + recall) > 0:
        f1 = 2 * (precision * recall) / (precision + recall)
    else:
        f1 = 0.0
        
    # If there are no terms in ref, we might just return 1.0 for recall and precision
    if true_positives == 0 and false_positives == 0 and false_negatives == 0:
        precision, recall, f1 = 1.0, 1.0, 1.0
        
    return {
        "entity_precision": precision,
        "entity_recall": recall,
        "entity_f1": f1,
        "entity_tp": true_positives,
        "entity_fp": false_positives,
        "entity_fn": false_negatives
    }

def calculate_and_dump_diff(ref_text: str, hyp_text: str, clip_id: str, provider_name: str, reports_dir: str, domain_terms: list = None):
    """
    Calculates metrics and dumps the alignment diff to reports/diffs/.
    """
    norm_ref = normalize_text(ref_text)
    norm_hyp = normalize_text(hyp_text)
    
    if not norm_ref.strip():
        norm_ref = "<empty>"
        
    diff_dir = os.path.join(reports_dir, "diffs")
    os.makedirs(diff_dir, exist_ok=True)
    
    out = jiwer.process_words(norm_ref, norm_hyp)
    
    diff_path = os.path.join(diff_dir, f"{provider_name}_{clip_id}.txt")
    with open(diff_path, "w") as f:
        f.write(jiwer.visualize_alignment(out))
        
    metrics = {
        "wer_norm": out.wer,
        "wer_fmt": jiwer.wer(ref_text if ref_text.strip() else "<empty>", hyp_text),
        "cer_norm": jiwer.cer(norm_ref, norm_hyp)
    }
    
    if domain_terms:
        metrics.update(calculate_entity_metrics(ref_text, hyp_text, domain_terms))
        
    return metrics


def _percentile(sorted_vals, pct):
    """Linear-interpolated percentile of an already-sorted list."""
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(sorted_vals) - 1)
    frac = k - lo
    return sorted_vals[lo] * (1 - frac) + sorted_vals[hi] * frac


def _common_prefix_len(a: str, b: str) -> int:
    n = min(len(a), len(b))
    i = 0
    while i < n and a[i] == b[i]:
        i += 1
    return i


def calculate_streaming_metrics(ref_text: str, stream) -> Dict[str, float]:
    """Metrics for a recorded streaming session (a StreamResult).

    - stream_wer: normalized WER of the final transcript (comparable to batch WER).
    - flicker: fraction of already-shown characters that were later rewritten /
      retracted across successive partials (0 = captions only ever append).
    - final_latency_{med,p95}_s: per final word, wall-clock finalization time minus
      the word's *self-reported* spoken start (labeled self-reported; a forced-
      alignment ground truth is Phase 5, intentionally not implemented).
    """
    norm_ref = normalize_text(ref_text) or "<empty>"
    norm_hyp = normalize_text(stream.final_text)
    stream_wer = jiwer.wer(norm_ref, norm_hyp)

    # Flicker: sum of prefix chars retracted between consecutive displayed captions.
    prev = ""
    retracted = 0
    for ev in stream.events:
        cur = ev.display_text
        cp = _common_prefix_len(prev, cur)
        retracted += max(0, len(prev) - cp)
        prev = cur
    total_chars = max(1, len(stream.final_text))
    flicker = retracted / total_chars

    lats = sorted(w.final_t_recv - w.start_s for w in stream.final_words)
    return {
        "stream_wer": stream_wer,
        "flicker": flicker,
        "final_latency_med_s": _percentile(lats, 50),
        "final_latency_p95_s": _percentile(lats, 95),
        "num_final_words": len(stream.final_words),
    }
