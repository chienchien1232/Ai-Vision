# Kiểm tra ESP32, máy ảo và AI — 26/10/2026

## Thiết bị thật

- ESP32-S3 nhận qua USB-SERIAL CH340 COM4, serial 115200.
- Đọc log sau reset: BLE `Ai-Vision Glasses` yêu cầu quảng bá; camera OV2640 QVGA JPEG, mic I2S, speaker I2S khởi tạo thành công. Đây là kết quả khởi tạo, chưa xác nhận chất lượng ảnh/âm thanh thực tế.
- WakeNet Hi ESP được load; log ghi MultiNet/LLM chưa load. Firmware trên board có wake model, không đồng nghĩa đã có AI hội thoại trên chip.
- SD mount thất bại, mã 0x107; `sd=0`.

## App và kết nối

- ADB nhận `emulator-5554`, Android 16 x86_64; app `com.example.ai_vision` mở thành công. APK hiện cài có lastUpdateTime 19/10/2026.
- Tắt Demo đã là trạng thái ban đầu. Bấm Connect trên UI: app đi tới `Finding glasses over BLE`, sau đó `Timed out while finding glasses over BLE`.
- Bluetooth dump ghi quét khoảng 14,9 giây, 0 kết quả. Chưa có kết nối ESP32 → app, chưa kiểm được TCP PING/Status, ảnh camera thật, mic thật hoặc phát loa kính từ app.
- Bước tiếp theo để nghiệm thu phần cứng: dùng điện thoại Android thật với Bluetooth và hotspot, rồi Connect → PING → Status → Capture → kiểm mic/loa.

## Kiểm thử chức năng

Chạy trực tiếp gói instrumentation có sẵn `app-debug-androidTest.apk` ngày 19/10 trên máy ảo hiện tại. Kết quả runner `OK (37 tests)`, 66,259 giây; 36 ca chạy đạt, 1 ca Gemini opt-in bị bỏ qua.

| Lớp | Số ca chạy đạt |
|---|---:|
| LocalSpeechSmokeTest | 1 |
| ManualVoiceFlowTest | 11 |
| OfflineReadingTest | 10 |
| PcmOneShotPlaybackTest | 2 |
| PhaseOneAcceptanceTest | 1 |
| ReplyPlaybackTest | 4 |
| RuntimeLifecycleTest | 2 |
| VietnameseTtsSmokeTest | 5 |

Bao gồm STT native với WAV mẫu Việt/Anh, OCR, TTS/PCM và UI Demo. Lần này không tắt mạng máy ảo, không bật `requireOffline=1`; không coi đây là phép chứng minh chạy trong điều kiện mất Internet. Camera/transport trong các ca Demo hoặc fake không thay cho board thật.

Build/unit test mới chưa chạy được: Gradle lỗi `java.io.IOException: Unable to establish loopback connection`, kể cả khi thử IPv4 và no-daemon. Không dùng XML unit test ngày 19/10 làm kết quả mới. Chưa xác nhận APK có sẵn tương ứng hoàn toàn với mã nguồn hiện tại.

## AI cloud và backend

- Không có dịch vụ trả lời `/health` tại 127.0.0.1:8080.
- Không có thư mục backend/.local-dev và không thấy GEMINI_API_KEY ở Process/User/Machine. Không ghi giá trị bí mật vào báo cáo; không tạo khóa hay dùng khóa giả để chạy dịch vụ.
- Chưa gọi Gemini thật; cần cấu hình khóa hợp lệ, bật backend và cấu hình app trước khi kiểm AI cloud.
- Chạy lại 12 backend unittest với Python 3.14: 10 đạt, 2 lỗi thuộc cloud audio legacy do thiếu `audioop` (`test_wav_normalizes_to_pcm16k_mono`, `test_speak_returns_session_pcm`). Các endpoint/mock test không chứng minh Gemini thật hoạt động. Đường mặc định hiện dùng STT/TTS Android; tài liệu legacy yêu cầu Python <=3.12.

Không sửa mã nguồn ứng dụng hoặc nạp firmware trong lượt kiểm tra này. Đã cài gói test lên máy ảo và mở lại app, giữ Demo tắt sau khi thử kết nối.

## Tiếp tục: build lại source và hồi quy offline ngày 26/10

Phần này cập nhật kết quả sau lượt kiểm tra ở trên.

### Build và APK mới

