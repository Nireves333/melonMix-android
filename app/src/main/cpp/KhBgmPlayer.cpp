// [KHMM] Remastered-BGM replacement player — see KhBgmPlayer.h for the model.

#include "KhBgmPlayer.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <strings.h>
#include <vector>

#include <oboe/Oboe.h>

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_OGG
#include "dr_flac.h"

#include "MelonLog.h"
#include "plugins/Plugin.h"

namespace KhBgm {

static const char* TAG = "MelonMixKhBgm";

// ---------------------------------------------------------------------------------------
// Audio sources: pull s16 interleaved frames from a file, wrapping at the loop points.
// ---------------------------------------------------------------------------------------

class Source {
public:
    virtual ~Source() = default;
    virtual bool open(const std::string& path) = 0;
    // Fill `out` with up to `frames` interleaved s16 frames, looping at the loop point.
    // Returns frames written (a short read only ever means an I/O/decode failure).
    virtual size_t readFrames(int16_t* out, size_t frames) = 0;
    virtual bool seekToFrame(int64_t frame) = 0;

    int sampleRate = 0;
    int channels = 0;
    // Current read position and loop points, all in frames on the file's own timeline
    // (loopEnd is exclusive, matching the desktop byte math).
    int64_t positionFrames = 0;
    int64_t loopStartFrame = 0;
    int64_t loopEndFrame = 0;

    int64_t positionMs() const {
        return sampleRate > 0 ? (positionFrames * 1000) / sampleRate : 0;
    }
    int64_t msToFrame(int64_t ms) const {
        return (ms * sampleRate) / 1000;
    }
};

// WAV: 16-bit PCM with the loop points in a `smpl` chunk (desktop: AudioWavParser +
// AudioSourceWav). No markers = the whole file loops.
class WavSource : public Source {
public:
    ~WavSource() override {
        if (file != nullptr)
            fclose(file);
    }

    bool open(const std::string& path) override {
        file = fopen(path.c_str(), "rb");
        if (file == nullptr)
            return false;

        uint8_t riff[12];
        if (fread(riff, 1, 12, file) != 12 || memcmp(riff, "RIFF", 4) != 0 || memcmp(riff + 8, "WAVE", 4) != 0)
            return false;

        int64_t smplLoopStart = -1;
        int64_t smplLoopEnd = -1;

        uint8_t header[8];
        while (fread(header, 1, 8, file) == 8) {
            uint32_t chunkSize;
            memcpy(&chunkSize, header + 4, 4);
            long chunkStart = ftell(file);

            if (memcmp(header, "fmt ", 4) == 0) {
                uint8_t fmt[16];
                if (chunkSize < 16 || fread(fmt, 1, 16, file) != 16)
                    return false;
                uint16_t audioFormat;
                uint16_t numChannels;
                uint32_t rate;
                uint16_t bits;
                memcpy(&audioFormat, fmt + 0, 2);
                memcpy(&numChannels, fmt + 2, 2);
                memcpy(&rate, fmt + 4, 4);
                memcpy(&bits, fmt + 14, 2);
                // 16-bit PCM only (every KHMM pack file is 44.1kHz s16); 0xFFFE is
                // WAVE_FORMAT_EXTENSIBLE, whose first sub-format bytes still describe PCM.
                if ((audioFormat != 1 && audioFormat != 0xFFFE) || bits != 16 || numChannels < 1 || numChannels > 2)
                    return false;
                channels = numChannels;
                sampleRate = (int) rate;
            } else if (memcmp(header, "data", 4) == 0) {
                dataStart = chunkStart;
                dataFrames = chunkSize / (2 * (channels > 0 ? channels : 2));
            } else if (memcmp(header, "smpl", 4) == 0) {
                // smpl: 9 u32 fields, the 8th being numSampleLoops, then per loop
                // {cuePointId, type, start, end, fraction, playCount} — start/end in frames.
                uint32_t smpl[9];
                if (chunkSize >= 36 + 24 && fread(smpl, 4, 9, file) == 9 && smpl[7] >= 1) {
                    uint32_t loop[6];
                    if (fread(loop, 4, 6, file) == 6) {
                        smplLoopStart = loop[2];
                        smplLoopEnd = loop[3];
                    }
                }
            }

            // Chunks are word-aligned
            fseek(file, chunkStart + (long) chunkSize + (chunkSize & 1), SEEK_SET);
        }

        if (dataStart < 0 || sampleRate == 0 || dataFrames <= 0)
            return false;

        if (smplLoopStart >= 0 && smplLoopEnd > smplLoopStart && smplLoopEnd <= dataFrames) {
            loopStartFrame = smplLoopStart;
            loopEndFrame = smplLoopEnd;
        } else {
            loopStartFrame = 0;
            loopEndFrame = dataFrames;
        }

        return seekToFrame(0);
    }

