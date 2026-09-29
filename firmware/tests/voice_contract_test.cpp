#include "../arduino/AiVisionGlasses/VoiceFlow.h"
#include "../arduino/AiVisionGlasses/DeviceCommandExecutor.h"
#include <assert.h>
#include <limits>
#include <initializer_list>
#include <stdio.h>

using namespace glasses;
struct FakeDevice final : DeviceActions {
  int photos = 0, stops = 0;
  bool cameraOk = true;
  PcmGain gain;
  bool takePhoto() override { ++photos; return cameraOk; }
  void stop() override { ++stops; }
  void changeVolume(int direction) override { gain.change(direction); }
  bool connected() const override { return true; }
};
int main() {
  VoiceFlow flow;
  assert(flow.start() && !flow.start());
  flow.classify(); assert(!flow.start());
  flow.sendAudio(); flow.waitAndroid(0xfffffff0U);
  assert(!flow.expired(100)); assert(flow.expired(uint32_t(0xfffffff0U + 120000U)));
  flow.play(); assert(!flow.start()); flow.reset(); assert(flow.start());
  UnconfiguredIntentProvider provider;
  std::atomic<bool> cancelled{false};
  assert(!provider.available());
  assert(provider.classify({nullptr, 0, "test", "vi-VN"}, cancelled).kind == RecognitionKind::Unavailable);
  FakeDevice device;
  for (auto kind : {RecognitionKind::Unknown, RecognitionKind::Failed, RecognitionKind::NotLocal, RecognitionKind::Unavailable})
    assert(executeCommand({kind, LocalIntent::TakePhoto, 1}, .8f, device) == CommandStatus::Rejected);
  assert(executeCommand({RecognitionKind::Recognized, LocalIntent::TakePhoto, .5f}, .8f, device) == CommandStatus::Rejected);
  assert(executeCommand({RecognitionKind::Recognized, LocalIntent::TakePhoto, std::numeric_limits<float>::quiet_NaN()}, .8f, device) == CommandStatus::Rejected);
  assert(device.photos == 0);
  for (auto intent : {LocalIntent::TakePhoto, LocalIntent::Stop, LocalIntent::VolumeUp, LocalIntent::VolumeDown, LocalIntent::GetStatus})
    assert(executeCommand({RecognitionKind::Recognized, intent, .9f}, .8f, device) == CommandStatus::Success);
  assert(device.photos == 1 && device.stops == 1);
  device.cameraOk = false;
  assert(executeCommand({RecognitionKind::Recognized, LocalIntent::TakePhoto, .9f}, .8f, device) == CommandStatus::Failed);
  assert(executeCommand({RecognitionKind::Recognized, LocalIntent::Repeat, .9f}, .8f, device) == CommandStatus::ReplayOnAndroid);
  for (int i = 0; i < 100; ++i) device.gain.change(1);
  assert(device.gain.percent() == 150 && device.gain.apply(30000) == 32767 && device.gain.apply(-30000) == -32768);
  for (int i = 0; i < 100; ++i) device.gain.change(-1);
  assert(device.gain.percent() == 0 && device.gain.apply(-32768) == 0);
  puts("PASS: voice state, wraparound deadline, disabled provider, intent guards, executor, gain, replay delegation");
}
