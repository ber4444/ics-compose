import os
import sys
import json
import argparse
from pathlib import Path
from collections import Counter
import difflib

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import TranscriptResult

def align_and_vote(texts: list) -> tuple:
    """
    Given 2 text strings, splits into words and performs a basic majority vote.
    Returns (draft_text, disagreement_spans).
    """
    if len(texts) < 2:
        return texts[0] if texts else "", []
        
    if not texts[0] and texts[1]:
        return texts[1], []
    elif texts[0] and not texts[1]:
        return texts[0], []
        
    w1 = texts[0].split()
    w2 = texts[1].split()
    
    matcher = difflib.SequenceMatcher(None, w1, w2)
    consensus = []
    disagreements = []
    
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == 'equal':
            consensus.extend(w1[i1:i2])
        else:
            span_id = len(disagreements)
            flag_text = f"[[FLAG_{span_id}]]"
            consensus.append(flag_text)
            disagreements.append({
                "span": span_id,
                "variants": [
                    " ".join(w1[i1:i2]),
                    " ".join(w2[j1:j2])
                ]
            })
            
    return " ".join(consensus), disagreements

def main():
    parser = argparse.ArgumentParser()
    args = parser.parse_args()
    
    with open(config.MANIFEST_PATH, 'r') as f:
        manifest = json.load(f)
        
    providers = ["deepgram", "soniox"]
    
    os.makedirs(config.REPORTS_DIR, exist_ok=True)
    os.makedirs(config.REFS_DIR, exist_ok=True)
    report_path = os.path.join(config.REPORTS_DIR, "disagreement_report.md")
    
    report_md = ["# Disagreement Report\n"]
    
    for entry in manifest["entries"]:
        clip_id = entry["id"]
        
        texts = []
        for p in providers:
            fix_path = os.path.join(config.FIXTURES_DIR, p, f"{clip_id}.json")
            if os.path.exists(fix_path):
                with open(fix_path, 'r') as f:
                    data = json.load(f)
                    texts.append(data.get("text", ""))
            else:
                texts.append("")
                
        draft_text, disagreements = align_and_vote(texts)
        
        ref_path = os.path.join(config.REFS_DIR, f"{clip_id}.txt")
        # Only overwrite if not verified
        if not entry.get("verified", False):
            with open(ref_path, "w") as f:
                f.write(draft_text)
                
        if disagreements:
            report_md.append(f"## Clip: {clip_id}")
            report_md.append(f"Draft written to `{ref_path}`.\n")
            report_md.append("| Span | Deepgram | Soniox |")
            report_md.append("|---|---|---|")
            for d in disagreements:
                v = d["variants"]
                v_padded = v + [""] * (2 - len(v))
                report_md.append(f"| FLAG_{d['span']} | {v_padded[0]} | {v_padded[1]} |")
            report_md.append("\n")
            
    with open(report_path, "w") as f:
        f.write("\n".join(report_md))
        
    print(f"Bootstrapping complete. Wrote {report_path}")
    print("Please review the flagged spans in the refs, fix them, and set verified: true in manifest.json.")

if __name__ == "__main__":
    main()
