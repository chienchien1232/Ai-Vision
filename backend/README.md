# Ai-Vision AI backend

**Kiểm tra mới nhất 29/09/2026 lúc 16:43:** app → backend health 200 và token hợp lệ, nhưng Probe Gemini trả backend 502 / upstream 503; log trước đó có 502/504. PASS ở phần dưới là lịch sử một lượt trước đó trong ngày, không bảo đảm dịch vụ hiện sẵn sàng. Chưa thêm retry/đổi model. Xem [bàn giao cho lập trình viên AI](../docs/ban-giao-cho-lap-trinh-vien-ai-2026-09-29.md) cho đầu việc cloud/local và tiêu chí test.

## Khóa riêng cho backend local — 29/09/2026

`local-test.ps1 -Action SetGeminiKey` nhận key qua prompt ẩn, lưu Windows DPAPI tại `.local-dev/gemini-key.dpapi` (đã bỏ qua Git). Không truyền key qua tham số, không chép vào APK/source. Sau đó dùng `-Action Restart` và `-Action Status`; `-Action Probe` gọi đúng một câu hỏi thật, có thể tiêu thụ quota, không retry tự động. App URL/token giữ nguyên.

Kết quả áp dụng key mới ngày 29/09/2026: backend đã restart, health/token đạt; một Probe `/v1/answer` qua Gemini thật PASS, trả 154 ký tự. Điện thoại truy cập `/health` qua ADB reverse nhận 200 OK, URL đã lưu vẫn `http://127.0.0.1:8080`. 12 backend mock test đạt. Kết quả này thay trạng thái 401 của key trước ở mục lịch sử 28/09 bên dưới; chưa chứng minh luồng ảnh/mic → LLM → loa đã nghiệm thu đầy đủ. Không lưu giá trị key hay nội dung phản hồi vào tài liệu.

Khi Start/Restart, file DPAPI được ưu tiên; nếu chưa có file, dùng GEMINI_API_KEY trong Process → User → Machine. Chỉ truyền key vào môi trường process Python, không sửa biến hệ thống toàn máy. Định dạng key được kiểm tra ký tự/độ dài, không bắt buộc prefix AIza vì Google cũng hỗ trợ authorization keys. Key bị từ chối không tự chuyển model/key/endpoint. Khóa miễn phí vẫn phải bảo mật và thay sau khi lộ.

## Test trên điện thoại thật — trạng thái 28/09/2026

Backend đã bật trên PC tại `127.0.0.1:8080`, không nghe IP LAN/công khai. APK debug trên điện thoại đã nhập URL `http://127.0.0.1:8080` và app token qua ADB, lưu token bằng Android Keystore, xóa tệp import. Token phía PC được bảo vệ Windows DPAPI tại `.local-dev/app-token.dpapi` (bỏ qua Git). Gemini key chỉ nằm ở môi trường backend, không đưa vào APK.

Health và xác thực app token đã đạt. **Gemini thật chưa đạt:** khóa hiện có bị upstream từ chối; kiểm tra danh sách model trả `401 UNAUTHENTICATED`. Backend trả `502 AI_AUTH_FAILED` nếu Gemini trả 401/403; đừng nhầm với 401 do app token sai. Cần kiểm tra/cập nhật `GEMINI_API_KEY` trên máy và khởi động lại backend; không gửi khóa vào chat/Git. Không tự đổi key/model hay retry để né lỗi.

Từ thư mục dự án:

```powershell
.\backend\local-test.ps1 -Action Start
.\backend\local-test.ps1 -Action Status
.\backend\local-test.ps1 -Action ConfigurePhone -Device 'SERIAL_ADB_THAT'
# Sau khi cập nhật khóa, mở terminal mới hoặc cập nhật biến môi trường hiện tại:
.\backend\local-test.ps1 -Action Restart
# Chỉ chạy sau khi sửa khóa; gọi một câu hỏi thật và tiêu thụ quota:
.\backend\local-test.ps1 -Action Probe
```

`Start` giữ token cũ và không dừng tiến trình khác đang dùng cổng. `ConfigurePhone` cần app debug đã cài, chỉ truyền token qua stdin vào vùng riêng `run-as`, không đưa vào tham số/log. Điện thoại cần duy trì kết nối ADB với PC; ngắt ADB/reboot có thể làm mất reverse, chạy lại ConfigurePhone. Backend phải tiếp tục chạy trên PC. Không phải cấu hình triển khai độc lập khi đi ra ngoài.

Chỉ debug cho phép **đúng** URL HTTP loopback trên; release vẫn bắt buộc HTTPS. Android network policy mặc định cấm cleartext và chỉ mở domain `127.0.0.1` trong debug. Khi triển khai thật, dùng backend HTTPS tin cậy và app token riêng; không bật HTTP LAN/global cleartext.

## Luồng local-first từ 28/09/2026

