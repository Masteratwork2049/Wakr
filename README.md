# Wakr

Wakr is a Material 3 Expressive alarm and clock app for Android. It builds on [Expressive Clock](https://github.com/samuelbeckman/expressive-clock) and adds a Morning tab with upcoming alarms and a seven-day schedule.

## Features

- **Alarms:** Tap an alarm to schedule a date, choose a time zone, pause a repeating alarm, or enable **Auto-delete after dismissal** for a one-time alarm. Temporary alarms stay on the grid through snoozes.
- **Quick alarms:** Hold the **+** button on the Alarms tab for **Alarm in…**. Hold an alarm card for duplication, skipping, locking, selection, or deletion.
- **Next alarm:** The Alarms tab shows the next active alarm and offers **Pause all alarms** and **Resume alarms**.
- **Timers:** Create and manage countdown timers.
- **Stopwatch:** Track elapsed time.
- **Analog clock widget:** A configurable home-screen clock widget.
- **Expressive design:** Material 3 Expressive styling, Google Sans Flex typography and carefully crafted animations.

Features and behaviour may evolve as the project develops.

## Building from source

Wakr is built with Kotlin and Jetpack Compose. Its Android package is `com.wakr.app`. Android installs it separately from the earlier Expressive Wake and Wake Form previews. Any alarms stay with the app that created them.

1. Clone this repository.
2. Open the project in Android Studio.
3. Allow Gradle to sync and download the required dependencies.
4. Build and run the app on a compatible Android device or emulator.

You can also use the included Gradle wrapper to build the project from the command line.

The [Phase 2 specification](docs/PHASE_2.md) describes scheduling, temporary alarms, and the shared design requirements.

## Acknowledgements

Wakr preserves the licensing and attribution of [Expressive Clock](https://github.com/samuelbeckman/expressive-clock) and the open-source work of [Rayane Bouafia](https://github.com/rbouaf) and the original [Material Clock repository](https://github.com/rbouaf/material-clock).

## Licence

The project's original source code is licensed under the MIT Licence. See [LICENSE](LICENSE) for the full licence text.

Bundled font files are licensed separately under the SIL Open Font License 1.1. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the files in the `licenses/` directory for details.

Google, Google Sans, Material, Android and Pixel are trademarks of Google LLC. Wakr is an independent project and is not affiliated with, sponsored by or endorsed by Google.
