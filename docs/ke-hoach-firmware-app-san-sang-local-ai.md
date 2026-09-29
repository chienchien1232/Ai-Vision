# Kế hoạch firmware + Android: sẵn sàng tích hợp Local AI

Ngày: 29/09/2026.

Trạng thái: đã triển khai mã nguồn trong phạm vi firmware/app, build và regression đạt; chưa nghiệm thu phần cứng. Xem [báo cáo bàn giao 29/09/2026](ban-giao-firmware-app-2026-09-29.md) để phân biệt kết quả đã kiểm thử và checklist còn lại. Không triển khai Local AI trên chip; contract và giới hạn hiện thực xem [local-ai-integration-contract.md](local-ai-integration-contract.md).

## 1. Kết quả bàn giao và giới hạn

- WAKE AI trên Android bắt đầu phiên thu mic kính. IDLE không đọc/feed mic để tìm wake word.
- CAPTURE trên Android vẫn dùng TAKE_PHOTO/GET_MEDIA; ảnh lưu phía Android.
- Khi chưa có provider Local AI: PCM kính → STT offline Android → local handler hoặc backend → TTS offline Android → loa kính.
- Chuẩn bị contract/provider slot, executor và kết quả phiên để người phụ trách Local AI tích hợp sau mà không phải viết lại app/transport.
- Nhánh Local AI trên S3 chỉ được kiểm thử bằng test double; không coi đây là nhận dạng giọng nói trên chip đã hoàn thiện.
- Không bổ sung nút vật lý, không train/download/tích hợp model chip mới, không thay thuật toán/model WakeNet hiện có, không thêm TinyStories/MultiNet hoặc sửa partition model.
- Không sửa backend trong phạm vi này. Chỉ xử lý lỗi backend ở app và dùng mock khi cloud thật chưa sẵn sàng.
- Không đổi BLE UUID, discovery, Wi-Fi provisioning, TCP port 5000, HELLO|2 hoặc framing V2. Không đổi tên wire command chỉ để khớp tên nút UI.

## 2. Ranh giới module

| Thành phần | Trách nhiệm | Không được làm |
|---|---|---|
| UI Android | Render state, nhận thao tác người dùng | I/O socket, decode ảnh nặng, tự điều phối lại một tác vụ |
| Voice coordinator Android | Phiên, trạng thái, Cancel, STT/local/cloud/TTS, kết quả tác vụ | Sở hữu GPIO/I2S hoặc coi mọi lỗi là lỗi TCP |
| V2GlassTransport | Framing, request/event, session metadata, deadlines, tải media | Phân loại câu hỏi, gọi Gemini, quyết định lưu Gallery |
| MicPipeline firmware | Một RX owner, start/stop, PCM có giới hạn, buffer lifetime | Thực thi intent hoặc tự gọi AI/cloud |
| Voice coordinator firmware | State, deadline, handoff PCM, dispatch kết quả provider | Suy đoán intent khi provider chưa cài |
| LocalIntentProvider contract | Nhận PCM chuẩn hóa, trả result có kiểu, lifecycle/cancel | Đọc I2S, điều khiển camera/loa/GPIO, giữ con trỏ PCM quá lifetime |
| Device command executor firmware | Camera, stop, gain, status, replay nếu đủ khả năng | Chứa nhận dạng ngôn ngữ hoặc quản lý model |
| Speaker playback firmware | Buffer, gain, I2S TX, kết thúc thật, Cancel | Mở mic trong lúc loa còn phát |

Giữ các implementation đang tốt. Chỉ tách khối đang thay đổi hoặc cần test; không dựng framework nhiều lớp, không di chuyển toàn bộ dự án để làm đẹp cây thư mục.

Tên module/interface dưới đây là đề xuất, được chốt trong phase 0. DeviceViewModel hiện chứa DeviceController; chưa rename hàng loạt.

## 3. Contract cho người tích hợp Local AI

### 3.1 Đầu vào

