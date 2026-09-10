# Giao thức bản giả lập

Nguồn model hiện tại: `android/app/src/main/java/com/example/ai_vision/device/protocol`.

## Hợp đồng Kotlin hiện tại

- GlassCommand: requestId (String), name (CommandName).
- GlassEvent: requestId (String), name (EventName), status (GlassStatus?, mặc định null).
- GlassStatus: deviceName (String), firmwareVersion (String).

| Command | Event | Dữ liệu |
|---|---|---|
| PING | Pong | status = null |
| GET_STATUS | Status | status chứa tên và phiên bản thiết bị |

Mỗi lần gửi tạo requestId mới. Event phải giữ mã của command. App kiểm tra cả mã và tên event; Status phải có dữ liệu status. Sai phản hồi dẫn tới lỗi, không cập nhật thành công.

Connect chờ tối đa 5 giây; lệnh chờ tối đa 3 giây theo cấu hình đang sử dụng. Một lệnh hoạt động mỗi lúc. Disconnect hủy tác vụ, ngắt transport, xóa trạng thái lệnh và dữ liệu thiết bị. Hủy do người dùng khác với timeout.

## Phần chưa triển khai

Chưa có JSON serializer, phiên bản wire protocol, GATT UUID, cách phân mảnh BLE, hoặc endpoint media. Tên enum trên đây phản ánh code hiện tại, chưa phải giao thức firmware đã kiểm chứng. Phải chốt các mục này trước khi tích hợp BLE thật.
