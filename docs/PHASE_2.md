# Phase 2 · Alarm engine and scheduling

Upgrade alarm scheduling, time and time-zone change handling, reboot recovery, and the ringing flow. Keep the Next alarm card, low-battery feedback, and Pause all on Morning. Keep elastic press feedback on the card without a glow. Add the Alarm in… picker, and typed time entry. Add scheduling, pause-until, time zone, flash, missed-alarm repeats, lock, and duplication to the existing Edit alarm sheet.

## Temporary alarms

- Add an **Auto-delete after dismissal** switch for one-time alarms in the existing **Edit alarm** sheet. Default it to off so regular one-time alarms remain available for reuse.
- Show a **Temporary** badge on the Alarms tab whenever the switch is on. Use the existing alarm-card typography and theme colors, keep the time and label readable, and include the badge in the card’s accessibility description.
- Keep the alarm and badge visible while it is scheduled, ringing, or snoozed. After final dismissal from the ringing screen or notification, remove the alarm and cancel its pending callbacks. Skipping an occurrence and manually turning an alarm off do not count as dismissal.
- Verify that repeated delivery of a dismissal action cannot delete another alarm, and that dismissal after snoozing removes a temporary alarm only once.

## Alarm editor, layouts, and defaults

- Show **Type time**, **Alarm in…** for new alarms, and the scheduling controls inline with Sound and Vibration in the editor. Make the full width of the **Silence after** and **Repeat missed alarm** pickers swipeable.
- Place a **Duplicate alarm** pill beneath the alarm name field. Require pressing and holding **Unlock** before editing a locked alarm.
- Show saved names on alarm cards above status tags. Offer persistent **Grid** and **List** layouts on Alarms and in Settings. Long-press an alarm to select it immediately. Offer **Select all** and **Duplicate** icons in the selection toolbar, with **Duplicate** immediately before **Delete**. Keep locked alarms protected in bulk deletion.
- Show the existing default silence duration and a new default for missed-alarm repeats in Alarm Settings. New alarms start with the chosen retry count and use the app silence duration unless overridden.

## Phase 2 UI refinements

- Extend **Duplicate alarm** to the full width of the editor beneath **Alarm Name** and add a copy icon with two overlapping rounded rectangles on the left. The editor and the selection toolbar preserve the original alarm’s on/off state. The copy has its own identity and schedule.
- In List view, align the time on the left, place AM or PM immediately after it in 12-hour mode, and put the alarm name to the right. Make list rows about 30% taller and align the weekday letters to the first and last time digits. In Grid view, center the name and any status tag in the space above the time.
- Use the same layout for all alarm option rows, with an on/off switch on the right for **Flash** and **Auto-delete after dismissal**. Place **Auto-delete after dismissal** directly below **Vibration** and **Flash** directly above **Lock alarm** when those options are available. Use a pill for the **Grid view** and **List view** control beside Settings.
- Tap the selected hour or minute to open the numeric keyboard. Keep swipe-to-scroll on both wheels and validate the number against the active time format.

## Phase 3 follow-up · Wake-up missions and pre-ring lock

- Require a short math problem or puzzle before turning off or changing a protected alarm during its pre-ring lock window. Apply the same protection to controls that could otherwise prevent the alarm from ringing, including skip, delete, and bulk pause.

## Final ringing-screen pass

Implemented in the [Phase 3 interface update](PHASE_3.md).

- Give the ringing screen a two-way slider with **Snooze · X min** visible on the left and **Dismiss** visible on the right before dragging. Read the snooze duration from Settings. Sliding left snoozes and sliding right dismisses. Provide equivalent accessibility actions and retain Wakr’s existing motion and touch feedback.
- Add **Mute** for the current ringing occurrence. Muting silences its sound while leaving the alarm and its snooze and dismiss controls active.
- Add a separate **Emergency** hold-to-stop action for a ringing alarm with an active mission. Record its use as an emergency stop in wake history. Use the mission runner’s dismissal rules.

## Design and motion across the app

Use the existing Material 3 Expressive theme, typography, shapes, colors, and interaction components on every new or changed surface in this and later phases. Reuse the app’s spring transitions, rolling time readouts, touch feedback, and dock behavior where those interactions already exist. Check light, dark, and AMOLED colors, large text, TalkBack, and Android’s animation settings. Check frame pacing on device during tab navigation and while using alarm controls.

This plan extends the [original audit](EXPRESSIVE_WAKE_AUDIT.txt).
