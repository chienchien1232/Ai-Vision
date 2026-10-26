# Kế hoạch lập trình AI cho kính AI-VISION

Cập nhật 19/10/2026. Điện thoại Android là thành phần bắt buộc. Nút **WAKE AI** là đường sử dụng và kiểm thử hiện hành.

## 1. Mục tiêu và phân chia xử lý

Người dùng gọi kính, nói yêu cầu và nghe kết quả qua loa. Chức năng đích: chụp ảnh; bắt đầu/dừng và lưu video; đọc chữ, đọc tiếp, phát lại; mô tả cảnh và hỏi về vật đang nhìn; giờ/ngày/phép tính/kiến thức; dừng, âm lượng và trạng thái.

| Thành phần | Trách nhiệm |
|---|---|
| ESP32-S3 | Camera, mic, loa, kết nối; wake word và tập lệnh ngắn sau khi tích hợp, kiểm chứng model |
| Android | STT Việt/Anh offline, router, OCR, câu trả lời local, TTS, lưu media, quản lý phiên |
| Backend | Giữ khóa API, xác thực app, gọi Gemini, giới hạn yêu cầu và chuẩn hóa lỗi |
| Gemini | Kiến thức, mô tả và giải thích ảnh; cần Internet |

Luồng đích: gọi kính → thu câu nói → thử nhận lệnh ngắn trên ESP32 → nếu không nhận được thì chuyển PCM sang Android → xử lý local hoặc Gemini → TTS Android → loa kính. Khi chưa tích hợp model chip/wake, bắt đầu bằng WAKE AI và dùng Android STT offline.

## 2. Các giai đoạn và cổng nghiệm thu

### Giai đoạn 1 — Luồng AI Android bằng WAKE AI

- Sửa phủ định theo hành động; câu hỏi có “không” và số không vẫn được xử lý.
- Nhận các cách hỏi cảnh: “mô tả căn phòng”, “nhìn giúp tôi”, “vật bên trái là gì”.
- Hỏi lại khi chưa rõ muốn đọc chữ hay mô tả cảnh.
- Chuẩn hóa phép tính số bằng chữ; bảo đảm local không gọi Gemini.
- Đồng bộ thu âm → STT → định tuyến → xử lý → TTS → phát → cleanup; loại kết quả sau hủy.
- Code chính: `IntentRouter`, `SpokenArithmetic`, `LocalAnswerHandler`, `CloudPolicy`, `DeviceController` trong `DeviceViewModel.kt`.
- **Đạt khi:** kiểm thử phủ định/câu hỏi có “không”/ảnh/số bằng chữ đạt; chụp ảnh, giờ, phép tính, replay hoạt động khi tắt Internet; kết quả phiên hủy không phát trong lượt sau. Cần bằng chứng trên kính thật.

### Giai đoạn 2 — Ổn định STT và tiếng phát

- Giữ pipeline STT offline Việt/Anh; thu mẫu mic kính ở nhiều khoảng cách và mức tiếng ồn.
- Đo đúng câu và đúng ý định; tách lỗi mic với lỗi model; chỉnh bắt đầu/kết thúc câu để không mất âm đầu/cuối.
- Không nghe rõ thì yêu cầu nói lại; đo tải model, STT, TTS và truyền âm thanh.
- Chia đoạn phản hồi dài theo giới hạn phát; kiểm Cancel, mất kết nối và replay.
- **Đạt khi:** có báo cáo kính/điện thoại thật, ít nhất 20 phiên liên tiếp không kẹt hoặc lẫn kết quả, nghe rõ đầu/cuối câu và có độ trễ từng bước.

### Giai đoạn 3 — OCR và hỏi về cảnh

- OCR offline: ảnh mới → OCR Android → chia nội dung → TTS → loa; giữ đọc tiếp/xoay/đọc lại.
- Ảnh mờ hoặc không có chữ phải hướng dẫn giữ yên, đưa gần, chụp lại; đánh giá độ phân giải với bộ nhớ và chữ Việt có dấu, nhãn, biển báo, sách.
- Hỏi cảnh dùng ảnh mới gắn đúng lượt; gửi ảnh/câu hỏi qua backend; trả lời ngắn và nêu thiếu thông tin khi cần.
- Không dùng ảnh cũ khi chụp mới thất bại; giữ câu trả lời chữ khi phát tiếng lỗi.
- **Đạt khi:** đọc tiếp/replay không chụp lại hoặc gọi Gemini; câu hỏi cảnh có ảnh mới hoặc lỗi chụp rõ ràng; có bộ ảnh thực tế và bảng đánh giá OCR/mô tả.

