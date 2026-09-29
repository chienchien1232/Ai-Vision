# Bàn giao cho lập trình viên AI — AI Smart Glasses

Ngày: 29/09/2026. Workspace: `D:\Project\Ai-Vision`.

Tài liệu này bàn giao hiện trạng và đề xuất đầu việc; **không xác nhận model Local AI đã chạy trên chip hoặc sản phẩm đã nghiệm thu**. Lượt viết tài liệu không thay code, model, firmware đang nạp hay cấu hình backend. Các đầu việc dưới đây cần được triển khai/test riêng, không phải chức năng vừa hoàn thành.

## 1. Đọc trước khi bắt đầu

1. [Hợp đồng Local AI](local-ai-integration-contract.md): API provider, ownership PCM, executor, session và Cancel.
2. [Giao thức TCP V2](protocol-v2-draft.md): bản đang dùng; V1/JSON legacy chỉ là lịch sử.
3. [Bản sửa playback mới nhất](audio-jitter-fix-2026-09-29.md): artifact đã nạp, preload và ngân sách PSRAM.
4. [Test trên kính thật](test-kinh-that-2026-09-29.md), [bàn giao firmware/app](ban-giao-firmware-app-2026-09-29.md).

Kho làm việc có nhiều thay đổi chưa commit. Không reset/restore hoặc xóa file để lấy một cây nguồn “sạch”. Ghi lại revision và diff đang nhận bàn giao; thống nhất phần mình sở hữu trước khi sửa. Các tài liệu 27–28/09 và hash bản đầu 29/09 không thay thế trạng thái mới trong tài liệu này.

## 2. Hiện trạng thật

| Thành phần | Đã có trong code / quan sát | Chưa được phép kết luận |
|---|---|---|
| Board | GOOUUU ESP32-S3-CAM, flash 16 MB, PSRAM 8 MB, OV2640, INMP441, MAX98357A; boot camera/mic/speaker sẵn sàng | Chưa nghiệm thu độ chính xác nhận dạng, tiếng ồn, pin/độ bền |
| Kết nối | BLE cấp thông tin hotspot; TCP V2 port 5000 truyền ảnh/PCM và lệnh | Không phải tai nghe Bluetooth A2DP; không có xác thực mạng production đầy đủ |
| Thu âm | Mic kính, RX owner `MicPipeline`; PCM16 mono 16 kHz; WAKE AI trên app bắt đầu phiên | Không tự nghe wake word ở IDLE trong bản hiện hành |
| Wake/model S3 | Model Hi ESP cũ có trong flash và log load được | Không quảng cáo `WAKE_EVENT`; không có “Kính ơi”; không có Local AI mới/MultiNet/LLM được tích hợp |
| STT | Offline Android, sherpa-onnx/Moonshine, vi/en, CPU 2 luồng | Không phải STT trên S3; test emulator không chứng minh nghe rõ mic kính thực |
| Local Android | Router/handler: chụp, giờ/ngày, phép tính hỗ trợ, chào/hướng dẫn, OCR, replay; lệnh chưa hỗ trợ báo rõ | “Local” không có nghĩa mọi chức năng này chạy trên chip |
| TTS/output | Việt: model VAIS1000/Piper đóng gói; Anh: voice hệ thống offline; PCM về loa kính | Không dùng Gemini TTS mặc định; không tự fallback giọng Anh/cloud khi lỗi |
| Local AI S3 | Interface provider, worker, executor, event và Android xử lý kết quả đã có | Provider mặc định `UnconfiguredIntentProvider`; nhận dạng trên chip chưa có |
| Cloud | `/v1/answer`, text và JPEG tùy chọn; key ở backend, token riêng cho app | Chưa có classifier xác định câu “khó”; chỉ gọi khi câu ngoài handler local/đòi hiểu cảnh |
| Video/SD | Có code/protocol video | Board hiện SD mount lỗi `0x107`, không có capability VIDEO; không lấy video làm baseline nghiệm thu AI |

### Luồng mặc định đang chạy

