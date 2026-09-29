# Bàn giao bản kiểm thử bốn chức năng kính AI

Ngày 28/09/2026. Đây là **bản kiểm thử tích hợp**, chưa phải sản phẩm đã nghiệm thu đầy đủ trên kính thật. Không đổi phần cứng, firmware, pin hoặc phân vùng trong đợt OCR này; không commit/push và không thay khóa/token có sẵn.

## 1. Kết quả và ranh giới nghiệm thu

| Chức năng | Xử lý hiện tại | Bằng chứng | Chưa nghiệm thu |
| --- | --- | --- | --- |
| Wake Hi ESP | WakeNet trên S3 → PCM mic kính → Android | Serial báo Hi ESP loaded; unit test cấu hình/capability và wake → audio event đạt | Nói thật, tỷ lệ nhận đúng/sai và kích hoạt nhầm; chưa có wake Kính ơi |
| Chụp ảnh, giờ/ngày, tính toán | STT offline Android → router/handler local → TTS offline → loa kính | Unit và máy ảo đạt; test tách ảnh đã lưu khỏi lỗi phát tiếng; kết nối/camera/Gallery thật có bằng chứng ở đợt trước | Luồng nói qua mic kính tới loa kính trên APK mới; độ trễ và khả năng nhận giọng thật |
| Đọc chữ | Ảnh mới → ML Kit Latin kèm APK → đoạn đọc → TTS Việt → PCM16 mono 16 kHz | OCR native chữ Việt/Anh, xoay, JPEG hỏng, ảnh trắng, hủy, đọc tiếp/phát lại; OCR → TTS → AudioTrack đạt khi tắt mạng | Trang sách/nhãn/biển báo thật qua camera QVGA; chất lượng dấu/số; RAM/độ trễ điện thoại thật |
| Hỏi cảnh | Ảnh mới và session → backend → Gemini → TTS local | Client giả lập kiểm tra ảnh mới mỗi câu, 429 không tự retry, Cancel không đọc kết quả cũ; backend test đạt | Gemini thật HOÃN theo yêu cầu; lần trước lỗi upstream 401 UNAUTHENTICATED, không phải bằng chứng lỗi hạn mức |

Các bộ test tự động chạy trên máy ảo `sdk_gphone16k_x86_64` (emulator-5554). Điện thoại `25098PN5AC` đã kết nối lại ở cuối vòng này: cài APK chính ARM64 thành công lúc 23:03:20 ngày 28/09/2026, mở MainActivity và xác nhận process chạy/foreground. **Chưa nghiệm thu OCR/giọng trực tiếp trên kính**. Giữ dữ liệu app; không cài lại test APK hoặc vượt chặn test APK của điện thoại. Không probe Gemini thật hoặc tiêu thụ thêm API cho kiểm thử mới.

## 2. Những phần code đã thêm hoặc sửa

- `android/app/src/main/java/com/example/ai_vision/vision/LocalTextReader.kt`: interface `TextReader`, OCR worker tuần tự, giới hạn JPEG 4 MiB/8 triệu pixel, xoay 0/90/180/270. Bitmap và recognizer chỉ đóng sau native task hoàn tất. Cancel bỏ kết quả cũ; không giải phóng native sớm.
- `vision/ReadingDocument.kt`: giữ văn bản đầy đủ, chuẩn hóa xuống dòng, chia đoạn mặc định 220 ký tự theo từ/câu/đoạn. Không âm thầm xóa một từ quá dài; giới hạn tổng văn bản 32.000 ký tự. PCM vẫn giới hạn 30 giây mỗi đoạn theo giao thức, vượt giới hạn giữ chữ và báo lỗi audio.
- `core/IntentRouter.kt`: định tuyến “Đọc chữ trước mặt tôi”, “Đọc nhãn”, “Đọc văn bản này” và “Đọc tiếp” sang local. Câu hỏi giải thích/cảnh vẫn đi AI. Phủ định không thực thi lệnh.
- `ui/DeviceViewModel.kt`: điều phối OCR qua interface, chụp mới khi đọc mới, đọc tiếp/phát lại dùng dữ liệu đã có; xoay dùng ảnh OCR trước; lỗi OCR không giả làm lỗi TCP. Giữ busy đến hết phát tiếng; Cancel chờ tác vụ native trả về rồi loại kết quả phiên cũ. `AiClient` có thể inject để test không dùng cloud.
- `ui/DeviceScreen.kt`: nút chụp/đọc chữ, đọc tiếp, xoay; hiện góc và đoạn đọc, văn bản đầy đủ và giới hạn ảnh demo/QVGA.
- `ai/AiFailureMessage.kt`: phân biệt app token, khóa Gemini, quota và timeout; không đưa nội dung exception/proxy hoặc bí mật vào lỗi UI.
- `app/build.gradle.kts`: `com.google.mlkit:text-recognition:16.0.1` bundled, không tải model lúc sử dụng. Đã kiểm tra model OCR thực nằm trong APK ở `assets/mlkit-google-ocr-models/`.
- `device/GlassBleProvisioner.kt`, `voice/PhoneSpeechInput.kt`, manifest/UI permissions và debug network config: sửa lỗi lint về quyền BLE, coarse location, API nhận giọng hệ thống và cấu hình domain. Không thêm lint baseline hoặc tắt kiểm tra để giấu lỗi.
- `device/FakeGlassSession.kt`: ảnh demo chữ đen trên trắng, font tiêu đề monospace rõ hơn. Vẫn đánh dấu ảnh nhân tạo, không dùng làm bằng chứng camera thật.

