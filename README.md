# Civic Services Surveillance & Monitoring (Nagar Netra)

On-device detection of **10 street-level civic conditions** from a phone camera or
vehicle-mounted footage — garbage, potholes, encroachments, billboards, manhole state,
streetlight state, and stray animals. A single YOLOv8m model runs fully offline on an
Android phone; every confirmed issue is saved locally as a cropped photo plus a JSON
incident report, geo-tagged with live GPS.

| | |
|---|---|
| **Model** | YOLOv8m (Ultralytics) · INT8 TFLite, ~26 MB (float32 I/O) |
| **Classes** | 10 |
| **Dataset** | 17,000+ images |
| **Trained** | 2026-10-03 (early-stop ep201, best ep161) |
| **mAP@50** | **0.80** |
| **mAP@50-95** | **0.70** |
| **Deployment** | Android (CameraX + TFLite, NNAPI delegate), offline |

> **v2 change:** the seven individual animal species in v1 (cow, cat, horse, dog,
> buffalo, goat, camel) are merged into a single **`animal`** class that the model now
> predicts directly — no on-device remapping. This removed the species-confusion failure
> mode (e.g. dog→cow) and lifted overall accuracy substantially over the v1 16-class
> YOLO11n baseline (test mAP@50 0.604).

---

## Classes

| id | class | id | class |
|:--:|---|:--:|---|
| 0 | garbage_pile | 5 | manhole_open |
| 1 | pothole | 6 | manhole_broken |
| 2 | encroachment | 7 | streetlight_working |
| 3 | billboard_hoarding | 8 | streetlight_not_working |
| 4 | manhole_closed | 9 | animal |

`manhole_closed` and `streetlight_working` are **healthy states** — detected on purpose,
then deliberately **not** reported. A working streetlight is not a civic incident.

**Reportable classes:** garbage_pile, pothole, encroachment, billboard_hoarding,
manhole_open, manhole_broken, streetlight_not_working, animal.

---

## Results

Trained on a **17,000+ image** dataset with synthetic night/rain/fog augmentation to
harden against poor field conditions, then evaluated on the held-out test split.

| metric | score |
|---|---:|
| **mAP@50** | **0.80** |
| **mAP@50-95** | **0.70** |

Exported to INT8 TFLite for mobile (float32 input/output so the app feeds normalized
frames directly). The INT8 quantization uses a capped calibration fraction (~0.12) —
higher fractions blow export time past 5 hours with no accuracy gain.

---

## Quick start

```bash
git clone https://github.com/PrinceVerma04/Nagar-Netra.git
cd Nagar-Netra
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

Run the detector on an image (using the `.pt` weights):

```python
from ultralytics import YOLO
model = YOLO("runs/train/civic_yolov8m/weights/best.pt")
model.predict("street.jpg", conf=0.35, save=True)
```

> Confidence threshold in the app is **0.35** (raised from 0.25 to cut false fires).

### Interactive dashboards

```bash
python dashboard/app.py            # run detections on image / folder / video   :5099
python dashboard/annotate_app.py   # pre-annotate + keep/correct/reject          :5098
```

Dashboards have **no authentication** — fine on a LAN, add auth before exposing to the
internet.

---

## Export for mobile

```bash
python export.py --weights runs/train/civic_yolov8m/weights/best.pt
```

The app needs a **float32-I/O** INT8 TFLite, placed at
`android_app/app/src/main/assets/model.tflite`.

---

## Android app

`android_app/` — Kotlin, CameraX + TFLite, fully offline. Features live GPS geo-tagging
in reports, EXIF, and on-screen overlay.

```bash
export JAVA_HOME=/path/to/jdk
cd android_app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The detector **letterboxes** frames to square before inference, matching how the model
was trained. Stretching to square instead — which the app originally did — distorts every
object and cost ~9 points of accuracy on real photos. Inference uses the **NNAPI
delegate** with an analysis-resolution cap to keep latency low.

---

## Data pipeline

The dataset itself is **not** in this repo. Full provenance for every source — licence,
origin, and why it was kept or rejected — is in `docs/DATASET_UPDATE_LOG.md`.

| script | purpose |
|---|---|
| `scripts/audit_dataset.py` | integrity audit (leakage, duplicates, degenerate boxes, split coverage) |
| `data/restratify_splits.py` | leakage-safe stratified splitter using perceptual hashing |
| `data/remap_class_ids.py` | contiguous class-id renumbering |
| `scripts/snapshot_dataset.py` | freezes a dataset record before every training run |

### Why perceptual hashing matters here

An exact-hash duplicate check reported a clean dataset while a large share of the test
set had a near-identical twin in training — consecutive video frames, re-uploads under
different filenames, and the same stock photo arriving via two source datasets. None were
byte-identical. The splitter groups images by dHash (Hamming ≤ 6) and keeps each group
entirely within one split.

> **Note:** `configs/data.yaml` currently still describes the v1 16-class pool and is
> **stale** relative to the deployed 10-class model — update it before retraining.

---

## Known limitations

- **manhole_closed** remains undertrained (very few training images) and can be confused
  with manhole_broken; in the app it can file false incident reports.
- **Camera tilt beyond ~15°** degrades accuracy sharply.
- Live on-device camera testing of v2 is the final outstanding validation step.

---

## Licence

Code released under the MIT Licence. Dataset images are **not** redistributed here;
see `docs/DATASET_UPDATE_LOG.md` for per-source licences and attribution.
