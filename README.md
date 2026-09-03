# AI Smart Glasses (Ai-Vision)

Ứng dụng Android cho kính AI: camera + AI Vision (Gemini) + trợ lý giọng nói (STT/TTS),
điều phối bằng Command Router song ngữ Việt/Anh, sẵn sàng thay nguồn camera/mic
điện thoại bằng phần cứng ESP32-S3 Sense.

## Cấu trúc module

- `:app` — composition root (Hilt DI), `MainActivity`
- `:feature` — Home + navigation
- `:core:camera` — CameraX preview/capture, lưu ảnh/video qua MediaStore
- `:core:audio` — `SpeechRecognizer` (STT), `TextToSpeech` (TTS)
- `:core:voice` — `VoiceAssistantViewModel`, command routing + conversation UI
- `:core:common`, `:core:network`
- `:data` — Gemini engines, conversation context (in-memory), preprocessing
- `:domain` — contracts: assistant, orchestrator, wearable device layer, use cases

## Pipeline

```text
Mic/Typed/QuickAction
  → RuleBasedCommandRouter
  → AiOrchestrator (DescribeScene / IdentifyObject / ReadText /
      SummarizeScene / TakePhoto / RecordVideo / Stop / RepeatLastAnswer)
  → UI + TTS
```

## Yêu cầu

- Android Studio (AGP 9.x, Kotlin 2.2, JDK 17+)
- Thiết bị Android thật, minSdk 26 (CameraX + SpeechRecognizer không chạy đầy đủ trên emulator)

## Cấu hình Gemini API key

Tạo file `local.properties` ở thư mục gốc (file này **không** commit lên git):

```properties
GEMINI_API_KEY=<key của bạn>
```

Nếu key trống, app tự dùng `MockVisionEngine` / `MockAssistantEngine` để dev offline.

## Build & test

```bat
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug lintDebug
```

APK debug: `app\build\outputs\apk\debug\app-debug.apk`

Quyền cần cấp: Camera, Microphone (`RECORD_AUDIO`), Internet.
Ảnh lưu ở `Pictures/AI Smart Glasses`, video 30s ở `Movies/AI Smart Glasses`.