Thuật toán OCR/chia chữ không đưa vào UI. STT/TTS tiếp tục dùng module hiện tại; OCR không yêu cầu lệnh firmware/backend mới. Tiếng Việt không gọi `/v1/speak` hoặc tự đổi sang giọng Anh; Gemini chỉ tạo nội dung ngoài nhóm local.

## 3. Vòng debug và số liệu kiểm thử

| Vòng | Môi trường | Kết quả |
| --- | --- | --- |
| Baseline trước OCR | Android unit/máy ảo | 41 unit và 11 instrumentation đã đạt ở đợt trước |
| Test riêng OCR đầu | Máy ảo, native và fake controller | 7/7 đạt; hồi quy offline 18/18 đạt |
| Mở rộng kiểm thử + lint | Build Android | Phát hiện 16 lỗi lint cũ về BLE/API/quyền/config; sửa thật, chạy lại build và lint đạt |
| Test riêng mở rộng | Máy ảo | 9/10 đạt; OCR ảnh demo đôi lúc bỏ chữ I trong AI-VISION, không hạ assertion để bỏ qua |
| Sau sửa ảnh mẫu | Máy ảo | 10/10 đạt, 18,622 giây cho cả bộ; gồm OCR → TTS Việt native → AudioTrack |
| Hồi quy cuối, Wi-Fi/data tắt | Máy ảo | 21/21 đạt, 20,967 giây cho cả bộ; khôi phục trạng thái mạng sau test |
| Unit cuối sau sửa | JVM | 53/53 đạt, không failure/error/skipped |
| Backend cuối | Python, mock upstream | 12/12 đạt; không gọi Gemini thật |
| Lint cuối | Android debug | 0 lỗi, 20 cảnh báo và 2 hint; còn cảnh báo phiên bản thư viện/SDK và API BLE tương thích cũ |
| Kiểm tra diff | Git | `git diff --check` đạt; chỉ thông báo chuyển LF/CRLF |

Các thời gian trên là thời gian chạy bộ test trên máy ảo, **không phải số đo độ trễ phản hồi hoặc RAM trên điện thoại/kính**. 10 ca OCR mới đạt trong cả lượt riêng và lượt hồi quy sau sửa. Không tuyên bố độ chính xác OCR hoặc wake từ các test này.

Serial COM6 đọc trong vòng này báo camera QVGA/JPEG, mic I2S 16 kHz, speaker/ring 64 KiB PSRAM và Hi ESP loaded. RAM nội trống 78.812 byte, khối lớn nhất 36.852 byte, PSRAM trống 7.916.016 byte ở mốc boot; không phải đo dưới tải OCR/TTS. SD báo lỗi `0x107` riêng, không sửa bằng OCR/TTS. Không có thay đổi/nạp firmware mới trong vòng này.

## 4. APK và tài liệu giao

Build debug, ký bằng debug key của máy; không phải bản release/store. Có model STT vi/en, TTS Việt và OCR kèm APK, nên dung lượng lớn.

