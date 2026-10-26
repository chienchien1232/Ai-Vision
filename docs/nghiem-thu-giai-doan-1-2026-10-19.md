# Nghiệm thu Giai đoạn 1 trên Android Emulator — 19/10/2026

**Kết quả: phần Android trên máy ảo đạt.** Build APK/APK test thành công; 67 unit test đạt; 34 instrumentation test đạt, 1 ca Gemini thật bỏ qua, 0 thất bại. Lint: 0 lỗi, 20 cảnh báo, 2 gợi ý.

Giai đoạn 1 toàn sản phẩm vẫn chờ kiểm tra đầu-cuối từ mic kính đến loa kính thật theo [kế hoạch](ke-hoach-ai-vision.md). Lượt này nghiệm thu app trên emulator với camera/session giả và các model Android đóng gói.

## 1. Môi trường và phiên bản

| Mục | Giá trị |
|---|---|
| Source nền | `110fc05` + working-tree diff nhánh `feature/android-ai-phase1` |
| SDK | `D:\Android\Sdk`; Platform API 37.0 revision 2, Build Tools 36.0.0 |
| Gradle / AGP / Kotlin | 9.4.1 / 9.2.1 / 2.2.10 |
| JDK dùng chạy Gradle | Temurin 21.0.7 |
| AVD / ADB serial | `AiVision_Android_16` / `emulator-5554` |
| Android / ABI | Android 16, API 36, `x86_64` |
| System image | `google/sdk_gphone64_x86_64/emu64xa:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys` |
| App | `com.example.ai_vision`, versionName `1.0`, versionCode `1` |
| STT | sherpa-onnx 1.13.8, Moonshine base vi / tiny en quantized 2026-02-27 |
| TTS Việt | `vits-piper-vi_VN-vais1000-medium`, archive SHA-256 `fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a` |
| OCR | ML Kit Latin 16.0.1 đóng gói trong APK |

Các SHA-256 archive STT được pin trong `android/app/build.gradle.kts`; model/TTS notices và manifest được sinh bởi các task build hiện có.

Trước instrumentation đã bật airplane mode, tắt Wi-Fi và mobile data. ADB xác nhận `airplane_mode_on=1`, Wi-Fi disabled, `ip route` không có tuyến. Ca UI với `requireOffline=1` xác nhận `ConnectivityManager.activeNetwork == null` ngay trong app. Sau kiểm thử đã khôi phục trạng thái ban đầu: airplane mode 0, Wi-Fi 1, mobile data 1.

## 2. Kết quả theo tiêu chí

| Tiêu chí | Kết quả / bằng chứng |
|---|---|
| Phủ định không thực thi, câu hỏi có “không” vẫn đúng | PASS unit `IntentRouterTest`: “đừng chụp ảnh”, “không quay video”, “đây có phải chai nước không?”, số không |
| Câu hỏi cảnh yêu cầu ảnh mới; câu mơ hồ hỏi lại | PASS router; integration `sceneAlwaysUsesFreshPhotoAndQuotaDoesNotBreakLocalReading` dùng ảnh mới mỗi lượt, AI giả lập lỗi quota |
| Số bằng chữ local | PASS Việt/Anh, số âm/thập phân/trộn chữ số; UI thực nhập “Hai cộng ba”, nhận `5` và hoàn tất phát PCM |
| Chụp ảnh, giờ, phép tính, replay khi mất Internet | PASS `PhaseOneAcceptanceTest`: thao tác trên Compose UI, camera Demo → lưu Gallery; giờ và kết quả local → TTS Việt native → AudioTrack; replay giữ nguyên ảnh |
| Không gọi Gemini cho local | PASS policy/unit và `stopTextCancelsBusyActionAndOfflineCommandsNeverUseAi`: bộ đếm AI bằng 0 cho chụp/giờ/tính/replay; UI chạy cloud disabled, URL/token trống |
| Câu phủ định và làm rõ trên UI | PASS: ảnh giữ nguyên sau “Đừng chụp ảnh”; “Xem giúp tôi” hiển thị câu hỏi đọc chữ hay mô tả cảnh và hoàn tất phát tiếng |
| Hủy không phát kết quả cũ | PASS: AI/OCR/TTS trả muộn bị bỏ; phép tính lượt sau vẫn đúng; hủy playback và phát tiếp hoạt động |
| ACK/ảnh trả về sau Cancel | PASS: không bind lại phiên, không cập nhật/lưu ảnh muộn, có thể bắt đầu lượt mới |
| Test mic về IDLE | PASS: cleanup hoàn tất, WAKE AI kế tiếp được chấp nhận |
| 20 phiên liên tiếp | PASS: `twentySequentialManualSessionsReturnToIdleWithoutCloudOrDuplicateCommands`; 20 phiên với session/STT/TTS giả, về IDLE, 0 lời gọi AI |
| STT native offline Việt/Anh | PASS: giải mã hai WAV mẫu upstream bằng model đóng gói; chưa đánh giá độ chính xác mic kính |
| TTS, PCM và OCR native offline | PASS: preset, tổng hợp tiếng Việt, giải phóng/cancel model, OCR chữ Việt/Anh, AudioTrack playback; chữ vẫn được giữ khi phát lỗi |

### Tổng hợp instrumentation cuối cùng

