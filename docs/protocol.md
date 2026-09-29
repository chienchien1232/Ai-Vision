# Giao thức dùng chung Ai-Vision

Lưu ý: phần TCP V1 trong tài liệu này là lịch sử, **không còn là luồng app/firmware đang chạy**. Giao thức hiện hành là [V2](protocol-v2-draft.md), với BLE provisioning bên dưới vẫn giữ nguyên. Nguồn yêu cầu gốc: [giao_viec_esp32_ai_smart_glasses_v1.docx](giao_viec_esp32_ai_smart_glasses_v1.docx). Bản JSON cũ ở [protocol-legacy-json.md](protocol-legacy-json.md) chỉ để tham khảo.

## 1. Ranh giới các đường truyền

| Đường | Vai trò | Đã có hợp đồng V1 |
|---|---|---|
| BLE GATT | Tìm kính, gửi thông tin hotspot, nhận IP TCP | Có; UUID/payload là đề xuất Android cần firmware xác nhận |
| Wi-Fi local + TCP port 5000 | PING, GET_STATUS, CAPTURE, JPEG | Có theo tài liệu giao việc |
| Android ↔ backend AI | Câu hỏi tự do, ảnh mới, câu trả lời | App và backend đã có hợp đồng HTTPS thử nghiệm ở mục 7; chưa có khóa/triển khai thực |
| Âm thanh/video | Lệnh local, file media, kết quả wake word | Android có parser/audio V2 thử nghiệm nhưng firmware chưa xác nhận; xem mục 8 và protocol-v2-draft.md |

ESP32 là BLE peripheral và TCP server. Android là BLE central và TCP client. Khi Connect với kính thật, Android tạo local-only hotspot, gửi SSID/password qua BLE; kính tham gia hotspot rồi báo IPv4 của nó; app mở TCP :5000 tới địa chỉ vừa nhận. Không có chế độ nhập IP thủ công trong app. Không dùng `localhost` để nối kính thật. Local-only hotspot không cấp Internet cho kính; cloud AI chạy trên điện thoại và cần kiểm tra đường Internet riêng.

## 2. BLE provisioning: hợp đồng đề xuất để hai bên khớp code

Tài liệu nguồn chỉ chốt vai trò BLE, tên kính và thông báo WIFI_CONNECTED; nó không đưa UUID hay gói gửi SSID/password. Các giá trị sau hiện đã nằm trong Android. Người viết firmware cần xác nhận hoặc gửi giá trị thực để cập nhật cả tài liệu và app.

| Trường | Giá trị Android hiện dùng |
|---|---|
| Tên quảng bá | Ai-Vision Glasses (ASCII hyphen, đúng từng ký tự) |
| Service UUID | 6e400001-b5a3-f393-e0a9-e50e24dcca9e |
| RX của ESP32 / Android write with response | 6e400002-b5a3-f393-e0a9-e50e24dcca9e |
| TX của ESP32 / Android notify | 6e400003-b5a3-f393-e0a9-e50e24dcca9e |
| CCCD để bật notify | 00002902-0000-1000-8000-00805f9b34fb |

Trình tự hiện tại: scan theo tên/service → connect GATT với MTU mặc định 23 (không yêu cầu MTU 185 trên điện thoại thử nghiệm vì cấu hình đó gây ngắt kết nối) → discover service → bật notify và ghi CCCD → gửi Wi-Fi → chờ WIFI_CONNECTED → mở TCP V2. Mỗi BLE write chờ callback thành công rồi mới gửi đoạn kế, tối đa 20 byte. Firmware nối các đoạn thành một dòng kết thúc LF rồi mới giải mã.

Gói Android gửi là một dòng UTF-8: WIFI|base64url(SSID UTF-8)|base64url(password UTF-8) + LF. Base64url dùng bảng URL-safe, có thể có dấu = ở cuối. Ví dụ minh họa: SSID A, password B → WIFI|QQ==|Qg== + LF. Không ghi SSID/password vào log. Base64 chỉ để đóng gói, không phải mã hóa bảo mật; bản sản phẩm phải bảo vệ việc cấp mật khẩu bằng cơ chế BLE phù hợp.

Kính trả qua notify: WIFI_CONNECTED|<IPv4>|5000. Chấp nhận có hoặc không có LF vì tài liệu gốc không quy định LF ở thông báo này. Notify có thể bị chia đoạn; Android ghép đến khi nhận đủ IPv4 và port. Lỗi đề xuất: ERROR|<CODE> + LF; CODE chưa được firmware chốt. Android chờ thông báo tối đa 25 giây, sau đó đóng GATT/hotspot và báo lỗi.

## 3. TCP V1: quy tắc chung

