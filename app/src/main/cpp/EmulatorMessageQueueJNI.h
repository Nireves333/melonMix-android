#ifndef MELONDS_ANDROID_MESSAGEQUEUE_JNI_H
#define MELONDS_ANDROID_MESSAGEQUEUE_JNI_H

namespace MelonDSAndroid {
    void fireEmulatorEvent(int type, int dataLength, void* data);
    // [KHMM] inline: this header is now included from more than one translation unit
    inline void fireEmulatorEvent(int type) { fireEmulatorEvent(type, 0, nullptr); };
}

#endif // MELONDS_ANDROID_MESSAGEQUEUE_JNI_H