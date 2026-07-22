import json
import os

base_dir = os.path.dirname(os.path.dirname(__file__))
manifest_path = os.path.join(base_dir, "golden", "manifest.json")

# Times in seconds
event_ranges = {
    8: (323, 2597, "clean"),
    10: (229, 2820, "clean"),
    11: (480, 3607, "clean"),
    13: (761, 4091, "clean"),
    15: (540, 7369, "music_bed")
}

entries = []
num_clips_per_event = 5
clip_duration = 60

for evt, (start_s, end_s, condition) in event_ranges.items():
    # Calculate spacing to spread clips evenly
    valid_duration = (end_s - start_s) - clip_duration
    if valid_duration < 0:
        continue # Should not happen with these ranges
        
    step = valid_duration // (num_clips_per_event - 1)
    
    for i in range(num_clips_per_event):
        clip_start = start_s + (i * step)
        entries.append({
            "id": f"evt{evt}-clip-{i+1}",
            "source": {
                "event": str(evt),
                "rendition": "_aac",
                "start_s": clip_start,
                "dur_s": clip_duration
            },
            "conditions": [condition],
            "domain_terms": [],
            "ref": f"refs/evt{evt}-clip-{i+1}.txt",
            "verified": False
        })

data = {"entries": entries}
os.makedirs(os.path.dirname(manifest_path), exist_ok=True)
with open(manifest_path, "w") as f:
    json.dump(data, f, indent=2)

print(f"Generated manifest with {len(entries)} clips")
