#pragma once
#include <atomic>
#include <stddef.h>
#include <stdint.h>
#include <math.h>

namespace glasses {
enum class LocalIntent : uint8_t { None, TakePhoto, Stop, VolumeUp, VolumeDown, GetStatus, Repeat };
enum class RecognitionKind : uint8_t { Recognized, NotLocal, Unknown, Unavailable, Failed };
struct RecognitionResult {
  RecognitionKind kind = RecognitionKind::Unavailable;
  LocalIntent intent = LocalIntent::None;
  float confidence = 0;
};
struct PcmView {
  const uint8_t *bytes;
  size_t size;
  const char *sessionId;
  const char *language;
  static constexpr uint32_t SampleRate = 16000; // PCM_S16LE mono
};

/** Borrowed PCM is valid only until classify returns. No I2S/GPIO/network access. */
class LocalIntentProvider {
 public:
  virtual ~LocalIntentProvider() = default;
  virtual bool available() const = 0;
  virtual float minimumConfidence() const { return 0.8f; }
  // Must honor cancellation, release its native objects, and return within its deadline.
  // A streaming model may feed its own small blocks from this completed recording.
  virtual RecognitionResult classify(const PcmView &pcm, const std::atomic<bool> &cancelled) = 0;
};
class UnconfiguredIntentProvider final : public LocalIntentProvider {
 public:
  bool available() const override { return false; }
  RecognitionResult classify(const PcmView &, const std::atomic<bool> &) override { return {}; }
};

// One composition point for the future model adapter. No inference is installed here.
inline LocalIntentProvider &localIntentProvider() {
  static UnconfiguredIntentProvider provider;
  return provider;
}

inline bool executable(const RecognitionResult &result, float threshold) {
  return result.kind == RecognitionKind::Recognized && result.intent != LocalIntent::None &&
      static_cast<unsigned>(result.intent) <= static_cast<unsigned>(LocalIntent::Repeat) &&
      isfinite(threshold) && threshold >= 0 && threshold <= 1 &&
      isfinite(result.confidence) && result.confidence >= threshold && result.confidence <= 1;
}
inline const char *intentName(LocalIntent intent) {
  switch (intent) {
    case LocalIntent::TakePhoto: return "TAKE_PHOTO";
    case LocalIntent::Stop: return "STOP";
    case LocalIntent::VolumeUp: return "VOLUME_UP";
    case LocalIntent::VolumeDown: return "VOLUME_DOWN";
    case LocalIntent::GetStatus: return "GET_STATUS";
    case LocalIntent::Repeat: return "REPEAT";
    default: return "UNKNOWN";
  }
}
}
