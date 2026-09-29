# Đề xuất giao thức V2 cho voice, media và sự kiện

> Cập nhật 29/09/2026: sản phẩm chuyển sang WAKE AI trên Android, dùng START_LISTENING hiện hữu. Firmware không quảng cáo WAKE_EVENT/không tự bật Hi ESP; mic RX chỉ thu trong phiên. Chưa tích hợp Local AI command trên chip, provider mặc định chuyển PCM về Android. Event LOCAL_COMMAND_EXECUTED được chuẩn bị session/descriptor ảnh và REPLAY_ON_ANDROID; xem [contract tích hợp](local-ai-integration-contract.md). Các đoạn mô tả always-wake Hi ESP bên dưới là lịch sử, không phải flow FINAL hiện hành. Không đổi BLE/TCP port/framing.

Trạng thái: V2 là luồng TCP duy nhất của app và firmware Arduino hiện tại. BLE provisioning, PING/STATUS và ảnh JPEG V2 đã chạy trên board + điện thoại thật; mic/loa và video vẫn cần nghiệm thu phần cứng. Tài liệu này giữ tên cũ `draft` để không làm đứt các liên kết hiện có.

OCR local bổ sung ngày 28/09/2026 không có lệnh wire mới: Android dùng `TAKE_PHOTO`/`GET_MEDIA`, nhận dạng bằng model kèm APK, rồi phát PCM qua `PLAY_AUDIO`/`AUDIO_OUT`. Đọc tiếp/phát lại không chụp lại; xoay OCR dùng JPEG đã nhận. Không đổi firmware hoặc backend cho OCR. Hi ESP được xử lý trên S3; STT tiếng Việt và TTS/OCR chạy trên Android.

## 1. Nâng cấp phiên

TCP vẫn ở port 5000. Android luôn gửi dòng ASCII HELLO|2 + LF. Firmware trả HELLO|2|<capabilities> + LF; capabilities là danh sách tên phân cách bằng dấu phẩy, ví dụ PHOTO,AUDIO_IN,VIDEO,AUDIO_OUT. Firmware hiện tại từ chối lệnh TCP V1 bằng `ERROR|V2_REQUIRED`. App chỉ gửi frame V2 sau khi nhận đúng HELLO|2. Không tự suy ra V2 từ tên board hay phiên bản firmware.

## 2. Frame V2

Mỗi frame gồm: header JSON UTF-8 một dòng kết thúc LF, rồi đúng payloadBytes byte nhị phân. Header tối đa 1024 byte; payloadBytes từ 0 đến 65536 mỗi frame. Không có LF sau payload. Bên nhận đọc đủ header, kiểm tra v/type/name/id/payloadBytes, rồi đọc đúng payloadBytes. Nếu hỏng frame hoặc EOF giữa payload: đóng TCP; không cố đoán ranh giới mới.

Header chung: {"v":2,"type":"request|response|event|error","id":"UUID","name":"...","payloadBytes":0,"meta":{}}. id của response/error bằng id request. Event tự phát dùng id riêng mới. meta chỉ chứa metadata nhỏ, không chứa ảnh/audio Base64. App cho một request điều khiển đang chờ; event audio có thể xen giữa các response trên cùng socket. MEDIA_CHUNK và MEDIA_END là frame type=response với id bằng id của GET_MEDIA.

## 3. Lệnh và phản hồi

| Request | Response thành công | meta bắt buộc |
|---|---|---|
| PING | PONG | Không |
| GET_STATUS | STATUS | wifi; rssi, recording, wakeReady, wakeWord, pendingVideo tùy chọn |
| TAKE_PHOTO | PHOTO_CAPTURED | mediaId, mime=image/jpeg, size, width, height |
| GET_MEDIA | MEDIA_CHUNK nhiều frame rồi MEDIA_END | mediaId, seq, offset, totalSize |
| START_VIDEO | VIDEO_STARTED | recordingId |
| STOP_VIDEO | VIDEO_STOPPED | recordingId, mediaId, mime, size |
| SET_VOICE_CONFIG | VOICE_CONFIGURED | request: language=vi-VN/en-US, wakeWord="Hi ESP" hoặc "" |
| START_LISTENING | LISTENING_STARTED | request: language, mode="diagnostic" tùy chọn; response: sessionId |
| STOP_LISTENING | LISTENING_STOPPED | Không; hủy buffer thu âm, không đóng TCP |
| ACK_MEDIA | MEDIA_ACKNOWLEDGED | request: mediaId của video đã lưu Gallery |
| CANCEL | CANCELLED hoặc ERROR | targetId |