```text
WAKE AI trên app → START_LISTENING → mic kính thu một câu
  → provider S3 chưa cấu hình → AUDIO_CHUNK/AUDIO_END → STT offline Android
  → IntentRouter
      ├─ lệnh/giờ/ngày/phép tính/OCR hỗ trợ → xử lý local
      └─ câu ngoài handler hoặc hiểu cảnh → backend → Gemini → text
  → preset/TTS offline Android → PCM16 mono 16 kHz
  → kính nhận đủ PCM + END → I2S → loa kính → cleanup/IDLE
```

Demo có client AI/mic/output khác để test app; không dùng demo thay chứng cứ luồng kính thật. OCR chỉ đọc chữ đi local; hỏi giải thích cảnh mới cần ảnh mới và Gemini. Câu hỏi ảnh phải dùng ảnh mới của lượt đó, không tùy tiện dùng ảnh cũ.

## 3. Điểm vào mã nguồn

Các đường dẫn dưới đây tính từ root repo; liên kết mở trực tiếp file tương ứng.

| Việc | File đầu tiên nên đọc |
|---|---|
| Provider và kết quả nhận lệnh | [LocalIntentProvider.h](../firmware/arduino/AiVisionGlasses/LocalIntentProvider.h) |
| Inference worker/Cancel/borrow PCM | [IntentWorker.h](../firmware/arduino/AiVisionGlasses/IntentWorker.h) |
| Thu mic/VAD/AFE/lifetime RX | [MicPipeline.h](../firmware/arduino/AiVisionGlasses/MicPipeline.h) |
| State và WAIT_ANDROID | [VoiceFlow.h](../firmware/arduino/AiVisionGlasses/VoiceFlow.h) |
| Executor intent và gain | [DeviceCommandExecutor.h](../firmware/arduino/AiVisionGlasses/DeviceCommandExecutor.h) |
| Nối worker → executor/event/fallback | [AiVisionGlasses.ino](../firmware/arduino/AiVisionGlasses/AiVisionGlasses.ino): `pollAudio`, `finishLocalCommand`, `resetAudio`, `setup`, `loop` |
| Giới hạn preload | [PlaybackPolicy.h](../firmware/arduino/AiVisionGlasses/PlaybackPolicy.h) |
| STT điện thoại | [LocalSpeechToTextEngine.kt](../android/app/src/main/java/com/example/ai_vision/voice/LocalSpeechToTextEngine.kt) |
| Định tuyến và điều kiện cloud | [IntentRouter.kt](../android/app/src/main/java/com/example/ai_vision/core/IntentRouter.kt), [CloudPolicy.kt](../android/app/src/main/java/com/example/ai_vision/core/CloudPolicy.kt), [LocalAnswerHandler.kt](../android/app/src/main/java/com/example/ai_vision/core/LocalAnswerHandler.kt) |
| Điều phối và nhận kết quả chip | [DeviceViewModel.kt](../android/app/src/main/java/com/example/ai_vision/ui/DeviceViewModel.kt): `completeLocalCommand`, `transcribeUtterance`, `askQuestion` |
| HTTP/session/lỗi AI | [BackendAiClient.kt](../android/app/src/main/java/com/example/ai_vision/ai/BackendAiClient.kt), [AiFailureMessage.kt](../android/app/src/main/java/com/example/ai_vision/ai/AiFailureMessage.kt) |
| TTS/cache phản hồi | [LocalSpeechRenderer.kt](../android/app/src/main/java/com/example/ai_vision/speech/LocalSpeechRenderer.kt), [ReplyAudio.kt](../android/app/src/main/java/com/example/ai_vision/speech/ReplyAudio.kt), [mô tả TTS](vietnamese-offline-tts.md) |
| TCP playback IO/deadline | [V2GlassTransport.kt](../android/app/src/main/java/com/example/ai_vision/device/V2GlassTransport.kt) |
| Gemini payload/HTTP/backend auth | [server.py](../backend/server.py), [test_server.py](../backend/test_server.py), [local-test.ps1](../backend/local-test.ps1), [backend README](../backend/README.md) |