| Tệp | Dung lượng | SHA-256 |
| --- | --- | --- |
| `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 319.512.962 byte / 304,71 MiB | `F29FFAD6DC5ACB31EABCDD15347C133464877A362B388E026D828AA626219D4E` |
| `android/app/build/outputs/apk/debug/app-x86_64-debug.apk` | 327.055.192 byte / 311,90 MiB | `4EE62E34A0D0633CEF10065A22E2ADAFA8A14B0BC74A78AD14C978FFFCEABBF2` |

Kế hoạch: [Markdown](ke-hoach-bon-chuc-nang-2026-09-28.md) và [Word](Ke_hoach_hoan_thien_4_chuc_nang_kinh_AI_28-09-2026.docx). Word có 3 trang, đã xuất PDF kiểm tra và mở kiểm tra cả 3 ảnh trang; không bàn giao ảnh/PDF QA như sản phẩm. Script tạo tài liệu nằm trong `tools/documents/`.

Firmware đã có trong `firmware/build/voice-local/AiVisionGlasses.with-model.bin`; dùng hướng dẫn `firmware/build-local-voice.ps1`/README firmware. Không nạp Arduino merged image thông thường thiếu phân vùng WakeNet, không nạp nhầm partition hoặc ghi đè NVS/credential để thử OCR.

## 5. Cách chạy lại kiểm thử

Trong `D:\Project\Ai-Vision\android`:

```powershell
$env:JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-configuration-cache --console=plain
```

Trong `D:\Project\Ai-Vision\backend`:

```powershell
python -m unittest -v test_server
```

Máy ảo x86_64 đang chạy, từ root dự án (không dùng những lệnh cài test này để vượt chặn điện thoại):

```powershell
$adb='C:/Users/Admin/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-x86_64-debug.apk
& $adb -s emulator-5554 install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s emulator-5554 shell am instrument -w -e class com.example.ai_vision.OfflineReadingTest,com.example.ai_vision.VietnameseTtsSmokeTest,com.example.ai_vision.ReplyPlaybackTest,com.example.ai_vision.RuntimeLifecycleTest,com.example.ai_vision.LocalSpeechSmokeTest com.example.ai_vision.test/androidx.test.runner.AndroidJUnitRunner
```

Xem dòng `OK (21 tests)`, không chỉ exit code ADB. Lặp lại khi tắt Wi-Fi/data máy ảo, rồi khôi phục trạng thái mạng cũ. Demo chỉ kiểm chứng workflow; backend scene trong test dùng fake/mocks.

## 6. Nghiệm thu thật còn phải làm

1. APK ARM64 đã cài/mở trên điện thoại. Giữ nguyên settings/token; không reset dữ liệu app. Connect kính, PING/Status, beep và Test giọng Việt trước; cài/mở app không chứng minh chất lượng TTS hoặc đường loa.
2. Hi ESP: 30 lượt phòng yên, 30 lượt có nền; tối thiểu 30 phút không gọi để đo wake nhầm. Ghi đúng/sai và thời gian, không chỉ nghe một lần thành công. Mục tiêu đề xuất ít nhất 27/30 lượt yên tĩnh; chưa đo.
3. Mic kính: nói chụp ảnh 10 chu kỳ, giờ/ngày 10 câu, 20 phép tính gồm âm/thập phân/chia không. Hiện parser cần chữ số trong transcript, ví dụ “Tính -2,5 nhân 3”; nếu STT trả “hai cộng ba” chưa được hỗ trợ local đầy đủ. Không cam kết hiểu mọi cách nói tiếng Việt.
4. OCR: nhãn chữ lớn, biển gần, trang sách; nhiều ánh sáng/khoảng cách. Xoay và đọc lại, đọc tiếp, replay không chụp lại/API; thử ảnh trắng/mờ. Kiểm tra dấu, số và mất chữ. Camera 320×240 hiện chưa bảo đảm sách chữ nhỏ; tăng độ phân giải phải kiểm tra camera framebuffer/RAM riêng.
5. TTS/loa: tắt Internet, giữ kết nối kính; thử tiếng Việt có dấu, giờ, số âm/thập phân, phản hồi dài. Đo RAM, tổng hợp/khởi phát âm, peak/clipping và chất lượng nghe. Mục tiêu audio xác nhận bắt đầu dưới 1 giây sau tác vụ là mục tiêu, chưa coi đã đạt.
6. Cancel trong OCR/AI/TTS/phát; mất TCP khi tải ảnh/phát tiếng; reconnect không thực thi lại tác vụ, không phát nội dung cũ. Cancel OCR native là bỏ kết quả và chờ task kết thúc an toàn, không cam kết ngắt tức thời native. Mốc timeout OCR 30 giây cũng không thể giải phóng native khi nó chưa xong.
7. TTS lỗi không biến ảnh đã lưu/văn bản nhận dạng thành tác vụ thất bại. Đọc quá 30 giây không tự cắt audio giữa câu. Chưa có AEC/nghe chen, wake tạm ngừng khi loa phát.
8. Gemini HOÃN: khi khóa/quyền/hạn mức ổn, thử cảnh thật với ảnh mới và kiểm tra nội dung. 401 xác thực và 429 hạn mức là hai loại khác nhau; không auto retry hoặc chuyển OCR sang cloud.

Chỉ sau các bước phần cứng này mới chốt sản phẩm nghiệm thu thực tế. Bản hiện tại dùng để tiếp tục kiểm thử, không dùng OCR/AI chưa kiểm chứng cho điều hướng an toàn hoặc quyết định rủi ro.