- PCM signed 16-bit little-endian, mono, 16000 Hz; không đổi format transport.
- sessionId, languageTag, số sample, trạng thái kết thúc câu và token hủy/epoch.
- Có thể nhận các block begin/feed/end của cùng phiên; provider có thể tự tích lũy để classify cả câu hoặc xử lý tăng dần.
- Buffer thuộc firmware. Block chỉ có lifetime được quy định; muốn giữ lâu hơn phải copy theo ngân sách riêng. Provider không free buffer của recorder.
- Provider không được chạy inference đồng bộ trong task RX hoặc vòng parser TCP. Dùng worker có deadline, cancellation và quy tắc thu hồi tài nguyên.

### 3.2 Đầu ra có kiểu

- Recognized(intent, confidence): chỉ các intent cho phép, có kiểm tra ngưỡng ở boundary.
- NotLocal: chuyển câu về Android.
- Unknown: không thực thi thao tác đoán mò; chuyển Android để STT/clarification.
- Unavailable(NOT_CONFIGURED): mặc định bản giao này, chuyển Android; không giả vờ model đã phân loại.
- Failed(code): xử lý theo chính sách fallback rõ ràng; không thực thi intent từ kết quả lỗi hoặc hết hạn.

Intent dự kiến: TAKE_PHOTO, STOP, VOLUME_UP, VOLUME_DOWN, GET_STATUS, REPEAT. Question không cần model chip trả transcript; PCM gốc vẫn là đường fallback.

Confidence không được mặc định tương đương giữa các model. Provider thực phải mô tả score và hiệu chỉnh ngưỡng; fixture chỉ kiểm tra enforcement, không chứng minh độ chính xác.

### 3.3 Provider mặc định và test

- Provider mặc định chỉ trả NOT_CONFIGURED; không có model, features hoặc inference mới.
- Test double chỉ thuộc target test/simulator, không được kích hoạt bằng lời nói giả trong production.
- Test double phát lần lượt các intent, Unknown, lỗi, timeout và kết quả muộn để kiểm tra executor/session/app.
- Provider thực sau này triển khai contract, đăng ký tại một composition point và khai báo dependency/build nếu cần. Chỉ chép file model chưa đủ để chạy AI.
- Bàn giao fixture PCM, schema kết quả, hướng dẫn memory budget/lifetime/cancel và ví dụ kết nối adapter. Không cam kết mọi model bất kỳ sẽ vừa Flash/PSRAM.

## 4. Flow sản phẩm trong phạm vi hiện tại

```text
IDLE → Android WAKE AI → START_LISTENING
→ firmware thu PCM → speech end / giới hạn thu
→ provider slot (NOT_CONFIGURED ở bản hiện tại)
→ AUDIO_CHUNK/AUDIO_END → Android STT offline
→ local handler Android hoặc câu hỏi backend
→ Android TTS/preset → PLAY_AUDIO → AUDIO_PLAYED → IDLE
```

Khi provider thực được bổ sung:

```text
PCM → provider
  ├─ Recognized → executor → kết quả + confirmation → IDLE
  └─ NotLocal/Unknown/Unavailable → fallback Android hiện hữu
```

Không có AEC/barge-in. STOP bằng giọng nói chỉ có thể nhận khi phiên đang nghe; đang phát loa thì dùng Cancel trên app. Không hứa nói STOP để ngắt loa khi mic đang dừng.

Firmware giữ trạng thái xử lý/chờ Android với deadline hữu hạn. Android sở hữu toàn bộ phiên STT/cloud/TTS và chặn Wake mới trong thời gian đó. Sau lỗi/hủy không phát audio, Android gửi cleanup bằng lệnh hiện có khi socket còn hợp lệ; mất framing thì đóng socket. Không thêm command trạng thái mới nếu chưa chứng minh bắt buộc.

## 5. Thứ tự triển khai

### Phase 0 — Baseline và hợp đồng tích hợp

- Ghi nhận trạng thái dirty worktree, không reset/ghi đè thay đổi có sẵn.
- Xác nhận build firmware Arduino và Android, test hiện tại, mẫu frame hai phía.
- Chốt state table, intent/result contract, ownership, deadline và chính sách lỗi.
- Tạo bộ fixture request/event/media hiện hữu; chốt metadata local-command/photo trước khi code consumer.
- Đầu ra: baseline test, contract, danh sách file sửa và ngân sách tài nguyên. Chưa tích hợp model.