Không đưa inference vào UI, parser TCP, executor hoặc task đọc I2S. Model adapter có trách nhiệm nhận dạng; executor mới có quyền tác động phần cứng.

## 4. Hợp đồng cho Local AI trên S3

### 4.1 Input/output

Implement `glasses::LocalIntentProvider` trong header/source riêng và thay composition point `localIntentProvider()` để đăng ký adapter. Chưa chọn/training model trong bản bàn giao này.

- `available()`: true chỉ khi runtime/model đã thực sự sẵn sàng. Không đổi true chỉ để bật capability hoặc che lỗi load.
- `setup()` gọi `intentWorker.begin(localIntentProvider())` một lần. Nếu `available()` false tại đó, không tạo task; đổi thành true về sau không tự tạo worker. Adapter cần lifecycle khởi tạo trước điểm begin, hoặc thiết kế rõ init/re-init có kiểm soát và test; interface hiện chưa có hàm init riêng. Không để lazy-load vô tình khiến model mãi không được gọi.
- `minimumConfidence()`: default 0.8, phải hiệu chỉnh bằng dữ liệu của model; score phải hữu hạn trong [0,1]. Không coi 0.8 là ngưỡng đã nghiệm thu.
- `classify(const PcmView&, const std::atomic<bool>& cancelled)`: chạy ở worker, nhận **câu thu hoàn chỉnh**, không phải stream mic trực tiếp.
- Input PCM signed 16-bit little-endian, mono, 16000 Hz, tối đa 8 giây / 256000 byte. `bytes` được mượn; không sửa nội dung hoặc giữ pointer sau khi trả về. Session/language nằm trong job do worker copy; không giữ các pointer đó cho công việc bất đồng bộ sau return.
- Output `RecognitionResult`: kind (`Recognized`, `NotLocal`, `Unknown`, `Unavailable`, `Failed`), intent, confidence. Không có trường transcript/text tự do trong interface hiện tại.
- Intent hiện có: `TakePhoto`, `Stop`, `VolumeUp`, `VolumeDown`, `GetStatus`, `Repeat`. Chưa có intent giờ/ngày/phép tính/OCR/video trong enum chip; các phần đó vẫn nằm phía Android. Thêm intent cần cập nhật đồng bộ enum/executor/event/parser/app/test/docs.

Không thể chuyển toàn bộ STT hoặc LLM sang chip chỉ bằng thay model provider này. Nếu muốn chip trả transcript/câu trả lời, cần đề xuất interface và giao thức mới, ngân sách bộ nhớ, Cancel và điểm fallback trước khi viết.

### 4.2 Deadline, Cancel, fallback

- Worker tạo task khi provider có sẵn; hiện cấu hình task stack argument 6144, priority 2. Phải đo stack high-water mark thực tế, không suy ra đủ bộ nhớ từ build.
- Main-loop hủy classification sau 5 giây và phát `LOCAL_AI_TIMEOUT`. Đây là **nhánh lỗi/cleanup**, không phải đảm bảo timeout sẽ tự chuyển PCM về Android.
- Provider phải hợp tác với cờ Cancel và trả về sớm. Không force-delete task hoặc free PCM/model còn đang được native inference dùng. Firmware giữ deferred release cho tới khi worker hoàn tất.
- Kết quả không được thực thi (Unknown/NotLocal/Unavailable/Failed/score thấp) mà worker trả về bình thường sẽ đi nhánh gửi PCM về Android. Lỗi submit worker có `LOCAL_AI_BUSY`, không mặc định giả thành nhận dạng thành công.
- Worker/model đang mượn buffer thì không được bắt đầu phiên mới hoặc hồi sinh kết quả phiên cũ. Kiểm session trên Android giữ nguyên.
- Model lỗi load hoặc thiếu asset phải giữ được đường manual → Android fallback, không reboot loop hoặc đoán lệnh.

### 4.3 Executor và event

