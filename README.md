# BillO

**Fully offline, on-device probabilistic ledger engine for street vendors** — turning noisy multi-channel evidence (crumpled wholesale slips, Hinglish speech under 85dB traffic noise, delayed SMS chimes, handwritten tallies) into a mathematically sound double-entry picture with ranges and confidence intervals, not blind point guesses.

## Offline-first, enforced by the OS

The Android manifest declares **no `INTERNET` permission**. The platform itself guarantees that no component can reach a network — offline-first is a hard property, not a policy. All perception runs on-device:

| Channel | Engine | Status |
|---|---|---|
| Hindi/Hinglish speech | Vosk (Kaldi) acoustic model, 16 kHz PCM | Phase 1 ✓ |
| Noise gate | RMS→dBFS metering, evidence down-weighting above gate | Phase 1 ✓ |
| Slip / tally OCR | ML Kit bundled Latin + Devanagari recognizers + grayscale/contrast preprocessing for oil-stained paper | Phase 1 ✓ |
| Delayed SMS | Passive `SMS_RECEIVED` receiver + inbox backfill, dedup on import | Phase 1 ✓ |
| Handwritten tallies | CameraX capture → OCR pipeline | Phase 1 ✓ |
| Numerals & units | Hindi numeral grammar (dedh/dhai/sawa/paune/saadhe, hazaar/lakh/crore) + commerce unit normalization (kilo/plate/darjan…) | Phase 1 ✓ |

## Double-entry core

`LedgerRepository.post()` refuses any entry where `sum(debits) != sum(credits)` or a leg is both/neither — the balance invariant is held from the first entry. Every entry records **two clocks**: the hostile wall clock (display only) and the monotonic per-boot clock (ordering), the foundation for hostile-clock survival in the sync phase.

One-time setup for speech (performed once on a connected machine): download `vosk-model-small-hi-0.22` from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) and extract it to `Android/data/com.billo.ledger/files/model-hi/` on the device. After that, recognition is permanently offline.

## Roadmap

- **Phase 0 ✓** — teardown of the previous cloud codebase; on-device shell; local Room DB; double-entry schema with balance invariant
- **Phase 1 ✓** — offline perception: local STT + noise gate, on-device OCR + preprocessing, SMS ingestion, camera capture, Hindi numerals + units
- **Phase 2** — probabilistic inference core: distributions on every fact, Bayesian entity/amount resolution, calibrated confidence, mid-event Lexicon Drop
- **Phase 3** — cross-modal fusion: evidence pool → single events, no triple-counting, partial/contradictory evidence handling
- **Phase 4** — zero-shot recipe inference (burn rates → per-plate priors), loss attribution (spoilage/theft/consumption posteriors over environmental covariates), COGS, true accrual profit
- **Phase 5** — CRDT sync over Bluetooth, hostile clocks (1970 resets / forward jumps), contradictory credit claims
- **Phase 6** — probabilistic UI, evaluation harness (Raw Material Burn → Inferred Wastage → Reconciled Credit → True Accrual Profit), demo

## Build

```bash
./gradlew :app:assembleDebug     # or open in Android Studio
./gradlew :app:installDebug      # with an emulator/device attached
```
