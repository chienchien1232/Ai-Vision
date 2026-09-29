# Triển khai local-first — 28/09/2026

## Kết luận nghiệm thu

Đã triển khai, build và nạp phần mềm local-first lên ESP32-S3 thật; APK mới đã cài/mở trên điện thoại thật. Chưa được coi là kính hoàn thiện: model wake đã tải nhưng chưa đo tỷ lệ nhận đúng/sai, thẻ SD mount lỗi, âm thanh/voice end-to-end/pin/nhiệt chưa nghiệm thu. Backend + app token đã bật, nhưng Gemini thật bị lỗi xác thực upstream 401.

## Cập nhật triển khai thực tế chiều 28/09/2026

- COM6: ESP32-S3 rev0.2, flash16MB/PSRAM8MB. Đã sao lưu đủ16MB trước khi đổi partition/nạp, SHA-256 `CC27B3686D61C073B97E425B7F9802ADA56F92563C7D1111331FB34C053A7929`; tệp nằm ở `firmware/backups/esp32s3-44bd8d7a2c78-before-local-voice-20260928-181322.bin`, bị loại khỏi Git vì có thể chứa NVS bí mật.
- Đã ghi full image/model `EDC87955C4542E191B8AD96B92917D28246BA6924A5E4727FEA3DDC35A398201` vào flash từ0x0; esptool xác minh hash thành công. Nội dung flash cũ bị thay, có thể phục hồi từ backup; không sửa eFuse.
- Serial thật: OV2640 QVGA JPEG ready, mic I2S/speaker TX initialized, boot probe đọc16000byte, Hi ESP loaded; internal free78952byte/largest36852, PSRAM sau audio7916016byte. Đây chỉ là boot/init, không chứng minh chất lượng thu/phát hay wake.
- **SD mount FAILED 0x107; sd=0.** Chưa quay video; cần kiểm tra thẻ/format/đấu nối. Chụp ảnh đi thẳng camera→app không yêu cầu SD nhưng vẫn cần thử luồng mới thật.
- Điện thoại model25098PN5AC arm64: cài đè APK debug và mở thành công, giữ dữ liệu. APK mới SHA-256 `143ACCFE67B82135350ADDB81E60DE269E1911C2D07B74CA47CDF03984078C48`. Android **33 unit test PASS**.
- Backend chỉ nghe127.0.0.1:8080, token WindowsDPAPI→ADB private import→AndroidKeystore. Xác minh URL, token mã hóa hiện diện và import file đã xóa; ADB reverse8080 đã bật. Auth app token kiểm bằng request hợp lệ về auth/không hợp lệ về câu hỏi trả400, không tiêu thụ Gemini.
- Một lần probe Gemini thật thất bại502; kiểm tra GET models riêng trả401 UNAUTHENTICATED. Khóa môi trường có, không có whitespace, nhưng chưa xác thực thành công. Không đổi khóa, không tự retry. Cần sửa khóa/quyền trước nghiệm thu cloud.
- Điện thoại chặn cài APK kiểm thử riêng (`INSTALL_FAILED_USER_RESTRICTED`); không vượt chặn. Test native STT/cloud từ instrumentation trên điện thoại **chưa chạy**; kết quả emulator bên dưới là lượt trước.
- Hướng dẫn bật lại: `backend/local-test.ps1` và `backend/README.md`. Đây là đường test qua PC/ADB, không phải backend dùng độc lập ngoài hiện trường.

## Phân chia xử lý hiện tại

Cập nhật TTS Việt cuối ngày: [TTS offline trong app](vietnamese-offline-tts.md) thay voice hệ thống cho vi-VN bằng VAIS1000/Piper; có năm PCM preset, Phát lại và trạng thái audio riêng. Android41unit PASS; emulator11instrumentation PASS (10+1 test AudioTrack riêng). APK cuối đã cài/mở trên điện thoại, giữ token/cài đặt. Các mô tả TTS cần voice đã cài bên dưới chỉ còn áp dụng cho tiếng Anh; metrics/checksum APK mới ở tài liệu TTS.

