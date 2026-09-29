#include "../arduino/AiVisionGlasses/IntentWorker.h"
#include <assert.h>
#include <functional>
#include <stdio.h>
#include <string.h>

using namespace glasses;
struct TestProvider final : LocalIntentProvider {
  std::function<void()> onClassify;
  bool available() const override { return true; }
  RecognitionResult classify(const PcmView &pcm, const std::atomic<bool> &) override {
    assert(pcm.size == 2 && pcm.bytes[0] == 42);
    assert(strcmp(pcm.sessionId, "test-session") == 0 && strcmp(pcm.language, "vi-VN") == 0);
    if (onClassify) onClassify();
    return {RecognitionKind::Recognized, LocalIntent::TakePhoto, .9f};
  }
};
int main() {
  UnconfiguredIntentProvider disabled;
  IntentWorker empty;
  assert(empty.begin(disabled) && !empty.configured());
  assert(task_test::creations == 0);
  TestProvider provider;
  IntentWorker worker;
  assert(worker.begin(provider) && worker.configured());
  uint8_t pcm[] = {42, 0};
  char session[] = "test-session";
  assert(worker.submit(pcm, 2, session, "vi-VN"));
  session[0] = 'X'; // Session metadata is copied, while PCM remains borrowed.
  assert(worker.busy() && !worker.submit(pcm, 2, "other", "vi-VN"));
  RecognitionResult result;
  assert(!worker.take(result));
  task_test::step();
  assert(!worker.busy() && worker.take(result) && result.kind == RecognitionKind::Recognized);
  provider.onClassify = [&] { assert(worker.busy()); worker.cancel(); assert(pcm[0] == 42); };
  assert(worker.submit(pcm, 2, "test-session", "vi-VN"));
  task_test::step();
  assert(worker.take(result) && result.kind == RecognitionKind::Failed);
  provider.onClassify = {};
  assert(worker.submit(pcm, 2, "test-session", "vi-VN"));
  task_test::step(); worker.cancel(); // Cancel after result publish, before dispatch.
  assert(worker.take(result) && result.kind == RecognitionKind::Failed);
  puts("PASS: disabled provider allocates no task; single owner; metadata copy; in-flight and late cancellation");
}
