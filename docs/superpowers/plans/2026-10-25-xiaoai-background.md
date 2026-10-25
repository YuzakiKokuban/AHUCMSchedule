# XiaoAi background delivery implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task.

**Goal:** After this app saves the school-current timetable, update XiaoAi immediately without opening any activity or requiring its timetable page.

**Architecture:** Keep the validated JS sync driver and durable provider checkpoints. Hook the existing exported XiaoAi settings search provider as a narrow authenticated wake endpoint. Execute our bundled driver in an isolated, unattached WebView, using native HTTPS and XiaoAi's own authorization helper inside XiaoAi. Credentials never cross back to the timetable app. Both manual and automatic submission use this channel.

**Stack:** Kotlin, modern libxposed API 102, Android WebView, HttpsURLConnection, existing Node driver tests.

- [x] Prove background authentication and API read without opening UI. Verified on installed XiaoAi 8.2.65 using its native authorization helper and authenticated tables endpoint.
- [x] Add regression tests for wake hook registration and absence of activity launches; observe failure before implementation.
- [x] Implement authenticated provider wake, isolated driver execution, bounded native transport, heartbeat and cleanup. Preserve cancellation and readback checks.
- [x] Handle a newer timetable arriving during an active job by deferring the newest payload until the current claim finishes.
- [x] Run unit, JS and device tests. Install signed test build, restart host once to load updated hooks, then test with the XiaoAi page closed and its process stopped. Independently read back changed and restored course data and check no activity was opened.

## Verification matrix

- A saved unchanged revision is verified and missing courses repaired.
- A changed revision updates the bound table without showing XiaoAi UI.
- A newer pending revision is retained and supersedes an older pending payload.
- Only the timetable application's UID can invoke the wake protocol; unrelated provider calls proceed unchanged.
- Network, authorization and rendering failures remain pending or failed, never successful without readback.
- Cancellation, old receipt fencing, colors, settings, table ownership and unrelated tables retain existing behavior.

The native helper is private to this installed host version; fail visibly if it becomes unavailable rather than launching UI or exporting authentication data.


## Release review follow-up

- Dispatch integrations immediately using the current semester identified by the timetable endpoint, before calendar and notice requests.
- Fence startup with a generation ticket and an atomic STARTING-to-RUNNING transition; late deadlines and callbacks cannot retire a successor.
- Keep watchdog scheduling independent of heartbeat Binder calls, network requests and final receipts; limit heartbeat work to one in flight and disconnect retired connections.
- Handle background renderer loss without crashing the host.
- Added opt-in on-device cold-start, consecutive-change and guaranteed-restore test. Normal device tests isolate files and notifications from the real queue.
- Independent review approved the fixes; release verification is recorded in the v0.6.8 checklist.
