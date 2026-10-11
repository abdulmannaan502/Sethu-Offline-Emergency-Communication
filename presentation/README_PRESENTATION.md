# Sethu hackathon presentation - README

## Files
| File | What it is |
|---|---|
| `Sethu_Presentation.pptx` | 12-slide 16:9 deck. Text, chips and architecture diagrams are native, editable PowerPoint shapes; artwork is embedded. Speaker notes on every slide. |
| `Sethu_Presentation_Video.mp4` | ~120 s, 1920x1080, 30 fps, H.264 + AAC. Narration (Windows "Zira" text-to-speech), soft synthesized ambient pad, burned-in captions. |
| `assets/` | Generated artwork, narration WAVs, rendered slide PNGs (`assets/render/`), video preview frames, design reference image. |
| `scripts/` | Everything that built the deliverables (`art.py`, `build_pptx.py`, `build_video.py`, `tts.ps1`, `render_pptx.ps1`). Re-run to rebuild. |

Rebuild: `cd scripts; python build_pptx.py; powershell -File tts.ps1; python build_video.py` (needs `python-pptx`, `Pillow`, `numpy`, `imageio-ffmpeg`; PowerPoint is only used for the render check).

## Design direction
Dark navy, electric-blue/cyan Bluetooth accents, emergency-red and amber highlights, following the supplied reference image. Only the *style* was reused; no text or fabricated screenshots were copied from it.

## What is real and what is illustration
* **Real screenshot:** one - slide 10, a screenshot of the iQOO 15 *Direct Device Link* screen from the first physical connection attempt (it shows "Connection Failed"). The status bar was cropped. It is labelled as real and as a failed attempt. Delete that box if you prefer not to show it, but do not replace it with a success claim.
* **Illustrative mockups:** every other phone screen is drawn by `scripts/art.py` using the app's real colour palette (`ui/theme/Color.kt`) and real labels. They are labelled "ILLUSTRATIVE MOCKUP" and are **not** screenshots of the running app. The ADB-attached phones disconnected before live screenshots of the working flow could be taken, and the workflow has not yet completed on hardware, so no real "success" screenshots exist.
* **Artwork:** the flood scene is drawn procedurally (no stock photo, no AI image). It is an illustration of a scenario, not a real event.

## Claims and their sources (verified against the project on 2026-10-11)
| Claim in deck/video | Basis |
|---|---|
| Sender / Relay / Coordinator roles, manual Start Server + tap-to-connect, link-driven automatic forwarding | `StoreAndForwardController.kt`, `OfflineCommScreen.kt`, `DirectLinkActions.kt`; controller has no connect/listen calls |
| One active link at a time | `BluetoothRfcommTransport` keeps a single session |
| States PENDING FORWARD -> FORWARDED -> DELIVERED TO COORDINATOR; sender PENDING -> TRANSMITTED -> ACKED BY RELAY -> ACKED BY COORDINATOR | `DeliveryState`, `SenderDeliveryState` enums |
| Saved to disk before any ACK; duplicate IDs create one record | stores persist-then-publish; covered by unit/simulated tests |
| Max 3 sends per connection | `ForwardingConfig.maxAttemptsPerLink` |
| Rule-based priority CRITICAL/HIGH/MEDIUM/UNKNOWN | `PriorityClassifier` (keyword rules + stated reason) |
| Offline voice | Vosk, model bundled in assets (`model-en-in`) |
| Local AI assist | Qwen2.5-0.5B via llama.cpp (`OfflineAiExtractor`); the 491 MB `.gguf` is **not** inside the APK - it must be copied to the phone. Proposal fields are editable |
| Offline maps | Mapsforge; needs a local `bengaluru.map` file on the phone (not bundled); the screen shows a notice if it is missing |
| No INTERNET permission | merged debug and release manifests contain no `android.permission.INTERNET` |
| 188 unit tests, 0 failed | `gradlew test --rerun-tasks` (debug variant results), includes simulated end-to-end tests on fake Bluetooth sockets |

## What is NOT claimed
* **Physical Bluetooth end-to-end has not been verified.** The first iQOO -> Vivo attempt failed at the RFCOMM connect step (`read failed, socket might closed or timeout, read ret: -1`). The log showed the Vivo's service was found (channel 4) but the channel open was rejected; the cause (stale pairing keys vs. link security) is still open. The deck and video therefore say "physical Bluetooth testing in progress" and present the three-phone sequence as the *intended* demonstration.
* Simulated tests prove application logic, not the Bluetooth stack on the phones.
* No encryption or app-level authentication (Bluetooth pairing is not app-level authentication). Listed as future work.
* Multi-hop relays, background delivery, authorized integrations, more languages, field testing are labelled **FUTURE**.
* No benchmark numbers, response times, accuracy figures or user counts appear anywhere.

## Discrepancies found
* `docs/RELEASE_CANDIDATE_REPORT.md` still describes the earlier *automatic* dialing engine and 82/165 test counts. The current code is manual-connection + link-driven forwarding with 188 tests; see `docs/MANUAL_THREE_PHONE_WORKFLOW.md`. The presentation follows the current code.
* The video's step order (Start Server -> tap peer -> SOS -> link ends -> second manual link -> automatic forward) is the intended workflow. It is shown with mockups because it has not yet run end-to-end on the phones.

## Tools and limits
* No video-generation service exists here; visuals are drawn in Python (PIL) and encoded with the ffmpeg binary bundled in the `imageio-ffmpeg` pip package (installed for this task).
* Narration is a built-in Windows text-to-speech voice, not a human voice; replace `assets/audio/narr_*.wav` and re-run `build_video.py` to use a recording.
* The Android application source was not modified for the presentation.
