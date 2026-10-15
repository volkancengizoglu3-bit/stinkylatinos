package com.farmbotingen

/**
 * Deterministic decision model for Boss Hunter.
 *
 * UI/accessibility code feeds observations into this class; it never performs taps itself.
 * Keeping decisions pure makes it possible to test state transitions without a game session.
 */
class BossHunterEngine(
    private val minimumAtk: Int,
    private val minimumDef: Int,
    private val buffEnabled: Boolean,
    private val requiredGrayFrames: Int = 3
) {
    enum class State {
        STOPPED,
        SCANNING,
        TARGET_SELECTED,
        MOVING_TO_BOSS,
        ATTACKING,
        CHECKING_STATS,
        GOING_TO_BUFF,
        WAITING_FOR_BUFF,
        RANDOM_TELEPORT,
        PAUSED
    }

    enum class BossMarker { YELLOW_SKULL, PALE_GRAY_SKULL, UNKNOWN }

    enum class Action {
        NONE,
        SCAN_MAP,
        SELECT_TARGET,
        MOVE_TO_TARGET,
        CLOSE_MAP_AND_ATTACK,
        KEEP_ATTACKING,
        CONFIRM_BOSS_DEAD,
        CHECK_STATS,
        OPEN_CHAT_AND_GO_TO_BUFF,
        WAIT_FOR_BUFF,
        RETURN_TO_SCAN,
        TAP_RANDOM,
        PAUSE_FOR_UNCERTAIN_STATE
    }

    data class Stats(val atk: Int?, val def: Int?)
    data class Decision(val state: State, val action: Action, val reason: String)

    var state: State = State.STOPPED
        private set
    private var grayFrameCount = 0

    fun start(): Decision {
        grayFrameCount = 0
        state = State.SCANNING
        return Decision(state, Action.SCAN_MAP, "Automation started; scan current map.")
    }

    fun stop(): Decision {
        state = State.STOPPED
        grayFrameCount = 0
        return Decision(state, Action.NONE, "Automation stopped.")
    }

    fun onBossFound(): Decision {
        if (state != State.SCANNING && state != State.RANDOM_TELEPORT) return unchanged("Boss scan result ignored outside scan state.")
        state = State.TARGET_SELECTED
        return Decision(state, Action.SELECT_TARGET, "A candidate boss marker was found.")
    }

    fun onTargetSelected(): Decision {
        if (state != State.TARGET_SELECTED) return unchanged("No target is awaiting selection.")
        state = State.MOVING_TO_BOSS
        return Decision(state, Action.MOVE_TO_TARGET, "Move toward the selected map coordinate.")
    }

    fun onPlayerOverlapsTarget(): Decision {
        if (state != State.MOVING_TO_BOSS) return unchanged("Target overlap ignored outside movement state.")
        state = State.ATTACKING
        grayFrameCount = 0
        return Decision(state, Action.CLOSE_MAP_AND_ATTACK, "Player reached target; close map and start auto-attack.")
    }

    fun onBossMarker(marker: BossMarker): Decision {
        if (state != State.ATTACKING) return unchanged("Boss marker ignored outside attack state.")
        when (marker) {
            BossMarker.YELLOW_SKULL -> {
                grayFrameCount = 0
                return Decision(state, Action.KEEP_ATTACKING, "Yellow skull confirms boss is still alive.")
            }
            BossMarker.PALE_GRAY_SKULL -> {
                grayFrameCount++
                if (grayFrameCount < requiredGrayFrames) {
                    return Decision(state, Action.KEEP_ATTACKING, "Gray skull observed $grayFrameCount/$requiredGrayFrames times; continue observing.")
                }
                state = State.CHECKING_STATS
                return Decision(state, Action.CONFIRM_BOSS_DEAD, "Gray skull persisted; treat boss as defeated and check stats.")
            }
            BossMarker.UNKNOWN -> {
                grayFrameCount = 0
                return Decision(state, Action.PAUSE_FOR_UNCERTAIN_STATE, "Marker is unclear; do not assume the boss is dead.")
            }
        }
    }

    fun onStats(stats: Stats): Decision {
        if (state != State.CHECKING_STATS && state != State.WAITING_FOR_BUFF) {
            return unchanged("Stats observation ignored in current state.")
        }
        if (stats.atk == null || stats.def == null) {
            return Decision(state, Action.PAUSE_FOR_UNCERTAIN_STATE, "ATK/DEF not both readable in this frame; retain state.")
        }
        val belowMinimum = stats.atk < minimumAtk || stats.def < minimumDef
        if (state == State.WAITING_FOR_BUFF && belowMinimum) {
            return Decision(state, Action.WAIT_FOR_BUFF, "Buff stats have not recovered yet.")
        }
        if (belowMinimum && buffEnabled) {
            state = State.GOING_TO_BUFF
            return Decision(state, Action.OPEN_CHAT_AND_GO_TO_BUFF, "ATK or DEF is below minimum; visit configured buff coordinate.")
        }
        state = State.SCANNING
        grayFrameCount = 0
        return Decision(state, Action.RETURN_TO_SCAN, "Stats are sufficient or buffing is disabled; resume boss scan.")
    }

    fun onReachedBuffPoint(): Decision {
        if (state != State.GOING_TO_BUFF) return unchanged("Buff arrival ignored outside buff travel state.")
        state = State.WAITING_FOR_BUFF
        return Decision(state, Action.WAIT_FOR_BUFF, "At buff point; wait and re-check ATK/DEF.")
    }

    fun onBuffRecovered(stats: Stats): Decision = onStats(stats)

    fun onNoBossInRange(): Decision {
        if (state != State.SCANNING) return unchanged("Empty scan ignored outside scan state.")
        state = State.RANDOM_TELEPORT
        return Decision(state, Action.TAP_RANDOM, "No suitable boss in range; tap Random and scan again.")
    }

    fun onRandomTeleportComplete(): Decision {
        if (state != State.RANDOM_TELEPORT) return unchanged("Random teleport completion ignored outside teleport state.")
        state = State.SCANNING
        return Decision(state, Action.SCAN_MAP, "Map remains open; scan the new area.")
    }

    fun pause(reason: String): Decision {
        state = State.PAUSED
        return Decision(state, Action.PAUSE_FOR_UNCERTAIN_STATE, reason)
    }

    private fun unchanged(reason: String) = Decision(state, Action.NONE, reason)
}