    size_t readFrames(int16_t* out, size_t frames) override {
        size_t written = 0;
        while (written < frames) {
            int64_t framesLeftInLoop = loopEndFrame - positionFrames;
            if (framesLeftInLoop <= 0) {
                if (!seekToFrame(loopStartFrame))
                    break;
                framesLeftInLoop = loopEndFrame - positionFrames;
                if (framesLeftInLoop <= 0)
                    break;
            }
            size_t chunk = (size_t) std::min<int64_t>((int64_t) (frames - written), framesLeftInLoop);
            size_t read = fread(out + written * channels, 2 * channels, chunk, file);
            positionFrames += (int64_t) read;
            written += read;
            if (read < chunk)
                break; // truncated file
        }
        return written;
    }

    bool seekToFrame(int64_t frame) override {
        if (frame < 0 || frame > dataFrames)
            frame = 0;
        if (fseek(file, (long) (dataStart + frame * 2 * channels), SEEK_SET) != 0)
            return false;
        positionFrames = frame;
        return true;
    }

private:
    FILE* file = nullptr;
    long dataStart = -1;
    int64_t dataFrames = 0;
};

// FLAC via dr_flac, loop points in LOOPSTART/LOOPEND vorbis comments (desktop:
// AudioSourceFlac). No comments = the whole file loops.
class FlacSource : public Source {
public:
    ~FlacSource() override {
        if (flac != nullptr)
            drflac_close(flac);
    }

    bool open(const std::string& path) override {
        flac = drflac_open_file_with_metadata(path.c_str(), &FlacSource::onMetadata, this, nullptr);
        if (flac == nullptr)
            return false;

        sampleRate = (int) flac->sampleRate;
        channels = (int) flac->channels;
        if (channels < 1 || channels > 2)
            return false;

        int64_t totalFrames = (int64_t) flac->totalPCMFrameCount;
        if (metaLoopStart >= 0 && metaLoopEnd > metaLoopStart && metaLoopEnd <= totalFrames) {
            loopStartFrame = metaLoopStart;
            loopEndFrame = metaLoopEnd;
        } else {
            loopStartFrame = 0;
            loopEndFrame = totalFrames;
        }
        return loopEndFrame > 0;
    }

    size_t readFrames(int16_t* out, size_t frames) override {
        size_t written = 0;
        while (written < frames) {
            int64_t framesLeftInLoop = loopEndFrame - positionFrames;
            if (framesLeftInLoop <= 0) {
                if (!seekToFrame(loopStartFrame))
                    break;
                framesLeftInLoop = loopEndFrame - positionFrames;
                if (framesLeftInLoop <= 0)
                    break;
            }
            size_t chunk = (size_t) std::min<int64_t>((int64_t) (frames - written), framesLeftInLoop);
            drflac_uint64 read = drflac_read_pcm_frames_s16(flac, chunk, out + written * channels);
            positionFrames += (int64_t) read;
            written += (size_t) read;
            if (read < chunk)
                break; // decode error / truncated stream
        }
        return written;
    }

