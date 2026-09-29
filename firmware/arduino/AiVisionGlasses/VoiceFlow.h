#pragma once
#include <stdint.h>

namespace glasses {
enum class VoiceState : uint8_t { Idle, Listening, Classifying, SendingAudio, WaitingAndroid, Playing };

/** Main-loop owned. No driver, allocations, or recognition code in this state machine. */
class VoiceFlow {
 public:
  VoiceState state() const { return state_; }
  bool idle() const { return state_ == VoiceState::Idle; }
  bool start() { if (!idle()) return false; state_ = VoiceState::Listening; return true; }
  void classify() { state_ = VoiceState::Classifying; }
  void sendAudio() { state_ = VoiceState::SendingAudio; }
  void waitAndroid(uint32_t now) { state_ = VoiceState::WaitingAndroid; waitingSince_ = now; }
  void play() { state_ = VoiceState::Playing; }
  void reset() { state_ = VoiceState::Idle; waitingSince_ = 0; }
  bool expired(uint32_t now) const {
    return state_ == VoiceState::WaitingAndroid && uint32_t(now - waitingSince_) >= 120000;
  }
 private:
  VoiceState state_ = VoiceState::Idle;
  uint32_t waitingSince_ = 0;
};
}