- Lần build đầu hết thời gian chờ sau 200 giây. Chạy lại với `--no-daemon` thành công; lỗi loopback cũ không tái hiện, chưa xác định nguyên nhân gốc của lỗi đó.
- Tắt configuration cache trong `android/gradle.properties`: tác vụ `prepareSpeechModels` sử dụng Gradle script/archive API không serialize được. Build lại sau thay đổi đạt, không còn báo lỗi lưu configuration cache.
- Đã ép chạy lại compile Kotlin main/unit/androidTest, unit test và package cả app/test, thay vì dùng kết quả `UP-TO-DATE`. **67 unit test đạt**, XML có timestamp mới ngày 26/10/2026.
- `:app:assembleDebug :app:lintDebug --offline --no-daemon --console=plain` đạt. Các tác vụ phân tích lint dùng kết quả incremental hiện có.
- APK x86_64 và gói instrumentation mới đã được cài thành công lên `emulator-5554`; app mở được sau kiểm thử.

| APK | SHA-256 |
|---|---|
| `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | `433B3A58206731649081620A2BC586A6D97442B9B5E0136A2EF05E429F4B28DD` |
| `android/app/build/outputs/apk/debug/app-x86_64-debug.apk` | `0326AB3C9C271ADB2DF8FA557447DAC944716C34E9FE99878967383F9D7B2904` |

Lệnh tái tạo và kiểm chứng từ PowerShell tại `android/` (model/phụ thuộc đã có trong cache):

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
.\gradlew.bat :app:compileDebugKotlin --rerun :app:compileDebugUnitTestKotlin --rerun :app:testDebugUnitTest --rerun :app:packageDebug --rerun :app:compileDebugAndroidTestKotlin --rerun :app:packageDebugAndroidTest --rerun --offline --no-daemon --no-configuration-cache --console=plain
```

### Hồi quy mất Internet trên máy ảo

- Tắt cả Wi-Fi và dữ liệu di động qua `adb shell svc`, chạy runner với `-e requireOffline 1`. Ca UI nghiệm thu xác nhận `ConnectivityManager.activeNetwork == null`. Khôi phục cả hai về trạng thái bật ban đầu sau mỗi lượt.
- Lượt đầu: 35 đạt, 1 bỏ qua cloud, 1 lỗi timeout trong `PcmOneShotPlaybackTest.shortAndLongVietnameseRepliesConsumeOneBufferWithoutUnderrun` (10.533 giây). Lúc đó máy cũng chạy Gradle/lint. Chưa xác định nguyên nhân timeout.
- Chạy riêng lớp PCM: **2/2 đạt**. Chạy lại toàn bộ offline khi không chạy build đồng thời: runner **`OK (37 tests)`**, 47.292 giây, **36 ca đạt và 1 ca cloud opt-in bỏ qua**.
- Đã đạt các ca hiện có về phủ định, phép tính, hỏi làm rõ, hủy tác vụ/loại kết quả đến muộn, replay không gọi AI/TTS lại, và **20 phiên WAKE thủ công liên tiếp dùng session/STT/audio giả lập**.
- STT native dùng WAV mẫu Việt/Anh; OCR native dùng ảnh mẫu/Demo; TTS Việt và AudioTrack chạy thật trên máy ảo. Ca 20 phiên không phải 20 lần nói vào mic kính thật. Timeout PCM ở lượt đầu vẫn là điểm cần theo dõi khi nghiệm thu điện thoại.

### Điểm dừng theo thứ tự triển khai

**Bước 1 đã có APK build lại từ source hiện tại. Bước 2 chờ điện thoại thật:** ADB vẫn chỉ thấy `emulator-5554`. Chưa nghiệm thu camera/mic/loa kính, chưa xác nhận toàn bộ các bước 2–6 đạt trên phần cứng.

Kết nối điện thoại Android bằng USB, bật USB debugging và chấp nhận hộp thoại RSA. Khi `adb devices -l` hiện điện thoại ở trạng thái `device`, cài APK ARM64 ở trên, rồi thử:

1. Bật Bluetooth, cấp quyền app, tắt Demo → Connect → PING → Status.
2. Capture: kiểm ảnh thật từ OV2640 và lưu Gallery.
3. Test loa bằng beep → Test giọng Việt: nghe trực tiếp loa kính.
4. Test mic: ghi peak/RMS/clipping; WAKE AI → nói “Hai cộng ba” → kiểm transcript, kết quả `5` và âm thanh từ loa kính.
5. Sau khi luồng cơ bản đạt mới nghiệm thu hủy/mất kết nối/phục hồi, replay, mất Internet và 20 phiên thật liên tiếp; ghi PASS/FAIL riêng cho từng ca.