App mới chỉ gọi `/v1/answer` cho câu hỏi ngoài các handler local; không gọi Gemini để phân loại độ khó. STT tiếng Việt/Anh và TTS nằm trên điện thoại; âm thanh đến từ mic kính. `/v1/transcribe` và `/v1/speak` giữ để tương thích nhưng mặc định trả HTTP 410 `LOCAL_AUDIO_ONLY`. Chỉ bật bằng `ENABLE_LEGACY_CLOUD_AUDIO=1` khi chủ động thử luồng cũ; phần resample audio cũ cần Python 3.10–3.12 vì audioop đã bị bỏ từ 3.13. Backend answer không còn import audioop khi khởi động.

Answer yêu cầu tối đa 3 câu/90 từ, `maxOutputTokens=512`, thinking low. Đây không phải hạn mức chi phí tuyệt đối. HTTP 429 từ Gemini được trả thành 429 `AI_QUOTA`; không tự retry. Probe cloud thật ngày28/09 thất bại xác thực; trạng thái mới nhất ở phần trên. Triển khai thật vẫn cần HTTPS reverse proxy, quota/rate limit ở tầng triển khai và API key đúng. Mô tả STT/TTS cloud bên dưới là luồng legacy opt-in, không phải đường mặc định của app mới.

Backend Python tối giản giữ GEMINI_API_KEY ngoài APK và gọi Gemini 3.8 Flash. Python 3.10+; không cần package ngoài chuẩn. Chạy bằng `python server.py` sau khi đặt biến môi trường GEMINI_API_KEY và APP_TOKEN (một token dài, bí mật). Có thể đặt GEMINI_MODEL để đổi model. Mặc định chỉ nghe 127.0.0.1:8080; khi dùng từ điện thoại cần đặt sau HTTPS reverse proxy có xác thực và chỉ mở endpoint cần thiết. Không đưa khóa, token hoặc file .env thật vào Git.
App gửi POST /v1/answer với Authorization: Bearer <APP_TOKEN> và JSON gồm sessionId, question, imageBase64 (JPEG, tùy chọn). Backend trả sessionId và answer. GET /health trả {"ok":true}. Ảnh tối đa 5 MiB; câu hỏi tối đa 4000 ký tự. Backend không lưu ảnh hoặc lịch sử. Bản đầu dùng yêu cầu/đáp ứng thường; streaming và lịch sử hội thoại sẽ thêm sau khi đo độ trễ thực.

Với firmware V2 có mic, kính thu khoảng 8 giây PCM signed 16-bit little-endian mono 16 kHz trước khi gửi về app; app đóng thành WAV rồi gọi POST /v1/transcribe với cùng Bearer token: `{ "sessionId": "uuid", "languageTag": "vi-VN", "audioBase64": "..." }`. Backend kiểm tra WAV tối đa 30 giây/1 MiB, gọi Gemini để chép lời và trả `{ "sessionId": "uuid", "transcript": "..." }`. Có thể đặt `GEMINI_TRANSCRIBE_MODEL` riêng; mặc định dùng `GEMINI_MODEL`. App điền transcript đúng sessionId vào ô Command or question; chỉ sau khi người dùng bấm Send mới định tuyến lệnh hoặc gọi `/v1/answer` (lấy ảnh mới nếu câu hỏi về cảnh). Nếu STT lỗi, app hiện mã lỗi backend khi có và cho thử lại từ bản ghi đang giữ trong RAM mà không thu âm lại; đóng app sẽ mất bản ghi này. Phần cứng đã được đo thu đủ 256000 byte/8 giây và chuyển đủ 16 chunk, nhưng cần nghiệm thu thêm phiên STT thành công trên backend thật.

Model Gemini 3.8 Flash được chọn làm mặc định vì [tài liệu chính thức](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash/) hiện liệt kê input ảnh/audio, cửa sổ 1.048.576 token và mức suy luận low. Điều này chưa chứng minh độ trễ hoặc độ chính xác trên ảnh kính; phải đo trên ảnh thật. Backend dùng endpoint generateContent hiện có và có thể đổi model qua biến môi trường.

Khi điện thoại không có voice TTS on-device cho ngôn ngữ cần (vd máy thiếu voice vi-VN), app gửi POST /v1/speak với `{ "sessionId": "uuid", "text": "...", "languageTag": "vi-VN" }` và cùng Bearer token. Backend gọi Gemini TTS rồi chuẩn hóa về PCM_S16LE mono 16 kHz, trả `{ "sessionId": "uuid", "audioBase64": "...", "sampleRate": 16000 }`; audio tối đa 30 giây. Có thể đặt `GEMINI_TTS_MODEL` (mặc định `gemini-3.8-flash-tts`) và `GEMINI_TTS_VOICE` (mặc định `Kore`) để đổi model/giọng.

Ghi chú quota của luồng cũ (27/09/2026) không phải mức bảo đảm cho tài khoản/model hiện tại. Bản mới trả 429 `AI_QUOTA` khi upstream hết quota, không tự retry. Kiểm tra quota/billing trong tài khoản trước khi triển khai; không đổi khóa để né hạn mức.
