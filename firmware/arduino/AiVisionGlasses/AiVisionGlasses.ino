/*
 * AI-Vision Glasses prototype for ESP32-S3-CAM / OV2640.
 * Tested with Arduino-ESP32 3.3.11. Android protocol source of truth:
 * android/app/src/main/java/com/example/ai_vision/device/{GlassProtocol,
 * GlassBleProvisioner,V2GlassTransport}.kt
 *
 * BLE provisions the phone's local-only hotspot. Images and PCM go over TCP,
 * never BLE. TCP accepts only the V2 session after HELLO|2.
 * Speaker playback arrives from the app over V2 AUDIO_OUT (protocol §6b).
 * No router credentials, secrets.h or LLM. Hi ESP WakeNet uses model flash.
 */

#include <Arduino.h>
#include <ctype.h>
#include <math.h>
#include <stdint.h>
#include <errno.h>
#include <lwip/sockets.h>
#include <string.h>
#include <WiFi.h>
#if __has_include(<NetworkServer.h>)
#include <Network.h>
#include <NetworkServer.h>
#include <NetworkClient.h>
using TcpServer = NetworkServer;
using TcpClient = NetworkClient;
#else
using TcpServer = WiFiServer;
using TcpClient = WiFiClient;
#endif
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ESP_I2S.h>
// Packaging dependency: Arduino copies srmodels.bin when ESP_SR is discovered.
// Do not call ESP_SR.begin(): MicPipeline is the sole I2S RX owner.
#include <ESP_SR.h>
#include <SD_MMC.h>
#include <FS.h>
#include <esp_camera.h>
#include <esp_system.h>
#include <esp_heap_caps.h>
#include <mbedtls/base64.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include "MicPipeline.h"
#include "VoiceFlow.h"
#include "DeviceCommandExecutor.h"
#include "IntentWorker.h"
#include "PlaybackPolicy.h"

constexpr char BLE_NAME[] = "Ai-Vision Glasses";
constexpr char SERVICE_UUID[] = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
constexpr char RX_UUID[] = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
constexpr char TX_UUID[] = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
constexpr uint16_t TCP_PORT = 5000;
constexpr size_t MAX_JPEG = 5U * 1024U * 1024U;
constexpr size_t MAX_V2_PAYLOAD = 65536;
constexpr size_t MAX_PLAY_CHUNK = 8192; // one app->glass TTS frame, protocol §6b
constexpr size_t MAX_PLAY_TOTAL = glasses::PlaybackMaximumBytes;
constexpr size_t PLAY_RING_BYTES = 65536; // 2 s of PCM in PSRAM, not DMA memory
constexpr size_t PLAY_BLOCK_SAMPLES = 256; // 16 ms per I2S write
constexpr uint32_t PLAY_DRAIN_MS = 120; // I2S DMA can still hold ~90 ms of audio
constexpr uint32_t PLAY_UNDERRUN_MS = 100; // empty ring beyond the DMA headroom
constexpr uint32_t WIFI_TIMEOUT_MS = 20000;
constexpr uint32_t AUDIO_DURATION_MS = 8000;
constexpr uint32_t AUDIO_NO_DATA_MS = 1000;
constexpr size_t AUDIO_SAMPLES_PER_CHUNK = 800; // 50 ms at 16 kHz
constexpr size_t AUDIO_CAPTURE_BYTES = 16000U * 2U * 8U;
constexpr size_t AUDIO_TX_CHUNK = 16384; // V2 allows payloads up to 65536 bytes

// Mapping supplied in docs/hardware.md for this specific board revision.
constexpr int CAM_XCLK = 15, CAM_PCLK = 13, CAM_VSYNC = 6, CAM_HREF = 7;
constexpr int CAM_SDA = 4, CAM_SCL = 5;
constexpr int CAM_D0 = 11, CAM_D1 = 9, CAM_D2 = 8, CAM_D3 = 10;
constexpr int CAM_D4 = 12, CAM_D5 = 18, CAM_D6 = 17, CAM_D7 = 16;
constexpr int MIC_BCLK = 14, MIC_WS = 21, MIC_DATA = 47, SPEAKER_DATA = 1;
constexpr int SD_CLK = 39, SD_CMD = 38, SD_D0 = 40; // 1-bit SDMMC per docs/hardware.md
constexpr size_t MAX_VIDEO_BYTES = 15U * 1024U * 1024U;
constexpr uint32_t MAX_VIDEO_MS = 60000;
constexpr uint32_t VIDEO_FPS = 10;
constexpr size_t MAX_VIDEO_FRAMES = 800;
constexpr size_t VIDEO_CHUNK = 4096;

struct BleCommand {
  char line[256];
};

QueueHandle_t bleCommands = nullptr;
BLEServer *bleServer = nullptr;
BLECharacteristic *bleTx = nullptr;
volatile bool bleConnected = false;
volatile bool bleQueueOverflow = false;
char bleRxLine[sizeof(BleCommand::line)] = {};
size_t bleRxLength = 0;
bool bleRxDropping = false;

TcpServer tcpServer(TCP_PORT);
TcpClient tcpClient;
bool tcpListening = false;
char tcpLine[1025] = {};
size_t tcpLineLength = 0;
uint8_t protocolVersion = 0; // 0 = waiting for HELLO|2, 2 = V2 frames

enum class WifiPhase : uint8_t { Idle, Connecting, Ready };
WifiPhase wifiPhase = WifiPhase::Idle;
uint32_t wifiStartedAt = 0;

bool cameraReady = false;
bool micReady = false;
bool sdReady = false;
I2SClass microphone; // duplex một port: RX mic + TX loa chung BCLK/WS
MicPipeline micPipeline;
glasses::VoiceFlow voiceFlow;
glasses::IntentWorker intentWorker;
glasses::PcmGain speakerGain;
String voiceLeaseId;
bool deferredAudioRelease = false;
bool audioClassifying = false;
bool audioDiagnostic = false;
uint32_t classificationStarted = 0;
uint32_t speakerQuietUntil = 0;
uint32_t tcpLastProgress = 0;
camera_fb_t *pendingPhoto = nullptr;
String pendingMediaId;

bool audioActive = false;
bool audioSending = false;
String audioSessionId;
uint32_t audioStartedAt = 0;
uint32_t audioLastDataAt = 0;
uint32_t audioSeq = 0;
size_t audioTotalBytes = 0;
size_t audioCapturedBytes = 0;
const uint8_t *audioCapture = nullptr;
uint32_t audioSamples = 0;
uint32_t audioPeak = 0;
uint32_t audioClipped = 0;
uint64_t audioEnergy = 0;
uint32_t audioReadMs = 0;
uint32_t audioSendMs = 0;
uint32_t audioMaxReadMs = 0;
uint32_t audioMaxSendMs = 0;
int32_t micRaw[AUDIO_SAMPLES_PER_CHUNK];

bool speakerReady = false;
// Reserve enough PSRAM for the complete reply. Start TX only after END, so
// stop-and-wait/Wi-Fi jitter cannot starve playback during long answers.
uint8_t *playRing = nullptr;
size_t playRingCapacity = 0;
uint8_t *playBaseRing = nullptr; // boot-sized buffer reused by short confirmations
size_t playBaseCapacity = 0;
size_t playRead = 0;
size_t playWrite = 0;
size_t playBuffered = 0;
bool playActive = false;
String playRequestId;
String playSessionId;
String playEndRequestId;
uint32_t playSeq = 0;
size_t playReceived = 0;
size_t playWritten = 0;
size_t playTotal = 0;
uint32_t playStartedAt = 0;
uint32_t playDrainedAt = 0;
uint32_t playEmptyAt = 0;
uint32_t playUnderruns = 0;
uint32_t playQueuedAt = 0;
uint32_t playLastFeedAt = 0;
uint32_t playMaxFeedGapMs = 0;
bool playStarted = false;
bool playStarving = false;
// MJPEG video recording to SD (protocol §3): one frame per loop() so TCP stays alive.
bool recActive = false;
String recRequestId;
String recId;
String recPath;
File recFile;
String pendingVideoId;
String pendingVideoPath;
size_t pendingVideoSize = 0;
String pendingRecId;
size_t videoSize = 0;
uint32_t videoFrames = 0;
uint32_t videoStartMs = 0;
uint32_t videoNextFrameMs = 0;
uint16_t videoWidth = 0;
uint16_t videoHeight = 0;
uint32_t videoIdxOff[MAX_VIDEO_FRAMES];
uint32_t videoIdxSize[MAX_VIDEO_FRAMES];
uint8_t mediaBuf[VIDEO_CHUNK];
size_t finishedVideoBytes = 0;
String currentLanguage = "vi-VN";
// Single app->glass binary frame in flight (AUDIO_OUT_CHUNK).
bool readingPlayPayload = false;
String playHeader;
size_t playNeed = 0;
size_t playHave = 0;
uint8_t playChunk[MAX_PLAY_CHUNK];

