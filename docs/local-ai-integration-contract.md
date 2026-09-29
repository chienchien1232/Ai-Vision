# Bàn giao điểm tích hợp Local AI — firmware + Android

Phiên bản 29/09/2026. Đây là contract tích hợp, không phải báo cáo model nhận dạng đã chạy trên chip.

Người nhận việc đọc [bàn giao cho lập trình viên AI](ban-giao-cho-lap-trinh-vien-ai-2026-09-29.md) để xem phạm vi, kế hoạch theo mốc, baseline và checklist nghiệm thu. Contract này mô tả interface đang có; không phải model chỉ cần chép vào flash là chạy.

## Luồng đang triển khai

WAKE AI trên app dùng START_LISTENING. Firmware thu PCM rồi chuyển về Android để STT offline, local handler hoặc backend, TTS và PLAY_AUDIO. Không có mic luôn đọc ở IDLE để chờ wake word; không có nút vật lý mới.

Provider mặc định `UnconfiguredIntentProvider` trả Unavailable, `available() == false`. Không tạo inference worker/model mới ở cấu hình này. WakeNet/model partition có sẵn không bị train/thay thế hoặc flash trong lượt triển khai này; sản phẩm không quảng cáo WAKE_EVENT và không tự bật Hi ESP.

## Điểm nối của người phụ trách Local AI

1. Implement `glasses::LocalIntentProvider` trong header/source riêng, khai báo runtime/dependency phù hợp toolchain Arduino hiện tại.
2. Đăng ký provider tại `localIntentProvider()` trong LocalIntentProvider.h. Đây là composition point duy nhất, không đặt model code vào MicPipeline, TCP parser hoặc Android UI.
3. Trả `available()` đúng trạng thái, `minimumConfidence()` theo ngưỡng đã hiệu chỉnh. Chuẩn hóa confidence về [0,1]; không coi score của model khác nhau là tương đương.
4. Implement `classify(PcmView, cancelled)` trên intent-worker, không đọc driver. Input là một câu hoàn chỉnh; model xử lý block có thể feed các lát PCM từ view này.
5. Inference phải kiểm tra cờ hủy và trả về trong ngân sách 5 giây; nếu không đủ phải đo lại và điều chỉnh hợp đồng/deadline trước khi bật. Không giữ PCM pointer sau khi classify trả về.
6. Đo độ chính xác, false positives, stack, SRAM/PSRAM/largest free block trên kính thật trước khi công bố đã tích hợp.

Chỉ chép file model lên Flash không đủ. Adapter, dependency, ngôn ngữ, ngưỡng, lifetime và ngân sách bộ nhớ vẫn thuộc phần tích hợp model.

## Ownership và Cancel

- MicPipeline là RX owner duy nhất. Camera, loa và network vẫn thuộc firmware.
- Input: PCM_S16LE, mono, 16000 Hz, tối đa 8 giây/256000 byte; sessionId/language được copy vào job.
- PCM được recorder sở hữu. IntentWorker chỉ mượn đến lúc classify hoàn tất.
- Main-loop có thể yêu cầu Cancel/timeout trong lúc inference; không free PCM hoặc force-delete task khi model còn dùng buffer.
- Buffer chỉ được thu hồi khi worker công bố đã hoàn tất. Kết quả phiên đã hủy bị bỏ. Provider không hợp tác với Cancel có thể giữ trạng thái busy; không giả vờ phần cứng đã phục hồi an toàn.
- Provider chỉ trả intent/result, không gọi I2S/GPIO/camera/socket hoặc phát âm thanh.
- Playback full-preload 29/09 có thể cấp phát tạm thêm tối đa 960000 byte PSRAM, ngoài buffer boot. Người tích hợp model phải đo tổng heap/largest block khi model và playback cùng tồn tại, không dành toàn bộ PSRAM trống cho model. Không thay provider/model trong bản sửa này.
- Unknown, NotLocal, Unavailable, Failed và Recognized dưới ngưỡng đi Android fallback; không thực thi phần cứng đoán mò.

## Intent và executor

| Intent | Executor | Kết quả/giới hạn |
|---|---|---|
| TAKE_PHOTO | captureDevicePhoto, dùng camera hiện có | Chụp đúng một lần. App GET_MEDIA, lưu Android và nói xác nhận đã lưu |
| STOP | Cleanup phiên thu/voice | Không có barge-in khi loa đang phát; app Cancel vẫn là đường ngắt |
| VOLUME_UP/DOWN | PcmGain, bước 10%, giới hạn 0..150% | Gain saturating không overflow. Tăng gain có thể làm bẹt peak, phải nghe/đo thực tế |
| GET_STATUS | Trạng thái kết nối thật | Chưa có sensor pin, không trả phần trăm pin giả |
| REPEAT | ReplayOnAndroid | App gửi lại cache PCM/nội dung, không chụp lại/call Gemini. S3 không giữ cache replay sau khi phát; buffer preload chỉ tồn tại trong lượt phát |

