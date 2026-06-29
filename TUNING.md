# Tuning wake-word accuracy

Goal: fire on a real wake phrase — its declared lead + keyword, e.g. "hey jarvis" (also "hi bob", or a
bare "computer" with no lead) — with **high precision** (few false triggers) while keeping **high recall**
(rarely miss a genuine one). Two upstream Vosk references drive this:

- Accuracy overview: <https://alphacephei.com/vosk/accuracy>
- Adaptation (LM / acoustic): <https://alphacephei.com/vosk/adaptation>

## What we already do (the high-leverage, low-cost levers)

These follow Vosk's own top recommendations and need no extra work:

1. **Restricted grammar (the single biggest win).** Vosk's accuracy page stresses that a *fixed
   vocabulary matching the target keywords* is what makes short-phrase spotting reliable. We build a
   grammar from exactly the wake phrases + their keywords + each word's declared lead (e.g. `"hey"`) + an `"[unk]"` escape token, so the
   decoder either hears a wake phrase or dumps everything else into `[unk]` instead of forcing a
   look-alike onto a wake word. → `WakeRecognizer.buildGrammar`.
2. **A dynamic-graph (`lgraph`) model.** Runtime grammars require "a model with a dynamic graph"
   (per the adaptation page). `vosk-model-en-us-0.22-lgraph` is that model — its `Gr.fst` supports the
   `Recognizer(model, sampleRate, grammar)` constructor we use. Don't swap to a non-lgraph model
   without losing the grammar path. → `app/src/main/assets/model-en-us/README.md`.
3. **16 kHz mono capture.** Vosk models are 16 kHz; we capture at exactly that with no resampling.
   → `PcmCaptureFormat.SAMPLE_RATE` (= 16_000, in commons).
4. **A keyword must follow its declared lead word — and, for hand-off routes, form a clean uncontaminated phrase.**
   The mandatory lead (e.g. "hey", declared in the plugin's phrase) is the first precision gate (a bare
   keyword, look-alike, or noise-decoded keyword never fires). It is **not sufficient on its own** for a
   soundalike-prone keyword like *jarvis* —
   background audio can decode a full "hey jarvis" (see the on-device section) — so **strict routes**
   (jarvis/alexa) additionally require a clean phrase (`≤ CLEAN_PHRASE_MAX_WORDS`), the keyword over its
   floor, and **no rival wake keyword** in the decode. `setWords(true)` supplies the per-word confidence
   these use. → `WakeMatcher` (all thresholds + gates live here, unit-tested).
5. **A built-in feedback loop.** Vosk's debugging advice is "look at real transcripts." Every fire logs
   the full decode with per-word confidence to `files/debug.txt` —
   `wake detected → jarvis [hey(99) jarvis(100)]` — so thresholds and gates can be tuned from real
   on-device data instead of guesswork.
   → `WakeMicEngine`.

## What on-device testing showed (Portal+ "aloha", lgraph model, "hey jarvis")

Observed with "hey jarvis" (verbose decode logging on, millennium disabled):

- **Recall (clean phrase).** A real "hey jarvis" decodes as a clean 2-word phrase with the keyword at
  **0.92–1.00**; the latest check fired **8/8** at varied close-to-medium distance, every decode
  `[hey(96–100) jarvis(100)]`. So the lenient clean-phrase bypass stays **dormant** for jarvis too — real
  wakes clear any floor easily, and the strict clean-phrase requirement (below) doesn't touch them.
- **Precision (the failure mode that actually bit us).** Background audio **can** assemble a full
  "hey jarvis" for this keyword: a live stream false-fired with `[[unk] alexa [unk] [unk] hey jarvis]`,
  jarvis at **0.83** — a 6-word, `[unk]`-laden, cross-contaminated decode. The **"hey" gate did not catch
  it** (a "hey" was present). Fixed by tightening strict routes to require a **clean phrase** *and* **no rival wake keyword**
  in the decode (`WakeMatcher`); each independently blocks that decode.

Takeaway for jarvis: the "hey" gate is necessary but **not sufficient** against background audio — strict
hand-off routes additionally demand a short, uncontaminated phrase. Raising the *keyword* confidence floor
was rejected as the fix: the FP scored **0.83** (above any floor we'd keep) while real wakes score ~1.00, so
the clean-phrase and cross-keyword gates — not the keyword floor — do the work.

- **Precision (a second failure mode: a clean phrase with a weak "hey").** A live false fire during a
  phone call held near the Portal decoded `[[unk](100) hey(66) jarvis(95)]` — a *short, clean* phrase that
  the clean-phrase and cross-keyword gates above can't catch (it isn't long, and carries no rival keyword).
  What distinguishes it from a real wake is the **"hey" confidence**: 0.66 here vs **0.96–1.00** for every
  genuine wake. Fixed by gating the preceding "hey" on `WakeMatcher.LEAD_MIN_CONF` (0.80) for strict routes —
  present-but-weak no longer passes. The call audio reached the mic at all because `CallGate` reads the
  *Portal's* audio mode and so is blind to a call on a *separate phone*; the matcher floor is the backstop.

