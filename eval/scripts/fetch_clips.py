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

    event = source["event"]
    rendition = source["rendition"]
    start = source["start_s"]
    dur = source["dur_s"]
    out_path = output_path

    print(f"Fetching {clip_id}...")
    
    url = f"https://65e54f30ec73c.streamlock.net:443/live/event{event}{rendition}/playlist.m3u8?DVR"
    print(f"Executing: ffmpeg -ss {start} -i {url} -t {dur} -ar 16000 -ac 1 -c:a pcm_s16le {out_path}")
    
    if dry_run:
        print(f"[DRY-RUN] Would execute: ffmpeg -ss {start} -i {url} -t {dur} -ar 16000 -ac 1 -c:a pcm_s16le {out_path}")
    else:
        os.makedirs(config.CLIPS_DIR, exist_ok=True)
        try:
            cmd = [
                "ffmpeg", "-y", "-ss", str(start), "-i", url, "-t", str(dur),
                "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", out_path
            ]
            subprocess.run(cmd, check=True, capture_output=True)
            print(f"Successfully downloaded {clip_id}")
        except subprocess.CalledProcessError as e:
            print(f"Failed to fetch {clip_id}: {e.stderr.decode()}")

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
