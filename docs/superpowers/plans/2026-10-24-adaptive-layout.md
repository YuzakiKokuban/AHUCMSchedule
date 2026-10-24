# Adaptive layout and shared semester implementation

Spec: ../specs/2026-10-24-adaptive-layout-design.md
Goal: desktop workspace, touch-friendly Android landscape/tablet and user-specified independent calendar with unified semester/sync controls.
No new dependencies or changes to school write endpoints.

- [x] Core: explicit selected-term timetable download, per-term cache access, account-scoped persisted selection. TDD core coverage and full core tests. Assigned core-only implementation.
- [x] Shared UI: layout policy, side navigation/rail/bottom navigation, shared period controls and synchronization gate. Test compact, short landscape, desktop and tablet policies.
- [x] Wire shared semester selection and combined sync above destinations; remove calendar-specific network controls; stateful navigation survives layout changes. Test independent calendar, shared selected term, no stale data and top-sync requests.
- [x] Adapt timetable/details, calendar/details, notices and connection form for available width/height. Capture phone, desktop, landscape and tablet previews with anonymous data.
- [x] Run core/desktop suites, Android compile and Node regressions, review changed implementation and fix material findings. Record actual evidence and device limitations.

Review focus: old data under new semester; partial failure of either data source; history sync affecting external integrations; resize/rotate resetting selected data; toolbar/keyboard/font-scale clipping.

## Verification and review record

2026-10-24, implementation branch `codex/academic-calendar`.

- Final Gradle command: `gradlew.bat -I .local/update-toolchain.init.gradle -PandroidEnabled=true :core:test :composeApp:desktopTest :androidApp:compileDebugKotlin --console=plain` — successful. Core 47 tests (44 passed, 3 optional skipped), Compose desktop 23 passed, Android compile passed.
- Existing local toolchain override uses JDK 25 from `D:/AndroidStudio/jbr`; repository JDK declarations remain unchanged.
- Optional authorized `SchoolLiveTest` and `AcademicCalendarLiveTest` separately passed against the actual school, without storing credentials in sources or fixtures.
- Existing Node regression suite: 27 passed. `git diff --check` passed (only local CRLF normalization notices).
- UI matrix covers desktop 1180×820, narrow Windows 760×650, Android 844×390 and 390×844, tablet 1280×800 and 800×1280, including 1.3× font scale. Verified course selection, chosen week and month survive reflow and destination changes. Inspected generated anonymous screenshots in `composeApp/build/preview-*.png`.
- Independent review identified five material issues: semester persistence crossing account continuations; historical Android management substituting current term; cancellation during timetable commit; uncaught preference persistence failure after file import; rail outside system safe insets. All addressed, with core commit cancellation regressions, account persistence race regression, failed-import-preference UI regression, current-only management UI regression, and safeDrawing inset handling.
- Removed obsolete calendar-specific network controller and its superseded tests; unified sync now runs independently of the calendar destination, and tests cover success plus either data source failing without losing the other cache.
- Ruling: Android system calendar/XiaoAi auto-management is restricted to confirmed school-current semester; history is readable but does not silently replace active external schedules. Windows ICS exports the displayed snapshot.
- Ruling: available school semesters and cached semesters form a union; uncached/unpublished data produces a term-specific empty/error state. Explicit selection persists per account and triggers common sync when authenticated.
- Physical Android rotation, cutout/gesture interaction, accessibility traversal and IME behavior have not been device-verified; viewport/semantic verification and Android compilation do not constitute device acceptance. No release, push or device installation was performed.