### Giai đoạn 4 — Nhận lệnh ngắn trên ESP32-S3

- Tập đầu: chụp ảnh, dừng, tăng/giảm âm lượng, trạng thái, phát lại.
- Đánh giá model, toolchain và ngôn ngữ; thử tài nguyên/chất lượng trước khi chọn.
- Implement `LocalIntentProvider` → `IntentWorker`; model chỉ trả intent/độ tin cậy, `DeviceCommandExecutor` thực thi.
- Hiệu chỉnh threshold theo dữ liệu; fallback Android khi không hỗ trợ, không chắc chắn hoặc provider lỗi. Xử lý hủy, deadline và ownership PCM theo [contract](local-ai-integration-contract.md).
- **Đạt khi:** có đúng/nhầm, inference và RAM đỉnh trên board; chụp đúng một lần; fallback hoạt động; chỉ công bố tiếng Việt trên chip sau khi kiểm chứng model tương ứng.

### Giai đoạn 5 — Wake word và rảnh tay

- Kiểm chứng WakeNet trước khi bật nghe IDLE; dùng wake word model hỗ trợ. “Kính ơi” là đầu việc model riêng.
- Tín hiệu bắt đầu nghe, âm thanh đệm giữ đầu câu, chặn loa tự kích wake, tự về chờ khi không có lời nói.
- Đo gọi đúng/bỏ sót/kích hoạt nhầm trong yên tĩnh và tiếng ồn, cùng tiêu thụ điện.
- **Đạt khi:** nhiều lượt không cần bấm app và có số đo; WAKE AI tiếp tục ổn định. Nói chen để ngắt loa là cải tiến sau.

### Giai đoạn 6 — Cloud và hội thoại

- Kiểm chứng model/API bằng yêu cầu Gemini thật, riêng text và JPEG.
- Chuẩn hóa auth/quota/timeout/dịch vụ tạm lỗi; giới hạn tần suất và ngân sách; triển khai backend HTTPS dùng ngoài PC/ADB.
- Lịch sử ngắn có giới hạn theo phiên; gắn đúng ảnh từng lượt và ảnh được hỏi tiếp; cho phép kết thúc/xóa phiên.
- **Đạt khi:** mic → STT → Gemini → TTS → loa đạt trên thiết bị; local hoạt động khi mất Internet; lịch sử và mức sử dụng có giới hạn rõ ràng.

### Giai đoạn 7 — Media và nghiệm thu

- Sửa/kiểm SD trước nghiệm thu video; nối giọng nói với bắt đầu/dừng và lưu video.
- Kiểm xung đột video/mic/loa, mất kết nối, nối lại, hủy và sử dụng kéo dài.
- Ghi source revision, firmware, APK, model và kết quả tương ứng. Video trong phạm vi này là ghi/lưu; phân tích video liên tục cần kế hoạch riêng.
- **Đạt khi:** mỗi chức năng đạt đầu-cuối trên kính thật và có bản ghi kết quả tương ứng.

Thứ tự: router + mic/STT/TTS → OCR/ảnh → lệnh ngắn ESP32 → wake → cloud/hội thoại/video → nghiệm thu toàn bộ. Mỗi giai đoạn bàn giao source, kiểm thử, cách chạy, số đo thiết bị và giới hạn còn tồn tại.

## 3. Bản sửa Android ngày 19/10/2026

Source nền: `110fc05`; thay đổi nằm trong working tree nhánh `feature/android-ai-phase1`. Đã build APK ARM64/x86_64 và APK test; bản x86_64 đã cài/mở trên emulator. Hash và bằng chứng tại [báo cáo nghiệm thu Giai đoạn 1](nghiem-thu-giai-doan-1-2026-10-19.md).

### Mã nguồn đã bổ sung

