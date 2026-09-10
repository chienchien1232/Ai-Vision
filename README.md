# Ai-Vision

Ứng dụng Android thử nghiệm kính AI. Giai đoạn hiện tại dùng thiết bị giả lập; bước tiếp theo là nhận giọng nói tiếng Việt offline trên điện thoại rồi thực thi camera/audio của điện thoại. Khi có XIAO ESP32-S3 Sense, tích hợp phần cứng qua BLE/Wi-Fi.

## Mở dự án

Trong Android Studio, mở thư mục `android/`, không mở thư mục gốc Ai-Vision như một Android project.

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

Giữ package hiện tại `com.example.ai_vision`. `com/team/smartglasses` trong cây kế hoạch là tên minh họa, không phải package đã đổi.

## Phân chia mã nguồn

| Thư mục | Nội dung | Chủ sở hữu theo kế hoạch nhóm |
|---|---|---|
| android | App, giao diện, giao tiếp, voice và AI | Người 3 |
| firmware/main | Điểm khởi động firmware | Người 1 |
| firmware/components/core, connectivity, protocol | Hệ thống, mạng, giao thức | Người 1 |
| firmware/components/board, camera, audio, recorder, local_ai | Phần cứng, media và AI local | Người 2 |
| backend | Chưa triển khai; tạo dịch vụ khi tới mốc cloud | Người 3 |
| tools/device_simulator | Dành cho trình giả lập độc lập nếu cần | Người 3 |
| tests/integration | Checklist tích hợp hệ thống | Cả nhóm |
| docs | Thiết lập, phần cứng, giao thức và nghiệm thu | Cả nhóm |

Nếu tự làm một mình, thực hiện các vai trò lần lượt. Không cần viết đồng thời mọi thư mục.

## Bước tiếp theo

1. Xác nhận app Android build được sau khi chuyển thư mục.
2. Hoàn tất nút Get status và hiển thị thông tin fake nếu chưa làm.
3. Xây bản nhận tiếng Việt offline: mic điện thoại → Vosk → hiển thị văn bản. Kiểm tra không có Internet sau khi model đã được cài.
4. Ánh xạ câu nói thành command, rồi thêm chụp ảnh trên điện thoại.
5. Thêm ghi âm, video, kích hoạt giọng nói, sau đó cloud và TTS.
6. Khi board về: kiểm tra boot trước, rồi thay nguồn audio và transport bằng phần cứng thật.

Các thư mục dự phòng chưa có chức năng. Firmware mới là mẫu boot, chưa được xác nhận trên board.
