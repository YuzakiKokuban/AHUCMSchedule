# Today widget implementation

Spec: ../specs/2026-10-24-today-widget-design.md
Goal: Native Android home-screen widget using existing per-account timetable cache.

- [x] Core day projection: tests first, choose actual current-day snapshot, split course runs, derive interval-aware status and next change.
- [x] Android RemoteViews: resizeable collection, modern API31+ direct items and API26–30 service, dynamic/day/night resources and empty/source states.
- [x] Refresh lifecycle: async broadcast processing, next-boundary inexact alarm, account-checked publication, integration callbacks and add-widget entry.
- [x] Verification: core 49 passed / 3 opt-in skipped, desktop 27 passed, Android unit 10 passed, Node 22 passed; Android build and instrumentation APK compile; Lint zero errors. Independent review fixes confirmed for legacy account adapter identity and overflow row recycling.
- [ ] Device acceptance: no connected device. Dedicated headless emulator could not boot without hardware acceleration; software fallback also remained offline and was stopped. Instrumentation source covers native collection/empty rendering in both themes, overflow reapply and isolated account cache transitions, but has not executed on a device. Real launcher pin/resize/click/background refresh remains unverified.
- [ ] Main/release workflow: version 0.6.0 / Android code 11; verify CI packages, signing and checksums before publishing.

Review focus: historical browsing replacing today's data, account switches during rendering, midnight without a foreground app, missing periods, collection refresh API differences, and accidental inclusion of private caches in release assets.