- “Đừng chụp ảnh”, “không quay video”, “tôi không muốn chụp ảnh” trả lời làm rõ, không thực thi và không gọi Gemini.
- “Đây có phải chai nước không?” đi nhánh câu hỏi có ảnh mới; “không cộng hai” là phép tính local.
- Các câu hỏi cảnh ở giai đoạn 1 yêu cầu ảnh mới. “Xem giúp tôi”, “đọc giúp tôi”, “look at this” hỏi lại đọc chữ hay mô tả cảnh.
- Phép tính nhận chữ số như trước; thêm số bằng chữ Việt/Anh 0–999, số âm, phần thập phân đọc từng chữ số và toán hạng trộn chữ/chữ số. Ví dụ: “hai cộng ba”, “một trăm lẻ năm nhân hai”, “hai phẩy năm cộng 3”, “two plus three”. Ngữ pháp số chưa hỗ trợ thì hỏi lại khi xác định là phép tính; không đoán toán hạng.
- Kiểm tra cancellation sau các kết quả bất đồng bộ, trước cập nhật ảnh/transcript/phát tiếng; cleanup cũ chỉ được xử lý lỗi kết nối của chính nó.
- Chế độ test mic hoàn tất sẽ cleanup về IDLE. Controller nhận lệnh “Dừng” khi đang chạy; trên giao diện, dùng nút **Cancel action** để ngắt tác vụ.
- `CloudPolicy` giữ nguyên nguyên tắc: chỉ `AppIntent.Question` có lý do gọi cloud. Đã bổ sung kiểm thử cho các nhánh local.

### Kết quả kiểm tra trong môi trường hiện tại

| Hạng mục | Kết quả |
|---|---|
| Unit trong module Android | **67 test đạt**, gồm router/local/policy và state; không lỗi/skip |
| Build Android / APK / APK test | **Đạt**, dùng SDK `D:\Android\Sdk` |
| Lint | **0 lỗi, 20 cảnh báo, 2 gợi ý** |
| Instrumentation, emulator Android 16 mất mạng | **34 đạt, 1 bỏ qua, 0 lỗi**; gồm cleanup mic, ACK/ảnh/TTS đến sau hủy, local không gọi AI, 20 phiên liên tiếp |
| Nghiệm thu UI offline | **Đạt**: chụp/lưu ảnh Demo, giờ, phép tính số bằng chữ, replay, phủ định/làm rõ; TTS Việt native và AudioTrack thật |
| Kính/điện thoại thật, xác nhận nghe và độ trễ mic → loa | Chưa kiểm tra trong lượt này |

Lượt đầu có 12 test Kotlin/JVM độc lập đạt khi chưa cấu hình SDK. Lượt nghiệm thu tiếp đã chạy đầy đủ module Android và emulator; ca Gemini thật được bỏ qua có chủ đích trong điều kiện offline. **Phần Android Giai đoạn 1 đạt trên máy ảo; nghiệm thu đầu-cuối trên kính thật còn chờ.** Xem [báo cáo](nghiem-thu-giai-doan-1-2026-10-19.md) để phân biệt native model, UI và test doubles.

## 4. Hướng dẫn kiểm tra tiếp

SDK trên máy hiện tại ở `D:\Android\Sdk`, AVD `AiVision_Android_16` đang dùng API 36. Đặt `ANDROID_HOME` hoặc `sdk.dir` trong `android/local.properties`; xem [setup](setup.md). Chạy từ `android/`:

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-configuration-cache
# Máy ảo/thiết bị test đã được kết nối:
.\gradlew.bat :app:connectedDebugAndroidTest --no-configuration-cache
```

Lần build đầu cần mạng để tải dependencies và model; model STT/TTS/OCR dùng offline sau khi đóng gói. Chọn APK đúng ABI.

Trên kính thật, chọn tiếng Việt, kết nối rồi tắt Internet nhưng giữ BLE/hotspot:

1. WAKE AI → “chụp ảnh”: chụp/lưu một lần, nghe xác nhận.
2. WAKE AI → “bây giờ là mấy giờ”; “hai cộng ba”: nghe giờ/kết quả 5; không có yêu cầu Gemini.
3. “Phát lại”: dùng phản hồi đã có, không chụp lại hoặc gọi Gemini.
4. “Đừng chụp ảnh”: không chụp. “Xem giúp tôi”: nghe yêu cầu làm rõ.
5. Bật Internet/Gemini: “đây có phải chai nước không?”, “mô tả căn phòng”, “vật bên trái là gì”: mỗi lượt dùng ảnh mới. Cho chụp mới thất bại để xác nhận không gửi ảnh cũ.
6. Cancel trong STT/AI/TTS/chụp/phát; bắt đầu lượt tính toán mới sau cleanup: không phát/cập nhật phản hồi cũ.
7. Test mic xong rồi WAKE AI lại: không kẹt phiên. Chạy ít nhất 20 lượt, ghi kết quả từng lượt, tiếng đầu/cuối và độ trễ từng bước.

Mẫu ghi thiết bị: source revision + diff, firmware/APK/model version/hash, board/điện thoại, ngôn ngữ, khoảng cách/tiếng ồn, thao tác, kết quả mong đợi/thực tế, latency và PASS/FAIL. Chưa có số đo thì để trống, chưa đánh dấu hoàn thành.
