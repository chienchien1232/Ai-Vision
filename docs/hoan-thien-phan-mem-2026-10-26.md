# Hoàn thiện core và backend — 26/10/2026

## Phạm vi hiện tại

Theo yêu cầu mới, ưu tiên code và kiểm thử trên máy tính, không cần kết nối ESP32 hoặc chạy máy ảo. Kết quả trong tài liệu này là JVM unit test, Python unittest với HTTP server local và biên dịch/lint Android. Các báo cáo APK/native trước đó là kết quả của source ở thời điểm tương ứng.

## Các phần đã bổ sung

### Hiểu lệnh và hỏi làm rõ

- Thêm `ConversationRouter`: nhớ một câu hỏi làm rõ trong RAM tối đa 30 giây, dùng đồng hồ monotonic.
- Ví dụ: “Xem giúp tôi” → app hỏi đọc chữ hay mô tả cảnh → “đọc”/“text” chọn OCR local; “mô tả”/“scene” chọn câu hỏi cảnh cần ảnh mới. Câu trả lời ngắn chỉ có ý nghĩa này khi đang có ngữ cảnh hợp lệ.
- Lệnh đầy đủ mới thay thế ngữ cảnh cũ; phủ định không thực thi hành động. Cancel, Connect/Disconnect, mất kết nối, đổi ngôn ngữ và thao tác trực tiếp xóa ngữ cảnh. Bấm Send khi đang bận không tiêu thụ câu hỏi đang chờ.
- Câu hỏi làm rõ đọc/mô tả có phản hồi tiếng Anh khi chọn `en-US`.
- Phép tính thiếu vế như “hai cộng”, “8 chia”, “tính hai” được yêu cầu nói lại tại local.
- Các câu không dấu như “dung chup anh”, “dung doc chu” được hỏi lại; “dừng nói” và “dừng quay video” vẫn là lệnh dừng.

### Phiên, phản hồi và backend client

- Dọn STT/tác vụ/âm thanh khi mất session kể cả trường hợp không có endpoint để reconnect.
- Giới hạn thời gian dọn phiên voice ở 5 giây.
- Khi Cancel một phản hồi đang tạo/phát, khôi phục cache phản hồi hợp lệ trước đó; Cancel lúc đang Replay giữ phản hồi cũ. Đã bổ sung assertion trong instrumentation test; ở lượt hiện tại chỉ biên dịch các ca này, chưa chạy native.
- Client kiểm đúng kiểu chuỗi và session ID của đáp ứng backend, từ chối JSON number/object/null thay vì tự chuyển thành câu trả lời chữ. Câu hỏi client giới hạn 4000 ký tự như server.
- Timeout PCM có thêm số frame đã phát/dự kiến, trạng thái AudioTrack và underrun để chẩn đoán. Chưa kết luận đã sửa nguyên nhân timeout native trước đây.

### Backend Python

- Thay `audioop` bằng chuyển đổi PCM và nội suy tuyến tính dùng thư viện chuẩn. Đã chạy trên Python 3.14, không cần package bổ sung.
- Hỗ trợ WAV mono/stereo 8/16 bit, raw PCM L16 little-endian theo contract hiện có; xuất PCM16 mono 16 kHz. Kiểm biên độ có dấu, trộn kênh, thời lượng và giới hạn 30 giây.
- Từ chối WAV bị cắt thiếu dữ liệu, định dạng/sample rate/channel không hỗ trợ. Kiểm WAV transcription cũng phát hiện dữ liệu thiếu.
- Kiểm cấu trúc JSON trả về từ AI trước khi đọc text/audio; chỉ lấy phần text trả lời, bỏ phần `thought`.
- Lỗi chuẩn hóa: `AI_BAD_RESPONSE` (502), `AI_EMPTY_RESPONSE` (502), `AI_UNAVAILABLE` (502), `AI_TIMEOUT` (504). Giữ phân biệt app token, upstream auth và quota; không tự retry.
- JSON request không phải object hoặc trường sai kiểu trả 400 trước khi gọi upstream. Đóng HTTPError sau xử lý để giải phóng tài nguyên.

## Kiểm chứng không cần thiết bị

| Kiểm tra | Kết quả |
|---|---|
| JVM unit test | 73 ca đạt: gồm 4 ca ngữ cảnh mới, mở rộng router và kiểm đáp ứng client |
| Python unittest | 21 ca đạt trên Python 3.14; upstream dùng mock |
| Compile Kotlin main/androidTest | Đạt |
| Android lintDebug | Đạt, còn cảnh báo hiện có |

Chạy từ `android/`:

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug --offline --no-daemon --console=plain
```

Chạy từ `backend/`:

```powershell
python -m unittest -v
```

## Các phần cần đo/kiểm sau

- Native STT/TTS/audio và các assertion Cancel/Replay mới cần chạy lại khi quay về kiểm Android. Tập PCM TTS Việt thử trước khi đổi ưu tiên từng bị STT nhận sai (“Hai cộng ba” thành “Hai cộng b”); không sửa router để đoán số từ bản chép lời sai. Thử stress native 20 phiên chưa được nghiệm thu; ca 20 phiên với STT giả lập ở báo cáo trước là ca khác.
- Luồng WAKE Demo dùng `DemoVoiceSession` + `PhonePcmRecorder` và UI mic đã được code trong phần việc trước khi đổi ưu tiên; source hiện tại đã biên dịch. Việc đánh giá tiếp luồng này được hoãn theo yêu cầu mới.
- Gemini thật cần backend được cấu hình khóa hợp lệ; 21 test mock không chứng minh model/dịch vụ cloud thực tế hoạt động.
- Các APK và hash trong báo cáo build trước **chưa đại diện cho source core/backend cập nhật ở đây**. Lượt này chưa đóng gói/cài APK mới sau thay đổi cuối; cần build lại trước lần nghiệm thu Android tiếp theo.
