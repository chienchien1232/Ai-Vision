#pragma once
#include "LocalIntentProvider.h"

namespace glasses {
enum class CommandStatus : uint8_t { Success, Failed, ReplayOnAndroid, Rejected };
class DeviceActions {
 public:
  virtual ~DeviceActions() = default;
  virtual bool takePhoto() = 0;
  virtual void stop() = 0;
  virtual void changeVolume(int direction) = 0;
  virtual bool connected() const = 0;
};
inline CommandStatus executeCommand(const RecognitionResult &result, float threshold, DeviceActions &device) {
  if (!executable(result, threshold)) return CommandStatus::Rejected;
  switch (result.intent) {
    case LocalIntent::TakePhoto: return device.takePhoto() ? CommandStatus::Success : CommandStatus::Failed;
    case LocalIntent::Stop: device.stop(); return CommandStatus::Success;
    case LocalIntent::VolumeUp: device.changeVolume(1); return CommandStatus::Success;
    case LocalIntent::VolumeDown: device.changeVolume(-1); return CommandStatus::Success;
    case LocalIntent::GetStatus: return device.connected() ? CommandStatus::Success : CommandStatus::Failed;
    case LocalIntent::Repeat: return CommandStatus::ReplayOnAndroid;
    default: return CommandStatus::Rejected;
  }
}

class PcmGain {
 public:
  int percent() const { return percent_; }
  void change(int direction) {
    percent_ += direction > 0 ? 10 : -10;
    if (percent_ < 0) percent_ = 0;
    if (percent_ > 150) percent_ = 150;
  }
  int16_t apply(int16_t sample) const {
    int32_t value = static_cast<int32_t>(sample) * percent_ / 100;
    if (value > 32767) value = 32767;
    if (value < -32768) value = -32768;
    return static_cast<int16_t>(value);
  }
 private:
  int percent_ = 100;
};
}