- **Precision (a third failure mode: a clean phrase contaminated by `[unk]`).** A live false fire decoded
  `[[unk](51) hey(99) jarvis(99)]` — a *short, clean* phrase with **both** wake words maxed at ~0.99, so
  **no confidence floor could ever catch it** (`LEAD_MIN_CONF`, `STRICT_MIN_CONF` all cleared). What marks it
  as background audio is the leading **`[unk]`**: non-wake sound captured in the same finalized window. Across
  every record, a genuine close-mic wake decodes as a bare `[hey(95–100) jarvis(97–100)]` with **no `[unk]`**,
  while all three known FPs carried one. Fixed by rejecting **any `[unk]` token** (`WakeMatcher.UNK_TOKEN`) on
  strict routes — it subsumes the old "long `[unk]`-laden decode" case and costs no recall (the no-`[unk]`
  gate would have blocked all 3 logged FPs and none of the genuine fires). This is the precision gate to
  reach for on a background-audio FP — not the keyword floor.

> **Not a tuning dial: the first phrase after idle.** A "first 'hey jarvis' misses, second one works"
> symptom is a startup / capture-timing issue, **not** an accuracy/threshold one — don't chase it with the
> dials below. It is handled in the capture/recognition layer: frames captured while the Vosk model is
> still loading are buffered (`PcmRingBuffer`) and replayed once it's ready, and the Kaldi decoding-graph
> warm-up runs after every `WakeRecognizer.reset()`. If a first-utterance miss shows up, look there (and at
> `flushing N pre-ready frame(s)` in `debug.txt`), not at `WakeMatcher`.

## The dials, and how to turn them (no model work)

All in `WakeMatcher` / per-`WakeWord`:

- **`WakeWord.minConf`** — per-word confidence floor. The built-in `jarvis` **and** `alexa` defaults use
  the strict `WakeMatcher.STRICT_MIN_CONF` (`0.60`) — above `BASELINE_CONF`, so a strict route turns the
  lenient clean-phrase bypass **off** and applies all three gates: floor + clean-phrase + no-rival-keyword.
  On its own the floor is a *weak* guard (the live-stream FP scored 0.83, above any floor we'd keep, while
  real wakes score ~1.00), so the clean-phrase and cross-keyword gates do the heavy lifting — don't reach
  for the floor to fix a background-audio FP. A plugin sets it via the `com.portal.wake.min_confidence`
  meta-data on its receiver.
- **`WakeMatcher.BASELINE_CONF`** (0.50) — at/below this a word is "lenient": a clean short phrase fires
  even at low confidence (recall insurance). With the lgraph model this bypass is **dormant** (real jarvis
  wakes decode at ≥0.92). The built-in routes deliberately sit **above** it (see `STRICT_MIN_CONF`); it
  remains the default floor for plugin words that omit a `minConf`.
- **`WakeMatcher.CLEAN_PHRASE_MAX_WORDS`** (3) — the max words for a "clean phrase". It gates the lenient
  recall bypass **and** (since the live-stream FP) is a hard requirement for strict hand-off routes — a
  longer decode never fires jarvis/alexa, even over the floor. Lower it to be stricter; raise it to catch
  wakes spoken inside a few filler words, at some precision cost.
- **`WakeMatcher.LEAD_ALIASES`** — accepted recognizer mishearings per declared lead word (e.g. `hey` →
  **`hey`/`hay` only**). A lead with no entry matches itself only. Kept tight: `a`, `hi`, and `he` were
  dropped from `hey` — too-common lead-ins (filler / mis-decodes) that widened false fires. Add a variant
  back only if the logs show a real wake lost because the lead was misheard.
- **`WakeMatcher.LEAD_MIN_CONF`** (0.80) — the confidence the preceding lead must clear on a **strict**
  route. Real wakes decode "hey" at 0.96–1.00, so this is near-free on recall; it blocks a clean short
  phrase with an under-confident "hey" (observed: 0.66 from phone-call audio over a speaker). Lenient
  routes ignore it (present-only). Raise it toward 0.9 to be stricter on noisy "hey"s; if the logs show a
  genuine wake lost with `hey(0.8x)`, lower it — don't drop below the observed FP (0.66).
- **`WakeMatcher.UNK_TOKEN`** (`[unk]`) — Vosk's "unknown" escape token. On a **strict** route, **any**
  `[unk]` in the decode is contamination and rejects the fire outright (a genuine close-mic wake never
  decodes one). This is a binary gate, not a tunable threshold — it's the strongest precision lever for a
  background-audio FP and catches what no confidence floor can (the observed FP scored `hey(99) jarvis(99)`).
  The only recall cost is a wake Vosk pads with an `[unk]` (e.g. a noisy lead-in), which the logs show is
  rare; if one is lost, it surfaces as a `near-miss [...] rejected: contaminated ('[unk]' in decode)` line.

Workflow: run on device, read `debug.txt`, and adjust these — every change is covered by
`WakeMatcherTest`, so re-run the unit tests after editing.

## Heavier levers (only if the dials above aren't enough)

In rough order of effort, per the Vosk adaptation page:

1. **Tighten the grammar further / rebuild `Gr.fst`.** Compile a custom `Gr.fst` from a tiny corpus
   (OpenFST/OpenGRM in Kaldi) to bias even harder toward the wake phrases.
2. **Use a larger general model.** More accurate but a bigger APK and more CPU per frame — watch
   latency on the Portal's older SoC.
3. **Acoustic finetuning.** ~1 hour of in-room audio (the actual users + the actual Portal mic, at
   real distance) in Kaldi format, then a finetune script (`finetune_tdnn_1a.sh`-style). Highest
   accuracy for *this* device/room, highest effort — a later experiment, captured here so it isn't
   forgotten.