Gate: baseline build/test đạt hoặc ghi rõ lỗi môi trường; không âm thầm coi lỗi môi trường là chức năng đạt.

### Phase 1 — State machine, WAKE AI và Cancel

File chính: DeviceViewModel.kt, DeviceScreen.kt, GlassSession.kt, coordinator firmware.

- Thay trạng thái voice dựa trên tên chuỗi bằng kiểu trạng thái có transition rõ.
- Tách device connection, voice session và kết quả tác vụ; video có guard tài nguyên riêng.
- Nút WAKE AI gọi START_LISTENING hiện hữu; diagnostic mic vẫn là công cụ riêng.
- Không tự bật Hi ESP khi connect/reconnect. Không sửa model/thuật toán WakeNet.
- Theo dõi sessionId firmware và epoch kết nối; bỏ event/result cũ.
- Phân biệt user Cancel, timeout request và lỗi transport. Timeout phải thoát busy/recover đúng.
- Chặn Wake/Capture xung đột tài nguyên và không tự replay tác vụ sau reconnect.

Test: double Wake, Cancel từng trạng thái, timeout bắt đầu thu/PING/Capture, reset/reconnect, event cũ. Build app và firmware; regression kết nối/JPEG giữ nguyên.

### Phase 2 — Lifecycle recording, không always-listening

File chính: MicPipeline.h và phần điều phối I2S trong AiVisionGlasses.ino.

- Task RX ngủ/chờ khi IDLE; chỉ đọc/feed PCM khi LISTENING.
- Không cần xóa implementation WakeNet có sẵn, nhưng đường default không kích hoạt wake detection; không liên tục feed AFE ở IDLE.
- Giữ một RX owner, buffer thu có giới hạn và endpoint hiện hữu phù hợp. Chưa thay thuật toán nhận diện/VAD bằng model mới.
- Dừng RX/read loop an toàn khi kết thúc, Cancel hoặc disconnect; xóa dữ liệu cũ trước phiên tiếp theo.
- Không đóng I2S duplex chung làm hỏng TX loa. Stop/request/free phải phối hợp qua owner, không free khi worker còn dùng PCM.
- Chẩn đoán mic boot chỉ bật trong cấu hình diagnostic nếu yêu cầu sản phẩm là sau WAKE mới thu.

Test: không có PCM read/feed ở IDLE, không nghe trong playback, thu tối đa/không có dữ liệu/im lặng, Cancel liên tiếp, beep sau dừng mic. Đo số read, RAM và thời gian; không log nội dung mic.

### Phase 3 — Integration seam và executor, KHÔNG làm Local AI

File đề xuất: LocalIntentProvider.h, LocalIntentResult.h, DeviceCommandExecutor.h; chỉ tạo thêm implementation khi có trách nhiệm thực.

- Tạo contract/provider mặc định NOT_CONFIGURED và composition point.
- Chuẩn bị executor tái sử dụng camera/status/playback hiện có; không gọi lại handler bằng JSON giả.
- TAKE_PHOTO: camera chụp một lần, giữ media descriptor cho app tải/lưu.
- STOP: cleanup phiên theo state và ownership.
- VOLUME_UP/DOWN: gain phần mềm có giới hạn, áp dụng vào PCM ở playback; không giả lập nhận dạng lời nói và không cần lệnh TCP volume mới trong phạm vi này.
- GET_STATUS: trả trạng thái thật; battery không có phần cứng đo phải là unsupported, không tạo % giả.
- REPEAT: ưu tiên cache Android hiện hữu cho bản chạy hiện tại. Nếu muốn executor S3 replay độc lập về sau, dùng cache PCM giới hạn/lazy có ngân sách và test riêng; không dùng ring 64 KB như thể nó giữ cả câu. Chưa đủ ngân sách thì công bố unsupported thay vì nói đã phát lại.
- Hoàn thiện handling LOCAL_COMMAND_EXECUTED: session, status, media, hoàn tất busy; không thực thi tác vụ lần hai.
- Confirmation chỉ phát sau kết quả thực tế. Trạng thái ảnh captured chưa đồng nghĩa saved trên Android.

