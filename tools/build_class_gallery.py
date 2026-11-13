#!/usr/bin/env python3
"""Build a clean 10-class gallery for the README.

The dataset in data_for_training/ is labelled with the ORIGINAL 16-class scheme,
so each deployed (10-class) category is mapped to a representative source id.
For every category we pick the image whose target box is largest (cleanest,
most unambiguous representative), crop it with padding, square-pad, and resize.
"""
import os
from pathlib import Path
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parent.parent
LBL_DIRS = [ROOT / "data_for_training/labels/train",
            ROOT / "data_for_training/labels/val",
            ROOT / "data_for_training/labels/test"]
IMG_DIRS = [ROOT / "data_for_training/images/train",
            ROOT / "data_for_training/images/val",
            ROOT / "data_for_training/images/test"]
OUT = ROOT / "docs/images/classes"
OUT.mkdir(parents=True, exist_ok=True)

# (output index, display name, source 16-class id)
TARGETS = [
    (0, "garbage_pile", 0),
    (1, "pothole", 1),
    (2, "encroachment", 2),
    (3, "billboard_hoarding", 3),
    (4, "manhole_closed", 5),
    (5, "manhole_open", 6),
    (6, "manhole_broken", 7),
    (7, "streetlight_working", 8),
    (8, "streetlight_not_working", 9),
    (9, "animal", 4),  # cow as the representative animal
]
EXTS = (".jpg", ".jpeg", ".png", ".webp")
THUMB = 420
PAD = 0.22  # fraction of box size added on each side


def find_image(stem):
    for d in IMG_DIRS:
        for e in EXTS:
            p = d / (stem + e)
            if p.exists():
                return p
    return None


def best_candidates():
    """For each source id, remember (area, label_path, box) of the biggest box."""
    best = {sid: None for (_, _, sid) in TARGETS}
    wanted = set(best)
    for d in LBL_DIRS:
        if not d.exists():
            continue
        for lf in d.glob("*.txt"):
            try:
                lines = lf.read_text().strip().splitlines()
            except Exception:
                continue
            boxes = []
            cls_here = set()
            for ln in lines:
                parts = ln.split()
                if len(parts) < 5:
                    continue
                cid = int(float(parts[0]))
                cls_here.add(cid)
                boxes.append((cid, [float(x) for x in parts[1:5]]))
            for cid, (cx, cy, w, h) in boxes:
                if cid not in wanted:
                    continue
                area = w * h
                # prefer images where the box is sizeable; lightly favour
                # single-class images so the thumbnail isn't ambiguous
                score = area * (1.3 if len(cls_here) == 1 else 1.0)
                cur = best[cid]
                if cur is None or score > cur[0]:
                    best[cid] = (score, lf.stem, (cx, cy, w, h))
    return best


def crop_square(img, box, pad=PAD):
    W, H = img.size
    cx, cy, w, h = box
    bw, bh = w * W, h * H
    cxp, cyp = cx * W, cy * H
    half = max(bw, bh) * (0.5 + pad)
    left = max(0, cxp - half)
    top = max(0, cyp - half)
    right = min(W, cxp + half)
    bottom = min(H, cyp + half)
    crop = img.crop((int(left), int(top), int(right), int(bottom)))
    crop = ImageOps.pad(crop, (THUMB, THUMB), color=(18, 18, 20))
    return crop


def main():
    best = best_candidates()
    for idx, name, sid in TARGETS:
        cand = best.get(sid)
        if cand is None:
            print(f"!! no source image found for {name} (id {sid})")
            continue
        _, stem, box = cand
        imgp = find_image(stem)
        if imgp is None:
            print(f"!! image file missing for {name}: {stem}")
            continue
        try:
            img = Image.open(imgp).convert("RGB")
        except Exception as e:
            print(f"!! cannot open {imgp}: {e}")
            continue
        thumb = crop_square(img, box)
        out = OUT / f"{idx:02d}_{name}.jpg"
        thumb.save(out, quality=88)
        print(f"ok  {out.relative_to(ROOT)}  <- {imgp.name}")


if __name__ == "__main__":
    main()