Kết nối TCP tới IPv4 vừa nhận qua BLE, port 5000. UTF-8, các lệnh và header kết thúc LF (byte 0x0A); phản hồi text có thể kết thúc CRLF. Giữ cùng socket cho nhiều lệnh. Chỉ một lệnh đang chờ phản hồi trên socket. TCP là luồng byte: một lần read/write không tương ứng một lệnh, header hoặc ảnh hoàn chỉnh.

Android giới hạn header phản hồi 128 byte; firmware nên từ chối lệnh quá dài. Android connect timeout 4 giây; read timeout PING/GET_STATUS 3 giây, CAPTURE 10 giây. Sau EOF, timeout I/O, header sai hoặc phản hồi không khớp, Android đóng socket để tránh dùng nhầm byte của lệnh trước. Connect lại tạo phiên mới. V1 không có requestId hoặc retry tự động; không dùng schema JSON cũ.

| Android gửi | ESP32 trả | Ý nghĩa |
|---|---|---|
| PING + LF | PONG + LF | Kiểm tra TCP |
| GET_STATUS + LF | STATUS|WIFI=1|RSSI=-45 + LF | Trạng thái Wi-Fi; RSSI là số nguyên dBm |
| GET_STATUS + LF | STATUS|WIFI=1|CAMERA=0|MIC=0|SPEAKER=0 + LF | Dạng STATUS cũ vẫn được parser chấp nhận; ba cờ thiết bị là 0/1 |
| CAPTURE + LF | IMAGE|size|width|height|JPEG + LF, rồi đúng size byte JPEG | Chụp ảnh mới OV2640 |
| CAPTURE + LF khi lỗi | ERROR|CAPTURE_FAILED + LF | Camera không chụp được |

Tên lệnh, tên trường và chữ hoa/thường là cố định. STATUS phải có WIFI=0 hoặc WIFI=1 và ít nhất RSSI hoặc CAMERA; RSSI là số nguyên có thể âm; CAMERA/MIC/SPEAKER nếu gửi phải là 0/1. App chấp nhận cả hai dạng trên và có thể nhận cùng RSSI và các cờ. Không thêm dấu cách trong dòng. Kính có thể trả ERROR|<CODE> cho lỗi khác nếu hai bên thống nhất mã lỗi; Android hiện báo lỗi và đóng socket. Không dùng JSON hoặc HTTP /capture trong phiên TCP này.

## 4. Khung ảnh JPEG

Sau CAPTURE, firmware lấy frame JPEG, gửi header IMAGE|<size>|<width>|<height>|JPEG + LF, sau đó gửi đúng size byte raw JPEG bằng vòng lặp sendAll; cuối cùng trả frame buffer. Size là số byte thập phân của JPEG, không phải Base64 hay số ký tự. Width/height là số pixel. Không chèn dấu phân cách sau payload; byte đầu của phản hồi kế tiếp bắt đầu ngay sau byte JPEG cuối.

Android đọc header trước, kiểm tra size 1..5 MiB và width/height 1..4096, rồi đọc đủ size byte. JPEG phải bắt đầu FF D8 và kết thúc FF D9. App giải mã, hiển thị và có nút lưu Gallery trên Android 10+. Firmware V1 nên thử QVGA 320×240, JPEG quality khoảng 12 theo tài liệu giao việc.

## 5. Quản lý phiên và lỗi

Disconnect do người dùng: hủy tác vụ đang chờ, đóng TCP và BLE, dừng hotspot, xóa ảnh/trạng thái phiên. Mất kết nối khi đang rảnh có thể chỉ phát hiện ở lệnh sau; V1 chưa có heartbeat. Khi lỗi, app hiển thị lý do và cho Connect lại; firmware phải chấp nhận client mới mà không cần reset board. Không gửi lại CAPTURE tự động vì có thể tạo ảnh trùng.

Điện thoại và kính cần kiểm thử: Bluetooth tắt, không cấp quyền, không tìm thấy kính, hotspot lỗi, BLE mất giữa chừng, ESP32 không vào Wi-Fi, IP không hợp lệ, TCP không mở, PONG sai, STATUS sai, CAPTURE_FAILED, JPEG thiếu byte, ngắt giữa ảnh, app disconnect rồi reconnect.

## 6. Khả năng mở rộng và tương thích

V1 TCP chỉ cho luồng request → response tuần tự. Nó chưa có requestId, sự kiện bất đồng bộ hoặc cơ chế hủy lệnh trên firmware. Không tự đưa wake event, audio stream, video stream vào cùng socket theo cách khiến parser V1 đọc nhầm. [Bản đề xuất V2](protocol-v2-draft.md) mô tả frame type, request/session ID, length và các lệnh audio/media để nhóm duyệt; chưa dùng trên thiết bị hiện tại.