Test: test double cho mọi intent; lỗi executor, duplicate/stale result, mất kết nối giữa chụp/tải, confidence không đạt. Provider mặc định luôn fallback, không nhánh nhận dạng giả trong APK/firmware sản phẩm.

Gate: hạ tầng nhận result và thực thi được test; đánh dấu riêng recognizer on-chip = chưa tích hợp.

### Phase 4 — STT/local Android/LLM fallback

File chính: coordinator Android, LocalSpeechToTextEngine.kt, IntentRouter.kt, CloudPolicy.kt, BackendAiClient.kt.

- Giữ STT offline và local handler đang đúng; xử lý một phiên tại một thời điểm.
- Các command chưa hỗ trợ không được vô tình gọi Gemini như câu hỏi. Trả clarification/unsupported rõ ràng; không thêm command wire tùy tiện.
- Câu hỏi hợp lệ ngoài local handler mới gọi backend; câu hỏi cảnh dùng ảnh mới.
- Giữ cloud toggle, session echo, bỏ kết quả cũ và không cloud-STT/TTS dự phòng.
- Tách lỗi STT, timeout, cloud 401, 429, mất Internet và lỗi transport.
- Cleanup state firmware/app khi cloud/TTS lỗi trước playback; không giữ WAIT_ANDROID vô hạn.

Test: số lần API = 0 cho local; = 1 cho câu hỏi hợp lệ; Cancel/retry không tạo call ngoài chính sách; 401/429/mock offline không kẹt state. Không sửa backend và không cần khóa Gemini thật để chạy regression.

### Phase 5 — Speaker, confirmation, replay và audio error

File chính: speaker firmware, SpeechRenderer/ReplyAudio/coordinator Android.

- Giữ PCM16 mono 16 kHz, chunk/ACK, backpressure và giới hạn 30 giây.
- Giữ TTS Việt/preset offline; tiếng Anh theo hỗ trợ hiện tại.
- Gain chống clipping; không phụ thuộc media route điện thoại ở chế độ kính thật.
- Không chồng playback; busy tới khi AUDIO_PLAYED thật hoặc error/timeout được xử lý.
- Cancel xử lý ring/DMA an toàn; chỉ bắt đầu phiên thu mới sau khi TX ổn định.
- Replay cache không chụp lại/gọi Gemini; xác định rõ cache ở Android hay S3.
- Tách kết quả tác vụ và audio warning; giữ test beep/test giọng Việt riêng.

Test: waveform/gain, sequential playback, clipping, câu dài, model thiếu/hỏng, Cancel giữa chunk, mất TCP, đầu/đuôi audio. Beep/TTS phải nghe thử trên kính mới đánh dấu hardware đạt.

### Phase 6 — Capture/media và lưu Android

File chính: executor camera, V2GlassTransport.kt, GalleryPhotoSaver.kt, DeviceScreen.kt.

- Giữ TAKE_PHOTO/GET_MEDIA và nơi lưu phía Android.
- Chuẩn hóa descriptor/download dùng chung cho capture app và kết quả chụp local provider sau này, nhưng không gộp nhầm JPEG/video.
- Theo dõi URI/kết quả đã lưu, tránh lưu ảnh trùng không chủ ý.
- Lỗi disk/permission không được coi là lỗi TCP; ảnh còn trong RAM vẫn có thể lưu lại.
- Decode nền, giới hạn pixel thực và xử lý decode fail.
- Với Android 8/9: có đường lưu phù hợp hoặc chốt rõ min version; không tự nâng minSdk để giấu lỗi.
- Giữ video đang có, thêm guard hồi quy; không mở rộng chức năng SD/video trong scope này.

Test: Capture app, photo từ test provider, JPEG thiếu byte/sai size, Gallery lỗi, app recreation, TTS lỗi sau lưu. Ảnh luôn lưu Android, không có nút vật lý mới.

### Phase 7 — Transport và reliability/handoff

