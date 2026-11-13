# Nagar Netra — Civic Issue Detection

On-device detection of **10 street-level civic conditions** from a phone camera —
garbage, potholes, encroachments, billboards, manhole state, streetlight state, and stray
animals. A single **YOLOv8m** model runs fully offline on an Android phone; every
confirmed issue is saved locally as a cropped photo plus a JSON incident report,
geo-tagged with live GPS.

| | |
|---|---|
| **Model** | YOLOv8m |
| **Classes** | 10 |
| **Dataset** | 17,000+ images |
| **mAP@50** | **0.80** |
| **mAP@50-95** | **0.70** |
| **Deployment** | Android (CameraX + TFLite), fully offline |

---

## How it works

```mermaid
flowchart LR
    A["📷 Phone camera<br/>/ video frame"] --> B["Letterbox to<br/>square input"]
    B --> C["YOLOv8m<br/>INT8 TFLite<br/>(NNAPI)"]
    C --> D{"Detections"}
    D -->|"healthy state<br/>manhole_closed,<br/>streetlight_working"| E["Ignore<br/>(not an incident)"]
    D -->|"civic issue"| F["Confidence gate<br/>+ crop"]
    F --> G["Save photo + JSON<br/>+ GPS geo-tag"]
    G --> H["📁 Local reports log"]
```

The detector **letterboxes** frames to the model's square input, matching how the model
was trained (stretching instead distorts every object and costs accuracy). Inference runs
through the **NNAPI** delegate for low latency. `manhole_closed` and `streetlight_working`
are healthy states — detected on purpose, then deliberately **not** reported.

---

## Classes

| | | | | |
|:--:|:--:|:--:|:--:|:--:|
| ![garbage_pile](docs/images/classes/00_garbage_pile.jpg)<br>**garbage_pile** | ![pothole](docs/images/classes/01_pothole.jpg)<br>**pothole** | ![encroachment](docs/images/classes/02_encroachment.jpg)<br>**encroachment** | ![billboard_hoarding](docs/images/classes/03_billboard_hoarding.jpg)<br>**billboard_hoarding** | ![manhole_closed](docs/images/classes/04_manhole_closed.jpg)<br>**manhole_closed** |
| ![manhole_open](docs/images/classes/05_manhole_open.jpg)<br>**manhole_open** | ![manhole_broken](docs/images/classes/06_manhole_broken.jpg)<br>**manhole_broken** | ![streetlight_working](docs/images/classes/07_streetlight_working.jpg)<br>**streetlight_working** | ![streetlight_not_working](docs/images/classes/08_streetlight_not_working.jpg)<br>**streetlight_not_working** | ![animal](docs/images/classes/09_animal.jpg)<br>**animal** |

---

## Results

| metric | score |
|---|:---:|
| **mAP@50** | **0.80** |
| **mAP@50-95** | **0.70** |

Trained on a **17,000+ image** dataset with YOLOv8m, then exported to INT8 TFLite for
on-device inference.

---

## Mobile app interface

| Landing | Live detection | Reports |
|:--:|:--:|:--:|
| ![Landing](docs/images/app/01_landing.png) | ![Live detection](docs/images/app/02_live_detection.png) | ![Reports](docs/images/app/03_report.png) |

Kotlin · CameraX + TFLite · fully offline · live GPS geo-tagging.

### Demo

<video src="https://raw.githubusercontent.com/PrinceVerma04/Civic-monitor/main/docs/videos/working_demo.mp4" controls muted width="720"></video>

▶ [Watch the demo video](docs/videos/working_demo.mp4) (if the player above does not load)

---

## Run

```python
# detector (Python)
from ultralytics import YOLO
model = YOLO("weights/best.pt")
model.predict("street.jpg", conf=0.35, save=True)
```

```bash
# Android app
cd android_app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Licence

Code released under the MIT Licence.
