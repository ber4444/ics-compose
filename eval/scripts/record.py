import os
import sys
import json
import argparse
from pathlib import Path

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config
from providers import DeepgramProvider, SonioxProvider

def ensure_fixture_dir(provider_name: str, boost: bool):
    name = f"{provider_name}-boost" if boost else provider_name
    path = os.path.join(config.FIXTURES_DIR, name)
    os.makedirs(path, exist_ok=True)
    return path

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="Bypass cost checks")
    args = parser.parse_args()
    
    os.environ["EVAL_LIVE"] = "1"
    
    with open(config.MANIFEST_PATH, 'r') as f:
        manifest = json.load(f)
        
    providers = {
        "deepgram": DeepgramProvider(),
        "soniox": SonioxProvider()
    }
    
    total_audio_s = sum(entry["source"]["dur_s"] for entry in manifest["entries"])
    total_billed = total_audio_s * len(providers)
    
    print(f"Total audio to process: {total_audio_s} seconds per provider.")
    print(f"Total billed audio: {total_billed} seconds.")
    
    if total_billed > config.MAX_BILLED_AUDIO_SECONDS and not args.force:
        print(f"Error: Billed audio {total_billed} exceeds cap of {config.MAX_BILLED_AUDIO_SECONDS}. Use --force to override.")
        sys.exit(1)
        
    for entry in manifest["entries"]:
        clip_id = entry["id"]
        wav_path = os.path.join(config.CLIPS_DIR, f"{clip_id}.wav")
        if not os.path.exists(wav_path):
            print(f"Skipping {clip_id}, audio not found.")
            continue
            
        domain_terms = entry.get("domain_terms", [])
        
        for name, provider in providers.items():
            for boost in [False, True]:
                if boost and not domain_terms:
                    continue # Nothing to boost
                    
                fix_dir = ensure_fixture_dir(name, boost)
                fix_path = os.path.join(fix_dir, f"{clip_id}.json")
                
                if os.path.exists(fix_path):
                    print(f"Skipping {name} (boost={boost}) for {clip_id}, fixture exists.")
                    continue
                    
                print(f"Recording {name} (boost={boost}) for {clip_id}...")
                try:
                    result = provider.transcribe(wav_path, domain_terms, boost)
                    with open(fix_path, 'w') as f:
                        json.dump(result.model_dump(), f, indent=2)
                except Exception as e:
                    print(f"Failed to record {name} for {clip_id}: {e}")

if __name__ == "__main__":
    main()
