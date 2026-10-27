# LingBot-Map: what it is and where it could fit in Smriti

Source reviewed: `github references/lingbot-map-main` (Apache-2.0, Robbyant team, "Geometric Context Transformer for Streaming 3D Reconstruction").

## What it does
- Takes a video or a stream of images and, frame by frame, outputs a **camera pose** and **dense 3D points** (a point cloud of the room) with no iterative optimisation.
- Architecture (from `lingbot_map/aggregator/base.py`, `models/gct_base.py`): DINOv2 ViT-L/14 backbone (1024-d, 24 layers, 16 heads), a camera head, and a DPT head for depth/points. A paged KV cache keeps anchor frames, a pose-reference window and a trajectory memory so it stays stable over 10,000+ frames.
- Reported speed: about 20 FPS at 518x378 on an NVIDIA GPU with FlashInfer. README mentions an 8 GB RTX 4060 fork for small GPUs.

## Why it cannot run in the phone app
- PyTorch + CUDA + FlashInfer paged attention. There is no mobile export, and a ViT-L with a growing KV cache is far beyond what an NPU path (LiteRT/QNN) handles today.
- This laptop has an Intel HD 520 and no NVIDIA GPU, so it cannot run it either. A GPU machine is needed.

## Fits that would work
1. **Home 3D map (companion mode, optional).** The phone sends already face-blurred frames over USB or the local Wi-Fi to a PC that runs LingBot-Map. The PC returns camera poses and points. Smriti attaches an (x, y, z) to every sighting in the object ledger, so "where is my mouse" can answer "on the desk, about 2 m from the door" and the website can draw the room as a point cloud with item markers. Stays "zero cloud": it is your own machine on your own network, off by default, covered by the existing LAN guard.
2. **One-time room walkthrough.** Record a short video of each room, process it later on the PC, and import a light top-down map into the app. Needs a way to localise the phone against that map afterwards, which is the hard part.
3. **Ideas only, no code:** keyframe plus trajectory memory maps naturally onto Smriti's scenes (keep a few anchor notes per scene, drop the rest).

## Recommendation for the finals
Do not put it in the app. If a GPU machine is available at the venue, a 1 to 2 hour spike (run `demo.py` on `example/` scenes, show the point cloud next to the live dashboard) makes a strong "roadmap: 3D home memory" slide. Otherwise present it as the next step.
