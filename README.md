# AI Smart Glasses (Ai-Vision)

Ứng dụng Android cho kính AI: camera + AI Vision (Gemini) + trợ lý giọng nói (STT/TTS),
điều phối bằng Command Router song ngữ Việt/Anh, sẵn sàng thay nguồn camera/mic
điện thoại bằng phần cứng ESP32-S3 Sense.

## Cấu trúc module

- `:app` — composition root (Hilt DI), `MainActivity`
- `:feature` — Home + navigation
- `:core:camera` — CameraX preview/capture, lưu ảnh/video qua MediaStore
- `:core:audio` — Gemini cloud STT qua `AudioRecord`, Gemini cloud TTS qua `AudioTrack`; giữ `SpeechRecognizer`/`TextToSpeech` cũ làm fallback/tương thích khi cần
- `:core:voice` — `VoiceAssistantViewModel`, command routing + conversation UI
- `:core:common`
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
- Thiết bị Android thật, minSdk 26 (cần kiểm thử CameraX và microphone trên máy thật)

## Cấu hình Gemini API key

Tạo file `local.properties` ở thư mục gốc (file này **không** commit lên git):

```properties
GEMINI_API_KEY=<key của bạn>
```

Nếu key trống, app tự dùng `MockVisionEngine` / `MockAssistantEngine` để dev offline.

Giọng nói dùng Gemini cloud và luôn cần key thật cùng kết nối mạng; không trả transcript giả khi thiếu key.
Bấm Mic, nói tiếng Việt hoặc English, chờ tự ngắt câu hoặc bấm “Stop and send”.
Trong lúc “Transcribing”, bấm “Cancel” để hủy. Chỉ transcript cuối mới đi qua router;
“Chụp ảnh” / “Take a photo” chụp trực tiếp, không gọi AI vision để phân loại lệnh.

Âm thanh PCM16 mono 16 kHz được đóng gói WAV trong RAM, tối đa 12 giây, không lưu file.
Mic điện thoại dùng AudioRecord; nguồn PCM khác có thể triển khai WearableMicrophone để dùng cùng CloudSpeechToText.
Mic kính demo hiện chưa hỗ trợ. Tự ngắt câu dùng ngưỡng năng lượng, cần đo lại trên máy thật và trong tiếng ồn.
Model STT mặc định là `gemini-3.6-flash`, đổi bằng Gradle property `GEMINI_STT_MODEL`.
API key trong BuildConfig chỉ phù hợp prototype cá nhân; bản phân phối cần backend giữ key, xác thực và giới hạn sử dụng.
Không log audio/transcript; chính sách lưu dữ liệu phía Gemini độc lập với việc app không lưu bản ghi.

## Build & test

```bat
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug lintDebug
```

APK debug: `app\build\outputs\apk\debug\app-debug.apk`

Quyền cần cấp: Camera, Microphone (`RECORD_AUDIO`), Internet.
Ảnh lưu ở `Pictures/AI Smart Glasses`, video 30s ở `Movies/AI Smart Glasses`.
