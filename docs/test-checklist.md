# Checklist tích hợp Android ↔ ESP32

## Bốn chức năng chốt 28/09/2026 — bản OCR mới

Chi tiết môi trường, vòng debug, APK/checksum và ca nghiệm thu thật: [bàn giao](ban-giao-bon-chuc-nang-2026-09-28.md).

- [x] Android: 53 unit test đạt; build APK ARM64/x86_64 và test APK đạt; lint 0 lỗi (còn 20 cảnh báo/2 hint).
- [x] OCR: 10 test riêng và hồi quy 21 test khi tắt mạng đạt sau sửa ảnh demo; có OCR native chữ Việt/Anh → TTS Việt → AudioTrack. Không coi đây là nghiệm thu loa kính.
- [x] Backend: 12 test mock đạt, không gọi Gemini thật.
- [x] APK chính ARM64 mới đã cài và mở trên điện thoại 25098PN5AC; process foreground. Không cài lại test APK bị chặn và không coi mở app là nghiệm thu native/loa kính.
- [x] Local OCR không dùng backend; đọc tiếp/replay không nhận dạng/chụp/AI lại; hủy không phát kết quả phiên cũ; TTS lỗi vẫn giữ văn bản.
- [ ] Điện thoại thật và kính: APK mới, Hi ESP đúng/sai, mic → STT → chụp/giờ/math → loa, đọc sách/nhãn/biển thật, Cancel/mất TCP và số đo RAM/độ trễ.
- [ ] Gemini thật HOÃN theo yêu cầu; 401 trước đây là xác thực, không đồng nhất với quota 429.

## Bản local-first 28/09/2026

Kết quả và hướng dẫn mới: [local-first implementation](local-first-implementation-2026-09-28.md). Các checklist cloud STT/TTS/wake tùy ý bên dưới là lịch sử, không áp dụng cho đường mặc định mới.

- [x] Unit test Android: router vi/en, phủ định, local answer, PCM, transport phục hồi lỗi lệnh/DEVICE_ERROR/MEDIA_AVAILABLE và cancellation playback.
- [x] Backend: audio cloud bị khóa mặc định, lỗi Gemini 429 không auto retry.
- [x] Máy ảo Pixel 8: model STT native vi/en thực sự decode audio mẫu; runtime không mất kết nối demo khi ViewModel bị clear; token mã hóa Keystore round-trip.
- [x] Firmware esp_sr_16 build, đóng gói full image có srmodels.bin, backup16MB rồi nạp COM6; Serial báo Hi ESP loaded. Chưa nghiệm thu wake bằng giọng thật.
- [x] TTS mới: 41unit và11instrumentation emulator PASS (10+1 AudioTrack riêng); preset không tải model, native Việt sinh PCM, Cancel/idle/over30s, replay không callAI/render lại và audio lỗi không làm ảnh đã lưu thất bại. APK cuối đã cài/mở trên điện thoại; chưa nghiệm thu giọng trên kính thật.
- [ ] Trên kính thật: tải Hi ESP, RAM/PSRAM sau AFE, mic/VAD/noise, tỷ lệ wake đúng/sai, độ trễ, dòng điện/nhiệt.
- [ ] Lệnh nói chụp/quay/dừng → Gallery; video mở và seek được, mất TCP vẫn tải lại, ACK_MEDIA rồi quay mới.
- [ ] Tắt Internet: vi/en STT và lệnh local vẫn chạy; TTS Việt chạy dù không có voice Việt hệ thống, TTS Anh dùng voice offline đã cài.
- [ ] Test loa bằng beep → Test giọng Việt offline → chụp/lưu ảnh → audio xác nhận → Phát lại; không chụp lại/gọi AI lại khi replay.
- [ ] TTS lỗi/quá30giây: chữ/ảnh/video đã có vẫn giữ, kết quả tác vụ và warning audio hiển thị riêng.
- [ ] Cancel AI/STT/playback không phát kết quả cũ; khóa màn hình, xoay màn hình, reconnect TCP; Android giết process vẫn hiển thị trạng thái trung thực.
- [ ] Cloud HTTPS/API key thật, timeout/quota và ảnh thật; không dùng kính để điều hướng an toàn khi chưa kiểm chứng.