- Sửa validation khác nhau giữa audio chunk và request chung, không đổi framing.
- Deadline cho write, partial payload, chờ Android và cleanup. Khi hỏng framing đóng phiên, không đoán byte ranh giới.
- Không cho client mới tự chiếm phiên đang hoạt động. Đây chưa thay thế authentication; ghi rõ giới hạn bảo mật mạng thử nghiệm.
- Fault injection: reset, EOF, Wi-Fi yếu, frame sai, app background, reconnect giữa media/audio.
- Chạy soak test có số lượt/thời gian và báo cáo đo RAM, largest free block, stack, latency; mục tiêu số lượt được đặt trước khi chạy, không coi kế hoạch là kết quả.
- Cập nhật docs hiện hành/flow cũ, contract provider và checklist nghiệm thu.
- Build APK/firmware với hash, cấu hình và hướng dẫn; phân biệt compiled/simulated/verified on device.

Gate: mọi lỗi HIGH trong phạm vi có test hồi quy; chưa nghe/thu trên kính thì bàn giao là integration-ready, không phải sản phẩm phần cứng đã nghiệm thu. Không vượt chặn cài APK và không tự erase model partition.

## 6. Quy trình clean code sau MỖI chức năng

1. Chốt acceptance và invariant trước khi sửa; chọn một chức năng, không sửa dàn trải cả phase.
2. Thêm test tái hiện lỗi hoặc fixture cho behavior mới.
3. Implement thay đổi tối thiểu, giữ interface/wire đang hoạt động.
4. Tự review diff: ownership/lifetime, state transition, cancellation, timeout, thread, error, media format, side effect, logging/secret.
5. Loại magic string trạng thái, code trùng mới, nhánh chết và catch quá rộng do thay đổi đó tạo ra; không cleanup unrelated.
6. Build phần liên quan, chạy test chức năng, toàn bộ regression phù hợp và lint. Nếu lỗi: sửa rồi chạy lại; không chuyển chức năng khi lỗi còn tồn tại.
7. Test failure paths và hardware nếu có thiết bị; ghi rõ kết quả nào chỉ giả lập. Native object không bị free để ép Cancel nhanh.
8. Review lần hai sau khi sửa lỗi test; ghi nhận kết quả/phần chưa kiểm chứng vào checklist.

Không có cam kết mọi chức năng bắt buộc phải sửa nhiều lần nếu test đã đạt; mục tiêu là review/test lặp tới khi hết lỗi, không tạo số lần debug hình thức. Không commit/push tự động nếu chưa được yêu cầu.

## 7. Chính sách protocol và các quyết định trước implementation

- WAKE AI = nhãn UI, wire START_LISTENING. CAPTURE UI giữ TAKE_PHOTO/GET_MEDIA.
- startListening nên trả sessionId cho coordinator thay vì bỏ qua; đây là thay đổi API nội bộ Android, không đổi wire.
- LocalCommand giữ sessionId và descriptor cần thiết; version hiện hữu không giả sử command đã được thực thi nếu result báo lỗi.
- Nếu event local-photo thiếu size/mime/dimensions cần tải an toàn, lập một metadata extension tương thích ngược, fixture hai phía và giải thích trước khi áp dụng. Không đưa extension vào âm thầm.
- Không thêm protocol volume/repeat/state mới chỉ vì thuận tiện; chỉ thêm nếu có use case trong scope không thực hiện được bằng hợp đồng đã có.
- Không sửa model partition hoặc model data để hoàn thiện recording/app. Build script phải phân biệt firmware-only và artifact có model, không flash merged full image ngoài ý muốn.
- File/model/package Local AI thực do người tích hợp sau phụ trách, bao gồm phù hợp toolchain, tài nguyên, ngôn ngữ và độ chính xác.

## 8. Định nghĩa hoàn thành

Trong phạm vi bản này: app điều khiển và fallback thật chạy ổn; firmware recording/playback/capture có lifecycle đúng; executor/provider boundary có test; code và tài liệu đủ cho người phụ trách Local AI nối adapter vào.

Ngoài phạm vi: độ chính xác command recognizer trên chip, wake word tiếng Việt, model/training/quantization, phần cứng đo pin, physical buttons, AEC/barge-in, mở rộng backend và SD/video.

Chỉ sau khi provider thực được tích hợp và đo trên kính mới có thể nghiệm thu nhánh `PCM → Local AI trên S3 → command executor` như luồng thật.
