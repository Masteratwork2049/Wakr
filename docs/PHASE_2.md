# Phase 2 · Alarm engine and scheduling

Upgrade alarm scheduling, time and time-zone change handling, reboot recovery, and the ringing flow. Add the Next Alarm card, low-battery feedback, Pause all, the Alarm in… picker, and typed time entry. Add scheduling, pause-until, time zone, flash, missed-alarm repeats, lock, and duplication to the existing Edit alarm sheet.

## Temporary alarms

- Add an **Auto-delete after dismissal** switch for one-time alarms in the existing **Edit alarm** sheet. Default it to off so regular one-time alarms remain available for reuse.
- Show a **Temporary** badge on the Alarms tab whenever the switch is on. Use the existing alarm-card typography and theme colors, keep the time and label readable, and include the badge in the card’s accessibility description.
- Keep the alarm and badge visible while it is scheduled, ringing, or snoozed. After final dismissal from the ringing screen or notification, remove the alarm and cancel its pending callbacks. Skipping an occurrence and manually turning an alarm off do not count as dismissal.
- Verify that repeated delivery of a dismissal action cannot delete another alarm, and that dismissal after snoozing removes a temporary alarm only once.

## Design and motion across the app

Use the existing Material 3 Expressive theme, typography, shapes, colors, and interaction components on every new or changed surface in this and later phases. Reuse the app’s spring transitions, rolling time readouts, touch feedback, and dock behavior where those interactions already exist. Check light, dark, and AMOLED colors, large text, TalkBack, and Android’s animation settings. Check frame pacing on device during tab navigation and while using alarm controls.

This plan extends the [original audit](EXPRESSIVE_WAKE_AUDIT.txt).
