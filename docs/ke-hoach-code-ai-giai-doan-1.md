# Kế hoạch code AI — Giai đoạn 1

Ngày lập: 26/10/2026. Bám theo giai đoạn 1 của [kế hoạch tổng thể](ke-hoach-ai-vision.md). Đây là kế hoạch triển khai, chưa phải xác nhận đã hoàn thành.

## 1. Kết quả cần đạt

Người dùng kết nối điện thoại Android thật với kính, bấm **WAKE AI**, nói một câu vào mic kính; app nhận dạng, thực hiện yêu cầu được hỗ trợ và phát kết quả qua loa kính. Kết thúc hoặc hủy luôn giải phóng phiên để có thể dùng tiếp.

Luồng chính:

```text
WAKE AI → START_LISTENING → mic kính → PCM về Android
  → STT offline Việt/Anh → IntentRouter
      → chụp ảnh / giờ-ngày / phép tính / phát lại / làm rõ
      → câu hỏi kiến thức hoặc cảnh: chuyển đúng nhánh cloud
  → trả lời chữ → TTS offline Android → PCM → loa kính → IDLE
```

Giai đoạn này hoàn thiện AI phía app và đường mic/loa kính. Model nhận lệnh ngắn trên ESP32 thuộc giai đoạn 4; wake word rảnh tay thuộc giai đoạn 5. Gemini thật thuộc giai đoạn 6, không chặn nghiệm thu các chức năng local ở giai đoạn 1. Nhánh hỏi cảnh phải chọn ảnh mới đúng phiên; kiểm nhánh này bằng fake ở giai đoạn 1, đánh giá ảnh/câu trả lời thực ở giai đoạn 3 và 6. Giữ OCR hiện có và chạy hồi quy, không mở rộng tính năng OCR trong đợt này.

## 2. Điểm xuất phát

- Đã có STT Moonshine/sherpa-onnx offline Việt/Anh, router, phép tính số bằng chữ, TTS Việt đóng gói và giao thức TCP V2.
- Kiểm tra 26/10: gói APK/test có sẵn ngày 19/10 chạy được trên emulator; 36 ca đạt, 1 ca Gemini bỏ qua. Chưa chứng minh APK khớp toàn bộ source hiện tại.
- Build mới đang bị chặn bởi lỗi Gradle `Unable to establish loopback connection`; cần khôi phục trước khi lấy kết quả kiểm thử source mới.
- ESP32 COM4 khởi tạo camera/mic/loa được; SD lỗi 0x107. Máy ảo quét BLE không thấy kính, cần điện thoại thật để nghiệm thu.
- Backend chưa chạy, chưa cấu hình Gemini key. Không dùng phản hồi Demo làm bằng chứng Gemini thật.

Vì đã có phần lớn cấu trúc, ưu tiên kiểm tra và sửa khoảng trống có bằng chứng; không viết lại STT/TTS/router.

## 3. Các gói công việc theo thứ tự

| Mã | Công việc | Nơi sửa/kiểm tra | Đầu ra và điều kiện hoàn thành |
|---|---|---|---|
| G1-01 | Khôi phục build từ source hiện tại | JDK/SDK/Gradle, cấu hình máy local | Xác định nguyên nhân lỗi loopback; build APK ARM64/x86_64 và APK test, unit test chạy được; ghi revision + diff + hash APK. Không commit đường dẫn hoặc cấu hình riêng của máy. |
| G1-02 | Thiết lập baseline kết nối kính thật | `GlassBleProvisioner`, `LocalHotspot`, `V2GlassTransport` | Điện thoại thật BLE → hotspot → HELLO V2 → PING/Status/Capture đạt; test beep và thu PCM đạt về giao thức. Chỉ sửa code nếu log cho thấy lỗi; ghi riêng lỗi dây/nguồn/quyền/kết nối. |
| G1-03 | Khóa hành vi hiểu lệnh | `IntentRouter`, `SpokenArithmetic`, `LocalAnswerHandler`, `CloudPolicy` | Phủ định không thực thi; câu hỏi chứa “không” không bị hiểu là hủy; hiểu số bằng chữ trong phạm vi ngữ pháp đã hỗ trợ; câu mơ hồ hỏi lại; các tác vụ local không gọi backend. |
| G1-04 | Hoàn thiện điều phối phiên WAKE AI | `DeviceViewModel`/`DeviceController`, `VoiceSessionState`, `LocalSpeechToTextEngine` | Chỉ một phiên sở hữu mic/STT; bấm lặp không tạo lệnh trùng; STT rỗng/lỗi có thông báo; Cancel, disconnect, đổi phiên bỏ mọi kết quả cũ; lượt sau sử dụng được. |
| G1-05 | Hoàn thiện trả lời và phát lại | `LocalSpeechRenderer`, `ReplyAudio`, `V2GlassTransport`, nhánh playback trong controller | Giữ chữ khi TTS/loa lỗi; phát đúng phiên và đúng thiết bị; Cancel ngắt phát; replay không gọi Gemini hoặc chụp ảnh mới; thu lại sau phát hoạt động. Chỉ chỉnh firmware nếu có lỗi giao thức được tái hiện. |
| G1-06 | Kiểm thử và bàn giao | Unit, instrumentation, kính + điện thoại thật, tài liệu nghiệm thu | Chạy đầy đủ ma trận bên dưới; ghi môi trường và số đo; phân biệt fake/Demo, native emulator và phần cứng thật. Không đánh dấu hoàn thành khi còn ca bắt buộc chưa thử. |

