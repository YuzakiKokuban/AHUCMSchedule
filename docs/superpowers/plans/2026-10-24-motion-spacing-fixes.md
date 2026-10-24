# Motion and spacing fixes

User-reported defects: missing calendar month animation, touching semester options, touching update check/download buttons, incorrect page transition direction.

- Calendar month content was replaced directly. It now uses AnimatedContent with the rendered month passed into each outgoing/incoming grid.
- Destination IDs are 0,3,1,2 in visible navigation order; numeric comparison was incorrect. Direction now derives from that actual navigation list.
- Page, week and month share a bounded slide/fade transition. Outer clipping keeps departing content within its content area.
- Semester options and conditional update actions have 12dp gaps.
- Three UI regressions first failed, then passed: opposite calendar month directions, calendar→notices forward and reverse direction, and semester touch-surface spacing.
- Added interruption coverage: rapid month reversal, resize during transition, return to current month and rapid destination switches settle without duplicate month content or lost selection.
- Independent read-only review found no important issues; its suggested interruption verification was added.
- Local checks passed using the existing JDK 25 override: core 47 tests (3 optional skipped), desktop 27 tests, Android 10 tests, plus Android compilation. Static update-button spacing was checked in source and compiled; it was not verified on a device.

Release target: v0.5.1, Android versionCode 10, direct main workflow as requested by the user.