    bool seekToFrame(int64_t frame) override {
        if (frame < 0)
            frame = 0;
        if (!drflac_seek_to_pcm_frame(flac, (drflac_uint64) frame))
            return false;
        positionFrames = frame;
        return true;
    }

private:
    static void onMetadata(void* userData, drflac_metadata* metadata) {
        auto* self = (FlacSource*) userData;
        if (metadata->type != DRFLAC_METADATA_BLOCK_TYPE_VORBIS_COMMENT)
            return;

        drflac_vorbis_comment_iterator it;
        drflac_init_vorbis_comment_iterator(&it,
            metadata->data.vorbis_comment.commentCount, metadata->data.vorbis_comment.pComments);
        drflac_uint32 length;
        const char* comment;
        while ((comment = drflac_next_vorbis_comment(&it, &length)) != nullptr) {
            std::string entry(comment, length);
            if (entry.size() > 10 && strncasecmp(entry.c_str(), "LOOPSTART=", 10) == 0)
                self->metaLoopStart = strtoll(entry.c_str() + 10, nullptr, 10);
            else if (entry.size() > 8 && strncasecmp(entry.c_str(), "LOOPEND=", 8) == 0)
                self->metaLoopEnd = strtoll(entry.c_str() + 8, nullptr, 10);
        }
    }

    drflac* flac = nullptr;
    int64_t metaLoopStart = -1;
    int64_t metaLoopEnd = -1;
};

// ---------------------------------------------------------------------------------------
// Voice: one playing track = one oboe output stream at the file's own rate/channel count
// (desktop: one QAudioSink per melonMix::AudioPlayer; two can overlap during a crossfade).
// ---------------------------------------------------------------------------------------

class Voice : public oboe::AudioStreamDataCallback {
public:
    Voice(uint16_t bgmId, std::unique_ptr<Source> source)
        : bgmId(bgmId), source(std::move(source)) {}

    bool start(int64_t resumePositionMs, float initialVolume, int fadeInMs, bool startPaused) {
        if (resumePositionMs > 0)
            source->seekToFrame(source->msToFrame(resumePositionMs));

        {
            std::lock_guard<std::mutex> lock(mutex);
            if (fadeInMs > 0) {
                volume = 0.0f;
                setVolumeRampLocked(initialVolume, fadeInMs);
            } else {
                volume = initialVolume;
                volumeTarget = initialVolume;
                volumeStepPerFrame = 0.0f;
            }
        }

        oboe::AudioStreamBuilder builder;
        builder.setChannelCount(source->channels);
        builder.setSampleRate(source->sampleRate);
        builder.setFormat(oboe::AudioFormat::I16);
        builder.setFormatConversionAllowed(true);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::None);
        builder.setSharingMode(oboe::SharingMode::Shared);
        builder.setUsage(oboe::Usage::Game);
        builder.setDataCallback(this);

        if (builder.openStream(stream) != oboe::Result::OK) {
            LOG_WARN(TAG, "failed to open a BGM output stream (bgm %u)", bgmId);
            stream = nullptr;
            return false;
        }
        if (!startPaused)
            stream->requestStart();
        return true;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
        auto* out = (int16_t*) audioData;
        std::lock_guard<std::mutex> lock(mutex);

        size_t read = source->readFrames(out, (size_t) numFrames);
        if (read < (size_t) numFrames)
            memset(out + read * source->channels, 0, (numFrames - read) * source->channels * 2);

        for (int32_t i = 0; i < numFrames; i++) {
            if (volumeStepPerFrame != 0.0f) {
                volume += volumeStepPerFrame;
                if ((volumeStepPerFrame > 0.0f && volume >= volumeTarget) ||
                    (volumeStepPerFrame < 0.0f && volume <= volumeTarget)) {
                    volume = volumeTarget;
                    volumeStepPerFrame = 0.0f;
                }
            }
            for (int c = 0; c < source->channels; c++) {
                int32_t sample = (int32_t) ((float) out[i * source->channels + c] * volume);
                out[i * source->channels + c] = (int16_t) std::clamp(sample, -32768, 32767);
            }
        }