G1-01 và baseline G1-02 phải đạt trước nghiệm thu phần cứng. Có thể chuẩn bị ca router/session trong thời gian chờ điện thoại thật, nhưng chưa coi đã kiểm đường mic kính → loa kính.

## 4. Quy tắc triển khai

- Giữ giao thức TCP V2 và STT/TTS offline hiện có. Không thay model chỉ vì một ca lỗi khi chưa tách lỗi mic, kết nối và ứng dụng.
- Model STT chỉ trả bản chép lời; router quyết định intent; controller thực thi và quản lý phiên. Không để model tự gọi camera hoặc phát âm thanh.
- Rà soát các điểm sau kết quả bất đồng bộ: STT, lấy ảnh, backend, OCR, TTS, truyền/phát PCM. Chỉ phiên đang có quyền mới được cập nhật UI, lưu media hoặc bắt đầu phát.
- Phân biệt trạng thái kết nối và trạng thái tác vụ. Khi kết nối còn sống thì hoàn tất/hủy trở về IDLE; khi mất kết nối phải về trạng thái ngắt với tài nguyên được dọn sạch.
- Không tự gửi câu lệnh local lên cloud khi xử lý local lỗi. Nhánh cloud bị tắt hoặc thiếu cấu hình phải báo rõ; không bịa câu trả lời.
- Không ghi khóa, token, credentials hotspot hoặc PCM/câu nói cá nhân vào log thường. Log đo đạc dùng session ID, số byte, trạng thái và thời gian.
- Các file đang có thay đổi phải được giữ nguyên phần công việc sẵn có; mỗi lần sửa dựa trên diff và ca tái hiện cụ thể.

## 5. Ma trận nghiệm thu bắt buộc

