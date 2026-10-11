# Sethu - Release-Candidate Engineering Report

Scope: software verification only. **No physical device was used, no ADB, no install.** Bluetooth interoperability on the real
iQOO 15 / Vivo Y15 / Motorola Edge 30 Pro is **unverified**.

## A. Baseline (before any change)
- `gradlew test --rerun-tasks`: 82 tests per variant (debug and release), 0 failed, 0 skipped. `assembleDebug` OK, APK 101,834,295 bytes.
- Merged debug manifest: no INTERNET. Permissions: location x2, RECORD_AUDIO, BLUETOOTH/ADMIN (<=30), BLUETOOTH_CONNECT, BLUETOOTH_SCAN.
- The 82 tests never executed the socket/transport code (it was hard-wired to android.bluetooth classes), so "82 passing" said nothing about the field failure.

## B. Root cause of "paired in Android, but `No peer device is currently connected`"
Android pairing (bonding) is not an RFCOMM session. Sethu needs: permission -> listener on the relay -> outbound connect -> role handshake with a *valid* role -> ACK. Defects found by code reading (and now covered by simulated tests):
1. **Bluetooth runtime permission was only requested on the Direct Link screen.** The engine started at app launch without it: `listenUsingRfcommWithServiceRecord` threw `SecurityException`, the server loop did `break` (dead for the session), and `getBondedDevices()` swallowed the same exception and returned an empty list, so the engine "discovered" zero peers and never attempted a connection - silently, forever, even after the user later granted permission.
2. **`disconnect()` cancelled the server job and never restarted it.** The engine called it after every delivery batch, so after the first cycle the relay/sender could not be connected *to* (reverse ACKs, relay inbound) until the user pressed Host manually.
3. **Role was a non-persisted UI selection defaulting to SOS_SENDER.** A relay phone that was not explicitly set to Relay (or that was recreated) announced `SOS_SENDER`; the sender correctly refuses a sender peer, backs off 15 s and shows no reason. The UI only said "not connected".
4. Simultaneous dial livelock: fixed 15 s backoff on both phones, listener closed while dialing -> both refuse each other in lock-step.
5. Activity recreation created a second transport/engine (`remember` in a composable) while the first accept() thread kept the SPP UUID.
6. One-tap/structured SOS also sent directly over any open socket (any role) racing the engine.
Fix summary: see section C. The UI no longer claims "Connected" artificially; it shows the engine's real state (permission needed / Bluetooth off / no paired devices / connecting / wrong peer role X / retrying).