        if ((stopping && volume <= 0.0f && volumeStepPerFrame == 0.0f) || read == 0) {
            finished.store(true, std::memory_order_release);
            return oboe::DataCallbackResult::Stop;
        }
        return oboe::DataCallbackResult::Continue;
    }

    void setVolume(float target, int rampMs) {
        std::lock_guard<std::mutex> lock(mutex);
        if (!stopping)
            setVolumeRampLocked(target, rampMs);
    }

    void stop(int fadeOutMs) {
        std::lock_guard<std::mutex> lock(mutex);
        stopping = true;
        setVolumeRampLocked(0.0f, fadeOutMs);
        if (fadeOutMs == 0)
            finished.store(true, std::memory_order_release);
    }

    void pause() {
        if (stream != nullptr)
            stream->requestPause();
    }

    void resume() {
        if (stream != nullptr && !finished.load(std::memory_order_acquire))
            stream->requestStart();
    }

    // Position on the file's own timeline (loops already folded in by the loop wrap),
    // which is what the resume-position slot stores (desktop getCurrentPlayingPos).
    int64_t positionMs() {
        std::lock_guard<std::mutex> lock(mutex);
        return source->positionMs();
    }

    bool isFinished() const { return finished.load(std::memory_order_acquire); }

    // Joins the audio callback; never call from the callback thread itself.
    void close() {
        if (stream != nullptr) {
            stream->stop();
            stream->close();
            stream = nullptr;
        }
    }

    const uint16_t bgmId;
    bool stopping = false;

private:
    void setVolumeRampLocked(float target, int rampMs) {
        volumeTarget = target;
        if (rampMs <= 0 || source->sampleRate <= 0) {
            volume = target;
            volumeStepPerFrame = 0.0f;
        } else {
            int64_t rampFrames = ((int64_t) rampMs * source->sampleRate) / 1000;
            volumeStepPerFrame = rampFrames > 0 ? (target - volume) / (float) rampFrames : 0.0f;
            if (volumeStepPerFrame == 0.0f)
                volume = target;
        }
    }

    std::unique_ptr<Source> source;
    std::shared_ptr<oboe::AudioStream> stream;
    std::mutex mutex;
    float volume = 0.0f;
    float volumeTarget = 0.0f;
    float volumeStepPerFrame = 0.0f;
    std::atomic_bool finished { false };
};

// ---------------------------------------------------------------------------------------
// Manager state. All entry points run on the emu thread (pollPlugin/tick) or behind the
// emulator's own pause/stop choke points; `voicesMutex` protects the voice list against
// the JNI-called setters.
// ---------------------------------------------------------------------------------------

static std::mutex voicesMutex;
static std::vector<std::unique_ptr<Voice>> voices;

static std::atomic_int prefVolumePercent { 100 };
static std::atomic<int64_t> videoPositionMs { 0 };
static std::atomic<uint8_t> lastRamVolume { 0 };

static bool pausedByEmulator = false;
static bool pausedByPlugin = false;

// Resume-position slot (desktop bgmToResumeId/bgmToResumePosition): one track — Days'
// field theme — stops and later resumes from where it left off.
static uint16_t resumeSlotBgmId = 0xFFFF;
static int64_t resumeSlotPositionMs = 0;

// Pending delayed start (desktop delayedBgmStart, a single-shot QTimer).
struct PendingStart {
    bool armed = false;
    std::chrono::steady_clock::time_point deadline;
    uint16_t bgmId = 0;
    std::string path;
    bool resumeFromPosition = false;
    uint8_t ramVolume = 0;
};
static PendingStart pendingStart;

