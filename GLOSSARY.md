# Portal-Wake

An always-on, headless wake-word listener for the Portal. It holds the device's single mic slot and lends it to
other apps when a wake word is heard, taking it back once they are done.

## Wake words

**Wake word**:
A spoken phrase that Portal-Wake listens for: a keyword such as "jarvis", usually led by a word such as
"hey".
_Avoid_: hotword, trigger phrase, keyword (the keyword is only the last word of the phrase)

**Wake plugin**:
An installed app that advertises its own wake word to Portal-Wake through the published wake contract, and
becomes the consumer when that word is heard.
_Avoid_: handler app, extension, integration

**Wake declaration**:
The wake word a wake plugin declares on its receiver as meta-data, a phrase plus an optional id and min
confidence. Portal-Wake checks it and reports each mistake to `debug.txt`; only a missing phrase rejects it.
In code: `WakeDeclaration`.
_Avoid_: spec, manifest entry

**Wake set**:
The wake words currently in force: those discovered from installed wake plugins, plus a built-in default for
each word no plugin claims. A plugin always wins over a built-in default for the same word. Never empty: "hey
jarvis" is always in it, from a plugin or as a default.
In code: `WakeSet`.
_Avoid_: wake list, registry (the registry discovers the wake set; it is not the set)

**Wake target**:
A wake word in the wake set, paired with its consumer if it has one. A wake word with no consumer installed is
still listened for, but hearing it only logs: there is no handoff.
In code: `WakeTarget`, its consumer a `Consumer` (a wake plugin, or the native Alexa client).
_Avoid_: route, handler

## Mic arbitration

**Consumer**:
Any other app recording from the mic while Portal-Wake has stood down: a wake plugin after a handoff, the native
Alexa client after a handoff of "hey alexa", or an app that took the mic by itself. A voice call is not a consumer;
it is a separate reason to stand down.
In code, a wake target's `Consumer` names the app a handoff goes to; that app becomes the consumer once it records.
_Avoid_: client, foreigner

**Handoff**:
Portal-Wake standing down because one of its wake words was heard, and then waking that word's consumer so it
takes the mic.
_Avoid_: dispatch, transfer, yield

**Stand down**:
Portal-Wake pausing its own capture so someone else can have the mic, for a handoff, a voice call, or a
detection stand-down. It never interrupts whoever has the mic, and comes back only when the reason has passed:
by reclaim, or when the call ends.
_Avoid_: back off, release, yield

**Detection stand-down**:
A stand-down caused by seeing a consumer already recording (mic contention, or a capture loss to another
recorder) rather than by a wake word. It goes straight to BORROWED.
_Avoid_: forced handoff, auto-yield

**Mic contention**:
Another app's recording session appearing while Portal-Wake is capturing, spotted without any signal or
cooperation from that app (a foreground tap, an uncooperative app). It causes a detection stand-down.
_Avoid_: conflict, collision, mic stealing

**BORROWED**:
The state where a consumer holds the mic and Portal-Wake is waiting for it to stop recording. A handoff reaches
BORROWED once the consumer is seen recording; a detection stand-down starts there, because the consumer is
already recording.
_Avoid_: lent, handed off (as a state), waiting

**Reclaim**:
Portal-Wake taking the mic back once it detects that the consumer has stopped recording and no voice call
holds the mic, with no done signal and no timeout. After a detection stand-down the release is confirmed
before reclaiming.
_Avoid_: resume, timeout

**Capture loss**:
The wake mic engine reporting that capture is not running, or is running but delivers only digital silence, while
Portal-Wake wants it running (defined in portal-commons' `GLOSSARY.md`). If another app is recording, it is a detection stand-down; otherwise capture is
retried on the next poll, backing off (doubling, up to a cap) while losses keep coming.
_Avoid_: lost capture, capture error, engine stopped, mic failure
