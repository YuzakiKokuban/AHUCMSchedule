# Official Academic Calendar Implementation Plan

Goal: Add the school's read-only academic calendar to shared Android/Windows UI.
Spec: docs/superpowers/specs/2026-10-24-academic-calendar-design.md
Architecture: Independent validated model/parser/store in core; authenticated SchoolClient access; focused Compose panel and account-bound state.
Tech stack: existing Kotlin, kotlinx.serialization, Jsoup, Compose and miuix; no new dependencies.

## Constraints and review focus

Keep timetable/calendar-export behavior unchanged; never derive holiday or course changes from school date status. No credentials in files. Reject malformed or partial calendars without destroying cached data. Protect account/term ownership across async operations. School-listed terms may have no published calendar. Keep layout readable on phone widths and offline.

## Task 1: Validated parser and read-only client

- [x] Add anonymous tests in core AcademicCalendarTest for school term options and nested month/row/day JSON (fullText/date, month, value/day, zc/week and bz/note).
- [x] Run focused test and observe RED, implement AcademicCalendar.kt plus SchoolClient fetchAcademicCalendarTerms/fetchAcademicCalendar. Use school-listed term only, no write endpoints. Verify GREEN.
- [x] Test malformed/empty/duplicate/incomplete dates, cross-year dates, invalid weekday columns and API failure/login HTML.

## Task 2: Atomic per-account multi-term cache

- [x] Add AcademicCalendarStoreTest for round trip, multi-term retention, save failure/corrupt cache and AccountStorage isolation. Observe RED.
- [x] Implement AcademicCalendarStore and AccountStorage.academicCalendar; only publish snapshots after atomic save. Verify GREEN.

## Task 3: Shared calendar page

- [x] Add Compose tests for calendar entry, offline dates/week/note display and switching cached terms. Observe RED.
- [x] Add focused AcademicCalendarPanel with month navigation, today highlight, term picker, date detail, safe refresh feedback, and offline cache timestamp.
- [x] Expose per-account store through PlatformActions in Android/Windows. Bind async work to store/account; session-serialize downloads; discard stale/canceled UI results. Verify GREEN.

## Task 4: Verification and review

- [x] Run core:test, composeApp:desktopTest, androidApp:compileDebugKotlin and existing Node tests.
- [x] Verify actual published school calendar read-only, without persisting credentials or personal data; update README and execution ledger with evidence and limitations.
- [x] Review implementation for correctness, account isolation, error retention and phone layout, then address material findings.

## Completion record (2026-10-24)

All four tasks completed. Implementation and tests are in the current `codex/academic-calendar` working tree.

- Parser/client: RED (missing symbols), then GREEN; anonymous cross-month/year and malformed response cases covered.
- Store: RED, then GREEN; per-account multi-term persistence, corruption, failed writes and commit cancellation covered.
- UI/state: RED, then GREEN; 390dp phone rendering inspected, offline selection and in-app connection navigation covered, unpublished term and canceled/changed-owner requests covered.
- Full checks: core 37 tests (34 passed, 3 opt-in tests skipped), Compose desktop 16 passed; Android `compileDebugKotlin` passed; existing Node tests 27 passed; `git diff --check` passed.
- Local JVM verification used existing `.local/update-toolchain.init.gradle` with `D:/AndroidStudio/jbr` (JDK 25), because this machine has no detected JDK 21. Committed toolchain declarations were not changed.
- Final opt-in real SchoolClient verification passed separately: term 2026-2027-1, 140 dates spanning 2026-08-31 to 2027-01-17, 13 annotated dates, successful cache round trip. Credentials were only process environment input.
- Independent review found truncated-range replacement and a pre-commit cancellation window. Three regression tests first failed, then passed after full-week/range validation and pre-commit ownership/cancellation checks were added.
- Ruling: reject reduced coverage for an already cached term; the endpoint supplies no independent bounds, so this conservatively also rejects a legitimate official shortening and asks the user to verify it. First-sync whole-week suffix truncation cannot be established from this endpoint alone.
- Android device installation/touch behavior and enlarged-font layout have not been verified. The feature was not published or installed on the user's device.

The prior five proposed improvements remain separate pending work; this implementation covers the requested calendar only.
