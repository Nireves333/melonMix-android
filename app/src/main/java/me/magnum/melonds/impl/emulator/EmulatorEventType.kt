package me.magnum.melonds.impl.emulator

/**
 * Event types that can be emitted by the emulator. These constants must match the values defined in AndroidMelonEventMessenger.h
 */
enum class EmulatorEventType(val event: Int) {
    /**
     * Rumble start event. Data:
     * * rumble duration in ms (`i32`)
     */
    EventRumbleStart(100),
    /**
     * Rumble stop event. No data.
     */
    EventRumbleStop(101),
    /**
     * Emulator stop event. No data.
     */
    EventEmulatorStop(102),

    /**
     * RA achievement primed. Data:
     * * achievement ID (`i64`)
     */
    EventRAAchievementPrimed(200),

    /**
     * RA achievement triggered. Data:
     * * achievement ID (`i64`)
     */
    EventRAAchievementTriggered(201),

    /**
     * RA achievement unprimed. Data:
     * * achievement ID (`i64`)
     */
    EventRAAchievementUnprimed(202),

    /**
     * RA achievement progress updated. Data:
     * * achievement ID (`i64`)
     * * current progress value (`i32`)
     * * target progress value (`i32`)
     * * formated progress string size (`i32`)
     * * formated progress string (`u8[32]`)
     */
    EventRAAchievementProgressUpdated(203),

    /**
     * RA leaderboard attempt started. Data:
     * * leaderboard ID (`i64`)
     */
    EventRALeaderboardAttemptStarted(210),

    /**
     * RA leaderboard attempt updated. Data:
     * * leaderboard ID (`i64`)
     * * formated value string size (`i32`)
     * * formated value string (`u8[32]`)
     */
    EventRALeaderboardAttemptUpdated(211),

    /**
     * RA leaderboard attempt canceled. Data:
     * * leaderboard ID (`i64`)
     */
    EventRALeaderboardAttemptCanceled(212),

    /**
     * RA leaderboard attempt completed. Data:
     * * leaderboard ID (`i64`)
     * * leaderboard value (`i32`)
     * * formated value string size (`i32`)
     * * formated value string (`u8[32]`)
     */
    EventRALeaderboardAttemptCompleted(213),

    /**
     * [KHMM] KH pause-menu overlay snapshot (the plugin hides the game's native pause menu in
     * the composite and the frontend draws a replacement). Data (strings are `i32` length +
     * UTF-8 bytes):
     * * visible (`i32`)
     * * selected button index (`i32`)
     * * darken background (`i32`)
     * * size modifier * 1000 (`i32`)
     * * title (`str`)
     * * subtitle (`str`)
     * * button label count (`i32`)
     * * button labels (`str[count]`)
     */
    EventKhPauseMenu(300),

    /**
     * [KHMM] KH menu sound request (only fired by the HD-cutscene menu, which is not ported
     * yet; the game's own pause menu plays its own sounds). Data:
     * * sound ID (`i32`): 1=enter, 2=move, 3=continue, 4=select
     */
    EventKhMenuSound(301),

    /**
     * [KHMM] KH HD replacement cutscene: start or dismiss the video player. Data (strings
     * are `i32` length + UTF-8 bytes):
     * * playing (`i32`)
     * * video file path (`str`, only when playing != 0)
     * * subtitles file path (`str`, only when playing != 0; may be empty/nonexistent)
     */
    EventKhCutscene(302),
}
