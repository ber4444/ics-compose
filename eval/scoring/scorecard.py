import os
import json
import sys
from pathlib import Path
from typing import Dict, List, Any
from collections import defaultdict

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import TranscriptResult, StreamResult
from scoring.metrics import calculate_and_dump_diff, calculate_streaming_metrics, calculate_translation_fidelity


def _load_translation(kind, clip_id, target_lang):
    """Replay a recorded DeepL translation, or None if not recorded."""
    path = os.path.join(config.TRANSLATIONS_DIR, kind, f"{clip_id}.{target_lang}.json")
    if not os.path.exists(path):
        return None
    with open(path, "r") as f:
        return json.load(f).get("text", "")

def generate_scorecard(allow_unverified: bool = False):
    with open(config.MANIFEST_PATH, 'r') as f:
        manifest = json.load(f)
        
    providers = ["deepgram", "soniox"]
    results_by_provider = defaultdict(list)
    results_by_condition = defaultdict(lambda: defaultdict(list))
    
    # Also track boosted vs baseline runs
    boost_comparison = defaultdict(lambda: defaultdict(dict)) # clip_id -> provider -> {"baseline": metrics, "boosted": metrics}

    # Streaming (Phase 4) results
    stream_by_provider = defaultdict(list)  # provider -> [{clip_id, metrics}]

    # Translation fidelity (ASR -> DeepL) results
    target_lang = config.TRANSLATE_TARGET_LANG
    translation_by_provider = defaultdict(list)  # provider -> [{clip_id, metrics}]
    
    for entry in manifest["entries"]:
        clip_id = entry["id"]
        ref_path = os.path.join(config.GOLDEN_DIR, entry["ref"])
        domain_terms = entry.get("domain_terms", [])
        
        if not entry.get("verified", False) and not allow_unverified:
            # Skip unverified
            continue
            
        if not os.path.exists(ref_path):
            print(f"Warning: Ref not found for {clip_id}, skipping.")
            continue
            
        with open(ref_path, "r") as f:
            ref_text = f.read()

        ref_translation = _load_translation("ref", clip_id, target_lang)

        for provider in providers:
            # Baseline
            fix_path = os.path.join(config.FIXTURES_DIR, provider, f"{clip_id}.json")
            if os.path.exists(fix_path):
                with open(fix_path, "r") as f:
                    hyp = TranscriptResult(**json.load(f))
                    
                metrics = calculate_and_dump_diff(ref_text, hyp.text, clip_id, provider, config.REPORTS_DIR, domain_terms)
                
                res = {
                    "clip_id": clip_id,
                    "metrics": metrics
                }
                results_by_provider[provider].append(res)
                
                for cond in entry.get("conditions", []):
                    results_by_condition[cond][provider].append(res)
                    
                if domain_terms:
                    boost_comparison[clip_id][provider]["baseline"] = metrics
                    
            # Boosted
            if domain_terms:
                boost_fix_path = os.path.join(config.FIXTURES_DIR, f"{provider}-boost", f"{clip_id}.json")
                if os.path.exists(boost_fix_path):
                    with open(boost_fix_path, "r") as f:
                        hyp_boost = TranscriptResult(**json.load(f))
                    
                    boost_metrics = calculate_and_dump_diff(ref_text, hyp_boost.text, clip_id, f"{provider}-boost", config.REPORTS_DIR, domain_terms)
                    boost_comparison[clip_id][provider]["boosted"] = boost_metrics

            # Streaming (real-time)
            stream_fix_path = os.path.join(config.FIXTURES_DIR, f"{provider}-stream", f"{clip_id}.json")
            if os.path.exists(stream_fix_path):
                with open(stream_fix_path, "r") as f:
                    sr = StreamResult(**json.load(f))
                stream_metrics = calculate_streaming_metrics(ref_text, sr)
                stream_by_provider[provider].append({"clip_id": clip_id, "metrics": stream_metrics})

            # Translation fidelity (ASR -> DeepL): translate(hyp) vs translate(ref)
            hyp_translation = _load_translation(provider, clip_id, target_lang)
            if ref_translation is not None and hyp_translation is not None:
                fidelity = calculate_translation_fidelity(ref_translation, hyp_translation)
                translation_by_provider[provider].append({"clip_id": clip_id, "metrics": fidelity})

    # Generate Markdown
    md = []
    title = "STT Provider Scorecard"
    if allow_unverified:
        title += " (DRAFT - Unverified Refs)"
    md.append(f"# {title}\n")
    
    def avg_metric(res_list, key):
        if not res_list:
            return 0.0
        return sum(r["metrics"].get(key, 0.0) for r in res_list) / len(res_list)
        
    md.append("## Overall Metrics\n")
    md.append("| Provider | WER (Norm) | WER (Fmt) | CER (Norm) | Entity F1 |")
    md.append("|---|---|---|---|---|")
    
    for p in providers:
        runs = results_by_provider.get(p, [])
        if not runs:
            md.append(f"| {p} | n/a (not run) | n/a (not run) | n/a (not run) | n/a |")
            continue
            
        w_norm = avg_metric(runs, "wer_norm")
        w_fmt = avg_metric(runs, "wer_fmt")
        c_norm = avg_metric(runs, "cer_norm")
        
        # calculate avg entity F1 across clips that actually had domain terms
        runs_with_entities = [r for r in runs if "entity_f1" in r["metrics"]]
        if runs_with_entities:
            ent_f1 = sum(r["metrics"]["entity_f1"] for r in runs_with_entities) / len(runs_with_entities)
            ent_str = f"{ent_f1:.3f}"
        else:
            ent_str = "n/a"
            
        md.append(f"| {p} | {w_norm:.3f} | {w_fmt:.3f} | {c_norm:.3f} | {ent_str} |")
        
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
        
    md.append("## Keyterm Boosting Impact (Clips with Domain Terms)\n")
    md.append("| Clip ID | Provider | Baseline WER | Boosted WER | Baseline Entity F1 | Boosted Entity F1 |")
    md.append("|---|---|---|---|---|---|")
    
    for clip_id, p_dict in boost_comparison.items():
        for p, metrics_dict in p_dict.items():
            base = metrics_dict.get("baseline", {})
            boost = metrics_dict.get("boosted", {})
            if base and boost:
                b_wer = base.get("wer_norm", 0.0)
                bt_wer = boost.get("wer_norm", 0.0)
                b_f1 = base.get("entity_f1", 0.0)
                bt_f1 = boost.get("entity_f1", 0.0)
                md.append(f"| {clip_id} | {p} | {b_wer:.3f} | {bt_wer:.3f} | {b_f1:.3f} | {bt_f1:.3f} |")
    md.append("\n")
        
    # Streaming section (Phase 4)
    if any(stream_by_provider.get(p) for p in providers):
        md.append("## Streaming (Live-Caption Realism)\n")
        md.append("Real-time paced sessions. Flicker = fraction of already-shown characters later "
                  "rewritten (0 = captions only append). Finalization latency is measured against each "
                  "provider's **self-reported** word times (forced-alignment ground truth is out of scope).\n")
        md.append("| Provider | Clips | Streaming WER | Flicker | Final Latency (med) | Final Latency (p95) |")
        md.append("|---|---|---|---|---|---|")
        for p in providers:
            runs = stream_by_provider.get(p, [])
            if not runs:
                md.append(f"| {p} | 0 | n/a (not run) | n/a | n/a | n/a |")
                continue
            swer = avg_metric(runs, "stream_wer")
            flick = avg_metric(runs, "flicker")
            lat_med = avg_metric(runs, "final_latency_med_s")
            lat_p95 = avg_metric(runs, "final_latency_p95_s")
            md.append(f"| {p} | {len(runs)} | {swer:.3f} | {flick:.3f} | {lat_med:.2f}s | {lat_p95:.2f}s |")
        md.append("\n")

    # Translation fidelity section (ASR -> DeepL)
    if any(translation_by_provider.get(p) for p in providers):
        md.append(f"## Translation Fidelity (ASR → DeepL, target = {target_lang})\n")
        md.append("How much ASR error survives machine translation: each provider's transcript is "
                  "translated with DeepL and compared to the translation of the **verified reference** "
                  "(the ideal translation from perfect ASR). chrF is 0–100 (higher = closer to ideal); "
                  "Post-MT WER is on raw target-language text, comparable to the source WER above.\n")
        md.append("| Provider | Clips | chrF vs ideal | Post-MT WER | Source WER (Norm) |")
        md.append("|---|---|---|---|---|")
        for p in providers:
            runs = translation_by_provider.get(p, [])
            if not runs:
                md.append(f"| {p} | 0 | n/a (not run) | n/a | - |")
                continue
            chrf = avg_metric(runs, "trans_chrf")
            twer = avg_metric(runs, "trans_wer")
            # source WER over the same clips that were translated
            translated_ids = {r["clip_id"] for r in runs}
            src_runs = [r for r in results_by_provider.get(p, []) if r["clip_id"] in translated_ids]
            src_wer = avg_metric(src_runs, "wer_norm") if src_runs else 0.0
            md.append(f"| {p} | {len(runs)} | {chrf:.1f} | {twer:.3f} | {src_wer:.3f} |")
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