TAKE_PHOTO tạo ảnh mới trên kính. GET_MEDIA tải file/chunk theo mediaId, không chụp lại. Mỗi MEDIA_CHUNK có payload nhị phân và seq tăng từ 0; MEDIA_END có payloadBytes=0. App kiểm tra tổng size và MIME trước khi lưu/giải mã. Quay video và ghi âm chỉ bật khi firmware đo được RAM/SD và công bố MIME thật; không mặc định MP4.

Android V2 hiện yêu cầu: PONG response không có payload; STATUS response meta.wifi là boolean và meta.rssi là số nguyên tùy chọn. PHOTO_CAPTURED response có mediaId, mime=image/jpeg, size (1..5 MiB), width/height (1..4096); không có payload. Sau đó GET_MEDIA request meta.mediaId; mỗi MEDIA_CHUNK response mang meta.mediaId, seq (bắt đầu 0), offset, totalSize và payload nhị phân tối đa 65536 byte; MEDIA_END response cùng mediaId và payloadBytes=0. App chỉ nhận ảnh khi đủ size và JPEG bắt đầu FF D8, kết thúc FF D9.

Video V2 chờ nghiệm thu board: firmware quảng cáo `VIDEO` chỉ khi camera và SD sẵn sàng. `START_VIDEO` → `VIDEO_STARTED` meta `{recordingId}`, hoặc lỗi thiết bị/BUSY/`MEDIA_PENDING` nếu video trước chưa được app xác nhận lưu. MJPEG AVI nominal 10 fps, tối đa 60 giây / 15 MiB / 800 frame; tự dừng báo `MEDIA_AVAILABLE`. `STOP_VIDEO` → `VIDEO_STOPPED` meta `{recordingId,mediaId,mime:"video/x-msvideo",size}`; nếu còn video đã hoàn thành thì trả lại descriptor cũ. `GET_MEDIA` tải chunk 4 KB. Chỉ sau khi Gallery lưu thành công, app gửi `ACK_MEDIA`: giải phóng descriptor để quay tiếp, không xóa file SD. SD sẽ đầy nếu không dọn file thủ công. Khi mất TCP, firmware cố hoàn tất header/index AVI và giữ descriptor trong RAM để tải sau reconnect; không bảo đảm phục hồi sau mất điện. `CANCEL` playback hoặc recording dùng targetId request gốc; CANCEL recording chủ động vẫn hủy bản ghi. Không tự retry lệnh có tác dụng phụ.

## 4. Sự kiện tự phát từ kính

| Event | meta tối thiểu | Quy tắc app |
|---|---|---|
| WAKE_WORD_DETECTED | sessionId, language | Hiển thị trạng thái, không gửi lại wake |
| LOCAL_COMMAND_EXECUTED | sessionId, command, result, mediaId nếu có | Chỉ cập nhật kết quả; không thực thi lệnh lần hai |
| AUDIO_CHUNK | sessionId, seq, sampleRate, channels, encoding | Payload là audio từ mic kính để STT |
| AUDIO_END | sessionId, seq | Kết thúc câu hỏi |
| DEVICE_ERROR | code, sessionId nếu có | Báo lỗi phù hợp |
| MEDIA_AVAILABLE | mediaId, mime, size | Cho phép tải khi có Wi-Fi |

Audio hiện tại: PCM signed 16-bit little-endian, mono, 16000 Hz. Không stream mic lên cloud. WakeNet/VAD nằm trên chip khi model sẵn sàng; STT và định tuyến lệnh tiếng Việt/Anh nằm trên điện thoại. Mất Internet vẫn dùng lệnh local nếu kết nối kính–điện thoại còn hoạt động. Chưa có nhận dạng lệnh tiếng Việt độc lập trên chip khi không có điện thoại.