## Checklist prototype trước 28/09 (lịch sử)

Ghi ngày, điện thoại, phiên bản firmware và PASS/FAIL cho từng ca khi thử thật. Build app hoặc unit test với TCP giả lập không thay thế nghiệm thu board.

## Cập nhật 27/09/2026 — V2-only và kiểm thử phần cứng

- [x] APK V2-only `:app:assembleDebug :app:testDebugUnitTest` thành công và đã cài qua ADB lên điện thoại Android 16. App có bài thử loa 1 giây và đo mic 8 giây hoàn toàn local, không cần backend.
- [x] Sketch V2-only build bằng Arduino-ESP32 3.3.11, Flash 16 MB / app 3 MB / PSRAM OPI: 1,315,790 byte flash (41% partition app), 84,964 byte RAM tĩnh (25%). Arduino IDE của máy đang chọn Flash 4 MB/partition mặc định, cần đổi sang 16 MB/3 MB app.
- [x] Trên firmware đã nạp ở lần thử trước: BLE scan/provisioning → hotspot → TCP V2 PING/STATUS → TAKE_PHOTO/GET_MEDIA/JPEG trên board + điện thoại thành công; một ảnh thử đã lưu Gallery.
- [x] Firmware V2-only tối ưu audio đã nạp vào COM6, bootloader/partition/app xác minh hash đúng và reset thành công. Boot log: `camera=1 mic=1 speaker=1 sd=0`; PSRAM ~8 MB, RAM nội sau khởi tạo 139,096 byte, khối trống lớn nhất 98,292 byte.
- [x] Người dùng xác nhận beep tự kiểm tra lúc boot sạch và tone 1 giây từ nút `Test glasses speaker` trong app cũng sạch trên firmware mới. Chưa đo `[MEM]`/`[AUDIO-OUT] elapsed/max-gap` trong phiên phát, và chưa nghiệm thu tiếng TTS dài.
- [ ] Mic INMP441 chưa nghiệm thu: khi phần cứng sẵn sàng, dùng `Test glasses mic (8s, local only)` và đối chiếu peak/RMS/near-clipped của app với Serial; không suy ra mic tốt từ `mic=1`.
- [ ] Luồng backend STT/LLM/TTS HTTPS, video SD và wake word on-device chưa nghiệm thu trên bản này.

## Đã kiểm tra trên máy phát triển (26/09/2026)

- [x] Android `:app:assembleDebug` build thành công.
- [x] Unit test TCP: hai lần PING dùng cùng một socket, phản hồi chia đoạn vẫn đọc thành PONG.
- [x] Unit test TCP: IMAGE header + JPEG chia đoạn được đọc đủ byte, lệnh PING sau ảnh không lệch.
- [x] Unit test parser BLE/TCP và định tuyến lệnh local/câu hỏi cảnh.
- [x] App không còn đường nhập IPv4 thủ công; luồng kính thật bắt đầu bằng hotspot và BLE. Parser vẫn chấp nhận hai dạng STATUS trong tài liệu.
- [x] Backend unit test: kiểm tra token, echo sessionId và payload JPEG; chưa gọi Gemini thật.
- [x] TCP V2 giả lập: HELLO|2, nhận PCM qua AUDIO_CHUNK/AUDIO_END, TAKE_PHOTO và GET_MEDIA JPEG đúng seq/offset.
- [x] Backend unit test `/v1/transcribe`: kiểm tra WAV PCM16 mono 16 kHz và echo sessionId; chưa gọi Gemini thật.
- [ ] Chạy UI trên điện thoại qua ADB (chưa có thiết bị kết nối).
- [ ] Người làm firmware xác nhận hợp đồng BLE trong `docs/protocol.md`.

