# Boss Hunter implementation plan

## Agreed behavior
1. Operate on the currently active map; do not use the old map-list teleport flow.
2. Use the minimap's bottom-right Random button. The map stays open after each random teleport.
3. After each teleport, inspect the open map for yellow-skull boss markers inside the configured scan radius.
4. The user selects a boss location on the map; navigate toward it, and when the player marker overlaps the target, close the map and start auto-attack.
5. While attacking, monitor the boss marker in the top-right minimap. Yellow skull means alive; the same icon turning pale gray means dead.
6. Confirm death over multiple consecutive observations before moving on, reducing false positives caused by animation or occlusion.
7. After a confirmed kill, check ATK and DEF against user-configured minimums. If buff support is enabled and either stat is below its threshold, open chat, tap the configured buff coordinate, travel there, wait for stats to recover, then resume hunting. If buff support is disabled, skip the buff trip and continue hunting.
8. If no eligible boss is visible around the current random position, use Random again and scan.

## Implementation phases
- Phase 1: copy the Android app/build/accessibility foundation from FarmBotInGen into this separate repository.
- Phase 2: isolate boss hunting into a state machine: SCANNING, TARGET_SELECTED, MOVING_TO_BOSS, ATTACKING, CHECKING_STATS, GOING_TO_BUFF, WAITING_FOR_BUFF, RANDOM_TELEPORT, and PAUSED/ERROR.
- Phase 3: add conservative OCR/image checks for yellow/gray skulls and marker overlap; keep thresholds and scan radius configurable.
- Phase 4: build APK via GitHub Actions and test each transition on the Lenovo Tab 11.

## Reliability requirements
- Never assume a boss is dead from a single uncertain frame.
- If screenshot capture fails or the target marker cannot be identified, pause or rescan rather than issuing uncontrolled taps.
- Keep a visible pause/stop control and preserve the accessibility-service permission flow.
- Screenshots are not required to begin coding; visual thresholds may need tuning during testing.