`START_LISTENING` yêu cầu language vi-VN/en-US; response có sessionId. Một task `mic-owner` là nơi duy nhất đọc I2S RX sau chẩn đoán boot, không phụ thuộc vòng TCP/SD. PCM thu vào PSRAM tối đa 8 giây; mode diagnostic giữ đủ cửa sổ, mode thường kết thúc sau khoảng lặng ~700 ms khi đã có >=250 ms speech. Có model thì dùng WebRTC VAD từ AFE, không có model thì dùng ngưỡng năng lượng RMS 400 (cần hiệu chỉnh trên kính). Wake có pre-roll 250 ms. `WAKE_EVENT` chỉ được quảng cáo khi Hi ESP/AFE và task fetch tải thành công. `SET_VOICE_CONFIG` bật/tắt wake; chuỗi khác Hi ESP bị từ chối, không huấn luyện model qua ô nhập. Wake bị chặn khi phát loa/đang thu/không có phiên TCP, chưa có AEC hay barge-in. AUDIO_CHUNK có sessionId, seq từ 0, encoding=PCM_S16LE, sampleRate=16000, channels=1; payload chẵn tối đa 16 KB. AUDIO_END có seq bằng số chunk, không payload. App STT offline rồi tự định tuyến ngay, không cần bấm Send. LOCAL_COMMAND_EXECUTED chỉ cập nhật UI.

## 5. Hủy, lỗi và chống trùng

Mỗi request có UUID. Firmware chưa có cache deduplication; app không tự retry TAKE_PHOTO/START/STOP khi chưa biết kết quả. CANCEL có targetId request playback/recording gốc. App drain phản hồi request đang bay trong timeout hữu hạn trước khi gửi CANCEL để giữ ranh giới frame. Action vẫn busy tới khi phát xong. Kết quả STT/AI/TTS cũ bị bỏ qua khi sessionId không còn active. Error lệnh hợp lệ và DEVICE_ERROR không tự đóng TCP; frame hỏng/EOF/timeout transport thì đóng phiên.

Error frame có name=ERROR và meta.code. Mã khởi đầu: UNSUPPORTED_VERSION, INVALID_FRAME, UNKNOWN_COMMAND, BUSY, CAMERA_FAILED, SD_MISSING, SD_FULL, AUDIO_UNAVAILABLE, TIMEOUT, CANCELLED, INTERNAL_ERROR. Không gửi khóa API, SSID/password hoặc dữ liệu mic trong log lỗi.

## 6. Hợp đồng cloud và đầu ra giọng nói

STT trên Android dùng sherpa-onnx/Moonshine vi/en đã đóng gói, không mở mic điện thoại cho luồng kính và không gọi API. Transcript tự qua IntentRouter. Lệnh chụp/quay/trạng thái, giờ/ngày/chào/hướng dẫn và phép tính hai toán hạng chạy local. CloudPolicy chỉ cho AppIntent.Question gọi HTTPS `/v1/answer`; đây là câu ngoài nhóm handler, không phải một model đánh giá độ khó. Câu hỏi cảnh chụp JPEG mới trước khi gọi. Có công tắc tắt Gemini. STT lỗi giữ PCM trong RAM để Retry; disconnect xóa. TTS chọn voice không cần mạng trên điện thoại, resample rồi truyền loa kính. Thiếu voice offline thì báo cài voice, không fallback cloud. `/v1/transcribe` và `/v1/speak` cũ trả 410 mặc định; chỉ bật tương thích với ENABLE_LEGACY_CLOUD_AUDIO=1. API key chỉ ở backend; app token lưu mã hóa Keystore khi bấm Lưu cài đặt. Foreground service connectedDevice giữ runtime khi màn hình bị tạo lại; chưa bảo đảm khi OS giết process.

### 6b. Đầu ra loa kính (AUDIO_OUT, đã chốt hợp đồng, chờ nghiệm thu board)

Cập nhật TTS Việt: Android dùng VAIS1000/Piper qua sherpa-onnx đã đóng gói, hoặc PCM preset cho năm câu cố định; không cần voice Việt hệ thống. TTS Anh vẫn dùng voice hệ thống offline. Demo phát cùng PCM qua AudioTrack; kính thật giữ nguyên frame PLAY_AUDIO/chunk/end bên dưới. Model sinh22.050Hz được resample16kHz. Vượt30giây thì báo warning và giữ chữ, không phát bản cắt. Phát lại dùng phản hồi gần nhất trong RAM, không chụp lại/call AI. Xem [triển khai TTS Việt](vietnamese-offline-tts.md); mô tả TTS chọn voice ở §6 phía trên chỉ còn áp dụng cho tiếng Anh.

