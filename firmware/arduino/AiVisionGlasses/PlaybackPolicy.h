#pragma once
#include <stddef.h>
#include <stdint.h>

namespace glasses {
constexpr size_t PlaybackMaximumBytes = 16000U * 2U * 30U;

inline bool playbackFits(size_t total, size_t capacity) {
  return total >= 2 && total <= PlaybackMaximumBytes && (total % 2) == 0 && total <= capacity;
}

// Queued ACKs are not permission to start TX. A complete, committed response
// avoids exposing stop-and-wait/Wi-Fi jitter to I2S while speech is playing.
inline bool playbackReady(size_t total, size_t received, bool endAccepted) {
  return playbackFits(total, received) && received == total && endAccepted;
}

inline bool playbackTransferExpired(bool active, bool started, uint32_t now, uint32_t lastProgress) {
  return active && !started && uint32_t(now - lastProgress) >= 10000U;
}
}  // namespace glasses