## Nghiệm thu với kính thật

- [ ] App xin quyền Bluetooth/Nearby Wi-Fi và bật local-only hotspot.
- [ ] Quét thấy `Ai-Vision Glasses`, kết nối GATT, bật notify.
- [ ] ESP32 nhận SSID/password qua BLE và vào hotspot; mật khẩu không xuất hiện trong log.
- [ ] BLE báo `WIFI_CONNECTED|<IPv4>|5000`; app không yêu cầu nhập IP.
- [ ] TCP Connect → PING hai lần → hai PONG trên cùng socket.
- [ ] GET_STATUS trả `STATUS|WIFI=1|RSSI=<số>` và app hiển thị đúng.
- [ ] CAPTURE trả header và JPEG raw đúng kích thước; app hiển thị ảnh từ OV2640.
- [ ] Bấm Save to Gallery và mở được ảnh trong Gallery trên Android 10+.
- [ ] Thử sai phản hồi, mất điện ESP32, mất Wi-Fi, tắt Bluetooth, timeout, rồi Connect lại không cần reset chip.
- [ ] Disconnect lúc đang kết nối hoặc đọc ảnh không crash, không hiển thị dữ liệu phiên cũ.
- [ ] Chạy 20 lần CAPTURE và kiểm tra bộ nhớ/độ ổn định.
- [ ] Câu hỏi văn bản không cần ảnh → backend → văn bản → TTS điện thoại, đúng sessionId.
- [ ] Câu hỏi về cảnh → ảnh mới từ kính → backend → câu trả lời đúng ảnh; CANCEL không đọc kết quả cũ.
- [ ] Nút Phone mic demo nhận tiếng Việt on-device trên điện thoại hỗ trợ; không dùng ca này để nghiệm thu mic kính.
- [ ] Backend chạy qua HTTPS, token không nằm trong APK/Git; đo độ trễ và độ đúng trên ảnh thật.
- [ ] Firmware V2 thật: wake event/audio PCM từ mic kính → `/v1/transcribe` → ảnh mới khi hỏi cảnh → `/v1/answer` → TTS điện thoại, đúng sessionId.
- [ ] Unit test TCP giả lập: handshake `PLAY_AUDIO`/`AUDIO_OUT` chunk 8 KB có ACK, ảnh PCM ra đúng byte, từ chối khi thiếu capability.
- [ ] Firmware V2 thật: `HELLO|2` có `AUDIO_OUT`, `STATUS` báo `SPEAKER=1`, hỏi chữ → nghe câu trả lời trên loa MAX98357A, đối chiếu log `[AUDIO-OUT]` Serial.
- [ ] CANCEL khi đang phát loa kính → `CANCELLED`, loa dừng ngay; mất TCP giữa chunk → dừng phát, Connect lại bình thường.
- [ ] Máy thiếu voice TTS on-device (vd không có voice vi-VN): app tự gọi `/v1/speak` → vẫn nghe câu trả lời trên loa kính, đúng sessionId.
- [ ] Nói "chụp ảnh" vào mic kính → kính chụp ảnh mới, hiện ảnh trên app, loa kính xác nhận "Đã chụp ảnh".
- [ ] Nói "quay video" → `VIDEO_STARTED`, Serial `[VIDEO] Recording started`; nói "dừng quay" → tải `.avi` về, mở được trong Movies/Ai-Vision, loa xác nhận "Đã lưu video".
- [ ] Quay quá 60 giây → tự dừng, báo `MEDIA_AVAILABLE`; "dừng quay" sau đó vẫn tải được video.
- [ ] Không cắm thẻ SD → `HELLO|2` thiếu `VIDEO`, "quay video" báo lỗi rõ ràng.
- [ ] Ô wake word trong app đổi cụm mới → Serial `[V2] Wake word selected` hiện đúng cụm (detection on-device để sau).
- [ ] Ghi âm (audio-only recording): giao thức chưa chốt, vẫn báo lỗi rõ ràng khi yêu cầu.
