# Crew Schedule

A lightweight Android app for one subcontractor crew moving among remodeling projects.

## UX

- Week Mode is the default and always opens the current Monday-Friday workweek.
- Projects are alphabetized and editable/deletable.
- Each project/day cycles `✓` → `X` → `?`.
- Calendar Mode is a true five-column Monday-Friday calendar. It shows only projects scheduled with `✓`; green cards contain no extra checkmark.
- Offline edits are saved immediately on-device.

## Supabase

Run `supabase.sql` in Supabase SQL Editor. The app reads `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SCHEDULE_ID` from Gradle project properties or environment variables. `SCHEDULE_ID` defaults to `crew-schedule-shared`.

For GitHub Actions, add repository secrets named `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SCHEDULE_ID` if shared cloud sync is desired in the built APK.

## Build

The project uses Android Gradle Plugin 9.4, Gradle 9.6, Kotlin 2.4.10, and the September 2026 Compose BOM. GitHub Actions builds a debug APK and uploads it as `crew-schedule-debug-apk`.

<!-- Sync configuration updated; rebuild triggered. -->