// Explicit prototypes keep Arduino's .ino auto-prototype pass out of the way.
void notifyBle(const String &line);
void beginBle();
void speakerSelfTest();
bool decodeBase64Url(const String &encoded, String &decoded);
bool validWifiCredentials(const String &line, String &ssid, String &password);
void startProvisioning(const char *line);
void pollWifi();
bool beginCamera();
bool beginMicrophone();
void microphoneSelfTest();
bool beginSpeaker();
bool beginSD();
bool validJpeg(const camera_fb_t *fb);
void releasePhoto();
void resetAudio(bool preserveVoice = false);
bool captureDevicePhoto();
void finishLocalCommand(const glasses::RecognitionResult &result);
void resetPlayback();
bool reservePlayback(size_t total);
void closeTcp();
bool sendAll(const uint8_t *data, size_t count, uint32_t stallTimeoutMs = 5000);
bool sendText(const String &line);
void handleHandshake(const String &line);
void logMemory(const char *phase);
int jsonValueAt(const String &json, const char *key);
bool jsonString(const String &json, const char *key, String &value);
bool jsonUnsigned(const String &json, const char *key, uint32_t &value);
bool validUuid(const String &id);
String newUuid();
bool sendFrame(const char *type, const String &id, const char *name,
               const String &meta = "{}", const uint8_t *payload = nullptr,
               size_t count = 0);
void sendError(const String &requestId, const char *code);
void sendEvent(const char *name, const String &meta,
               const uint8_t *payload = nullptr, size_t count = 0);
void handleGetMedia(const String &requestId, const String &json);
void handleAudioChunk(const String &json, const uint8_t *data, size_t count);
void pollPlayback();
bool chunkPayloadBytes(const String &json, uint32_t &count);
void write32(File &f, uint32_t v);
void write16(File &f, uint16_t v);
void writeTag(File &f, const char *t);
void videoBegin(const String &id);
void videoStop(const String &id);
void videoAbort();
void autoStopRecording();
void pollVideo();
void handleV2(const String &json);
void pollAudio();
void pollTcp();

class GlassServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    bleConnected = true;
    Serial.println("[BLE] Client connected");
  }
  void onDisconnect(BLEServer *) override {
    bleConnected = false;
    bleRxLength = 0;
    bleRxDropping = false;
    const bool requested = BLEDevice::getAdvertising()->start();
    Serial.printf("[BLE] Client disconnected; advertising restart request: %s\n",
                  requested ? "OK" : "FAILED");
  }
};

class GlassRxCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    // Android writes with response in MTU-sized pieces. Keep the GATT callback
    // short: only collect the LF-terminated frame; Wi-Fi work runs in loop().
    const String piece = characteristic->getValue();
    for (size_t i = 0; i < piece.length(); ++i) {
      const char ch = piece[i];
      if (ch == '\n') {
        if (bleRxDropping) {
          bleQueueOverflow = true;
        } else if (bleRxLength != 0 && bleCommands != nullptr) {
          BleCommand command = {};
          if (bleRxLine[bleRxLength - 1] == '\r') --bleRxLength;
          memcpy(command.line, bleRxLine, bleRxLength);
          command.line[bleRxLength] = '\0';
          if (xQueueSend(bleCommands, &command, 0) != pdTRUE) {
            bleQueueOverflow = true;
          }
        }
        bleRxLength = 0;
        bleRxDropping = false;
      } else if (!bleRxDropping) {
        if (bleRxLength + 1 >= sizeof(bleRxLine)) {
          bleRxLength = 0;
          bleRxDropping = true;
        } else {
          bleRxLine[bleRxLength++] = ch;
        }
      }
    }
  }
};

void notifyBle(const String &line) {
  if (!bleConnected || bleTx == nullptr) return;
  // 20 bytes also works when the requested MTU increase did not succeed.
  for (size_t offset = 0; offset < line.length(); offset += 20) {
    if (!bleConnected) return;
    const size_t count = min(static_cast<size_t>(20), line.length() - offset);
    bleTx->setValue(reinterpret_cast<const uint8_t *>(line.c_str() + offset), count);
    bleTx->notify();
    delay(20);
  }
}

