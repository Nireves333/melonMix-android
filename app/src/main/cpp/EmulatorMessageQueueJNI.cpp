#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <limits.h>
#include <Platform.h>

// messagePipes[0] -> read
// messagePipes[1] -> write
static int messagePipes[2] = { -1, -1 };

extern "C"
{

JNIEXPORT jint JNICALL
Java_me_magnum_melonds_impl_emulator_EmulatorMessageQueue_initMessagePipe(JNIEnv* env, jobject thiz)
{
    if (messagePipes[0] != -1) {
        return messagePipes[0];
    }

    if (pipe(messagePipes) == -1) {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "Failed to create message queue pipes");
        return -1;
    }

    // Make read end non-blocking
    int flags = fcntl(messagePipes[0], F_GETFL, 0);
    fcntl(messagePipes[0], F_SETFL, flags | O_NONBLOCK);

    return messagePipes[0];
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_impl_emulator_EmulatorMessageQueue_closeMessagePipe(JNIEnv* env, jobject thiz)
{
    if (messagePipes[0] != -1) {
        close(messagePipes[0]);
        messagePipes[0] = -1;
    }
    if (messagePipes[1] != -1) {
        close(messagePipes[1]);
        messagePipes[1] = -1;
    }
}

}

namespace MelonDSAndroid {
    // [KHMM] Each event must land in the pipe in ONE write() call. Pipe writes up to
    // PIPE_BUF bytes are atomic, so a single write keeps the header and payload contiguous
    // per event. The previous two-write version had two corruption paths, both of which
    // desynced the byte stream PERMANENTLY (payload bytes get parsed as the next header;
    // there is no resync), silently eating every later event — pause-menu overlays stuck
    // on screen or never showing up again:
    //  1. Events fire from two threads (emu thread; UI thread via the video player's
    //     end/failure JNI returns), so two header/payload pairs could interleave.
    //  2. The Kotlin reader wakes on the header write and does one non-blocking read for
    //     the payload — preempt the writer between its two writes and the reader consumes
    //     the header, finds no payload, and drops out mid-event.
    void fireEmulatorEvent(int type, int dataLength, void* data) {
        if (messagePipes[1] == -1) {
            return;
        }

        // 8-byte header + data buffer; must match EmulatorMessageQueue.kt
        // (EVENT_SIZE_BYTES / DATA_SIZE_BYTES) and stay under PIPE_BUF for atomicity.
        constexpr int MAX_DATA_BYTES = 512;
        static_assert(sizeof(int) * 2 + MAX_DATA_BYTES <= PIPE_BUF, "event must fit an atomic pipe write");

        if (dataLength < 0 || dataLength > MAX_DATA_BYTES || (dataLength > 0 && data == nullptr)) {
            melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "Dropping malformed emulator event");
            return;
        }

        unsigned char buffer[sizeof(int) * 2 + MAX_DATA_BYTES];
        int header[2] = { type, dataLength };
        memcpy(buffer, header, sizeof(header));
        if (dataLength > 0)
            memcpy(buffer + sizeof(header), data, dataLength);

        ssize_t result;
        do {
            result = write(messagePipes[1], buffer, sizeof(header) + dataLength);
        } while (result == -1 && errno == EINTR);
    }
}