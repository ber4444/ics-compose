import os
import json
import sys
from pathlib import Path
from typing import Dict, List, Any
from collections import defaultdict

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import TranscriptResult
from scoring.metrics import calculate_and_dump_diff

def generate_scorecard(allow_unverified: bool = False):
    with open(config.MANIFEST_PATH, 'r') as f:
        manifest = json.load(f)
        
    providers = ["deepgram", "assemblyai", "soniox"]
    results_by_provider = defaultdict(list)
    results_by_condition = defaultdict(lambda: defaultdict(list))
    
    for entry in manifest["entries"]:
        clip_id = entry["id"]
        ref_path = os.path.join(config.GOLDEN_DIR, entry["ref"])
        
        if not entry.get("verified", False) and not allow_unverified:
            # Skip unverified
            continue
            
        if not os.path.exists(ref_path):
            print(f"Warning: Ref not found for {clip_id}, skipping.")
            continue
            
        with open(ref_path, "r") as f:
            ref_text = f.read()
            
        for provider in providers:
            fix_path = os.path.join(config.FIXTURES_DIR, provider, f"{clip_id}.json")
            if not os.path.exists(fix_path):
                # Missing fixture
                continue
                
            with open(fix_path, "r") as f:
                hyp = TranscriptResult(**json.load(f))
                
            metrics = calculate_and_dump_diff(ref_text, hyp.text, clip_id, provider, config.REPORTS_DIR)
            
            res = {
                "clip_id": clip_id,
                "metrics": metrics
            }
            results_by_provider[provider].append(res)
            
            for cond in entry.get("conditions", []):
                results_by_condition[cond][provider].append(res)
                
    # Generate Markdown
    md = []
    title = "STT Provider Scorecard"
    if allow_unverified:
        title += " (DRAFT - Unverified Refs)"
    md.append(f"# {title}\n")
    
    def avg_metric(res_list, key):
        if not res_list:
            return 0.0
        return sum(r["metrics"][key] for r in res_list) / len(res_list)
        
    md.append("## Overall Metrics\n")
    md.append("| Provider | WER (Norm) | WER (Fmt) | CER (Norm) |")
    md.append("|---|---|---|---|")
    
    for p in providers:
        runs = results_by_provider.get(p, [])
        if not runs:
            md.append(f"| {p} | n/a (not run) | n/a (not run) | n/a (not run) |")
            continue
            
        w_norm = avg_metric(runs, "wer_norm")
        w_fmt = avg_metric(runs, "wer_fmt")
        c_norm = avg_metric(runs, "cer_norm")
        md.append(f"| {p} | {w_norm:.3f} | {w_fmt:.3f} | {c_norm:.3f} |")
        
    md.append("\n## Metrics by Condition\n")
    for cond, p_dict in results_by_condition.items():
        md.append(f"### {cond}\n")
        md.append("| Provider | WER (Norm) |")
        md.append("|---|---|")
        for p in providers:
            runs = p_dict.get(p, [])
            if not runs:
                md.append(f"| {p} | n/a (not run) |")
            else:
                md.append(f"| {p} | {avg_metric(runs, 'wer_norm'):.3f} |")
        md.append("\n")
        
    md.append("## Worst 5 Clips by Provider (WER Norm)\n")
    for p in providers:
        runs = results_by_provider.get(p, [])
        if not runs:
            continue
        
        runs = sorted(runs, key=lambda x: x["metrics"]["wer_norm"], reverse=True)[:5]
        md.append(f"### {p}\n")
        md.append("| Clip ID | WER (Norm) | Diff |")
        md.append("|---|---|---|")
        for r in runs:
            cid = r["clip_id"]
            w = r["metrics"]["wer_norm"]
            diff_link = f"diffs/{p}_{cid}.txt"
            md.append(f"| {cid} | {w:.3f} | [View Diff]({diff_link}) |")
        md.append("\n")
        
    os.makedirs(config.REPORTS_DIR, exist_ok=True)
    report_path = os.path.join(config.REPORTS_DIR, "scorecard.md")
    with open(report_path, "w") as f:
        f.write("\n".join(md))
    print(f"Scorecard generated at {report_path}")

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-unverified", action="store_true")
    args = parser.parse_args()
    generate_scorecard(args.allow_unverified)