- ESP32-S3: camera/SD/PCM; một task sở hữu mic I2S RX; WakeNet **Hi ESP** và WebRTC VAD khi model flash sẵn sàng. Thiếu model thì vẫn có nút nghe và endpoint theo năng lượng. Không tải MultiNet/LLM.
- Android: nhận PCM mic kính, STT offline tiếng Việt/Anh bằng sherpa-onnx 1.13.8/Moonshine quantized; tự định tuyến transcript không cần Send; lệnh chụp/quay/dừng/trạng thái, giờ/ngày/chào/hướng dẫn và phép tính hai toán hạng chạy local.
- Gemini: chỉ AppIntent.Question ngoài nhóm handler qua `/v1/answer`, ảnh mới khi hỏi cảnh. Không gọi Gemini để đo độ khó. Có công tắc tắt cloud. Câu hỏi ngoài whitelist không đồng nghĩa đã được chứng minh là “khó”.
- TTS: Việt dùng model/preset đóng gói trong app; Anh chọn voice offline đã cài. Render/resample PCM16kHz rồi phát loa kính; không fallback Gemini. Demo phát cùng PCM qua AudioTrack. STT mic kính không xin mở mic điện thoại; nút Phone mic demo là đường riêng.

Wake word có thể chạy trên main board; đổi chữ ở UI không tạo model mới. **“Kính ơi” chưa có model huấn luyện**. Nhận câu nói tiếng Việt tự do vẫn nằm trên điện thoại, không chuyển lên ESP32-S3 trong bản này. MultiNet chính thức hỗ trợ lệnh giới hạn tiếng Anh/Trung; chuyển lệnh tiếng Việt lên chip cần dataset/model nhỏ riêng và đánh giá tài nguyên. Nguồn: [ESP-SR](https://docs.espressif.com/projects/esp-sr/en/latest/esp32s3/getting_started/readme.html), [MultiNet](https://docs.espressif.com/projects/esp-sr/en/latest/esp32s3/speech_command_recognition/README.html).

## Đã sửa

- Action giữ busy tới khi TTS/playback hoàn tất; session cũ không ghi đè phiên mới. HTTP hủy sẽ đóng connection; không tự retry Gemini. Không forward token qua HTTP redirect; response có giới hạn kích thước.
- BUSY/error lệnh và DEVICE_ERROR là lỗi phục hồi, không tự làm rớt TCP. Sai frame/EOF/timeout transport mới đóng socket. Cancellation drain ACK đang bay trong timeout hữu hạn rồi gửi CANCEL đúng request playback/START_VIDEO.
- STOP_LISTENING idempotent, bỏ PCM đang thu. MEDIA_AVAILABLE cập nhật video chờ. Mất TCP cố finalize AVI, không xóa bản đã xong. ACK_MEDIA chỉ sau Gallery lưu thành công; giữ file SD, không tự xóa video trước để quay mới.
- Sửa movi list size/index offset AVI. Tránh pollVideo đợi camera framebuffer đang bị TAKE_PHOTO giữ, khiến GET_MEDIA không được xử lý.
- Runtime tách khỏi ViewModel màn hình. Foreground service connectedDevice giữ kết nối; idle heartbeat mỗi 20 giây; reconnect TCP tối đa 3 lần với backoff 1/2/4 giây, giữ hotspot và không replay lệnh có tác dụng phụ. Hết retry thì người dùng Connect lại BLE/hotspot.
- Lưu URL/ngôn ngữ/cloud preference và app token mã hóa AES-GCM Android Keystore khi bấm Lưu cài đặt; loại trừ prefs này khỏi backup/transfer. Gemini API key vẫn chỉ ở backend.
- Cloud STT/TTS cũ khóa mặc định (410); cần ENABLE_LEGACY_CLOUD_AUDIO=1 để opt-in. Backend ánh xạ quota 429, không auto retry. Answer yêu cầu ngắn, maxOutputTokens 512/thinking low; không coi đây là budget chi phí tuyệt đối.

## Build và artifact

Trong `android`, dùng JDK Android Studio rồi chạy:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-configuration-cache
```

Gradle tải model upstream có pin SHA-256 trong build.gradle.kts; đóng gói model/LICENSE vào APK, không tải model qua mạng lúc nhận giọng nói. APK arm64-v8a dành cho điện thoại 64-bit ở `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (~229 MB); x86_64 dành cho máy ảo (~233 MB). Không phát hành APK 32-bit trong cấu hình này. Model vi/en riêng đã chiếm khoảng 185 MB; cần đo RAM/độ trễ trên điện thoại thật trước khi tối ưu thêm.

Trong thư mục dự án:

```powershell
.\firmware\build-local-voice.ps1
```

Script chỉ build, không flash. Arduino ESP32 3.3.11, Flash 16 MB, PSRAM OPI, partition esp_sr_16. Image `firmware/build/voice-local/AiVisionGlasses.with-model.bin` dài 16 MiB, có srmodels.bin tại 0xC10000. Stock merged.bin/flash_args Arduino bỏ model, không dùng thay image này. Đổi phân vùng flash cần sao lưu và cho phép trước khi nạp.

Backend: `python -m unittest -v test_server` trong `backend`; test dùng server/mock nội bộ, không tiêu thụ Gemini API.

## Kiểm chứng đã thực hiện

- Android: **32 unit test PASS**, APK/test APK build thành công. Có test hủy HTTP đóng socket và hủy START_VIDEO khi ACK tới muộn. XML/report ở `android/app/build/test-results/testDebugUnitTest` và `android/app/build/reports/tests/testDebugUnitTest`.
- Backend: **12 test PASS** — auth/session/JPEG/WAV/TTS legacy, local-only default, quota không retry và phân biệt lỗi xác thực upstream với app token.
- Máy ảo Pixel_8 x86_64: **3 instrumentation test PASS** — native inference thực sự với audio mẫu vi/en; clear ViewModel vẫn giữ runtime demo; Keystore lưu/đọc token không plaintext. Audio mẫu en gốc 24 kHz được resample về 16 kHz trước test.
- Một lượt đo sample trên máy ảo: vi-VN ~2067 ms; en-US ~775 ms, gồm khởi tạo model. Không phải số đo trên điện thoại/kính hoặc đánh giá độ chính xác tiếng nói thật.
- Firmware build với model; app khoảng 1.71 MB/3 MB; globals khoảng 106.8 KB/327.7 KB. Đây là số compile, không phải RAM trống sau khi AFE chạy. Full image đóng gói và SHA-256 được script kiểm tra.
- Lượt kiểm thử ban đầu chỉ chạy emulator; trạng thái nạp kính/cài điện thoại mới nhất nằm ở cập nhật triển khai thực tế bên trên.

## Điều kiện còn thiếu trước khi dùng thật

1. Sao lưu flash, cho phép nạp firmware/model, kiểm Serial Hi ESP loaded/capability và RAM/PSRAM/stack thực sau init. Thử wake đúng/sai trong yên tĩnh và ồn; đo dòng điện/nhiệt.
2. Mic thật đủ mức, không clip/mất mẫu; hiệu chỉnh endpoint. Hiện wake bị chặn khi phát loa, chưa có AEC/barge-in. TTS khi đang quay video còn bị firmware từ chối; chưa có feedback giọng nói đầy đủ cho mọi lệnh.
3. Dataset câu vi/en thực của người dùng: lệnh/giọng nói/độ ồn và câu phủ định; kiểm không có lệnh sai sau STT. Smoke test mẫu không chứng minh chất lượng nhận dạng thực tế.
4. Chụp/quay/dừng bằng giọng nói → Gallery; AVI play/seek, SD đầy/thiếu, auto-stop, đứt TCP, ACK rồi quay tiếp. Descriptor video hiện chỉ ở RAM: reboot/mất điện không tự liệt kê lại file SD; file đã ACK phải dọn thủ công khi SD đầy.
5. Khóa/xoay màn hình, quyền notification, Doze và OEM battery policy. Foreground service không bảo đảm sống khi OS giết process; không tự khôi phục/replay sau process death. Reconnect chỉ khôi phục TCP, không tự mở lại hotspot nếu hotspot đã mất.
6. TTS Việt model/preset đã đóng gói; TTS Anh cần voice offline cài đủ. Vẫn phải thử latency/RAM/pin/chất lượng nghe trên điện thoại arm64. APK lớn là hạn chế; không tải model theo nhu cầu trong bản này.
7. Backend HTTPS, token/API key thật, quota/rate limit tầng triển khai, ảnh thật và lỗi mạng. Không coi kính là thiết bị dẫn đường/an toàn cho tới khi được kiểm chứng phù hợp.
8. BLE provisioning hiện chưa bắt buộc pairing/bonding; TCP HELLO|2 chỉ chốt protocol, không xác thực ứng dụng. Cần thiết kế xác thực/ủy quyền thiết bị và kiểm thử bảo mật trước khi triển khai rộng rãi, không suy ra bảo mật thiết bị từ token HTTPS của backend.

Checksum artifact cuối (SHA-256): APK arm64 TTS offline `3DC6BAAC1EE900A0D112498CE4CC0EDBB22D3A63E720467ED853D5172674B296`; full firmware/model không đổi `EDC87955C4542E191B8AD96B92917D28246BA6924A5E4727FEA3DDC35A398201`.

Giao thức mới: [protocol-v2-draft.md](protocol-v2-draft.md). Ca nghiệm thu: [test-checklist.md](test-checklist.md).