void beginBle() {
  BLEDevice::init(BLE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new GlassServerCallbacks());
  BLEService *service = bleServer->createService(SERVICE_UUID);
  BLECharacteristic *rx = service->createCharacteristic(RX_UUID, BLECharacteristic::PROPERTY_WRITE);
  rx->setCallbacks(new GlassRxCallbacks());
  bleTx = service->createCharacteristic(TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  bleTx->addDescriptor(new BLE2902());
  service->start();

  // Name + flags fit the 31-byte primary ADV; UUID goes in scan response.
  // Android scans by the exact name, so never leave it only in a truncated ADV.
  BLEAdvertisementData adv;
  adv.setFlags(0x06);
  adv.setName(BLE_NAME);
  BLEAdvertisementData scanResponse;
  scanResponse.setCompleteServices(BLEUUID(SERVICE_UUID));
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->setScanResponse(true);
  const bool advSet = advertising->setAdvertisementData(adv);
  const bool scanSet = advertising->setScanResponseData(scanResponse);
  const bool requested = advertising->start();
  Serial.printf("[BLE] name=%s, adv=%d, scan-response=%d, start-request=%d\n",
                BLE_NAME, advSet, scanSet, requested);
}

bool decodeBase64Url(const String &encoded, String &decoded) {
  if (encoded.isEmpty() || encoded.length() > 128) return false;
  String base64 = encoded;
  base64.replace('-', '+');
  base64.replace('_', '/');
  while (base64.length() % 4 != 0) base64 += '=';
  uint8_t bytes[96] = {};
  size_t length = 0;
  const int result = mbedtls_base64_decode(bytes, sizeof(bytes) - 1, &length,
                                            reinterpret_cast<const unsigned char *>(base64.c_str()),
                                            base64.length());
  if (result != 0 || length == 0 || length >= sizeof(bytes) ||
      memchr(bytes, '\0', length) != nullptr) return false;
  bytes[length] = '\0';
  decoded = String(reinterpret_cast<const char *>(bytes));
  memset(bytes, 0, sizeof(bytes));
  return true;
}

bool validWifiCredentials(const String &line, String &ssid, String &password) {
  if (!line.startsWith("WIFI|")) return false;
  const int divider = line.indexOf('|', 5);
  if (divider < 0 || line.indexOf('|', divider + 1) >= 0) return false;
  if (!decodeBase64Url(line.substring(5, divider), ssid) ||
      !decodeBase64Url(line.substring(divider + 1), password)) return false;
  return ssid.length() >= 1 && ssid.length() <= 32 &&
         password.length() >= 8 && password.length() <= 63;
}

void startProvisioning(const char *line) {
  String ssid, password;
  if (!validWifiCredentials(String(line), ssid, password)) {
    notifyBle("ERROR|BAD_WIFI_FRAME\n");
    return;
  }
  closeTcp();
  if (tcpListening) {
    tcpServer.end();
    tcpListening = false;
  }
  WiFi.persistent(false); // do not retain phone hotspot secrets across boots
  WiFi.mode(WIFI_STA);
  WiFi.disconnect(false, true);
  WiFi.begin(ssid.c_str(), password.c_str());
  wifiStartedAt = millis();
  wifiPhase = WifiPhase::Connecting;
  Serial.printf("[Wi-Fi] Connecting to provisioned hotspot (SSID bytes=%u)\n",
                static_cast<unsigned>(ssid.length()));
  password = String();
  ssid = String();
}

void pollWifi() {
  if (wifiPhase == WifiPhase::Connecting) {
    if (WiFi.status() == WL_CONNECTED) {
      IPAddress address = WiFi.localIP();
      if (address == IPAddress(0, 0, 0, 0)) return;
      tcpServer.begin();
      tcpListening = true;
      wifiPhase = WifiPhase::Ready;
      Serial.printf("[TCP] Listening on %s:%u\n", address.toString().c_str(), TCP_PORT);
      notifyBle("WIFI_CONNECTED|" + address.toString() + "|5000\n");
    } else if (millis() - wifiStartedAt >= WIFI_TIMEOUT_MS) {
      wifiPhase = WifiPhase::Idle;
      WiFi.disconnect(false, true);
      notifyBle("ERROR|WIFI_FAILED\n");
      Serial.println("[Wi-Fi] Provisioning timed out");
    }
  } else if (wifiPhase == WifiPhase::Ready && WiFi.status() != WL_CONNECTED) {
    Serial.println("[Wi-Fi] Hotspot connection lost");
    closeTcp();
    if (tcpListening) {
      tcpServer.end();
      tcpListening = false;
    }
    wifiPhase = WifiPhase::Idle;
  }
}

bool beginCamera() {
  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = CAM_D0; config.pin_d1 = CAM_D1;
  config.pin_d2 = CAM_D2; config.pin_d3 = CAM_D3;
  config.pin_d4 = CAM_D4; config.pin_d5 = CAM_D5;
  config.pin_d6 = CAM_D6; config.pin_d7 = CAM_D7;
  config.pin_xclk = CAM_XCLK;
  config.pin_pclk = CAM_PCLK;
  config.pin_vsync = CAM_VSYNC;
  config.pin_href = CAM_HREF;
  config.pin_sccb_sda = CAM_SDA;
  config.pin_sccb_scl = CAM_SCL;
  config.pin_pwdn = -1;
  config.pin_reset = -1;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_QVGA;
  config.jpeg_quality = 14;
  config.fb_count = 1;
  config.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  const esp_err_t result = esp_camera_init(&config);
  Serial.printf("[CAMERA] OV2640 QVGA JPEG: %s (0x%04x), PSRAM=%d\n",
                result == ESP_OK ? "ready" : "FAILED",
                static_cast<unsigned>(result), psramFound());
  return result == ESP_OK;
}

bool beginMicrophone() {
  microphone.setPins(MIC_BCLK, MIC_WS, SPEAKER_DATA, MIC_DATA);
  const bool ready = microphone.begin(I2S_MODE_STD, 16000, I2S_DATA_BIT_WIDTH_32BIT,
                                      I2S_SLOT_MODE_MONO, I2S_STD_SLOT_LEFT);
  Serial.printf("[AUDIO] I2S mic: %s, BCLK=%d WS=%d DIN=%d DOUT=%d\n",
                ready ? "initialized" : "FAILED", MIC_BCLK, MIC_WS, MIC_DATA, SPEAKER_DATA);
  return ready;
}

void microphoneSelfTest() {
  if (!micReady) return;
  uint32_t totalMs = 0;
  size_t totalBytes = 0;
  for (int i = 0; i < 5; ++i) {
    const uint32_t startedAt = millis();
    const size_t got = microphone.readBytes(reinterpret_cast<char *>(micRaw), sizeof(micRaw));
    totalMs += millis() - startedAt;
    totalBytes += got;
    if (got != sizeof(micRaw)) break;
  }
  Serial.printf("[MIC] Boot I2S probe: %u/%u bytes in %u ms, error=%d\n",
                static_cast<unsigned>(totalBytes),
                static_cast<unsigned>(5 * sizeof(micRaw)),
                static_cast<unsigned>(totalMs), microphone.lastError());
}

bool beginSpeaker() {
  if (!micReady) {
    Serial.println("[AUDIO] Speaker unavailable: I2S port was not started by the microphone");
    return false;
  }
  // Duplex một port: TX cấu hình Y HỆT RX đã chứng minh được (32-bit mono LEFT)
  // để timing hai chiều giống nhau tuyệt đối; MAX98357A đọc 16 bit cao của slot.
  const bool ready = microphone.configureTX(16000, I2S_DATA_BIT_WIDTH_32BIT,
                                             I2S_SLOT_MODE_MONO, I2S_STD_SLOT_LEFT);
  Serial.printf("[AUDIO] I2S speaker TX duplex (MAX98357A DIN=%d): %s\n",
                 SPEAKER_DATA, ready ? "ready" : "FAILED");
  if (!ready) return false;
  playRing = static_cast<uint8_t *>(
      heap_caps_malloc(PLAY_RING_BYTES, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
  playRingCapacity = PLAY_RING_BYTES;
  if (playRing == nullptr) {
    playRingCapacity = 16384;
    playRing = static_cast<uint8_t *>(
        heap_caps_malloc(playRingCapacity, MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
  }
  Serial.printf("[AUDIO] PCM playback ring: %u bytes (%s)\n",
                static_cast<unsigned>(playRingCapacity),
                playRing == nullptr ? "FAILED" :
                playRingCapacity == PLAY_RING_BYTES ? "PSRAM" : "internal RAM");
  playBaseRing = playRing;
  playBaseCapacity = playRingCapacity;
  return playRing != nullptr;
}

// Short self-test tone at boot: proves TX -> amp -> speaker without any
// network involved. If this beep is silent, the fault is wiring/SD/I2S-TX,
// not the app protocol (which already logs [AUDIO-OUT] on arrival).
void speakerSelfTest() {
  constexpr uint32_t TONE_HZ = 880;
  constexpr uint32_t SAMPLES = 16000 * 3 / 10; // 0.3 s
  int32_t block[256]; // 32-bit mono như TX duplex
  uint32_t played = 0;
  while (played < SAMPLES) {
    uint32_t count = SAMPLES - played;
    if (count > 256) count = 256;
    for (uint32_t i = 0; i < count; ++i) {
      const float phase = 2.0f * 3.14159265f * TONE_HZ * (played + i) / 16000.0f;
      block[i] = static_cast<int32_t>(static_cast<int16_t>(16000.0f * sinf(phase))) * 65536;
    }
    size_t done = 0;
    uint32_t started = millis();
    while (done < count * sizeof(block[0])) {
      const size_t written = microphone.write(
          reinterpret_cast<const uint8_t *>(block) + done, count * sizeof(block[0]) - done);
      if (written != 0) {
        done += written;
        started = millis();
      } else if (millis() - started >= 3000) {
        Serial.println("[AUDIO] Speaker self-test: I2S write stalled");
        return;
      } else {
        delay(1);
      }
    }
    played += count;
  }
  Serial.println("[AUDIO] Speaker self-test tone played");
}

bool validJpeg(const camera_fb_t *fb) {
  return fb != nullptr && fb->format == PIXFORMAT_JPEG &&
         fb->len >= 4 && fb->len <= MAX_JPEG &&
         fb->width > 0 && fb->width <= 4096 &&
         fb->height > 0 && fb->height <= 4096 &&
         fb->buf[0] == 0xff && fb->buf[1] == 0xd8 &&
         fb->buf[fb->len - 2] == 0xff && fb->buf[fb->len - 1] == 0xd9;
}

void releasePhoto() {
  if (pendingPhoto != nullptr) esp_camera_fb_return(pendingPhoto);
  pendingPhoto = nullptr;
  pendingMediaId = "";
}

void resetPlayback() {
  if (playActive) {
    speakerQuietUntil = millis() + PLAY_DRAIN_MS;
    voiceFlow.reset();
    voiceLeaseId = "";
  }
  playActive = false;
  if (playRing != playBaseRing) heap_caps_free(playRing);
  playRing = playBaseRing;
  playRingCapacity = playBaseCapacity;
  playRequestId = "";
  playSessionId = "";
  playEndRequestId = "";
  playSeq = 0;
  playReceived = 0;
  playWritten = 0;
  playTotal = 0;
  playRead = 0;
  playWrite = 0;
  playBuffered = 0;
  playStartedAt = 0;
  playDrainedAt = 0;
  playEmptyAt = 0;
  playUnderruns = 0;
  playQueuedAt = 0;
  playLastFeedAt = 0;
  playMaxFeedGapMs = 0;
  playStarted = false;
  playStarving = false;
}

bool reservePlayback(size_t total) {
  if (glasses::playbackFits(total, playRingCapacity)) return true;
  if (!glasses::playbackFits(total, MAX_PLAY_TOTAL)) return false;
  uint8_t *response = static_cast<uint8_t *>(
      heap_caps_malloc(total, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
  if (response == nullptr) return false; // do not silently revert to jitter-prone streaming
  playRing = response;
  playRingCapacity = total;
  return true;
}

void resetAudio(bool preserveVoice) {
  intentWorker.cancel();
  if (intentWorker.busy()) deferredAudioRelease = true;
  else {
    glasses::RecognitionResult discarded;
    intentWorker.take(discarded);
    micPipeline.stop();
  }
  audioClassifying = false;
  if (!preserveVoice) { voiceFlow.reset(); voiceLeaseId = ""; }
  audioActive = false;
  audioSending = false;
  audioSessionId = "";
  audioStartedAt = 0;
  audioLastDataAt = 0;
  audioSeq = 0;
  audioTotalBytes = 0;
  audioCapturedBytes = 0;
  audioCapture = nullptr;
}

void closeTcp() {
  if (tcpClient) tcpClient.stop();
  releasePhoto();
  resetAudio();
  if (recActive) autoStopRecording(); // finalize SD file before losing the session
  resetPlayback();
  readingPlayPayload = false;
  playHeader = "";
  playNeed = 0;
  playHave = 0;
  tcpLineLength = 0;
  protocolVersion = 0;
}

bool sendAll(const uint8_t *data, size_t count, uint32_t stallTimeoutMs) {
  size_t sent = 0;
  uint32_t lastProgress = millis();
  while (sent < count) {
    if (!tcpClient || !tcpClient.connected()) return false;
    const size_t step = min(static_cast<size_t>(1460), count - sent);
    const int written = ::send(tcpClient.fd(), data + sent, step, MSG_DONTWAIT);
    if (written > 0) {
      sent += written;
      lastProgress = millis();
    } else {
      if (written < 0 && errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) return false;
      if (millis() - lastProgress >= stallTimeoutMs) return false;
      delay(1);
    }
  }
  return true;
}

bool sendText(const String &line) {
  return sendAll(reinterpret_cast<const uint8_t *>(line.c_str()), line.length());
}

void handleHandshake(const String &line) {
  if (line != "HELLO|2") {
    sendText("ERROR|V2_REQUIRED\n");
    closeTcp();
    return;
  }
  String capabilities;
  if (cameraReady) capabilities += "PHOTO";
  if (micReady) {
    if (!capabilities.isEmpty()) capabilities += ",";
    capabilities += "AUDIO_IN";
    // Manual activation only; no unsolicited wake event capability in this product.
  }
  if (speakerReady) {
    if (!capabilities.isEmpty()) capabilities += ",";
    capabilities += "AUDIO_OUT";
  }
  if (cameraReady && sdReady) {
    if (!capabilities.isEmpty()) capabilities += ",";
    capabilities += "VIDEO";
  }
  if (!sendText("HELLO|2|" + capabilities + "\n")) {
    closeTcp();
    return;
  }
  protocolVersion = 2;
  Serial.printf("[V2] Session started, capabilities=%s\n", capabilities.c_str());
  logMemory("tcp-connected");
}

void logMemory(const char *phase) {
  constexpr uint32_t internal = MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT;
  Serial.printf("[MEM] %s internal=%u largest=%u psram=%u\n", phase,
                static_cast<unsigned>(heap_caps_get_free_size(internal)),
                static_cast<unsigned>(heap_caps_get_largest_free_block(internal)),
                static_cast<unsigned>(ESP.getFreePsram()));
}

// The app currently sends only compact, bounded JSON request headers and no
// request payload. Extract exact simple keys; reject escapes and bad numbers.
// This is not a general-purpose JSON parser and must be replaced if the
// Android request schema changes.
int jsonValueAt(const String &json, const char *key) {
  const String marker = "\"" + String(key) + "\"";
  const int found = json.indexOf(marker);
  if (found < 0) return -1;
  int pos = found + marker.length();
  while (pos < static_cast<int>(json.length()) && isspace(json[pos])) ++pos;
  if (pos >= static_cast<int>(json.length()) || json[pos++] != ':') return -1;
  while (pos < static_cast<int>(json.length()) && isspace(json[pos])) ++pos;
  return pos < static_cast<int>(json.length()) ? pos : -1;
}

bool jsonString(const String &json, const char *key, String &value) {
  int pos = jsonValueAt(json, key);
  if (pos < 0 || json[pos++] != '"') return false;
  const int start = pos;
  while (pos < static_cast<int>(json.length()) && json[pos] != '"') {
    if (json[pos] == '\\' || static_cast<uint8_t>(json[pos]) < 0x20) return false;
    ++pos;
  }
  if (pos >= static_cast<int>(json.length())) return false;
  value = json.substring(start, pos);
  return true;
}

bool jsonUnsigned(const String &json, const char *key, uint32_t &value) {
  int pos = jsonValueAt(json, key);
  if (pos < 0 || !isdigit(json[pos])) return false;
  uint32_t number = 0;
  while (pos < static_cast<int>(json.length()) && isdigit(json[pos])) {
    const uint8_t digit = json[pos++] - '0';
    if (number > (UINT32_MAX - digit) / 10) return false;
    number = number * 10 + digit;
  }
  if (pos < static_cast<int>(json.length()) &&
      json[pos] != ',' && json[pos] != '}' && !isspace(json[pos])) return false;
  value = number;
  return true;
}

bool validUuid(const String &id) {
  if (id.length() != 36) return false;
  for (size_t i = 0; i < id.length(); ++i) {
    if (i == 8 || i == 13 || i == 18 || i == 23) {
      if (id[i] != '-') return false;
    } else if (!isxdigit(id[i])) {
      return false;
    }
  }
  return true;
}

String newUuid() {
  char id[37];
  const uint32_t a = esp_random(), b = esp_random(), c = esp_random(), d = esp_random();
  snprintf(id, sizeof(id), "%08lx-%04x-%04x-%04x-%04x%08lx",
           static_cast<unsigned long>(a), static_cast<unsigned>(b >> 16),
           static_cast<unsigned>(b & 0xffff), static_cast<unsigned>(c >> 16),
           static_cast<unsigned>(c & 0xffff), static_cast<unsigned long>(d));
  return String(id);
}

bool sendFrame(const char *type, const String &id, const char *name,
               const String &meta, const uint8_t *payload, size_t count) {
  if (!validUuid(id) || count > MAX_V2_PAYLOAD ||
      (count != 0 && payload == nullptr)) return false;
  char header[1025];
  const int length = snprintf(header, sizeof(header),
      "{\"v\":2,\"type\":\"%s\",\"id\":\"%s\",\"name\":\"%s\",\"payloadBytes\":%u,\"meta\":%s}\n",
      type, id.c_str(), name, static_cast<unsigned>(count), meta.c_str());
  if (length < 0 || length > 1024) return false;
  return sendAll(reinterpret_cast<const uint8_t *>(header), length) &&
         (count == 0 || sendAll(payload, count));
}

void sendError(const String &requestId, const char *code) {
  if (!sendFrame("error", requestId, "ERROR",
                 "{\"code\":\"" + String(code) + "\"}")) closeTcp();
}

void sendEvent(const char *name, const String &meta,
               const uint8_t *payload, size_t count) {
  if (!sendFrame("event", newUuid(), name, meta, payload, count)) {
    Serial.printf("[V2] Failed to send event %s; closing TCP\n", name);
    closeTcp();
  }
}

void handleGetMedia(const String &requestId, const String &json) {
  String requestedId;
  if (!jsonString(json, "mediaId", requestedId)) {
    sendError(requestId, "MEDIA_NOT_FOUND");
    return;
  }
  if (pendingPhoto != nullptr && requestedId == pendingMediaId) {
    const String mediaId = pendingMediaId; // our generated ID contains safe ASCII
    const size_t total = pendingPhoto->len;
    uint32_t seq = 0;
    bool delivered = true;
    for (size_t offset = 0; offset < total; offset += 4096, ++seq) {
      const size_t count = min(static_cast<size_t>(4096), total - offset);
      const String meta = "{\"mediaId\":\"" + mediaId + "\",\"seq\":" + String(seq) +
                          ",\"offset\":" + String(static_cast<unsigned>(offset)) +
                          ",\"totalSize\":" + String(static_cast<unsigned>(total)) + "}";
      if (!sendFrame("response", requestId, "MEDIA_CHUNK",
                     meta, pendingPhoto->buf + offset, count)) {
        delivered = false;
        break;
      }
    }
    releasePhoto();
    if (delivered) {
      const String meta = "{\"mediaId\":\"" + mediaId + "\"}";
      delivered = sendFrame("response", requestId, "MEDIA_END", meta);
    }
    Serial.printf("[V2] Media bytes=%u, delivered=%d\n",
                  static_cast<unsigned>(total), delivered);
    if (!delivered) closeTcp();
    return;
  }
  if (requestedId == pendingVideoId && pendingVideoSize > 0) {
    File video = SD_MMC.open(pendingVideoPath, FILE_READ);
    if (!video || video.size() != pendingVideoSize) {
      if (video) video.close();
      sendError(requestId, "MEDIA_NOT_FOUND");
      return;
    }
    const String mediaId = pendingVideoId;
    const size_t total = pendingVideoSize;
    uint32_t seq = 0;
    bool delivered = true;
    for (size_t offset = 0; offset < total; offset += VIDEO_CHUNK, ++seq) {
      const size_t count = min(VIDEO_CHUNK, total - offset);
      if (video.read(mediaBuf, count) != count) {
        delivered = false;
        break;
      }
      const String meta = "{\"mediaId\":\"" + mediaId + "\",\"seq\":" + String(seq) +
                          ",\"offset\":" + String(static_cast<unsigned>(offset)) +
                          ",\"totalSize\":" + String(static_cast<unsigned>(total)) + "}";
      if (!sendFrame("response", requestId, "MEDIA_CHUNK", meta, mediaBuf, count)) {
        delivered = false;
        break;
      }
    }
    video.close();
    if (delivered) {
      const String meta = "{\"mediaId\":\"" + mediaId + "\"}";
      delivered = sendFrame("response", requestId, "MEDIA_END", meta);
    }
    Serial.printf("[V2] Video bytes=%u, delivered=%d\n",
                  static_cast<unsigned>(total), delivered);
    if (!delivered) closeTcp();
    return;
  }
  sendError(requestId, "MEDIA_NOT_FOUND");
}

void write32(File &f, uint32_t v) {
  uint8_t b[4] = {static_cast<uint8_t>(v), static_cast<uint8_t>(v >> 8),
                  static_cast<uint8_t>(v >> 16), static_cast<uint8_t>(v >> 24)};
  f.write(b, 4);
}

void write16(File &f, uint16_t v) {
  uint8_t b[2] = {static_cast<uint8_t>(v), static_cast<uint8_t>(v >> 8)};
  f.write(b, 2);
}

void writeTag(File &f, const char *t) {
  f.write(reinterpret_cast<const uint8_t *>(t), 4);
}

bool beginSD() {
  SD_MMC.setPins(SD_CLK, SD_CMD, SD_D0);
  if (!SD_MMC.begin("/sdcard", true)) {
    Serial.println("[SD] Mount FAILED (1-bit SDMMC)");
    return false;
  }
  if (SD_MMC.cardType() == CARD_NONE) {
    Serial.println("[SD] No card attached");
    SD_MMC.end();
    return false;
  }
  Serial.printf("[SD] Ready, total=%lluMB free=%lluMB\n",
                SD_MMC.totalBytes() / (1024ULL * 1024ULL),
                (SD_MMC.totalBytes() - SD_MMC.usedBytes()) / (1024ULL * 1024ULL));
  return true;
}

// Minimal MJPEG AVI header (224 bytes); frame dims come from the first frame.
// Patch positions: riff size @4, avih frames @48, strh length @140, movi size @216.
bool videoWriteHeader(camera_fb_t *fb) {
  videoWidth = fb->width;
  videoHeight = fb->height;
  writeTag(recFile, "RIFF"); write32(recFile, 0); writeTag(recFile, "AVI ");
  writeTag(recFile, "LIST"); write32(recFile, 192); writeTag(recFile, "hdrl");
  writeTag(recFile, "avih"); write32(recFile, 56);
  write32(recFile, 1000000 / VIDEO_FPS);
  write32(recFile, 100000);
  write32(recFile, 0);
  write32(recFile, 0x10); // AVIF_HASINDEX
  write32(recFile, 0);    // dwTotalFrames -> patch
  write32(recFile, 0);
  write32(recFile, 1);
  write32(recFile, 20000);
  write32(recFile, videoWidth);
  write32(recFile, videoHeight);
  write32(recFile, 0); write32(recFile, 0); write32(recFile, 0); write32(recFile, 0);
  writeTag(recFile, "LIST"); write32(recFile, 116); writeTag(recFile, "strl");
  writeTag(recFile, "strh"); write32(recFile, 56);
  writeTag(recFile, "vids"); writeTag(recFile, "MJPG");
  write32(recFile, 0);
  write16(recFile, 0);
  write16(recFile, 0);
  write32(recFile, 0);
  write32(recFile, 1); // dwScale
  write32(recFile, VIDEO_FPS); // dwRate
  write32(recFile, 0);
  write32(recFile, 0); // dwLength -> patch
  write32(recFile, 20000);
  write32(recFile, 0xFFFFFFFF); // dwQuality: driver default
  write32(recFile, 0);
  write16(recFile, 0); write16(recFile, 0);
  write16(recFile, videoWidth); write16(recFile, videoHeight);
  writeTag(recFile, "strf"); write32(recFile, 40);
  write32(recFile, 40);
  write32(recFile, videoWidth);
  write32(recFile, videoHeight);
  write16(recFile, 1);
  write16(recFile, 24);
  writeTag(recFile, "MJPG");
  write32(recFile, (uint32_t)videoWidth * videoHeight * 3);
  write32(recFile, 0); write32(recFile, 0); write32(recFile, 0); write32(recFile, 0);
  writeTag(recFile, "LIST"); write32(recFile, 0); // movi size -> patch
  writeTag(recFile, "movi");
  videoSize = 224;
  return recFile.position() == 224;
}

bool videoAppend(camera_fb_t *fb) {
  if (fb->width != videoWidth || fb->height != videoHeight) return true; // skip odd frame
  if (videoFrames >= MAX_VIDEO_FRAMES || videoSize + fb->len + 9 > MAX_VIDEO_BYTES) return false;
  videoIdxOff[videoFrames] = (uint32_t)(videoSize - 220); // relative to the movi FOURCC; first chunk offset=4
  videoIdxSize[videoFrames] = (uint32_t)fb->len;
  writeTag(recFile, "00dc");
  write32(recFile, (uint32_t)fb->len);
  recFile.write(fb->buf, fb->len);
  if (fb->len % 2 != 0) recFile.write((uint8_t)0);
  videoSize += 8 + fb->len + (fb->len % 2);
  ++videoFrames;
  return true;
}

// Patches sizes, appends idx1 and closes. On any failure the partial file is
// removed so the card cannot fill with unplayable fragments.
bool videoFinish(bool keep) {
  finishedVideoBytes = 0;
  bool ok = keep && recFile && videoFrames > 0 && recFile.position() == videoSize;
  if (ok) {
    const uint32_t idxBytes = videoFrames * 16;
    ok = recFile.seek(216, SeekSet);
    if (ok) write32(recFile, (uint32_t)(videoSize - 220)); // includes the 4-byte movi tag
    if (ok && !recFile.seek(videoSize, SeekSet)) ok = false;
    if (ok) {
      writeTag(recFile, "idx1");
      write32(recFile, idxBytes);
      for (uint32_t i = 0; i < videoFrames && ok; ++i) {
        writeTag(recFile, "00dc");
        write32(recFile, 0x10);
        write32(recFile, videoIdxOff[i]);
        write32(recFile, videoIdxSize[i]);
      }
    }
    finishedVideoBytes = (uint32_t)(videoSize + 8 + idxBytes);
    if (ok && !recFile.seek(4, SeekSet)) ok = false;
    if (ok) write32(recFile, finishedVideoBytes - 8); // riff size
    if (ok && !recFile.seek(48, SeekSet)) ok = false;
    if (ok) write32(recFile, videoFrames); // avih frames
    if (ok && !recFile.seek(140, SeekSet)) ok = false;
    if (ok) write32(recFile, videoFrames); // strh length
    if (ok) {
      recFile.flush();
      recFile.close();
      File check = SD_MMC.open(recPath, FILE_READ);
      ok = check && check.size() == finishedVideoBytes;
      if (check) check.close();
    }
  }
  if (!ok) {
    finishedVideoBytes = 0;
    if (recFile) recFile.close();
    if (recPath.length() > 0) SD_MMC.remove(recPath);
  }
  return ok;
}

void videoAbort() {
  if (recFile) recFile.close();
  if (recPath.length() > 0) SD_MMC.remove(recPath);
  recActive = false;
  recRequestId = "";
  recId = "";
  recPath = "";
  videoSize = 0;
  videoFrames = 0;
}

void videoBegin(const String &id) {
  if (!cameraReady) {
    sendError(id, "CAMERA_UNAVAILABLE");
    return;
  }
  if (!sdReady) {
    sendError(id, "SD_MISSING");
    return;
  }
  if (recActive || audioActive || playActive) {
    sendError(id, "VIDEO_BUSY");
    return;
  }
  if (SD_MMC.totalBytes() - SD_MMC.usedBytes() < 20ULL * 1024ULL * 1024ULL) {
    sendError(id, "SD_FULL");
    return;
  }
  // Never discard an undownloaded recording just because a new one starts.
  if (pendingVideoPath.length() > 0) {
    sendError(id, "MEDIA_PENDING");
    return;
  }
  pendingVideoId = "";
  pendingVideoPath = "";
  pendingVideoSize = 0;
  pendingRecId = "";
  camera_fb_t *fb = esp_camera_fb_get();
  if (!validJpeg(fb)) {
    if (fb != nullptr) esp_camera_fb_return(fb);
    sendError(id, "CAPTURE_FAILED");
    return;
  }
  recPath = "/avision_" + String(esp_random(), HEX) + ".avi";
  recFile = SD_MMC.open(recPath, FILE_WRITE);
  if (!recFile || !videoWriteHeader(fb) || !videoAppend(fb)) {
    esp_camera_fb_return(fb);
    if (recFile) recFile.close();
    SD_MMC.remove(recPath);
    recPath = "";
    videoSize = 0;
    videoFrames = 0;
    sendError(id, "RECORDING_FAILED");
    return;
  }
  esp_camera_fb_return(fb);
  recId = newUuid();
  recRequestId = id;
  recActive = true;
  videoStartMs = millis();
  videoNextFrameMs = videoStartMs + 1000U / VIDEO_FPS;
  const String meta = "{\"recordingId\":\"" + recId + "\"}";
  if (!sendFrame("response", id, "VIDEO_STARTED", meta)) closeTcp();
  else Serial.printf("[VIDEO] Recording %s started -> %s\n", recId.c_str(), recPath.c_str());
}

void videoStop(const String &id) {
  if (recActive) {
    const String doneRec = recId;
    const String donePath = recPath;
    if (videoFinish(true)) {
      pendingVideoId = "video-" + String(esp_random(), HEX);
      pendingVideoPath = donePath;
      pendingVideoSize = finishedVideoBytes;
      pendingRecId = doneRec;
      recActive = false;
      recRequestId = "";
      recId = "";
      recPath = "";
      videoSize = 0;
      videoFrames = 0;
      const String meta = "{\"recordingId\":\"" + doneRec +
                          "\",\"mediaId\":\"" + pendingVideoId +
                          "\",\"mime\":\"video/x-msvideo\",\"size\":" +
                          String(static_cast<unsigned>(pendingVideoSize)) + "}";
      if (!sendFrame("response", id, "VIDEO_STOPPED", meta)) closeTcp();
      else Serial.printf("[VIDEO] Stopped %s, media=%s bytes=%u\n",
                         doneRec.c_str(), pendingVideoId.c_str(),
                         static_cast<unsigned>(pendingVideoSize));
    } else {
      videoAbort();
      sendError(id, "RECORDING_FAILED");
    }
    return;
  }
  if (pendingVideoId.length() > 0) {
    const String meta = "{\"recordingId\":\"" + pendingRecId +
                        "\",\"mediaId\":\"" + pendingVideoId +
                        "\",\"mime\":\"video/x-msvideo\",\"size\":" +
                        String(static_cast<unsigned>(pendingVideoSize)) + "}";
    if (!sendFrame("response", id, "VIDEO_STOPPED", meta)) closeTcp();
    return;
  }
  sendError(id, "VIDEO_NOT_RECORDING");
}

void autoStopRecording() {
  const String donePath = recPath;
  if (videoFinish(true)) {
    pendingVideoId = "video-" + String(esp_random(), HEX);
    pendingVideoPath = donePath;
    pendingVideoSize = finishedVideoBytes;
    pendingRecId = recId;
    recActive = false;
    recRequestId = "";
    recId = "";
    recPath = "";
    videoSize = 0;
    videoFrames = 0;
    const String meta = "{\"mediaId\":\"" + pendingVideoId +
                        "\",\"mime\":\"video/x-msvideo\",\"size\":" +
                        String(static_cast<unsigned>(pendingVideoSize)) + "}";
    if (tcpClient && tcpClient.connected() && protocolVersion == 2) sendEvent("MEDIA_AVAILABLE", meta);
    Serial.printf("[VIDEO] Auto-stopped at cap, media=%s bytes=%u\n",
                  pendingVideoId.c_str(), static_cast<unsigned>(pendingVideoSize));
  } else {
    videoAbort();
    if (tcpClient && tcpClient.connected() && protocolVersion == 2)
      sendEvent("DEVICE_ERROR", "{\"code\":\"RECORDING_FAILED\"}");
  }
}

void pollVideo() {
  if (!recActive || !sdReady) return;
  if (millis() - videoStartMs >= MAX_VIDEO_MS || videoFrames >= MAX_VIDEO_FRAMES ||
      videoSize >= MAX_VIDEO_BYTES) {
    autoStopRecording();
    return;
  }
  // fb_count=1: TAKE_PHOTO owns that buffer until GET_MEDIA finishes.
  // Waiting for another frame here would starve the TCP request that releases it.
  if (pendingPhoto != nullptr) return;
  // Capture at the frame rate written into the AVI header instead of taking a
  // frame on every loop iteration (which rapidly filled SD and starved audio).
  if (static_cast<int32_t>(millis() - videoNextFrameMs) < 0) return;
  videoNextFrameMs = millis() + 1000U / VIDEO_FPS;
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb == nullptr) return;
  if (!validJpeg(fb) || fb->width != videoWidth || fb->height != videoHeight) {
    esp_camera_fb_return(fb);
    return;
  }
  if (!videoAppend(fb)) {
    esp_camera_fb_return(fb);
    autoStopRecording();
    return;
  }
  esp_camera_fb_return(fb);
}

// True when this V2 header announces an app->glass playback chunk; the caller
// must then read exactly `count` binary bytes before handling anything else.
bool chunkPayloadBytes(const String &json, uint32_t &count) {  String type, name;
  if (!jsonString(json, "type", type) || type != "request") return false;
  if (!jsonString(json, "name", name) || name != "AUDIO_OUT_CHUNK") return false;
  return jsonUnsigned(json, "payloadBytes", count);
}

void handleAudioChunk(const String &json, const uint8_t *data, size_t count) {
  String id, sessionId;
  uint32_t seq = 0, version = 0;
  const int metaPos = jsonValueAt(json, "meta");
  if (!jsonUnsigned(json, "v", version) || version != 2 || metaPos < 0 || json[metaPos] != '{' ||
      !jsonString(json, "id", id) || !validUuid(id)) {
    // Cannot answer without a valid id; resync is impossible.
    closeTcp();
    return;
  }
  const bool ok = playActive && speakerReady && playRing != nullptr &&
      playEndRequestId.isEmpty() &&
      jsonString(json, "sessionId", sessionId) && sessionId == playSessionId &&
      jsonUnsigned(json, "seq", seq) && seq == playSeq &&
      count >= 2 && count <= MAX_PLAY_CHUNK && (count % 2) == 0 &&
      playBuffered <= playRingCapacity && count <= playRingCapacity - playBuffered &&
      playReceived + count <= playTotal;
  if (!ok) {
    resetPlayback();
    sendError(id, "BAD_PLAYBACK_SESSION");
    return;
  }
  // Capacity was reserved at PLAY_AUDIO; ACK immediately after copying, with
  // no drain/backpressure cycle that could start speaking before all data arrives.
  const size_t first = min(count, playRingCapacity - playWrite);
  memcpy(playRing + playWrite, data, first);
  if (first < count) memcpy(playRing, data + first, count - first);
  playWrite = (playWrite + count) % playRingCapacity;
  playBuffered += count;
  playReceived += count;
  ++playSeq;
  playStarving = false;
  playEmptyAt = 0;
  const String meta = "{\"sessionId\":\"" + playSessionId +
                      "\",\"seq\":" + String(seq) + "}";
  // ACK means queued, not played. TX starts only after validated AUDIO_OUT_END.
  if (!sendFrame("response", id, "AUDIO_OUT_ACK", meta)) {
    closeTcp();
    return;
  }
}

void pollPlayback() {
  if (!playActive || playRing == nullptr) return;
  if (!playStarted) {
    if (!glasses::playbackReady(playTotal, playReceived, !playEndRequestId.isEmpty())) return;
    playStarted = true;
    playStartedAt = millis();
    Serial.printf("[AUDIO-OUT] I2S full-preload=%u bytes, receiveMs=%u\n",
                  static_cast<unsigned>(playBuffered),
                  static_cast<unsigned>(playStartedAt - playQueuedAt));
  }
  // 16 ms blocks keep Cancel/parser responsive. All PCM is already local;
  // hardware DMA continues sending already-written samples between calls.
  if (playBuffered >= 2) {
    const uint32_t feedAt = millis();
    if (playLastFeedAt != 0) playMaxFeedGapMs = max(playMaxFeedGapMs, feedAt - playLastFeedAt);
    playLastFeedAt = feedAt;
    int32_t block[PLAY_BLOCK_SAMPLES];
    const size_t samples = min(PLAY_BLOCK_SAMPLES, playBuffered / 2);
    size_t nextRead = playRead;
    for (size_t i = 0; i < samples; ++i) {
      const int16_t pcm = static_cast<int16_t>(
          playRing[nextRead] | (playRing[nextRead + 1] << 8));
      block[i] = static_cast<int32_t>(speakerGain.apply(pcm)) * 65536;
      nextRead += 2;
      if (nextRead == playRingCapacity) nextRead = 0;
    }
    const uint8_t *output = reinterpret_cast<const uint8_t *>(block);
    const size_t outputBytes = samples * sizeof(block[0]);
    size_t done = 0;
    uint32_t lastProgress = millis();
    while (done < outputBytes) {
      if (!tcpClient || !tcpClient.connected()) {
        closeTcp();
        return;
      }
      size_t written = 0;
      i2s_channel_write(microphone.txChan(), output + done, outputBytes - done, &written, 100);
      if (written > outputBytes - done || (written % sizeof(block[0])) != 0) {
        Serial.println("[AUDIO-OUT] I2S returned a partial sample; closing socket");
        closeTcp();
        return;
      }
      if (written != 0) {
        done += written;
        lastProgress = millis();
      } else if (millis() - lastProgress >= 3000) {
        Serial.println("[AUDIO-OUT] I2S write stalled; closing socket");
        closeTcp();
        return;
      } else {
        delay(1);
      }
    }
    playRead = nextRead;
    playBuffered -= samples * 2;
    playWritten += samples * 2;
  }
  if (playBuffered == 0 && playWritten < playTotal) {
    if (playEmptyAt == 0) playEmptyAt = millis();
    if (!playStarving && millis() - playEmptyAt >= PLAY_UNDERRUN_MS) {
      playStarving = true;
      ++playUnderruns;
      Serial.printf("[AUDIO-OUT] Buffer underrun #%u at %u/%u bytes\n",
                    static_cast<unsigned>(playUnderruns),
                    static_cast<unsigned>(playWritten),
                    static_cast<unsigned>(playTotal));
    }
  }
  if (playWritten != playTotal || playBuffered != 0) return;
  if (playDrainedAt == 0) playDrainedAt = millis();
  if (playEndRequestId.isEmpty() || millis() - playDrainedAt < PLAY_DRAIN_MS) return;
  const String doneId = playSessionId;
  const String responseId = playEndRequestId;
  const size_t doneBytes = playWritten;
  const uint32_t elapsedMs = millis() - playStartedAt;
  const uint32_t underruns = playUnderruns;
  const uint32_t maxFeedGap = playMaxFeedGapMs;
  const String meta = "{\"sessionId\":\"" + doneId + "\"}";
  if (!sendFrame("response", responseId, "AUDIO_PLAYED", meta)) {
    closeTcp();
    return;
  }
  Serial.printf("[AUDIO-OUT] Played %s, %u bytes, elapsed=%u ms, underruns=%u, maxFeedGapMs=%u\n",
                doneId.c_str(), static_cast<unsigned>(doneBytes),
                static_cast<unsigned>(elapsedMs), static_cast<unsigned>(underruns),
                static_cast<unsigned>(maxFeedGap));
  resetPlayback();
  logMemory("speaker-end");
}

void handleV2(const String &json) {
  String type, id, name;
  uint32_t version = 0, payloadBytes = UINT32_MAX;
  const int metaPos = jsonValueAt(json, "meta");
  if (!jsonUnsigned(json, "v", version) || version != 2 ||
      !jsonString(json, "type", type) || type != "request" ||
      !jsonString(json, "id", id) || !validUuid(id) ||
      !jsonString(json, "name", name) ||
      !jsonUnsigned(json, "payloadBytes", payloadBytes) || payloadBytes != 0 ||
      metaPos < 0 || json[metaPos] != '{') {
    Serial.println("[V2] Malformed request; closing socket");
    closeTcp(); // no safe way to resynchronize an unknown binary payload
    return;
  }

  if (name == "PING") {
    if (!sendFrame("response", id, "PONG")) closeTcp();
  } else if (name == "GET_STATUS") {
    const String meta = "{\"wifi\":true,\"rssi\":" + String(WiFi.RSSI()) +
      ",\"recording\":" + String(recActive ? "true" : "false") +
      ",\"wakeReady\":false,\"wakeWord\":\"\",\"pendingVideo\":" + String(pendingVideoId.length() ? "true" : "false") + "}";
    if (!sendFrame("response", id, "STATUS", meta)) closeTcp();
  } else if (name == "TAKE_PHOTO") {
    if (!cameraReady) {
      sendError(id, "CAMERA_UNAVAILABLE");
      return;
    }
    if (recActive || audioActive || playActive || intentWorker.busy()) { sendError(id, "BUSY"); return; }
    if (!captureDevicePhoto()) {
      sendError(id, "CAPTURE_FAILED");
      return;
    }
    camera_fb_t *fb = pendingPhoto;
    const String meta = "{\"mediaId\":\"" + pendingMediaId +
                        "\",\"mime\":\"image/jpeg\",\"size\":" +
                        String(static_cast<unsigned>(fb->len)) +
                        ",\"width\":" + String(fb->width) +
                        ",\"height\":" + String(fb->height) + "}";
    if (!sendFrame("response", id, "PHOTO_CAPTURED", meta)) closeTcp();
  } else if (name == "GET_MEDIA") {
    handleGetMedia(id, json);
  } else if (name == "ACK_MEDIA") {
    String mediaId;
    if (!jsonString(json, "mediaId", mediaId) || mediaId != pendingVideoId || mediaId.isEmpty()) {
      sendError(id, "MEDIA_NOT_FOUND");
    } else {
      // Gallery save was acknowledged. Keep the SD file; permit next recording.
      pendingVideoId = "";
      pendingVideoPath = "";
      pendingVideoSize = 0;
      pendingRecId = "";
      if (!sendFrame("response", id, "MEDIA_ACKNOWLEDGED")) closeTcp();
    }
  } else if (name == "START_VIDEO") {
    videoBegin(id);
  } else if (name == "STOP_VIDEO") {
    videoStop(id);
  } else if (name == "SET_VOICE_CONFIG") {
    String language, wake;
    if (!jsonString(json, "language", language) || (language != "vi-VN" && language != "en-US")) {
      sendError(id, "BAD_LANGUAGE");
    } else if (!jsonString(json, "wakeWord", wake) || (wake != "Hi ESP" && wake != "")) {
      sendError(id, "WAKE_MODEL_UNAVAILABLE");
    } else if (wake.length()) {
      sendError(id, "WAKE_MODEL_UNAVAILABLE");
    } else {
      currentLanguage = language;
      if (!sendFrame("response", id, "VOICE_CONFIGURED")) closeTcp();
    }
  } else if (name == "STOP_LISTENING") {
    resetAudio();
    if (!sendFrame("response", id, "LISTENING_STOPPED")) closeTcp();
  } else if (name == "START_LISTENING") {
    String language;
    if (!micReady) {
      sendError(id, "MIC_UNAVAILABLE");
    } else if (audioActive || playActive || recActive || !voiceFlow.idle() || intentWorker.busy() || deferredAudioRelease ||
               (speakerQuietUntil != 0 && static_cast<int32_t>(millis() - speakerQuietUntil) < 0)) {
      sendError(id, "AUDIO_BUSY");
    } else if (!jsonString(json, "language", language) ||
               (language != "vi-VN" && language != "en-US")) {
      sendError(id, "BAD_LANGUAGE");
    } else {
      String wake;
      if (jsonString(json, "wakeWord", wake)) {
        if (wake.length() > 32) {
          sendError(id, "INVALID_FRAME");
          return;
        }
        // This field never changes the trained model. SET_VOICE_CONFIG enables wake.
      }
      resetAudio();
      String mode;
      jsonString(json, "mode", mode);
      audioDiagnostic = mode == "diagnostic";
      if (!micPipeline.start(mode == "diagnostic")) {
        sendError(id, "AUDIO_UNAVAILABLE");
        return;
      }
      audioSessionId = newUuid();
      voiceLeaseId = audioSessionId;
      voiceFlow.start();
      currentLanguage = language;
      audioCapture = micPipeline.bytes();
      const String meta = "{\"sessionId\":\"" + audioSessionId + "\"}";
      if (!sendFrame("response", id, "LISTENING_STARTED", meta)) {
        closeTcp();
        return;
      }
      audioSeq = 0;
      audioTotalBytes = 0;
      audioSamples = 0;
      audioPeak = 0;
      audioClipped = 0;
      audioEnergy = 0;
      audioReadMs = 0;
      audioSendMs = 0;
      audioMaxReadMs = 0;
      audioMaxSendMs = 0;
      audioStartedAt = millis();
      audioLastDataAt = audioStartedAt;
      audioActive = true;
      Serial.printf("[V2] Audio session %s started (%s), capture=%u PSRAM bytes\n",
                    audioSessionId.c_str(), language.c_str(),
                    static_cast<unsigned>(AUDIO_CAPTURE_BYTES));
      logMemory("mic-start");
    }
  } else if (name == "PLAY_AUDIO") {
    String encoding;
    uint32_t sampleRate = 0, channels = 0, total = 0;
    if (!speakerReady) {
      sendError(id, "SPEAKER_UNAVAILABLE");
    } else if (playActive || audioActive || recActive || intentWorker.busy() ||
               (voiceFlow.state() != glasses::VoiceState::Idle && voiceFlow.state() != glasses::VoiceState::WaitingAndroid)) {
      sendError(id, "AUDIO_OUT_BUSY");
    } else if (!jsonString(json, "encoding", encoding) || encoding != "PCM_S16LE" ||
               !jsonUnsigned(json, "sampleRate", sampleRate) || sampleRate != 16000 ||
               !jsonUnsigned(json, "channels", channels) || channels != 1 ||
               !jsonUnsigned(json, "totalBytes", total) || total < 2 ||
               total > MAX_PLAY_TOTAL || (total % 2) != 0) {
      sendError(id, "BAD_AUDIO_FORMAT");
    } else {
      resetPlayback();
      if (!reservePlayback(total)) {
        sendError(id, "AUDIO_BUFFER_UNAVAILABLE");
        return;
      }
      playSessionId = newUuid();
      playRequestId = id;
      playTotal = total;
      playActive = true;
      playQueuedAt = millis();
      voiceFlow.play();
      tcpLastProgress = millis();
      const String meta = "{\"sessionId\":\"" + playSessionId + "\"}";
      if (!sendFrame("response", id, "AUDIO_READY", meta)) {
        closeTcp();
        return;
      }
      Serial.printf("[AUDIO-OUT] Playback %s started, total=%u bytes\n",
                    playSessionId.c_str(), static_cast<unsigned>(total));
      logMemory("speaker-start");
    }
  } else if (name == "AUDIO_OUT_END") {
    String sessionId;
    uint32_t seq = 0;
    if (!playActive || !jsonString(json, "sessionId", sessionId) ||
        sessionId != playSessionId || !jsonUnsigned(json, "seq", seq) ||
        seq != playSeq || playReceived != playTotal ||
        !playEndRequestId.isEmpty()) {
      resetPlayback();
      sendError(id, "BAD_PLAYBACK_SESSION");
    } else {
      // The response is deferred until the ring and the I2S DMA tail drain.
      playEndRequestId = id;
    }
  } else if (name == "CANCEL") {
    String targetId;
    if (!jsonString(json, "targetId", targetId)) {
      sendError(id, "UNKNOWN_TARGET");
    } else if (playActive && targetId == playRequestId) {
      resetPlayback();
      if (!sendFrame("response", id, "CANCELLED")) closeTcp();
      else Serial.println("[AUDIO-OUT] Playback cancelled");
    } else if (recActive && targetId == recRequestId) {
      videoAbort();
      if (!sendFrame("response", id, "CANCELLED")) closeTcp();
      else Serial.println("[VIDEO] Recording cancelled");
    } else {
      sendError(id, "UNKNOWN_TARGET");
    }
  } else {
    sendError(id, "UNKNOWN_COMMAND");
  }
}

bool captureDevicePhoto() {
  if (!cameraReady) return false;
  releasePhoto();
  camera_fb_t *fb = esp_camera_fb_get();
  if (!validJpeg(fb)) { if (fb) esp_camera_fb_return(fb); return false; }
  pendingPhoto = fb;
  pendingMediaId = "photo-" + String(esp_random(), HEX);
  return true;
}

class FirmwareActions final : public glasses::DeviceActions {
 public:
  bool takePhoto() override { return captureDevicePhoto(); }
  void stop() override { resetAudio(); }
  void changeVolume(int direction) override { speakerGain.change(direction); }
  bool connected() const override { return WiFi.status() == WL_CONNECTED; }
};

void finishLocalCommand(const glasses::RecognitionResult &result) {
  const String sessionId = audioSessionId;
  resetAudio(true); // Worker returned; its borrowed PCM may now be released.
  FirmwareActions device;
  const auto status = glasses::executeCommand(result, glasses::localIntentProvider().minimumConfidence(), device);
  const bool success = status == glasses::CommandStatus::Success;
  const char *outcome = success ? "SUCCESS" : status == glasses::CommandStatus::ReplayOnAndroid ? "REPLAY_ON_ANDROID" : "FAILED";
  const bool vietnamese = currentLanguage == "vi-VN";
  const char *confirmation = vietnamese ? "Đã thực hiện." : "Done.";
  if (!success) confirmation = vietnamese ? "Chưa thực hiện được lệnh." : "Command could not be completed.";
  if (result.intent == glasses::LocalIntent::GetStatus && success)
    confirmation = vietnamese ? "Kính đang kết nối. Chưa có cảm biến đo mức pin." : "Glasses connected. Battery sensor is unavailable.";
  String meta = "{\"sessionId\":\"" + sessionId + "\",\"command\":\"" + glasses::intentName(result.intent) +
      "\",\"result\":\"" + outcome + "\",\"confirmation\":\"" + confirmation + "\"";
  if (result.intent == glasses::LocalIntent::TakePhoto && success && pendingPhoto) {
    meta += ",\"mediaId\":\"" + pendingMediaId + "\",\"mime\":\"image/jpeg\",\"size\":" + String(pendingPhoto->len) +
        ",\"width\":" + String(pendingPhoto->width) + ",\"height\":" + String(pendingPhoto->height);
  }
  meta += "}";
  voiceLeaseId = sessionId;
  voiceFlow.waitAndroid(millis());
  sendEvent("LOCAL_COMMAND_EXECUTED", meta);
}

void pollAudio() {
  if (!audioActive || !tcpClient || protocolVersion != 2 || audioCapture == nullptr) return;
  if (!audioSending) {
    if (micPipeline.failed()) {
      sendEvent("DEVICE_ERROR", "{\"code\":\"MIC_NO_DATA\",\"sessionId\":\"" + audioSessionId + "\"}");
      resetAudio();
      return;
    }
    if (!micPipeline.isReady()) return;
    if (!audioDiagnostic && intentWorker.configured() && !audioClassifying) {
      voiceFlow.classify();
      if (!intentWorker.submit(audioCapture, micPipeline.size(), audioSessionId.c_str(), currentLanguage.c_str())) {
        sendEvent("DEVICE_ERROR", "{\"code\":\"LOCAL_AI_BUSY\",\"sessionId\":\"" + audioSessionId + "\"}");
        resetAudio(); return;
      }
      audioClassifying = true;
      classificationStarted = millis();
      return;
    }
    if (audioClassifying) {
      if (intentWorker.busy()) {
        if (millis() - classificationStarted >= 5000) {
          sendEvent("DEVICE_ERROR", "{\"code\":\"LOCAL_AI_TIMEOUT\",\"sessionId\":\"" + audioSessionId + "\"}");
          resetAudio();
        }
        return;
      }
      glasses::RecognitionResult result;
      if (!intentWorker.take(result)) return;
      if (glasses::executable(result, glasses::localIntentProvider().minimumConfidence())) { finishLocalCommand(result); return; }
      audioClassifying = false;
    }
    voiceFlow.sendAudio();
    audioCapturedBytes = micPipeline.size();
    audioSamples = audioCapturedBytes / 2;
    for (size_t i = 0; i < audioCapturedBytes; i += 2) {
      const int16_t pcm = static_cast<int16_t>(audioCapture[i] | (audioCapture[i + 1] << 8));
      const uint32_t magnitude = abs(static_cast<int32_t>(pcm));
      audioPeak = max(audioPeak, magnitude);
      if (magnitude >= 32600) ++audioClipped;
      audioEnergy += static_cast<uint64_t>(magnitude) * magnitude;
    }
    audioSending = true;
    Serial.printf("[MIC] Captured %u PCM bytes in %u ms; now transferring over TCP\n",
                  static_cast<unsigned>(audioCapturedBytes),
                  static_cast<unsigned>(millis() - audioStartedAt));
  }

  if (audioTotalBytes < audioCapturedBytes) {
    const size_t count = min(AUDIO_TX_CHUNK, audioCapturedBytes - audioTotalBytes);
    const String meta = "{\"sessionId\":\"" + audioSessionId +
                        "\",\"seq\":" + String(audioSeq) +
                        ",\"encoding\":\"PCM_S16LE\",\"sampleRate\":16000,\"channels\":1}";
    const uint32_t sendStartedAt = millis();
    sendEvent("AUDIO_CHUNK", meta, audioCapture + audioTotalBytes, count);
    if (!tcpClient) return; // closeTcp already released audioCapture on failure
    const uint32_t sendElapsed = millis() - sendStartedAt;
    audioSendMs += sendElapsed;
    if (sendElapsed > audioMaxSendMs) audioMaxSendMs = sendElapsed;
    if (sendElapsed > 150) {
      Serial.printf("[MIC] slow transfer chunk %u: TCP=%u ms bytes=%u\n",
                    static_cast<unsigned>(audioSeq),
                    static_cast<unsigned>(sendElapsed),
                    static_cast<unsigned>(count));
    }
    audioTotalBytes += count;
    ++audioSeq;
    return;
  }

  const String meta = "{\"sessionId\":\"" + audioSessionId +
                      "\",\"seq\":" + String(audioSeq) + "}";
  sendEvent("AUDIO_END", meta);
  if (!tcpClient) return;
  const unsigned rms = audioSamples == 0 ? 0 :
      static_cast<unsigned>(sqrt(static_cast<double>(audioEnergy) / audioSamples));
  Serial.printf("[V2] Audio ended: %u PCM bytes, %u chunks, peak=%u, rms=%u, near-clipped=%u\n",
                static_cast<unsigned>(audioTotalBytes),
                static_cast<unsigned>(audioSeq),
                static_cast<unsigned>(audioPeak), rms,
                static_cast<unsigned>(audioClipped));
  Serial.printf("[MIC] read total/max=%u/%u ms, TCP send total/max=%u/%u ms\n",
                static_cast<unsigned>(audioReadMs),
                static_cast<unsigned>(audioMaxReadMs),
                static_cast<unsigned>(audioSendMs),
                static_cast<unsigned>(audioMaxSendMs));
  voiceFlow.waitAndroid(millis());
  resetAudio(true);
  logMemory("mic-end");
}

void pollTcp() {
  if (wifiPhase != WifiPhase::Ready || !tcpListening) return;
  TcpClient incoming = tcpServer.accept();
  if (incoming) {
    if (tcpClient && tcpClient.connected()) { incoming.stop(); return; }
    closeTcp();
    tcpClient = incoming;
    tcpClient.setNoDelay(true);
    tcpClient.setConnectionTimeout(1000);
    tcpLastProgress = millis();
    Serial.println("[TCP] Client connected");
  }
  if (!tcpClient) return;
  if (((readingPlayPayload || tcpLineLength || protocolVersion == 0) && millis() - tcpLastProgress >= 10000) ||
      glasses::playbackTransferExpired(playActive, playStarted, millis(), tcpLastProgress) ||
      (voiceFlow.idle() && !recActive && !playActive && millis() - tcpLastProgress >= 90000)) {
    closeTcp(); return;
  }
  if (!tcpClient.connected()) {
    closeTcp();
    return;
  }
  int budget = 2048;
  while (tcpClient && tcpClient.available() > 0 && budget-- > 0) {
    if (readingPlayPayload) {
      const int got = tcpClient.read(playChunk + playHave, playNeed - playHave);
      if (got <= 0) break;
      playHave += static_cast<size_t>(got);
      tcpLastProgress = millis();
      if (playHave >= playNeed) {
        readingPlayPayload = false;
        const String header = playHeader;
        playHeader = "";
        handleAudioChunk(header, playChunk, playNeed);
      }
      continue;
    }
    const int next = tcpClient.read();
    if (next < 0) break;
    tcpLastProgress = millis();
    if (next == '\n') {
      if (tcpLineLength != 0 && tcpLine[tcpLineLength - 1] == '\r') --tcpLineLength;
      tcpLine[tcpLineLength] = '\0';
      const String line(tcpLine);
      tcpLineLength = 0;
      if (protocolVersion == 2) {
        uint32_t chunkBytes = 0;
        if (chunkPayloadBytes(line, chunkBytes)) {
          if (chunkBytes < 1 || chunkBytes > MAX_PLAY_CHUNK) {
            Serial.println("[V2] Playback chunk size out of range; closing socket");
            closeTcp();
            return;
          }
          readingPlayPayload = true;
          playHeader = line;
          playNeed = chunkBytes;
          playHave = 0;
        } else {
          handleV2(line);
        }
      } else handleHandshake(line);
    } else if (tcpLineLength < (protocolVersion == 2 ? 1023U : 127U)) {
      tcpLine[tcpLineLength++] = static_cast<char>(next);
    } else {
      Serial.println("[TCP] Header too long; closing socket");
      closeTcp();
      return;
    }
  }
  pollAudio();
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\nAI-Vision Glasses: BLE hotspot provisioning + TCP V2 only");
  WiFi.mode(WIFI_OFF); // never auto-connect to previously saved router credentials
  logMemory("boot");
  bleCommands = xQueueCreate(2, sizeof(BleCommand));
  if (bleCommands == nullptr) {
    Serial.println("[FATAL] BLE command queue allocation failed");
    return;
  }
  beginBle(); // discovery stays possible even if camera or I2S init fails
  logMemory("ble-ready");
  cameraReady = beginCamera();
  logMemory("camera-ready");
  micReady = beginMicrophone();
  speakerReady = beginSpeaker();
  if (speakerReady) speakerSelfTest(); // audible beep: TX->amp->speaker path check
#ifdef AI_VISION_BOOT_MIC_DIAGNOSTICS
  microphoneSelfTest();
#endif
  if (micReady) micReady = micPipeline.begin(microphone);
  if (!intentWorker.begin(glasses::localIntentProvider())) Serial.println("[LOCAL] Worker unavailable; Android fallback remains active");
  logMemory("audio-ready");
  sdReady = beginSD();
  logMemory("sd-ready");
  Serial.printf("[READY] BLE requested, camera=%d mic=%d speaker=%d sd=%d, free heap=%u\n",
                cameraReady, micReady, speakerReady, sdReady,
                static_cast<unsigned>(ESP.getFreeHeap()));
}

void loop() {
  if (bleCommands != nullptr) {
    BleCommand command = {};
    if (xQueueReceive(bleCommands, &command, 0) == pdTRUE) {
      startProvisioning(command.line);
    }
    if (bleQueueOverflow) {
      bleQueueOverflow = false;
      notifyBle("ERROR|BLE_FRAME_TOO_LONG\n");
    }
  }
  pollWifi();
  if (speakerQuietUntil && static_cast<int32_t>(millis() - speakerQuietUntil) >= 0) speakerQuietUntil = 0;
  micPipeline.suppressWake(true);
  if (deferredAudioRelease && !intentWorker.busy()) {
    glasses::RecognitionResult discarded;
    intentWorker.take(discarded);
    micPipeline.stop(); deferredAudioRelease = false;
  }
  if (voiceFlow.expired(millis())) {
    const String expired = voiceLeaseId;
    resetAudio();
    sendEvent("DEVICE_ERROR", "{\"code\":\"VOICE_SESSION_TIMEOUT\",\"sessionId\":\"" + expired + "\"}");
  }
  pollPlayback();
  pollTcp();
  pollPlayback();
  pollVideo();
  delay(2);
}
