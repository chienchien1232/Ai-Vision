#pragma once
#include "LocalIntentProvider.h"
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <string.h>

namespace glasses {
/** Single inference worker. Cancellation never frees a PCM view still borrowed by native code. */
class IntentWorker {
 public:
  bool begin(LocalIntentProvider &provider) {
    provider_ = &provider;
    if (!provider.available()) return true; // Zero worker/model allocation in the shipped configuration.
    return xTaskCreate(task, "intent-worker", 6144, this, 2, &task_) == pdPASS;
  }
  bool configured() const { return provider_ && provider_->available() && task_; }
  bool busy() const { return running_.load(); }
  bool submit(const uint8_t *pcm, size_t size, const char *session, const char *language) {
    if (!configured() || busy() || ready_.load() || !pcm || !size) return false;
    cancelled_.store(false);
    strncpy(session_, session, sizeof(session_) - 1);
    strncpy(language_, language, sizeof(language_) - 1);
    pcm_ = pcm; size_ = size;
    running_.store(true);
    xTaskNotifyGive(task_);
    return true;
  }
  void cancel() { cancelled_.store(true); }
  bool take(RecognitionResult &result) {
    if (running_.load() || !ready_.exchange(false)) return false;
    result = cancelled_.load() ? RecognitionResult{RecognitionKind::Failed, LocalIntent::None, 0} : result_;
    pcm_ = nullptr;
    size_ = 0;
    return true;
  }
 private:
  static void task(void *arg) { static_cast<IntentWorker *>(arg)->run(); }
  void run() {
    for (;;) {
      ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
      result_ = provider_->classify({pcm_, size_, session_, language_}, cancelled_);
      if (cancelled_.load()) result_ = {RecognitionKind::Failed, LocalIntent::None, 0};
      ready_.store(true);
      running_.store(false); // Last publish: main may release PCM only after this store.
    }
  }
  LocalIntentProvider *provider_ = nullptr;
  TaskHandle_t task_ = nullptr;
  std::atomic<bool> cancelled_{false}, running_{false}, ready_{false};
  const uint8_t *pcm_ = nullptr;
  size_t size_ = 0;
  char session_[37] = {}, language_[8] = {};
  RecognitionResult result_;
};
}