Firmware chỉ thực thi kết quả `Recognized` hợp lệ vượt threshold. Tác vụ thực thi ở main-loop qua executor; model không gọi GPIO/I2S/camera/socket.

- `TAKE_PHOTO`: chụp đúng một lần; gửi `LOCAL_COMMAND_EXECUTED` kèm session, kết quả và descriptor JPEG. Android `GET_MEDIA` rồi lưu. Không gửi `TAKE_PHOTO` lần hai; chỉ xác nhận đã lưu sau save thành công.
- `REPEAT`: `REPLAY_ON_ANDROID` giao app dùng cache gần nhất; không gọi Gemini, không chụp lại. Cache chỉ RAM, mất khi process chết.
- `GET_STATUS`: không có sensor pin, không sinh phần trăm pin giả.
- Volume: bước 10%, giới hạn 0–150%, saturating. Trên Android, lệnh volume qua transcript hiện báo chờ Local AI thay vì gửi Gemini.
- `STOP`: không phải wake/nhận giọng chen khi loa đang nói; bản này chưa có AEC/barge-in. App Cancel là đường dừng hiện hành.
- `WAIT_ANDROID` tối đa 120 giây. Wire giữ V2/BLE UUID/HELLO|2/port 5000; không tự đổi schema trong adapter.

## 5. Wake word là đầu việc riêng

Model Hi ESP load được **không đồng nghĩa wake tự hoạt động**. Bản hiện hành chỉ đọc/feed mic khi thu; RX bị disable khi IDLE, wake bị suppress và app không nhận capability WAKE_EVENT. Không chỉ bật cờ `supportsWake()` rồi tuyên bố xong.

Nếu được giao triển khai wake, cần thiết kế lại state/ownership RX để có đường feed liên tục ở chế độ chờ, bật/tắt đúng lúc phát loa và xử lý Cancel/mất TCP. Rà `fetchLoop`/`readLoop`, điều kiện phát wake event, VAD và pre-roll: `start()` hiện reset ring trước nhánh copy pre-roll, chưa có bằng chứng giữ được 250 ms trước wake. Cần test phần này nếu tái sử dụng, không dựa vào tên biến/ring hiện có.

“Kính ơi” cần model/dữ liệu/quyền sử dụng riêng; tài liệu này không chọn hoặc bảo đảm model Việt chạy được. Bàn giao wake phải có model manifest, RAM/CPU/power, tỷ lệ miss/false wake và hành vi loa không tự kích hoạt mic. Wake phải được nghiệm thu riêng với nhận dạng lệnh.

## 6. Ngân sách bộ nhớ và phát âm thanh

- Mic buffer tối đa 256000 byte PSRAM; model và feature buffers phải tính thêm vào ngân sách runtime.
- Playback dùng base 64 KiB (fallback 16 KiB), câu dài thêm buffer PSRAM đúng kích thước tối đa 960000 byte. Chỉ phát sau toàn bộ PCM + END; buffer tạm được thu hồi sau phiên.
- Log boot bản đã nạp: audio-ready internal 82272 byte, largest 38900, PSRAM 7916016 byte. Bản trước ở TCP từng còn khoảng 27 KiB internal heap. Các snapshot không phải bảo đảm headroom cho model mới.
- Đo internal free/largest, PSRAM free/largest, stack, thời gian inference và memory sau lặp/Cancel. Đo khi camera/TCP/model/playback cùng tồn tại theo state cho phép, không lấy PSRAM tổng 8 MB làm RAM khả dụng cho model.
- Giới hạn output 30 giây: nếu TTS vượt, giữ chữ và báo lỗi audio; không cắt giữa câu hoặc gọi cloud TTS dự phòng.
- Bản audio mới đã cài/nạp và verify đạt nhưng **chưa được người dùng xác nhận hết ngắt tiếng**. `maxFeedGapMs` là cadence feed, không chứng minh không có DMA underrun. Cần nghe lại câu dài khi Gemini hoạt động, hoặc dữ liệu test offline hợp lệ.

## 7. Backend/Gemini — trạng thái và việc giao tiếp theo