- Firmware thêm `AUDIO_OUT` vào capabilities `HELLO|2` chỉ khi loa I2S khởi tạo thành công; app chỉ gọi khi có capability này, nếu không báo lỗi rõ ràng thay vì phát loa điện thoại.
- Định dạng duy nhất: PCM signed 16-bit little-endian, mono, 16000 Hz (giống đường mic để firmware giữ một cấu hình I2S duplex chung BCLK/WS với mic). App tự resample trước khi gửi.
- `PLAY_AUDIO` request (không payload), meta `{encoding:"PCM_S16LE", sampleRate:16000, channels:1, totalBytes:2..960000 chẵn}` (tối đa 30 giây). Firmware trả response `AUDIO_READY` meta `{sessionId}` (UUID do firmware sinh) hoặc error `SPEAKER_UNAVAILABLE`, `AUDIO_OUT_BUSY`, `BAD_AUDIO_FORMAT`, `AUDIO_BUFFER_UNAVAILABLE` (không cấp phát được buffer đủ câu, chưa phát).
- `AUDIO_OUT_CHUNK` request CÓ payload nhị phân (2..8192 byte, số byte chẵn), meta `{sessionId, seq}` với seq bắt đầu 0 và tăng 1. Đây là loại request app→kính duy nhất mang payload; firmware đọc đúng `payloadBytes` rồi mới xử lý. Firmware trả response `AUDIO_OUT_ACK` meta `{sessionId, seq}` sau khi đã kiểm tra và copy vào buffer; ACK **không** có nghĩa là I2S đã phát xong. Bản sửa jitter 29/09 dùng full preload: câu ngắn tái sử dụng buffer 64 KB (fallback 16 KB); câu lớn cấp phát riêng đúng totalBytes trong PSRAM, tối đa 960000 byte, giải phóng sau kết thúc/Cancel/mất TCP. Chỉ phát sau khi nhận đủ PCM và AUDIO_OUT_END hợp lệ, không drain giữa các ACK. Chính sách buffer thay đổi, wire/framing không đổi. Có thể tăng thời gian chờ nạp trước phát; không tự chuyển sang streaming khi thiếu PSRAM.
- Android bật `TCP_NODELAY` và ghi gộp JSON header, dấu `\n`, payload của từng request trong một lần `write`; tách thành nhiều lần ghi nhỏ khi truyền PCM stop-and-wait có thể gây trễ giữa các chunk dù firmware đã ACK sớm.
- `AUDIO_OUT_END` request (không payload), meta `{sessionId, seq}` với seq bằng số chunk đã gửi; firmware chỉ chấp nhận khi đã nhận đủ `totalBytes`, rồi trả response `AUDIO_PLAYED` meta `{sessionId}` sau khi vòng đệm đã ghi hết xuống I2S và chờ thêm 120 ms cho đuôi DMA.
- `CANCEL` với meta `{targetId}` bằng id của request `PLAY_AUDIO` đang phát → response `CANCELLED`, bỏ vòng đệm và ngừng xếp thêm dữ liệu phát (đuôi DMA có thể còn rất ngắn); targetId lạ → error `UNKNOWN_TARGET`. Đóng TCP cũng bỏ vòng đệm phát.
- Mã lỗi thêm: `BAD_PLAYBACK_SESSION` (sai sessionId/seq/kích thước). Nếu ghi I2S kẹt quá 3 giây, firmware đóng TCP và log Serial; app nhận lỗi mất kết nối.
- Phiên chưa bắt đầu phát hết hạn nếu không có tiến triển trong 10 giây, kể cả nhận đủ PCM nhưng thiếu `AUDIO_OUT_END`; firmware thu hồi buffer và đóng TCP, không giữ PSRAM vô thời hạn.
- Toàn bộ lượt gửi PCM Android chạy trên Dispatchers.IO, không quay về UI giữa các chunk. Timeout mỗi chunk 8 giây; `AUDIO_OUT_END` dùng `max(8000, ceil(totalBytes*1000/32000)+5000)` mili giây, tối đa 35 giây, để chờ toàn bộ câu preload + đuôi DMA. App cũ dùng deadline END cố định 15 giây cần cập nhật trước khi thử câu dài với firmware preload. Sai frame hoặc EOF giữa payload thì đóng TCP như §2.

## 7. Điều kiện chuyển draft thành chuẩn

Người 1/2/3 chốt mẫu frame thật cho mỗi nhóm lệnh, codec/mime, giới hạn RAM, timeout và cách ghép chunk. Firmware và Android có parser test cùng bộ fixture, thử mất kết nối giữa payload, trùng id, hủy, Wi-Fi yếu và thẻ SD lỗi. Chỉ sau đó cập nhật [protocol.md](protocol.md) và bật tính năng tương ứng trong UI.
