#ifndef MELONDS_OBOECALLBACK_H
#define MELONDS_OBOECALLBACK_H

#include <oboe/Oboe.h>
#include <atomic>
#include <fstream>
#include "MelonInstance.h"

class OboeCallback : public oboe::AudioStreamCallback {
private:
    int _volume;
    void (*onErrorCallback)(void);
    std::ostream* _recordingStream;
    float audioSampleFrac;

public:
    std::weak_ptr<MelonDSAndroid::MelonInstance> activeInstance;

    // [KHMM] Hard-mute the DS audio output while an HD replacement cutscene plays (the video's
    // own audio track replaces it; desktop: emuInstance->audioVolume = 0 in EmuThread.cpp:923).
    // The SPU ring is still drained so no stale audio bursts out when the mute lifts.
    static std::atomic_bool khMuteDsAudio;

    OboeCallback(int volume, void (*onErrorCallback)(void)) : OboeCallback(volume, onErrorCallback, nullptr) { };
    OboeCallback(int volume, void (*onErrorCallback)(void), std::ostream* recordingStream);
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result result) override;
    
private:
    int getNumSamplesOut(int len);
};


#endif //MELONDS_OBOECALLBACK_H
