# Thiết lập môi trường

Bản local-first 28/09/2026: xem [hướng dẫn build, model và nghiệm thu](local-first-implementation-2026-09-28.md), [TTS tiếng Việt offline](vietnamese-offline-tts.md) và [bàn giao bốn chức năng](ban-giao-bon-chuc-nang-2026-09-28.md). APK đóng gói STT vi/en, TTS Việt và OCR Latin, xuất riêng arm64-v8a/x86_64. Tiếng Việt không cần voice hệ thống; chỉ TTS tiếng Anh vẫn cần voice offline đã cài.

## Đọc chữ offline và thử bốn chức năng

Kết nối kính rồi chọn tiếng Việt và wake **Hi ESP**. OCR có nút **Chụp và đọc chữ** hoặc lệnh “Đọc chữ trước mặt tôi”, “Đọc nhãn”, “Đọc văn bản này”. **Đọc tiếp** dùng văn bản đã nhận dạng; **Xoay ảnh và đọc lại** dùng ảnh cũ, không chụp lại. **Phát lại** chỉ phát đoạn đã có, không gọi Gemini. Demo dùng ảnh nhân tạo, không chứng minh camera kính đọc sách tốt.

Mic kính gửi PCM về Android để STT offline. WakeNet Hi ESP chạy trên S3; không có model wake “Kính ơi”. Giờ/ngày/phép tính và OCR không cần URL/token backend. Phép tính hiện nhận toán hạng dạng chữ số trong transcript, ví dụ “Tính 2,5 cộng 3”; chưa hỗ trợ mọi cách đọc số bằng chữ. Gemini chỉ tạo nội dung câu hỏi ngoài nhóm local; lỗi cloud không chặn OCR/lệnh local.

Thử **Test loa bằng beep** trước, rồi **Test giọng Việt**, sau đó chụp/lưu ảnh và đọc chữ. Tắt Internet nhưng giữ Bluetooth/hotspot kính. Camera QVGA hiện phù hợp chữ lớn, gần và rõ; chữ nhỏ có thể thiếu hoặc sai. Chưa đo độ trễ/TTS/RAM trên điện thoại thật cho bản OCR mới.

## Android trên máy hiện tại

Đã kiểm tra ngày 24/09/2026:

- Android Studio: AI-261.23567.138.2611.15646644.
- JDK đi kèm Android Studio: 21.0.10.
- Android SDK Platform: API 37.0; Build Tools: 37.0.0; Platform Tools/ADB: 37.0.0.
- Gradle wrapper: 9.4.1; Android Gradle Plugin: 9.2.1; Kotlin: 2.2.10.
- App đã build thành công và chạy trên điện thoại Android 16 (API 36) qua ADB không dây.

Mở **D:\Project\Ai-Vision\android** trong Android Studio. Chọn Gradle JDK là JDK đi kèm Android Studio nếu IDE hỏi. Đường dẫn SDK của máy được lưu trong `android/local.properties`; tệp này đã tạo và được Git bỏ qua. Máy khác cần tự chọn SDK của mình.

Từ PowerShell trong thư mục `android`, chạy:

```powershell
.\gradlew.bat :app:assembleDebug --offline
```

APK debug hiện xuất riêng: `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` cho điện thoại ARM64 và `app-x86_64-debug.apk` cho máy ảo x86_64. Để chạy từ Android Studio, chọn điện thoại trong danh sách thiết bị rồi nhấn Run. Nếu không thấy điện thoại, bật Wireless debugging hoặc USB debugging trên điện thoại và kiểm tra bằng `adb devices -l`. Khi cần tải phụ thuộc mới, build không có `--offline`.

Luồng thử hiện tại trên điện thoại: bật Bluetooth → mở app → cấp quyền Bluetooth/Nearby Wi-Fi → bấm Connect. App tạo local-only hotspot, tìm BLE `Ai-Vision Glasses`, gửi thông tin Wi-Fi theo `docs/protocol.md`, nhận IP qua notify rồi mở TCP port 5000. Khi Connected, thử PING, Status, Capture và Save to Gallery (Android 10+). Không nhập IP thủ công. Firmware Arduino phải dùng cùng UUID và định dạng BLE trong protocol; nếu khác, sửa hợp đồng chung trước khi thử. Local-only hotspot không có Internet; trước khi thử cloud AI đồng thời, kiểm tra điện thoại còn đường Internet qua mạng di động.

Chế độ thử cũ nhập IPv4 từ Serial Monitor đã được bỏ khỏi app. Firmware cần hoàn thành BLE provisioning và thông báo `WIFI_CONNECTED|<IPv4>|5000` trước khi có thể Connect; TCP chỉ được mở tới địa chỉ app nhận qua BLE. Nếu firmware chưa hỗ trợ bước này, app sẽ báo lỗi thay vì âm thầm chuyển sang đường kết nối khác.

Phần Assistant cho nhập câu hỏi dạng văn bản. Câu hỏi về cảnh lấy ảnh mới từ kính rồi gửi tới backend AI; lệnh đơn giản như `chụp ảnh` đi local. Để dùng cloud, chạy [backend](../backend/README.md) sau HTTPS reverse proxy rồi nhập URL HTTPS và APP_TOKEN vào app. App không chứa GEMINI_API_KEY. Nút Phone mic demo chỉ dùng mic điện thoại và chỉ bật khi máy có dịch vụ nhận dạng on-device; chưa chứng minh mic hoặc wake word của kính.

App hiện dùng **V2 duy nhất**; không còn công tắc V1/V2. Sau `HELLO|2`, ảnh được truyền bằng `TAKE_PHOTO`/`GET_MEDIA`. Nút test loa phát tone 1 giây trực tiếp qua V2, không cần backend; dùng để tách lỗi loa/I2S khỏi TTS/cloud. Nút test mic thu 8 giây và báo peak/RMS/near-clipped, không gửi lên cloud. Nút nghe từ kính nhận PCM mic rồi STT offline trên Android; không gọi `/v1/transcribe`. Kết nối do runtime/service quản lý khi màn hình đổi trạng thái; vẫn cần nghiệm thu màn hình tắt trên điện thoại thật. Mic kính và tỷ lệ wake đúng/sai chưa được nghiệm thu.

Kiểm thử không cần board: từ `android/` chạy `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline`. Unit test dùng TCP server giả lập để xác nhận V2 handshake, request/response, ảnh/media, audio và tone chẩn đoán; không thay thế kiểm thử BLE, hotspot, I2S hay camera thật.

Không commit `local.properties`, cache Gradle, thư mục build, mật khẩu hoặc khóa API. Git trên máy hiện tại đã được tin cậy riêng cho `D:/Project/Ai-Vision`.

## ESP32

Sketch đang dùng nằm ở [firmware Arduino](../firmware/arduino/README.md); `firmware/main` là khung ESP-IDF cũ. Trên board 16 MB/PSRAM 8 MB đã thử, chọn Arduino-ESP32 3.3.11, Flash 16 MB, PSRAM OPI và partition app 3 MB. Cấu hình 4 MB/default trong Arduino IDE có thể báo sketch quá lớn dù phần cứng thực có 16 MB. Xem README firmware để lấy FQBN, mapping chân và bước nạp; đo RAM nội qua log `[MEM]` khi chạy, không dùng tỷ lệ flash để kết luận tràn heap.