static float computeVolume(uint8_t ramVolume) {
    float volume = (float) prefVolumePercent.load(std::memory_order_relaxed) / 100.0f;
    // The game lowers this RAM volume to 0x40 when paused and during cutscenes; desktop
    // maps it to a 0.7 duck of the configured BGM volume (getBgmMusicVolume).
    if (ramVolume == 0x40)
        volume *= 0.7f;
    return volume;
}

static float computeVolumeFromLastRam() {
    return computeVolume(lastRamVolume.load(std::memory_order_relaxed));
}

static std::unique_ptr<Source> openSource(const std::string& path) {
    std::unique_ptr<Source> source;
    size_t dot = path.find_last_of('.');
    std::string ext = dot == std::string::npos ? "" : path.substr(dot + 1);
    if (ext == "wav" || ext == "WAV" || ext == "Wav")
        source.reset(new WavSource());
    else if (ext == "flac" || ext == "FLAC" || ext == "Flac")
        source.reset(new FlacSource());
    else
        return nullptr;

    if (!source->open(path))
        return nullptr;
    return source;
}

// Must be called with voicesMutex held.
static void startVoiceLocked(uint16_t bgmId, const std::string& path, bool resumeFromPosition, uint8_t ramVolume) {
    for (auto& voice : voices) {
        if (voice->bgmId == bgmId && !voice->stopping)
            return; // already playing (desktop startBgmMusic dedupe)
    }

    std::unique_ptr<Source> source = openSource(path);
    if (source == nullptr) {
        LOG_WARN(TAG, "could not open replacement BGM %u at %s", bgmId, path.c_str());
        return;
    }

    int64_t startPositionMs = 0;
    if (resumeFromPosition && bgmId == resumeSlotBgmId)
        startPositionMs = resumeSlotPositionMs;

    // Desktop kFadeInDurationMs: fade in only when resuming mid-track.
    int fadeInMs = startPositionMs > 0 ? 600 : 0;

    auto voice = std::make_unique<Voice>(bgmId, std::move(source));
    if (voice->start(startPositionMs, computeVolume(ramVolume), fadeInMs, pausedByEmulator || pausedByPlugin)) {
        LOG_INFO(TAG, "playing replacement BGM %u (%s)%s", bgmId, path.c_str(),
                 startPositionMs > 0 ? " [resumed]" : "");
        voices.push_back(std::move(voice));
    }
}

// Must be called with voicesMutex held.
static void reapFinishedVoicesLocked() {
    for (auto it = voices.begin(); it != voices.end();) {
        if ((*it)->isFinished()) {
            (*it)->close();
            it = voices.erase(it);
        } else {
            ++it;
        }
    }
}

void tick() {
    std::lock_guard<std::mutex> lock(voicesMutex);
    reapFinishedVoicesLocked();

    if (pendingStart.armed && std::chrono::steady_clock::now() >= pendingStart.deadline) {
        pendingStart.armed = false;
        startVoiceLocked(pendingStart.bgmId, pendingStart.path, pendingStart.resumeFromPosition, pendingStart.ramVolume);
    }
}

