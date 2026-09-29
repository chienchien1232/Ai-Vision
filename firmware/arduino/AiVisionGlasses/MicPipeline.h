#pragma once

#include <Arduino.h>
#include <ESP_I2S.h>
#include <atomic>
#include <esp_heap_caps.h>
#include <esp_partition.h>
#include <esp_afe_sr_models.h>
#include <freertos/semphr.h>
#include <freertos/task.h>

// The only RX reader after boot diagnostics. TCP/SD never run in these tasks.
// WakeNet runs on the board; arbitrary Vietnamese/English ASR runs on Android.
class MicPipeline {
 public:
  static constexpr size_t Capacity = 16000 * 2 * 8;
  bool begin(I2SClass &i2s) {
    input = &i2s;
    mutex = xSemaphoreCreateMutex();
    if (!mutex) return false;
    beginWake();
    // RX and TX share a port. Disable only RX; the speaker must remain usable.
    if (i2s_channel_disable(input->rxChan()) != ESP_OK) return false;
    if (afeData && xTaskCreate(fetchTask, "wake-fetch", 4096, this, 4, nullptr) != pdPASS) {
      afe->destroy(afeData);
      afeData = nullptr;
    }
    wakeReady.store(afeData != nullptr);
    return xTaskCreate(readTask, "mic-owner", 4096, this, 5, nullptr) == pdPASS;
  }
  bool supportsWake() const { return wakeReady.load(); }
  void suppressWake(bool suppress) { suppressed.store(suppress); }
  bool takeWake() { return wakePending.exchange(false); }
  bool start(bool diagnostic, bool preRoll = false) {
    xSemaphoreTake(mutex, portMAX_DELAY);
    stopLocked();
    capture = static_cast<uint8_t *>(heap_caps_malloc(Capacity, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    if (!capture) { xSemaphoreGive(mutex); return false; }
    if (i2s_channel_enable(input->rxChan()) != ESP_OK) {
      heap_caps_free(capture); capture = nullptr;
      xSemaphoreGive(mutex); return false;
    }
    rxEnabled = true;
    feedCount = 0;
    vadSpeech.store(false);
    ringCount = ringWrite = 0;
    fixed = diagnostic;
    count = 0;
    speechMs = 0;
    startedAt = lastDataAt = lastSpeechAt = millis();
    if (preRoll) {
      const size_t start = (ringWrite + RingSamples - ringCount) % RingSamples;
      for (size_t i = 0; i < ringCount; ++i) append(ring[(start + i) % RingSamples]);
    }
    ready.store(false);
    noData.store(false);
    active.store(true);
    xSemaphoreGive(mutex);
    return true;
  }
  void stop() {
    if (!mutex) return;
    xSemaphoreTake(mutex, portMAX_DELAY);
    stopLocked();
    xSemaphoreGive(mutex);
  }
  // Once ready is published the buffer is immutable until main-loop stop().
  bool isReady() const { return ready.load(); }
  bool failed() const { return noData.load(); }
  const uint8_t *bytes() const { return capture; }
  size_t size() const { return count; }

 private:
  static constexpr size_t RingSamples = 4000; // 250 ms of pre-roll
  I2SClass *input = nullptr;
  SemaphoreHandle_t mutex = nullptr;
  uint8_t *capture = nullptr;
  size_t count = 0, ringWrite = 0, ringCount = 0;
  int16_t ring[RingSamples] = {};
  int32_t raw[1024] = {};
  int16_t feed[1024] = {};
  size_t feedCount = 0, feedSize = 512;
  uint32_t startedAt = 0, lastDataAt = 0, lastSpeechAt = 0, speechMs = 0;
  bool fixed = false;
  bool rxEnabled = false;
  std::atomic<bool> active{false}, ready{false}, noData{false};
  std::atomic<bool> suppressed{true}, wakePending{false}, wakeReady{false}, vadSpeech{false};
  const esp_afe_sr_iface_t *afe = nullptr;
  esp_afe_sr_data_t *afeData = nullptr;
  srmodel_list_t *models = nullptr;

  void stopLocked() {
    active.store(false);
    disableRxLocked();
    ready.store(false);
    noData.store(false);
    if (capture) heap_caps_free(capture);
    capture = nullptr;
    count = 0;
  }
  void disableRxLocked() {
    if (rxEnabled) { i2s_channel_disable(input->rxChan()); rxEnabled = false; }
  }
  void append(int16_t sample) {
    if (count + 2 > Capacity) return;
    capture[count++] = static_cast<uint8_t>(sample & 0xff);
    capture[count++] = static_cast<uint8_t>((sample >> 8) & 0xff);
  }
  void beginWake() {
    // The stock app3M partition has no model. Manual capture must still work.
    const esp_partition_t *part = esp_partition_find_first(ESP_PARTITION_TYPE_DATA,
        ESP_PARTITION_SUBTYPE_ANY, "model");
    uint32_t magic = UINT32_MAX;
    if (!part || esp_partition_read(part, 0, &magic, sizeof(magic)) != ESP_OK || magic == UINT32_MAX || magic == 0) {
      Serial.println("[WAKE] No flashed model partition; manual listening only");
      return;
    }
    models = esp_srmodel_init("model");
    if (!models) return;
    afe_config_t *config = afe_config_init("M", models, AFE_TYPE_SR, AFE_MODE_LOW_COST);
    if (!config) return;
    // Explicitly select the shipped Hi ESP model, never a UI-defined wake word.
    config->wakenet_model_name = esp_srmodel_filter(models, ESP_WN_PREFIX, "hiesp");
    config->wakenet_model_name_2 = nullptr;
    config->aec_init = false;
    config->se_init = false;
    config->ns_init = false;
    config->agc_init = false;
    config->vad_init = true;
    config->vad_model_name = nullptr; // WebRTC VAD, no extra neural model
    config->vad_min_noise_ms = 500;
    config->wakenet_init = config->wakenet_model_name != nullptr;
    config->memory_alloc_mode = AFE_MEMORY_ALLOC_MORE_PSRAM;
    if (config->wakenet_init) {
      afe = esp_afe_handle_from_config(config);
      if (afe) afeData = afe->create_from_config(config);
    }
    afe_config_free(config);
    if (afeData) {
      const int chunk = afe->get_feed_chunksize(afeData);
      if (chunk <= 0 || chunk > 1024 || afe->get_feed_channel_num(afeData) != 1) {
        afe->destroy(afeData); afeData = nullptr;
      } else feedSize = static_cast<size_t>(chunk);
    }
    Serial.printf("[WAKE] Hi ESP %s; MultiNet/LLM not loaded\n", afeData ? "loaded" : "unavailable");
  }
  static void readTask(void *arg) { static_cast<MicPipeline *>(arg)->readLoop(); }
  static void fetchTask(void *arg) { static_cast<MicPipeline *>(arg)->fetchLoop(); }
  void fetchLoop() {
    uint32_t lastWakeAt = millis();
    for (;;) {
      if (!active.load()) { vTaskDelay(pdMS_TO_TICKS(20)); continue; }
      auto *result = afe->fetch_with_delay(afeData, pdMS_TO_TICKS(100));
      if (!result || result->ret_value != ESP_OK) continue;
      vadSpeech.store(result->vad_state == VAD_SPEECH);
      if (result->wakeup_state == WAKENET_DETECTED && !suppressed.load() &&
          !active.load() && !ready.load() && millis() - lastWakeAt > 2000) {
        lastWakeAt = millis();
        wakePending.store(true);
      }
    }
  }
  void readLoop() {
    for (;;) {
      // The same lock covers RX and capture lifetime: stop cannot free an in-flight buffer.
      xSemaphoreTake(mutex, portMAX_DELAY);
      if (!active.load()) {
        xSemaphoreGive(mutex);
        vTaskDelay(pdMS_TO_TICKS(10));
        continue;
      }
      size_t got = 0;
      i2s_channel_read(input->rxChan(), raw, feedSize * sizeof(int32_t), &got, 100);
      const size_t samples = min(got / sizeof(int32_t), feedSize);
      const uint32_t now = millis();
      uint64_t energy = 0;
      for (size_t i = 0; i < samples; ++i) {
        const int16_t pcm = static_cast<int16_t>(raw[i] >> 16);
        energy += static_cast<int64_t>(pcm) * pcm;
        ring[ringWrite] = pcm;
        ringWrite = (ringWrite + 1) % RingSamples;
        if (ringCount < RingSamples) ++ringCount;
        if (active.load()) append(pcm);
      }
      if (active.load()) {
        if (samples) lastDataAt = now;
        // Without AFE this is a tunable energy endpoint, not speech recognition.
        const bool speech = afeData ? vadSpeech.load() : (samples && energy / samples > 400ULL * 400ULL);
        if (speech) { lastSpeechAt = now; speechMs += samples * 1000 / 16000; }
        const bool endpoint = !fixed && speechMs >= 250 && now - lastSpeechAt >= 700;
        if (now - lastDataAt >= 1000) {
          active.store(false); noData.store(true);
        } else if (count >= Capacity || now - startedAt >= 8000 || endpoint) {
          active.store(false); ready.store(true);
        }
      }
      if (afeData && active.load()) {
        for (size_t i = 0; i < samples; ++i) {
          feed[feedCount++] = static_cast<int16_t>(raw[i] >> 16);
          if (feedCount == feedSize) { afe->feed(afeData, feed); feedCount = 0; }
        }
      }
      if (!active.load()) disableRxLocked();
      xSemaphoreGive(mutex);
      if (!samples) vTaskDelay(pdMS_TO_TICKS(5));
    }
  }
};