| Lớp kiểm thử | Đạt | Bỏ qua |
|---|---:|---:|
| `LocalBackendSmokeTest` | 0 | 1 |
| `LocalSpeechSmokeTest` | 1 | 0 |
| `ManualVoiceFlowTest` | 11 | 0 |
| `OfflineReadingTest` | 10 | 0 |
| `PhaseOneAcceptanceTest` | 1 | 0 |
| `ReplyPlaybackTest` | 4 | 0 |
| `RuntimeLifecycleTest` | 2 | 0 |
| `VietnameseTtsSmokeTest` | 5 | 0 |
| **Tổng** | **34** | **1** |

Ca Gemini thật là opt-in `localBackendSmoke=1`, không được bật trong nghiệm thu offline. XML cuối cùng ghi `tests=35`, `failures=0`, `errors=0`, `skipped=1`. Dùng số liệu XML thay vì dòng progress của Gradle.

## 3. Số đo trên máy ảo

| Phép đo | Kết quả |
|---|---:|
| STT Việt, một WAV mẫu, gồm khởi tạo model và decode | 3066 ms |
| STT Anh, một WAV mẫu, gồm khởi tạo model và decode | 1073 ms |
| Đọc năm preset PCM Việt | 14 / 8 / 14 / 37 / 11 ms |
| TTS native ba câu mẫu, lần đầu có load model | 2470 / 509 / 418 ms |
| Độ dài PCM ba câu mẫu tương ứng | 3065 / 2972 / 2182 ms |
| Toàn ca UI offline, gồm thao tác và chờ phát nhiều câu | 19.508 s |

Đây là số đo đơn lượt emulator, không phải P50/P95 hay độ trễ mic → loa của kính. Không dùng thời gian test 20 phiên giả lập làm số đo nhận giọng thật.

## 4. Build và chạy lại

Từ `android/`, đặt SDK trong shell hiện tại:

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-configuration-cache --max-workers=2
```

Lần đầu cần tải đủ dependencies/model. Sau đó có thể thêm `--offline` cho Gradle; cờ này chỉ áp dụng cho build host, không thay việc tắt mạng Android.

Chạy nghiệm thu offline trên đúng máy ảo nêu trên (trạng thái ban đầu airplane off, Wi-Fi/data on):

```powershell
$adb = 'D:\Android\Sdk\platform-tools\adb.exe'
try {
    & $adb -s emulator-5554 shell cmd connectivity airplane-mode enable
    & $adb -s emulator-5554 shell svc wifi disable
    & $adb -s emulator-5554 shell svc data disable
    Start-Sleep -Seconds 3
    .\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.requireOffline=1' --offline --no-configuration-cache --max-workers=2
} finally {
    & $adb -s emulator-5554 shell cmd connectivity airplane-mode disable
    & $adb -s emulator-5554 shell svc wifi enable
    & $adb -s emulator-5554 shell svc data enable
}
```

Máy khác cần ghi lại và khôi phục trạng thái mạng ban đầu tương ứng. `PhaseOneAcceptanceTest` thêm trong lượt nghiệm thu dùng UI/TTS/AudioTrack thật; camera vẫn là ảnh Demo. Các kiểm thử điều phối dùng test doubles để tạo ACK, PCM, lỗi và kết quả trễ có kiểm soát.

Bằng chứng được sinh dưới `android/app/build/`:

- Unit: `test-results/testDebugUnitTest/TEST-*.xml` và `reports/tests/testDebugUnitTest/index.html`.
- Instrumentation: `outputs/androidTest-results/connected/debug/TEST-AiVision_Android_16(AVD) - 16-_app-.xml`; HTML tại `reports/androidTests/connected/debug/index.html`.
- Log STT/TTS: các file `logcat-*LocalSpeechSmokeTest*` / `logcat-*VietnameseTtsSmokeTest*` trong thư mục kết quả của AVD.
- Lint: `reports/lint-results-debug.html` và `.txt`.

## 5. APK bàn giao

Đường dẫn từ root repo; cả hai APK app build thành công, bản `x86_64` đã cài/mở lại trên emulator (`am start -W`: `Status: ok`).

| Artifact | SHA-256 |
|---|---|
| `android/app/build/outputs/apk/debug/app-x86_64-debug.apk` | `6AD555249AED865BE5846082660B8503A78AA925506240ABE3E7A033022AD8DF` |
| `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | `536A02CDB816F6645ED4E0CDF42CF7E4A11C1C6D21188BAD6D3B1771B87B4D24` |
| `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `1AB7C91A38AD662F7E41EF26D1167F3D605E2BD3C2761360C68E227175A34439` |

Bản ARM64 đã build, chưa chạy trên điện thoại/kính thật trong lượt này. Firmware không được nạp hoặc đổi trong lượt nghiệm thu.

## 6. Cổng nghiệm thu còn lại

- Mic kính → TCP PCM → STT → lệnh local/AI → PCM → loa kính, dùng nút WAKE AI.
- Chụp/lưu bằng camera kính thật; nghe và xác nhận âm đầu/cuối, âm lượng và tiếng rõ.
- Cancel/mất kết nối/nối lại và ít nhất 20 phiên trên kính/điện thoại thật; ghi độ trễ từng bước.
- Câu hỏi text/ảnh với Gemini thật thuộc kiểm chứng cloud riêng; ca skip không được tính PASS cloud.

**Kết luận:** đã gỡ chặn SDK và hoàn tất nghiệm thu Android trên emulator cho Giai đoạn 1. Tiếp theo là nghiệm thu phần kính thật theo các tiêu chí trên.
