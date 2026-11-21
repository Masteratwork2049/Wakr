# Phase 3 · Wake-up missions and alarm protection

Open **Edit Alarm → Wake-up missions**. The pill starts collapsed. Expand it for difficulty and the ordered mission list. Choose **Add mission** to configure one. **Try it** uses a practice session. The move controls reorder the chain, and a removed mission can be restored with **Undo**.

| Difficulty | Dismissal and snooze | Protection |
| --- | --- | --- |
| Easy | Direct dismissal and unlimited snooze | Uses the app silence default |
| Normal | Complete the chain with up to three snoozes | Rings until completed or snoozed |
| Hard | Complete the chain with snooze off | One still-awake check and up to three reboots |
| No Mercy | Complete the chain with snooze off | Two still-awake checks, up to five reboots, and a one-hour pre-ring lock |
| Custom | Individual rules are configurable | Individual protections are configurable |

Existing alarms keep their stored behavior. Adding a first mission to an Easy alarm selects Normal. Selecting another preset applies its difficulty rules. Math, Memory, Focus Tap, Puzzle, Typing, QR/barcode and NFC work offline. The barcode reader uses CameraX and ZXing. If scanning hardware or permission is unavailable during ringing, a three-problem math challenge gives the user another way to complete the mission. Registration and practice verify the actual scan.

**Wake-up options** holds per-alarm sound, volume, fade-in, vibration, flash, voice, snooze, reboot, and still-awake settings. Surprise me rotates built-in tones by calendar day. Random from my music uses Android's file picker and keeps read access to the chosen tracks or folder. Voice uses an installed offline voice when available.

Pre-ring protection applies to edit, toggle, skip, delete, and group pause. A solved math challenge authorizes the specified upcoming occurrence for two minutes. The ringing activity and notification use the same dismissal and snooze rules. Reboot recovery retains completed missions. The current challenge can restart after a reboot, and private scan targets become available after first unlock. Relaunch uses Android's alarm notification and full-screen intent, subject to device permissions.

Still-awake prompts appear as notifications. Missing the 60-second confirmation starts a full-volume retry. A temporary alarm can disappear after dismissal while the pending check retains a private snapshot. Wake history records the outcome. The slider, Mute, and Emergency remain in the [final ringing-screen pass](PHASE_2.md#final-ringing-screen-pass).