### Hiện trạng kiểm tra lúc 16:43 ngày 29/09/2026 (Asia/Saigon)

- Backend PC `http://127.0.0.1:8080` đang hoạt động; điện thoại qua ADB reverse `/health` HTTP 200; app token được chấp nhận.
- Một Probe Gemini thật thất bại: backend HTTP 502, upstream HTTP 503. Log lượt người dùng trước đó có 502 và 504; log access cũ không giữ mã upstream từng lượt nên không khẳng định tất cả 502 đều là 503.
- Trước đó trong ngày có một Probe thành công; không coi PASS cũ là trạng thái hiện tại. Lần kiểm mới không trả 429 hoặc 401/403. Theo [Google](https://ai.google.dev/gemini-api/docs/troubleshooting), 503 có thể là dịch vụ tạm quá tải/không khả dụng; không suy ra tài khoản miễn phí hết hạn mức chỉ từ 503.
- Backend default trong source: `gemini-3.8-flash`, override bằng `GEMINI_MODEL`. Đây là cấu hình code, không bảo đảm tài khoản luôn truy cập được hoặc model luôn sẵn sàng; kiểm tra model/quyền khi triển khai. Không tự đổi model/key/endpoint để né hạn mức.
- `upstream_error()` hiện gộp lỗi ngoài quota/auth thành `AI_HTTP_ERROR`, thường HTTP 502, vẫn có trường `status` upstream. Android chưa sử dụng trường upstream đó để báo riêng 503. Timeout là 504 `AI_TIMEOUT`; backend Gemini timeout 35 giây, app read timeout 40 giây. Chưa có retry tự động.

### Đầu việc đề xuất, chưa triển khai

1. Tách lỗi dịch vụ không khả dụng, timeout, quota và auth ở backend/app. Chỉ trả/log mã an toàn; không forward body Google chứa thông tin nhạy cảm hoặc header/token.
2. Nếu chủ dự án chấp thuận retry: chỉ một tầng sở hữu retry, số lần/budget hữu hạn, backoff+jitter, honor Retry-After khi có và Cancel. Tổng budget phải phù hợp app 40 giây hoặc điều chỉnh đồng bộ; không chồng app retry + backend retry.
3. Không retry tự động auth 401/403, request lỗi hoặc đổi key né 429. Nếu retry một POST Gemini do lỗi tạm, ghi rõ khả năng tiêu thụ thêm quota/chi phí hoặc request trước đã được xử lý nhưng mất response.
4. Retry chỉ request AI đã có question/ảnh của phiên; không chụp lại, lưu lại hoặc làm lại side effect. Kết quả phiên đã Cancel không được phát. Replay không gọi API.
5. Nếu chọn model khác, kiểm text/JPEG, tham số thinking/generationConfig, schema response, quota và test trước. Không coi đổi tên model là fix 503 đã nghiệm thu.

### Chạy backend cho developer

Từ root repo, dùng script quản lý, không chép bí mật vào tài liệu:

```powershell
.\backend\local-test.ps1 -Action Start
.\backend\local-test.ps1 -Action Status
# Chủ động test cloud thật: một request có thể tiêu thụ quota.
.\backend\local-test.ps1 -Action Probe
# Sau khi đã cập nhật khóa bằng prompt ẩn SetGeminiKey:
.\backend\local-test.ps1 -Action Restart
```

Nếu app đã có URL/token đúng, chỉ nối lại `adb -s SERIAL_THAT reverse tcp:8080 tcp:8080`; dùng ConfigurePhone khi cần nhập lại cấu hình (nó force-stop app, làm mất cache phiên). Loopback HTTP chỉ cho debug. Đi ra ngoài/production cần thiết kế HTTPS backend riêng, không tự mở firewall/LAN.

Key/token Windows DPAPI nằm dưới `backend/.local-dev/`, bị ignore Git. Không đưa key/token vào firmware/APK/source/log/chat. DPAPI không phải cơ chế chia sẻ key sang máy developer khác; người nhận cần cấu hình riêng được cấp quyền, không nhận bản full-flash/backup chứa NVS bí mật để làm sample công khai.

## 8. Kế hoạch triển khai theo cổng nghiệm thu

### Mốc A — tái hiện baseline, chưa đổi model

Build source đang nhận; chạy unit/native/backend mock. Test manual WAKE → PCM → Android STT → local reply và câu chữ → AI riêng. Lưu số đo không chứa audio/ảnh/key nhạy cảm. Phân biệt Gemini 503 với lỗi mic/TTS/TCP.

### Mốc B — adapter nhận lệnh S3, vẫn kích hoạt bằng app

Chốt tập sáu intent và dữ liệu vi/en thực sự hỗ trợ. Có negative samples: im lặng, nhiễu, nói chuyện không ra lệnh, câu hỏi tự do, phủ định “đừng chụp”, câu mơ hồ. Tách tập train/validation/test theo người nói/phiên thu để tránh leak; ghi license/nguồn/dialect/distance/noise. Chọn model sau khi kiểm dependency/flash/RAM; không mang model Android sang S3 mặc nhiên.

Implement adapter riêng, composition point, load/failed/unavailable, confidence/cancel. Test double không có side effect trước; rồi thử model thật. Không nhầm Unknown với TakePhoto. Nếu threshold không đạt, fallback Android, không tạo lệnh đoán.

### Mốc C — tích hợp hai nhánh và cleanup

Chứng minh cùng một mic kính: câu lệnh chắc chắn chạy executor chip đúng một lần; câu ngoài local chuyển Android STT/cloud theo policy. Test local result tới trước/sau ACK, Cancel trong inference, timeout, disconnect, phát liên tiếp, replay không gọi cloud, ảnh đã lưu không bị báo thất bại vì TTS.

### Mốc D — wake tự động, nếu được giao riêng

Sau manual flow đạt mới bật IDLE RX/AFE/wake. Chưa đổi wake tiếng Việt/model/partition nếu chưa có kế hoạch và phép nạp. Đo false wakes/misses, pre-roll, không mất đầu câu, power, loa tự kích hoạt và recovery khi mất kết nối.

### Mốc E — cloud ổn định và bàn giao

Lỗi 503/504 có thông báo đúng, retry nếu được chấp thuận có test budget/cancel/quota. Nghiệm thu câu AI chữ/ảnh thật khi dịch vụ sẵn sàng. Hỏng cloud không chặn OCR/giờ/chụp/replay/local intent. Lưu kết quả nghe/RAM/latency thật; SD/video không được gắn nhãn đạt nếu chưa sửa riêng.

Sau **mỗi chức năng**: tự review ownership, session, cancellation, side effect, timeout, memory, bảo mật; thêm regression cho lỗi tìm thấy; chạy lại test liên quan; cập nhật docs, không chỉ đổi UI. Mỗi thay đổi có phạm vi nhỏ, không refactor toàn bộ project chung với thêm model.

## 9. Build/test và tiêu chí bàn giao

### Lệnh baseline

```powershell
# Android, từ thư mục android; cần JAVA_HOME trỏ JBR/JDK phù hợp.
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache
# Firmware, từ root; build app-only, không tự flash/model merge.
.\firmware\build-local-voice.ps1
# Backend mock, từ root; không gọi Gemini thật.
python -m unittest discover -s backend -p test_server.py
```

Build offline cần dependencies/model đã tải; lần đầu có thể cần mạng. Toolchain đã dùng: Arduino ESP32 3.3.11, FQBN `esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=esp_sr_16,PSRAM=opi,FlashMode=qio`.

Native C++17 với `-Wall -Wextra -Werror`: [voice_contract_test.cpp](../firmware/tests/voice_contract_test.cpp), [intent_worker_test.cpp](../firmware/tests/intent_worker_test.cpp) (include `firmware/tests/stubs`), [playback_policy_test.cpp](../firmware/tests/playback_policy_test.cpp). Worker stubs không thay test scheduling/stack/model trên S3.

Baseline đã ghi nhận: 63 Android unit, 28 instrumentation emulator, 3 native đạt; lint 0 lỗi/20 cảnh báo/2 hints. Backend mock trước đó 12 đạt. Đây là số test của bản bàn giao, không thay kết quả cần chạy lại sau khi developer sửa. Test instrumentation chạy emulator; không vượt chặn cài APK test trên điện thoại.

### Checklist phải có bằng chứng

- [ ] Model manifest: tên/version/hash, source/license, preprocessing, runtime/toolchain, supported language/intents; lệnh build/reproduce rõ ràng.
- [ ] Metrics trên người nói chưa có trong train: confusion matrix per intent, false accept ngoài local/phủ định, false reject, điều kiện tiếng ồn/khoảng cách. Ngưỡng mục tiêu phải chốt với chủ dự án trước khi kết luận đạt; không có con số “đã đạt” mặc định.
- [ ] Latency P50/P95 theo từng chặng: thu/endpoint/inference/STT/cloud/TTS/upload/start audio; RAM/stack/largest block trước-trong-sau, stress ít nhất 30 phiên liên tiếp và Cancel/disconnect.
- [ ] Không use-after-free, native object còn chạy không bị free, kết quả cũ không thực thi; thiếu/hỏng model fallback hoặc báo lỗi xác định.
- [ ] Chụp/lưu đúng một lần; replay không gọi Gemini; không local inference cho diagnostic mic; tác vụ và lỗi phát tiếng tách riêng.
- [ ] Không gửi dữ liệu local lên cloud ngoài policy; không cloud STT/TTS fallback; log không lưu key/token/PCM/ảnh/nguyên transcript nếu chưa được phép.
- [ ] Nếu wake có triển khai: measurements nhận đúng/sai, ambient false wake theo thời gian đo, loa không tự kích, pre-roll/IDLE RX kiểm chứng riêng.
- [ ] Unit/native/backend mock + emulator regression đạt; test kính/điện thoại thật có log số đo và xác nhận nghe, không suy từ build.
- [ ] Handoff gồm source diff có phạm vi, test report, limitations, artifact hash, hướng dẫn nạp/rollback được cấp phép và việc còn tồn.

## 10. Artifact và an toàn nạp

Bản audio đã cài/nạp gần nhất:

- APK ARM64: `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`, 319512962 byte (304.71 MiB), SHA-256 `A1F696D364FDC4FF5A01EDFB5840D2DF1DBA3724A6D15A41AD53691B61D9DAD0`.
- Firmware app: `firmware/build/voice-local/AiVisionGlasses.ino.bin`, 1715680 byte, SHA-256 `17DC21F1D89DECE1AAF29F131FD76890ED362C9FF0D72FFF9160B70CA5862E80`.

COM6 và serial ADB có thể thay đổi, không hardcode cho mọi máy. Board hiện app0 tại `0x10000`, app1 `0x310000`, mỗi slot 3 MiB; model `0xC10000` / `0x3E0000`. Các offset là bố cục đã kiểm của board bàn giao, **không phải lệnh flash cho board chưa kiểm**.

Trước lần nạp mới: sao lưu, đọc partition/OTA, xác minh slot và dung lượng/hash artifact. Không erase-all; không dùng `with-model.bin` cũ. Model mới chỉ nạp khi có kế hoạch dependency/partition và chủ dự án cho phép. Hash ở đây thuộc bản cũ đã nạp, không chứng nhận binary developer vừa build. Backup trong `firmware/build/backups/` có thể chứa dữ liệu riêng; không publish.

**Kết luận giao việc:** có hạ tầng để người AI nối adapter nhận lệnh nhỏ trên S3; vẫn cần lựa chọn/model/dữ liệu, tích hợp runtime, test thực và bật wake riêng. Không yêu cầu thay toàn bộ app/backend hoặc đem STT/TTS/LLM hiện tại lên chip. Lỗi cloud 503 và SD là hai nhánh độc lập, không để chúng làm mờ chất lượng luồng local.
