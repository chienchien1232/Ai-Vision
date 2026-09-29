# TTS tiếng Việt trong app — 28/09/2026

## Luồng và giới hạn

Vietnamese: text → LocalSpeechRenderer → VAIS1000/Piper/sherpa-onnx1.13.8 → PCM16 mono16kHz → loa kính qua V2. Không dùng engine Xiaomi/Google hoặc API TTS. English giữ PhoneSpeaker với voice offline đã cài. Demo phát cùng PCM qua AudioTrack.

Model `vits-piper-vi_VN-vais1000-medium`, speaker0, speed1.0, CPU2threads; output22.050Hz được resample16kHz. Archive SHA256 `fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a` được kiểm trước extract. Asset manifest chứa size/hash từng file; copy/kiểm vào vùng noBackup riêng khi dùng lần đầu. Model/dữ liệu khoảng81MB chưa nén; archive khoảng67MB. APK tăng vì đóng gói offline và máy cần chỗ cho cả APK lẫn bản copy riêng.

Năm preset PCM16kHz được tạo lúc build: ảnh đã lưu, video đã lưu, chào, hướng dẫn, nói lại. Đường preset không khởi tạo native TTS hoặc system TextToSpeech. Câu tự do dùng native renderer; chia nhóm tối đa160ký tự không cắt từ để có điểm kiểm Cancel giữa các lượt JNI. Tổng output vượt30giây bị từ chối toàn bộ, không phát bản cắt giữa câu; chữ vẫn giữ. Synthesis có timeout30giây; callback/các đoạn kiểm Cancellation; Cancel vẫn phải đợi lượt native đang chạy kết thúc trước khi giải phóng.

Native handle có một owner/mutex, lazy-init và release sau60giây idle. Cancel/close chỉ đặt cờ; không free object còn chạy JNI. Callback có typed `invoke(float[]):Integer` và @Keep: tránh NoSuchMethodError/CheckJNI abort với indy lambda Kotlin và sherpa1.13.8. Có regression test chữ ký. English không tự fallback network voice.

## Kết quả tác vụ và Phát lại

Sau khi ảnh/video lưu hoặc AI trả chữ, kết quả tác vụ được giữ độc lập với `audioStatus`. Lỗi model/loa không biến thao tác đã xong thành thao tác thất bại. Phát lại giữ một ReplyAudio gần nhất trong RAM (text/lang/PCM nếu đã sinh); không gọi lại Gemini, không chụp lại, không lưu Gallery thêm. PCM/câu trả lời không ghi lịch sử xuống ổ đĩa; process death làm mất replay. TTS/quay video đồng thời vẫn có thể bị firmware AUDIO_OUT_BUSY; không thêm AEC/barge-in hay đổi firmware.

## Build và test

```powershell
cd D:\Project\Ai-Vision\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-configuration-cache
```

Task prepareVietnameseTts gọi `tools/prepare_vi_tts.py`: tải/check model và tạo preset bằng Pythonvenv ở build/ttsDownloads, sherpa-onnx1.13.8/numpy2.2.6. Không cài package vào Python hệ thống, không cần API key. Build đầu cần mạng để tải dependency/model; chạy app không cần mạng cho TTS Việt. WAV preview nằm ở app/build/generated/ttsPreview, không đóng gói vào APK; assets có PCM/model/notices/licenses.

Trên app: Demo→Connect để test điện thoại, hoặc tắt Demo→Connect kính. Bấm **Test loa bằng beep** để kiểm đường output, rồi **Test giọng Việt offline** (câu không nằm trong preset, kiểm model thật). Sau đó thử chào, hỏi giờ/phép tính, Capture và Phát lại. Giữ Wi-Fi/hotspot kính nhưng tắt Internet để nghiệm thu local; câu ngoài nhóm local vẫn cần Gemini key/backend hoạt động.

Instrumentation chỉ chạy trên emulator trong lượt tự động nếu điện thoại còn chặn APK test. Không tắt chặn hoặc giả nhận đã nghe được loa kính. Mốc bắt đầu nghe dưới1giây cho preset cần đo trên kính thật, không suy từ thời gian đọc asset. Nghe rõ dấu/số/ngày giờ vẫn cần người dùng đánh giá; test PCM không chứng minh chất lượng phát âm.

## Attribution và phát hành

APK kèm MODEL_CARD, NOTICE, giấy phép sherpa/Piper/eSpeak. VAIS1000 model card ghi dataset CC BY4.0; không dùng VIVOS x_low CC BY-NC-SA. Dữ liệu/phonemization eSpeak có GPL3; cần rà nghĩa vụ phân phối toàn bộ native/model/data trước phát hành thương mại. Build được APK test không phải chứng nhận đã đủ điều kiện pháp lý để bán.

Nguồn: [model sherpa](https://k2-fsa.github.io/sherpa/onnx/tts/all/Vietnamese/vits-piper-vi_VN-vais1000-medium.html), [model card](https://huggingface.co/rhasspy/piper-voices/blob/main/vi/vi_VN/vais1000/medium/MODEL_CARD).

Lỗi Gemini401 và SD mount0x107 là vấn đề riêng; thay TTS không sửa hai lỗi này.

## Kết quả build/kiểm thử đã có

- 41 Android unit test PASS, gồm typed JNI callback, chuyển PCM/giới hạn30s, Cancel/close không free native đang chạy, idle release và failure khi thiếu model.
- Pixel_8 x86_64 emulator: 11 instrumentation test PASS: lượt10test (49,37s) gồm4 TTS preset/model/numbers/idle/cancel/overlength, 3 reply/replay/Gallery/busy, 2 runtime/Keystore và1 STT vi/en; lượt riêng1 AudioTrack test (3,329s) phát/cancel/phát tiếp thành công. Không gọi Gemini.
- Lượt đo emulator: preset render2–32ms ở lượt đầu; một câu warm model805ms cho2321ms audio, PSS toàn process khoảng268840KB; tải lại model ở một ca5766ms. Không phải benchmark/đánh giá nghe trên điện thoại thật. Cold lần đầu còn copy/check model vào noBackup.
- APK arm64: 297219263byte (~297,2MB thập phân/~283,5MiB), tăng khoảng69MB so với bản trước228600028byte. SHA256 `3DC6BAAC1EE900A0D112498CE4CC0EDBB22D3A63E720467ED853D5172674B296`.
- APK x86_64: 301433384byte, SHA256 `79B855E1B38D53272B549FDA4CE34BAC63D21ED94E68A3B3903A196DAC10677E`.
- Chưa tự động chạy instrumentation trên điện thoại do lượt trước bị chặn cài test APK; không vượt chặn. Giọng trên loa kính, thời gian bắt đầu nghe/pin/nhiệt/RAM điện thoại thật vẫn cần nghiệm thu trực tiếp.
- Điện thoại25098PN5AC arm64: đã cài đè APK cuối và mở thành công lúc19:18:27 (28/09/2026), giữ dữ liệu. URL backend/token mã hóa vẫn hiện diện, ADBreverse8080 giữ nguyên; backend health/auth PASS. Không cài lại test APK trên điện thoại.
