# Thiết lập dự án

## Android

- Mở `android/` bằng Android Studio.
- Phiên bản thư viện và plugin nằm trong `android/gradle/libs.versions.toml`.
- Gradle wrapper nằm trong `android/gradle/wrapper/gradle-wrapper.properties`.
- Giữ các phiên bản đang có; không nâng toàn bộ công cụ cùng lúc.
- Mỗi máy tự cấu hình SDK trong `android/local.properties`; không commit tệp này.
- Build: chạy `android/gradlew.bat :app:assembleDebug` từ thư mục `android`.
- Kiểm thử máy thật/máy ảo: `./gradlew.bat :app:connectedDebugAndroidTest` từ `android`.
- APK debug: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Firmware

Khung dùng ESP-IDF, target ESP32-S3. Trong ESP-IDF terminal, chuyển tới `firmware`, chạy `idf.py set-target esp32s3`, sau đó `idf.py build`. Chưa cài ESP-IDF thì phần này chưa sẵn sàng build. Ghi phiên bản được kiểm chứng sau lần build đầu tiên.

Khi nhận board, xác nhận cổng USB và dùng `idf.py -p <PORT> flash monitor`. Chỉ nghiệm thu khi thấy log boot sau khi khởi động lại. Chưa cấu hình pin camera/mic hoặc PSRAM theo giả định.

## Sau khi chuyển thư mục

Đóng cửa sổ Android Studio đang mở đường dẫn cũ, mở lại `android/` và sync Gradle. Cache `.gradle`, `.kotlin`, `build` còn ở thư mục gốc là sản phẩm build cũ, không phải mã nguồn; không sử dụng chúng làm project mới.
