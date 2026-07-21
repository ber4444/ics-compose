import jiwer
from typing import Dict, Any, Tuple
from .normalize import normalize_text
import os
import json

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

def calculate_and_dump_diff(ref_text: str, hyp_text: str, clip_id: str, provider_name: str, reports_dir: str):
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
        
    return {
        "wer_norm": out.wer,
        "wer_fmt": jiwer.wer(ref_text if ref_text.strip() else "<empty>", hyp_text),
        "cer_norm": jiwer.cer(norm_ref, norm_hyp)
    }
