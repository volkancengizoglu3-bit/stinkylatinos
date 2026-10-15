# FarmBotInGen base behavior (inherited)

## Farm spot
- Open the in-game chat and tap the user-configured farm coordinate to travel to the saved farm spot.
- Do not use a map-list teleport flow.
- Chat-button tap coordinates and farm-target tap coordinates are separately configurable.

## Buff mode
- Buff mode is optional and controlled by the user.
- The user configures independent chat-target coordinates for the buff location.
- The intended trigger is ATK **or** DEF falling below its configured threshold.
- When buff mode is enabled and either threshold is crossed, travel to the configured buff location.
- The buff selection/action is assumed to be ready in-game; the bot only needs to reach the configured buff location. Do not add an NPC interaction or extra menu selection unless requested later.
- After reaching the buff location, return to the saved farm spot using the chat coordinate flow.

## Death recovery
- Detect death/respawn using confirmed on-screen signals.
- After respawn, return to the saved farm spot using the chat coordinate flow.
- If buffing is also due, handle recovery and buff state without repeatedly triggering conflicting travel actions.

## Coordinate format
- Coordinates are normalized from 0 to 1000 relative to the display dimensions.
- Chat-button coordinates represent the point that opens the in-game chat.
- Farm/buff coordinates represent the respective destination entry/coordinate to tap after chat opens.

## Current implementation status
- Settings screen and manual test buttons are present.
- Tap sequence: tap chat button, wait 500 ms, tap configured target, then wait 1.5 seconds.
- Automatic ATK/DEF reading, automatic buff trigger, death/respawn detection, and automatic return logic are not implemented yet.
- Coordinates must be configured and manually tested in the game before relying on them.


## Farm activation controls
- After returning to the farm location, the intended sequence is to tap the configured auto-attack button and then the configured protective-shield button.
- Auto-attack and protective-shield screen tap points are configurable separately from chat, farm, buff, and Free Revival points.
- The intended worker runs continuously until the user presses Stop: check ATK/DEF, visit the buff target when either configured threshold is not met, verify the thresholds, return to farm, enable auto-attack and shield, and repeat after buff expiry or death.
- Important implementation note: OCR-based death detection exists, but reliable ATK/DEF value extraction and the complete persistent loop still need implementation and device testing. Do not represent those as already working until verified.
