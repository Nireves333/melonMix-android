#ifndef ANDROIDMELONEVENTMESSENGER_H
#define ANDROIDMELONEVENTMESSENGER_H

#include <MelonEventMessenger.h>

class AndroidMelonEventMessenger : public MelonDSAndroid::MelonEventMessenger
{
public:
    // [KHMM] KH Melon Mix pause-menu overlay events (fired from MelonInstance, not through
    // the messenger interface). Values must stay in sync with EmulatorEventType.kt.
    //
    // EVENT_KH_PAUSE_MENU payload (native byte order, strings are i32 length + UTF-8 bytes):
    //   i32 visible, i32 selection, i32 darkenBackground, i32 sizeModifier*1000,
    //   str title, str subtitle, i32 labelCount, str labels[labelCount]
    // EVENT_KH_MENU_SOUND payload: i32 soundId (1=enter, 2=move, 3=continue, 4=select)
    // EVENT_KH_CUTSCENE payload: i32 playing; when playing=1: str videoPath, str subtitlesPath
    // (the frontend starts/stops the HD replacement video player; desktop: windowStartVideo /
    // windowStopVideo). Paths are capped at 224 bytes each to fit the Kotlin data buffer.
    static constexpr int EVENT_KH_PAUSE_MENU = 300;
    static constexpr int EVENT_KH_MENU_SOUND = 301;
    static constexpr int EVENT_KH_CUTSCENE = 302;

    void onRumbleStart(int durationMs) override;
    void onRumbleStop() override;
    void onEmulatorStop(melonDS::Platform::StopReason reason) override;

    void onAchievementPrimed(long achievementId) override;
    void onAchievementTriggered(long achievementId) override;
    void onAchievementUnprimed(long achievementId) override;
    void onAchievementProgressUpdated(long achievementId, unsigned int current, unsigned int target, std::string progress) override;
    void onLeaderboardAttemptStarted(long leaderboardId) override;
    void onLeaderboardAttemptUpdated(long leaderboardId, std::string formattedValue) override;
    void onLeaderboardAttemptCanceled(long leaderboardId) override;
    void onLeaderboardAttemptCompleted(long leaderboardId, int value, std::string formattedValue) override;

private:
    // Event type constants
    static constexpr int EVENT_RUMBLE_START = 100;
    static constexpr int EVENT_RUMBLE_STOP = 101;
    static constexpr int EVENT_EMULATOR_STOP = 102;

    static constexpr int EVENT_RA_ACHIEVEMENT_PRIMED = 200;
    static constexpr int EVENT_RA_ACHIEVEMENT_TRIGGERED = 201;
    static constexpr int EVENT_RA_ACHIEVEMENT_UNPRIMED = 202;
    static constexpr int EVENT_RA_ACHIEVEMENT_PROGRESS_UPDATED = 203;
    static constexpr int EVENT_RA_LBOARD_ATTEMPT_STARTED = 210;
    static constexpr int EVENT_RA_LBOARD_ATTEMPT_UPDATED = 211;
    static constexpr int EVENT_RA_LBOARD_ATTEMPT_CANCELED = 212;
    static constexpr int EVENT_RA_LBOARD_ATTEMPT_COMPLETED = 213;
};

#endif // ANDROIDMELONEVENTMESSENGER_H