| Nhóm | Ca kiểm tra | Kỳ vọng |
|---|---|---|
| Kết nối | Connect trên điện thoại thật, PING, Status, Capture | Có phản hồi thật từ ESP32; ảnh mới được nhận và lưu. |
| Lệnh trực tiếp | “Chụp ảnh” | Chụp đúng một lần, phản hồi rõ, phiên kết thúc. |
| Local | “Mấy giờ rồi?”, “Hôm nay ngày mấy?”, “Hai cộng ba” | Đúng chức năng; phép tính trả 5; không gọi backend. |
| Phép tính | Số âm, thập phân, trộn chữ/số và mẫu ngoài ngữ pháp | Đúng với phạm vi đã hỗ trợ; ngoài phạm vi hỏi lại, không đoán. |
| Phủ định | “Đừng chụp ảnh”, “Không quay video” | Không thực hiện hành động bị phủ định; không gọi Gemini. |
| Câu hỏi cảnh | “Đây có phải chai nước không?”, “Vật bên trái là gì?” | Chọn nhánh câu hỏi cảnh, ảnh mới của lượt; không hiểu thành lệnh hủy. Dùng fake để kiểm giai đoạn 1. |
| Làm rõ | “Xem giúp tôi” | Hỏi lại đọc chữ hay mô tả cảnh; không tự chụp/gọi cloud. |
| STT | WAV mẫu Việt/Anh và câu ngắn từ mic kính | Fixture đúng kỳ vọng; ghi riêng kết quả mic thật và nội dung nghe được. Tập đánh giá tiếng ồn lớn thuộc giai đoạn 2. |
| Mất Internet | Tắt Internet, giữ BLE/hotspot local trên điện thoại | Chụp ảnh, giờ, phép tính, replay vẫn dùng được; xác nhận không có đường Internet, không chỉ tắt Wi-Fi. |
| Hủy | Hủy khi thu, STT, đợi ảnh, TTS hoặc phát; cố trả kết quả cũ bằng fake | Kết quả cũ không thực thi/phát/lưu thêm; phiên mới hoạt động. |
| Đồng thời | Bấm WAKE AI/Send lặp nhanh | Không tạo hai phiên, không chụp hoặc phát lặp do bấm trùng. |
| Lỗi và phục hồi | STT rỗng, TTS lỗi, mất TCP rồi nối lại | Thông báo cụ thể; không kẹt busy; giữ chữ khi phát lỗi. |
| Replay | Phát lại sau local, sau Cancel và sau lỗi phát | Phát bản phản hồi hợp lệ; không dùng kết quả đã hủy; không gọi backend/chụp thêm. |
| Độ bền phiên | 20 phiên local liên tiếp trên kính + điện thoại thật | Không kẹt phiên, không lẫn kết quả, không hành động trùng; ghi lỗi và thời gian từng lượt. |
| Hồi quy | OCR, đọc tiếp, ảnh và các test đang có | Không làm hỏng chức năng đã tồn tại. |

Unit test tập trung router/policy và các trường hợp số. Test phiên dùng fake có thể điều khiển kết quả về muộn; instrumentation kiểm STT/TTS native, UI và audio. Phần cứng kiểm riêng camera/mic/loa/BLE/TCP. Thêm ca mới chỉ khi có rủi ro hoặc lỗi chưa được test hiện có bao phủ.

## 6. Lịch dự kiến và phân vai

Ước lượng 5–7 ngày làm việc cho một người đã quen dự án, tính sau khi có điện thoại thật và công cụ build dùng được; không phải cam kết ngày hoàn thành.

| Thời lượng | Trọng tâm |
|---|---|
| Ngày 1 | G1-01: build, kiểm diff, chạy baseline source. |
| Ngày 2 | G1-02: kết nối điện thoại thật, đo PCM và thử loa/camera. |
| Ngày 3 | G1-03: rà router, phạm vi số, cloud policy; bổ sung ca còn thiếu. |
| Ngày 4–5 | G1-04/G1-05: phiên, hủy, lỗi, playback; kiểm cả fake và thiết bị. |
| Ngày 6–7 | G1-06: mất Internet, 20 phiên, hồi quy, sửa lỗi phát hiện và bàn giao. |

Nếu làm theo nhóm: Android phụ trách G1-01/03/04/05; firmware-kết nối phụ trách baseline G1-02 và lỗi board có bằng chứng; người kiểm thử phụ trách ma trận G1-06. Chưa gán tên nhân sự hoặc tạo tác vụ tự động.

## 7. Cổng hoàn thành và bàn giao

Chỉ chốt giai đoạn 1 khi build/unit/instrumentation của source mới đạt và các ca phần cứng bắt buộc có bằng chứng trên kính thật. Không lấy emulator, log “initialized” hoặc test Gemini giả làm bằng chứng mic/loa/AI cloud thật.

Bàn giao gồm source revision kèm diff chưa commit nếu có, phiên bản firmware/model, APK ARM64/x86_64 và SHA-256, bảng PASS/FAIL/chưa thử, mẫu đo thời gian thu/STT/xử lý/TTS/truyền-phát, cách chạy lại và lỗi còn tồn tại. Chưa đặt ngưỡng độ trễ đạt/chưa đạt khi chưa có baseline thiết bị.

SD/video, tích hợp model nhận lệnh trên chip, wake rảnh tay, hội thoại Gemini thật và triển khai backend nằm ở các giai đoạn tương ứng. Lỗi SD không chặn các ca chụp ảnh/âm thanh local nếu đường đó không dùng SD.

**Việc đầu tiên khi bắt đầu code:** hoàn tất G1-01, lấy bản build mới kiểm chứng được trước khi sửa thêm chức năng.