void pollPlugin(Plugins::Plugin* plugin) {
    // Desktop order (EmuThread.cpp:960-1000): stop, start, pause, resume, volume.
    if (plugin->shouldStopBackgroundMusic()) {
        uint16_t bgmId = plugin->getBackgroundMusicToStop();
        bool storeResumePosition = plugin->getStoreBackgroundMusicPosition();
        uint32_t fadeOutMs = plugin->getBackgroundMusicFadeOutToApply();

        std::lock_guard<std::mutex> lock(voicesMutex);
        pendingStart.armed = false; // desktop stopBgmMusic cancels a pending delayed start
        for (auto& voice : voices) {
            if (voice->bgmId == bgmId && !voice->stopping) {
                if (storeResumePosition) {
                    resumeSlotBgmId = bgmId;
                    resumeSlotPositionMs = voice->positionMs();
                }
                LOG_INFO(TAG, "stopping replacement BGM %u (%u ms fade%s)", bgmId, fadeOutMs,
                         storeResumePosition ? ", position stored" : "");
                voice->stop((int) fadeOutMs);
            }
        }
    }

    if (plugin->shouldStartBackgroundMusic()) {
        uint16_t bgmId = plugin->getCurrentBackgroundMusic();
        const std::string& path = plugin->getCurrentBackgroundMusicFilePath();
        bool resumeFromPosition = plugin->getResumeFromPositionBackgroundMusic();
        uint8_t ramVolume = plugin->getCurrentBgmMusicVolume();
        uint32_t delayAtStartMs = plugin->getBgmDelayAtStart();
        lastRamVolume.store(ramVolume, std::memory_order_relaxed);

        std::lock_guard<std::mutex> lock(voicesMutex);
        if (delayAtStartMs == 0) {
            startVoiceLocked(bgmId, path, resumeFromPosition, ramVolume);
        } else {
            // The delay is an offset from the beginning of the HD cutscene video that is
            // currently playing (desktop: delayAtStart - player->position()).
            int64_t remaining = (int64_t) delayAtStartMs - videoPositionMs.load(std::memory_order_relaxed);
            if (remaining < 0)
                remaining = 0;
            pendingStart.armed = true;
            pendingStart.deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(remaining);
            pendingStart.bgmId = bgmId;
            pendingStart.path = path;
            pendingStart.resumeFromPosition = resumeFromPosition;
            pendingStart.ramVolume = ramVolume;
            LOG_INFO(TAG, "replacement BGM %u scheduled in %lld ms", bgmId, (long long) remaining);
        }
    }

    if (plugin->shouldPauseBackgroundMusic()) {
        std::lock_guard<std::mutex> lock(voicesMutex);
        pausedByPlugin = true;
        for (auto& voice : voices)
            voice->pause();
    }

    if (plugin->shouldResumeBackgroundMusic()) {
        std::lock_guard<std::mutex> lock(voicesMutex);
        pausedByPlugin = false;
        if (!pausedByEmulator) {
            for (auto& voice : voices)
                voice->resume();
        }
    }

    if (plugin->shouldUpdateBackgroundMusicVolume()) {
        uint8_t ramVolume = plugin->getCurrentBgmMusicVolume();
        lastRamVolume.store(ramVolume, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(voicesMutex);
        float volume = computeVolume(ramVolume);
        for (auto& voice : voices)
            voice->setVolume(volume, 1000); // desktop: 1 sec transition
    }

    tick();
}

void pauseForEmulator() {
    std::lock_guard<std::mutex> lock(voicesMutex);
    pausedByEmulator = true;
    for (auto& voice : voices)
        voice->pause();
}

void resumeForEmulator() {
    std::lock_guard<std::mutex> lock(voicesMutex);
    pausedByEmulator = false;
    if (!pausedByPlugin) {
        for (auto& voice : voices)
            voice->resume();
    }
}

void stopAll(int fadeOutMs) {
    std::lock_guard<std::mutex> lock(voicesMutex);
    pendingStart.armed = false;
    pausedByPlugin = false;
    resumeSlotBgmId = 0xFFFF;
    resumeSlotPositionMs = 0;
    for (auto& voice : voices)
        voice->stop(fadeOutMs);
    if (fadeOutMs == 0) {
        reapFinishedVoicesLocked();
    }
    // With a fade, the voices reap on a later tick; if the emu is stopping for good the
    // final reap happens on the next session's first tick, which is harmless.
}

void setVolumePercent(int volumePercent) {
    prefVolumePercent.store(std::clamp(volumePercent, 0, 100), std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(voicesMutex);
    float volume = computeVolumeFromLastRam();
    for (auto& voice : voices)
        voice->setVolume(volume, 200);
}

void setVideoPositionMs(int64_t positionMs) {
    videoPositionMs.store(positionMs, std::memory_order_relaxed);
}

} // namespace KhBgm