## 7. App ↔ backend AI (không truyền trên TCP với kính)

App gọi HTTPS POST /v1/answer, header Authorization: Bearer <APP_TOKEN>, Content-Type: application/json. JSON yêu cầu: {"sessionId":"uuid","question":"câu hỏi","imageBase64":"..."}; imageBase64 tùy chọn và là JPEG mới từ kính khi câu hỏi liên quan cảnh. Backend trả HTTP 200: {"sessionId":"uuid","answer":"văn bản"}. App chỉ dùng câu trả lời có cùng sessionId của phiên còn hoạt động; CANCEL ở app bỏ kết quả muộn và dừng TTS. Backend từ chối yêu cầu sai với mã HTTP và error ngắn, không trả khóa API. Giới hạn: câu hỏi 4000 ký tự, JPEG 5 MiB, body 8 MiB.

Backend giữ GEMINI_API_KEY trong biến môi trường, mặc định Gemini 3.8 Flash với mức suy luận low. App giữ APP_TOKEN trong RAM phiên hiện tại, không ghi vào Git/APK; endpoint phải là HTTPS. Model có thể đổi ở backend mà không đổi protocol kính. Luồng V1 là văn bản và ảnh; audio kính chỉ được xử lý khi firmware đàm phán V2 thành công. Câu trả lời phát ra loa kính qua V2 AUDIO_OUT (PCM_S16LE mono 16 kHz, chỉ loa kính, không fallback loa điện thoại); demo mode không kính vẫn dùng TTS điện thoại. Chi tiết frame trong docs/protocol-v2-draft.md §6b; loa thật chờ nghiệm thu board.

Khi bật V2 thủ công và firmware trả HELLO|2, kính thu khoảng 8 giây PCM16 mono 16 kHz vào PSRAM rồi app nhận qua event AUDIO_CHUNK/AUDIO_END, đóng thành WAV và gọi HTTPS POST /v1/transcribe với cùng Bearer token. JSON: {"sessionId":"uuid","languageTag":"vi-VN hoặc en-US","audioBase64":"WAV base64"}. Backend kiểm tra WAV tối đa 30 giây/1 MiB và trả {"sessionId":"uuid","transcript":"văn bản"}. App chỉ dùng transcript đúng phiên và điền vào ô Command or question; người dùng bấm Send riêng để chạy lệnh hoặc gửi /v1/answer (câu hỏi về cảnh sẽ lấy JPEG mới). Parser V2/backend/TCP giả lập đã được thử; chất lượng mic kính cần nghiệm thu bằng thử nghiệm thực tế. V1 không nhận audio.

## 7.1 Hợp đồng bên trong app

Android tách các nguồn sự kiện thành: DeviceCommand (lệnh từ UI/voice), DeviceResult (kết quả kính), MediaAsset (ảnh/audio/video), QuestionSession (câu hỏi, ảnh/audio liên quan, trạng thái cloud). ID phiên trong app dùng để bỏ kết quả cloud/TTS đến muộn sau khi người dùng hủy; ID này chưa được gửi cho firmware V1. Lệnh chụp cho câu hỏi về cảnh phải lấy ảnh mới, không tái dùng ảnh cũ. API key cloud chỉ giữ trên điện thoại hoặc backend an toàn, không đưa vào BLE/TCP/firmware.

## 8. Các giao thức còn phải chốt trước khi code tính năng tương ứng

| Tính năng | Quyết định còn thiếu | Người xác nhận |
|---|---|---|
| Mic kính → app | Android V2 đã chọn tạm PCM_S16LE mono 16 kHz và frame 64 KiB; firmware phải xác nhận format, tốc độ chunk và dữ liệu mic thật | Firmware audio + Android |
| Wake word/lệnh local trên kính | Danh sách lệnh, ngôn ngữ, confidence, chống kích hoạt trùng, định dạng event | Firmware AI local + Android |
| Ghi âm/video | Start/stop/status, tên mediaId, định dạng file, tải file, lỗi thẻ SD | Firmware media + Android |
| Âm thanh app → kính | Codec PCM_S16LE mono 16 kHz đã chốt (§6b draft); cần ví dụ byte thật và nghiệm thu loa MAX98357A trên board | Firmware audio + Android |
| Cloud AI nâng cao | Đo Gemini 3.8 Flash trên ảnh thật, streaming, lịch sử, triển khai HTTPS và bảo vệ token | Android/backend |
| Phiên bản V2 | Frame type, requestId, độ dài, checksum nếu cần, tương thích V1 | Firmware kết nối + Android |

Chỉ đánh dấu mục này là đã tích hợp sau khi có ví dụ byte thật, test parser hai phía và kiểm thử trên board. Không suy ra khả năng mic/loa/video từ việc PING hoặc JPEG đã chạy.
