# Boss Hunter for MU Immortal

A separate Android application based on the FarmBotInGen interface, with a gold/yellow theme and an optional buff protocol.

## What is included
- Separate Android package: `com.farmbotingen.bosshunter` (can be installed alongside FarmBotInGen).
- Golden Monster hunt flow: open map, scan for compact yellow markers, select a target, close the map after a movement delay, start auto-attack, and look for a pale-gray minimap boss marker.
- Boss death is accepted only after three consecutive gray-marker observations.
- Random teleport is tapped when no yellow candidate is detected; the map is expected to remain open.
- Optional buff protocol: when both ATK and DEF are readable and either is below its configured threshold, visit the saved buff coordinate. The bot resumes scanning only after both values meet their configured minimums.
- Gold-themed settings and floating controls.

## First-time setup
1. Install the APK and grant **Display over other apps**.
2. Enable **Boss Hunter Automation** under Android Accessibility settings.
3. Open MU Immortal and configure the tap points in Boss Hunter: Attack, Shield, Free Revival, Map Open, Map Close, Random, and Boss Icon (minimap).
4. If buffing is enabled, also configure Chat Open, Chat Close, and Buff Target; set ATK/DEF thresholds.
5. Keep the game UI scale and orientation consistent with the coordinates you selected.

## Important limitations
Boss-marker discovery currently uses color-cluster heuristics, not a trained MU Immortal image model. It can miss a marker or confuse other yellow UI elements, depending on the map and screen scale. The movement-to-target phase uses a fixed delay rather than verified player-marker overlap. Test in a safe area first and stop the automation if it selects an unrelated point. ATK/DEF OCR also depends on the game's current text rendering and visibility.

The successful GitHub Actions build verifies compilation and packaging only; it does not prove the game-specific visual automation works on every device or map.