## C. Files changed
New (main): `SethuRuntime.kt` (ViewModel-owned single transport+stores+engine, persisted role), `bluetooth/RfcommAdapter.kt` + `AndroidRfcommAdapter.kt` (seam over the Android stack, abortable connect), `bluetooth/BoundedLineReader.kt` (bounded framing), `util/DurableJsonArrayFile.kt` (atomic fsync'd writes, corruption salvage + backup).
Rewritten/modified (main): `BluetoothRfcommTransport.kt` (session ownership model, parked server loop, watchdog connect, session-bound replies), `StoreAndForwardController.kt`, `Sender/Relay/CoordinatorReportStore.kt`, `SosReport.kt` (validation), `SethuMessage.kt` (+`COORDINATOR_ACK_RECEIPT`), `BluetoothPermissionHelper.kt`, `MainActivity.kt`, `SosSenderScreen.kt`, `OfflineCommScreen.kt` (type only), `OfflineSpeechManager.kt`, `OfflineAiExtractor.kt` (late-load leak/concurrency), `AndroidManifest.xml` (`allowBackup=false`).
New tests: `sim/` (FakeRfcomm, SimNode, TransportLifecycleTest, SimulatedEndToEndTest, BoundedLineReaderTest), `comm/storage/StoreDurabilityTest`, `comm/security/ManifestAndProtocolSecurityTest`. No existing test was edited or removed. `build.gradle.kts` unchanged (no new dependencies).

## D. Defects fixed
| # | Sev | Defect | Fix | Test |
|---|-----|--------|-----|------|
| 1 | Blocker | BT permission never requested at start; server loop died; bonded list swallowed SecurityException | startup permission request + resume on result/ON_RESUME; server loop parks (no polling) and is revived; explicit `PERMISSION_REQUIRED` status | `missingPermissionParksServerThenRecoversWhenGranted`, `missingBluetoothPermission_isReportedHonestly...` |
| 2 | Blocker | Device deaf after engine `disconnect()` | `releaseConnection()`; server loop always re-listens when idle | `serverResumesListeningAfterEverySessionEnds` (mutation-checked) |
| 3 | Blocker | Wrong/default role silently rejected | role persisted, adopted on entering a role screen, explicit WRONG_PEER_ROLE diagnosis | `pairedButWrongRolePeer_isRejectedHonestly...` |
| 4 | High | Final ACK marked delivered after local write (lost ACK unrecoverable) | receipt protocol (`COORDINATOR_ACK_RECEIPT`), see E | `finalAckWrittenButSenderNeverReceivesIt...` (mutation-checked), 4 more |
| 5 | High | Relay stored source address from shared state; stalled final ACK (found by stress test, ~1/6 runs) | session-bound `PeerContext` | `relayRecordsSourceAddressFromDeliveringSession...` (mutation-checked) |
| 6 | High | Lock-step retry livelock | jittered backoff; sender defers reverse polling; idle useless links released | stress + repeated-run tests |
| 7 | High | Corrupt/truncated store was replaced by empty store on next write; write failures swallowed; non-atomic fallback | salvage + `.corrupt-*` backup, atomic fsync write, `persistenceError`/`isPersisted`, ACK only if durable, persist-then-publish | `StoreDurabilityTest` (23) |
| 8 | High | Activity recreation duplicated transport/engine | ViewModel-owned runtime | by construction (not unit-testable on JVM) |
| 9 | Med | Unbounded `readLine` (memory exhaustion); malformed report with no id accepted under a random id; arbitrary role strings; unbounded lists | bounded frames, strict `SosReport.fromJson`, known roles only, caps | framing / malformed tests |
| 10 | Med | Unbounded outbound connect; stale reader could clear newer session; stale ACKs accepted from any peer | watchdog abort, per-session ownership, role+address-validated ACKs/receipts | lifecycle tests |
| 11 | Med | UI status derived from "a socket is open"; second unvalidated send path; double-tap duplicate (structured form) | status from durable state; single engine path; debounce | code review (Compose UI untested) |
| 12 | Low | `allowBackup=true` would copy SOS data to cloud backup | `false` | `ManifestAndProtocolSecurityTest` |
| 13 | Low | Speech/LLM model loaded after screen disposal leaked; repeated extraction could load the native model twice | generation guard / single-flight | code review only |

## E. Final-ACK semantics
1. Relay: `DELIVERED_TO_COORDINATOR`, `coordinatorAckPropagated=false`. 2. Whenever the originating sender is on a link the relay (re)sends `COORDINATOR_ACK`. 3. Sender persists `ACKNOWLEDGED_BY_COORDINATOR` (persist-then-publish, `isPersisted`) and only then sends `COORDINATOR_ACK_RECEIPT`. 4. Relay sets `coordinatorAckPropagated=true` only for a receipt from the exact stored source address while the peer role is SOS_SENDER. No receipt-of-receipt, so no loop (`noAckLoopAfterCompletion` proves traffic stops). Older peers ignore the unknown type (they will simply keep the ACK pending; both phones must run this build for full completion semantics).

## F. Lifecycle behaviour (documented, not promised)
- Foreground use: full automatic operation. Backgrounded: process and sockets normally survive for a while but Android may kill/doze them; no foreground service was added (a new subsystem with its own permission/notification/Android-14 restrictions; not justified without hardware evidence).
- Process killed / phone restarted / force-stopped: nothing runs until the user reopens Sethu; all queued reports and ACK state are on disk and resume automatically on next open. **Do not expect delivery while the app is force-stopped.**
- Bluetooth OFF: sockets and listener closed, state `Bluetooth is disabled`; ON: listening resumes automatically (simulated; the broadcast itself is not exercised on JVM).

## G. Security / offline
Merged debug manifest: **no INTERNET / network-state permission**; one exported activity (launcher), `ProfileInstallReceiver` (androidx, protected by `android.permission.DUMP`), non-exported `InitializationProvider`; no app services. Cloud backup disabled. RFCOMM traffic is **not encrypted or application-authenticated**: pairing is link-level only, roles/ACKs are shape- and order-validated, not cryptographically authenticated; a paired malicious device could forge ACKs for report IDs it can guess. Reports/coordinates are not logged by the new code. Writes can block indefinitely if a peer stalls mid-write (no write timeout).

## H. Verification boundary
Automated: 165 tests per variant (debug+release) pass, 0 failed/skipped (baseline 82; +83 new). `assembleDebug` passes. **Not run:** Android lint (`lintDebug` needs artifacts not in the offline Gradle cache; downloading was not authorised), instrumented/UI tests (no device/emulator by rule), any physical Bluetooth. Simulated tests use fake sockets; they validate application logic, not the Bluetooth stack.
Unverified on hardware: SDP/UUID service lookup on Vivo (reflection fallback retained), real accept()/connect() timing, simultaneous-open behaviour of real stacks, Android 12+ permission dialog flow, behaviour under OEM battery management (iQOO/Vivo), Bluetooth ON/OFF broadcast delivery, Compose UI states.
