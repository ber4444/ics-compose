import os
import json
import subprocess
import sys
import argparse
import urllib.parse
from pathlib import Path

# Add eval/ to path to import config
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))
import config

def get_stream_url(event_str: str, rendition: str) -> str:
    # event_str might be "evt1" or "1". Extract digits.
    event_num = ''.join(filter(str.isdigit, event_str))
    if not event_num:
        event_num = event_str
    
    # Construct Wowza URL similar to MediaKitConfig
    host = os.environ.get("WOWZA_HOST", "65e54f30ec73c.streamlock.net:443")
    user = os.environ.get("WOWZA_USER")
    passwd = os.environ.get("WOWZA_PASS")
    
    if user and passwd:
        base_url = f"https://{user}:{passwd}@{host}/live/event{event_num}{rendition}/playlist.m3u8?DVR"
    else:
        base_url = f"https://{host}/live/event{event_num}{rendition}/playlist.m3u8?DVR"
        
    return base_url

def fetch_clip(entry: dict, dry_run: bool):
    source = entry["source"]
    clip_id = entry["id"]
    output_path = os.path.join(config.CLIPS_DIR, f"{clip_id}.wav")
    
    if os.path.exists(output_path):
        print(f"Skipping {clip_id}, already exists.")
        return

    url = get_stream_url(source["event"], source["rendition"])
    start_s = source["start_s"]
    dur_s = source["dur_s"]

    print(f"Fetching {clip_id} from {url} (start={start_s}, dur={dur_s})")
    
    cmd = [
        "ffmpeg",
        "-y",
        "-ss", str(start_s),
        "-i", url,
        "-t", str(dur_s),
        "-ar", "16000",
        "-ac", "1",
        "-c:a", "pcm_s16le",
        output_path
    ]

    if dry_run:
        print(f"[DRY-RUN] Would execute: {' '.join(cmd)}")
    else:
        # We need to make sure CLIPS_DIR exists
        os.makedirs(config.CLIPS_DIR, exist_ok=True)
        try:
            subprocess.run(cmd, check=True)
            print(f"Successfully fetched {clip_id}")
        except subprocess.CalledProcessError as e:
            print(f"Failed to fetch {clip_id}: {e}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="Do not run ffmpeg")
    args = parser.parse_args()

    if not os.path.exists(config.MANIFEST_PATH):
        print(f"Manifest not found at {config.MANIFEST_PATH}")
        sys.exit(1)

    with open(config.MANIFEST_PATH, 'r') as f:
        manifest = json.load(f)

    # Validate with Pydantic
    try:
        validated_manifest = config.Manifest(**manifest)
        print("Manifest validated successfully.")
    except Exception as e:
        print(f"Manifest validation failed: {e}")
        sys.exit(1)

    for entry in manifest["entries"]:
        fetch_clip(entry, args.dry_run)

if __name__ == "__main__":
    main()