Nhận diện các intent này trên S3 chưa được cài. Unit/native fixtures chỉ kiểm tra hạ tầng dispatch; không chứng minh model hiểu giọng nói.

## Event local, không đổi framing/command

Giữ event LOCAL_COMMAND_EXECUTED đã có trong tài liệu V2. Hạ tầng mới bảo toàn sessionId và yêu cầu nó khớp phiên WAKE của app.

- `command`: TAKE_PHOTO, STOP, VOLUME_UP, VOLUME_DOWN, GET_STATUS, REPEAT.
- `result`: SUCCESS, FAILED hoặc REPLAY_ON_ANDROID (ủy quyền phát cache, không phải đã phát xong).
- `confirmation`: chuỗi UTF-8 ngắn do executor tạo từ kết quả thực tế, không lấy câu tùy ý từ model.
- TAKE_PHOTO thành công kèm mediaId, mime=image/jpeg, size, width, height giống descriptor PHOTO_CAPTURED để app gọi GET_MEDIA.
- App không gửi TAKE_PHOTO lần hai khi nhận event. App chỉ nói đã lưu sau khi lưu thành công.
- REPLAY_ON_ANDROID dùng phản hồi đã có, không gọi Gemini. Không có cache thì báo rõ.
- App phát confirmation bằng TTS/preset offline và PLAY_AUDIO hiện hữu; kính phát loa. Không thêm TTS neural trên chip.

Ví dụ metadata (header vẫn là V2 JSON + đúng payloadBytes, không có payload event):

```json
{"sessionId":"<LISTENING_STARTED sessionId>","command":"TAKE_PHOTO","result":"SUCCESS","mediaId":"photo-1","mime":"image/jpeg","size":12345,"width":320,"height":240}
```

Descriptor ảnh và mã replay là phần bổ sung cho event chưa được firmware phát trước đây, để app xử lý đủ kết quả. BLE UUID, port 5000, HELLO|2, TAKE_PHOTO/GET_MEDIA, audio framing không đổi.

## State và deadlines

Firmware: Idle → Listening → Classifying (khi provider thực có sẵn) → SendingAudio/WaitingAndroid hoặc local result → Playing → Idle.

- WAIT_ANDROID tối đa 120 giây. App cleanup bằng STOP_LISTENING nếu không phát audio hoặc khi kết thúc phiên.
- Parser đóng TCP khi header/payload dở quá 10 giây. Audio chunk validation giữ v=2 và meta object.
- Socket send có stall deadline; app có deadline bao gồm blocking write bằng cách đóng socket.
- Cancel bỏ ring và giữ khoảng an toàn đuôi DMA trước phiên mic mới; nghe thực tế vẫn là bước nghiệm thu bắt buộc.
- Khi mất framing, đóng socket; không tự retry tác vụ đã có side effect.

## Build và kiểm thử

- Android: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache`.
- Firmware: `firmware/build-local-voice.ps1` chỉ build app image mặc định, không merge/flash model. `-IncludeExistingWakeModel` là tùy chọn artifact riêng, không bắt buộc cho luồng app wake.
- Native: compile/run `firmware/tests/voice_contract_test.cpp` và `firmware/tests/intent_worker_test.cpp` bằng C++17 với warnings-as-errors. Worker test dùng stubs FreeRTOS single-step tại `firmware/tests/stubs`, không kiểm chứng scheduling/task stack trên S3.
- Playback: `firmware/tests/playback_policy_test.cpp` kiểm tra giới hạn buffer, đủ PCM + END mới phát và deadline thiếu END; không thay cho đo I2S/RAM thực tế. Báo cáo mới: [sửa jitter](audio-jitter-fix-2026-09-29.md).
- Instrumentation: ManualVoiceFlowTest cùng regression OCR/replay/runtime; chạy trên emulator, không vượt hạn chế cài test APK trên điện thoại.
- Không nạp full flash image cũ `with-model.bin` chỉ vì nó còn trong build directory. Xác minh hash/thời gian và offset của artifact trước bất kỳ lần nạp nào.

## Việc chưa thể xác nhận chỉ bằng code/test giả

Mic thực tế sau RX disable/enable, wiring MAX98357A SD/LR channel, nghe loa/độ trễ, RAM/stack thời gian chạy trên S3, độ chính xác Local AI và Gemini thật. Không ghi các mục này là đạt khi chưa đo.

Kết quả build/test và hash artifact: [báo cáo bàn giao](ban-giao-firmware-app-2026-09-29.md).
