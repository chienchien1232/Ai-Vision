# Bàn giao firmware + Android — 29/09/2026

**Bản sửa audio tiếp theo cùng ngày:** xem [sửa ngắt quãng phản hồi dài](audio-jitter-fix-2026-09-29.md) cho artifact/test/trạng thái cập nhật mới. Hash và log mục 7 bên dưới là lịch sử bản trước sửa jitter.

Trạng thái: đã triển khai và build mã nguồn, regression tự động đạt. Đây là bản chuẩn bị tích hợp Local AI, **không phải kính đã nghiệm thu ngoài thực tế**. Lượt này không nạp firmware, không cài APK lên điện thoại thật, không thay model/partition và không sửa mã nguồn backend.

**Cập nhật triển khai thật sau yêu cầu “nạp board và cài điện thoại”:** APK đã cài cập nhật/mở trên điện thoại; firmware đã nạp riêng app0 và xác minh digest. Xem mục 7 bên dưới. Các kết quả test tự động ở mục 3 vẫn là kết quả máy ảo/host, không đổi thành test trên kính thật. Hướng dẫn nghe/thu và nghiệm thu: [test kính thật](test-kinh-that-2026-09-29.md).

## 1. Luồng hiện hành

```text
Android WAKE AI → START_LISTENING → mic kính thu PCM
→ provider chưa cấu hình → AUDIO_CHUNK/AUDIO_END
→ STT offline Android → lệnh local Android hoặc backend cho câu hỏi
→ TTS/preset offline Android → PLAY_AUDIO → loa kính → cleanup/IDLE
```

- WAKE AI là thao tác trên app, không phải wake word mới trên chip. IDLE không đọc/feed mic để tìm wake word; dừng RX không đóng TX duplex dùng cho loa. WakeNet/model có sẵn không được train/thay thế trong lượt này.
- CAPTURE dùng TAKE_PHOTO/GET_MEDIA, ảnh lưu phía Android. Không bổ sung nút vật lý.
- Một phiên voice tại một thời điểm; giữ busy tới khi playback hoặc cleanup hoàn tất. Chỉ nhận kết quả có sessionId phù hợp, bỏ kết quả cũ sau Cancel/disconnect.
- Local handler, OCR, preset/TTS và cache phát lại hiện có được giữ. Lệnh chưa hỗ trợ như đo pin/tăng giảm âm lượng bằng nhận dạng Android không tự biến thành câu hỏi Gemini. Cloud thật được hoãn; không gọi cloud STT/TTS dự phòng.
- Người phụ trách Local AI nối adapter vào `localIntentProvider()` theo [contract](local-ai-integration-contract.md). Nhận dạng trên chip hiện **chưa cài**, không thể chỉ chép model là chạy ngay.

## 2. Các phần đã triển khai và tự review

| Nhóm | Mã nguồn và hành vi mới | Giới hạn nghiệm thu |
|---|---|---|
| Phiên Android | VoiceSessionState, session gate, xử lý event đến trước ACK, Cancel/join, cleanup STOP_LISTENING, timeout thành lỗi thay vì giả Cancel | Test tự động/máy ảo; chưa đo background/Wi-Fi yếu trên kính |
| Thu mic firmware | Một RX owner; khóa lifetime/read; chỉ đọc/feed khi thu; dừng RX, bỏ dữ liệu cũ; giữ TX | Build đạt; chưa xác minh driver RX enable/disable và âm học thật |
| Provider/executor | LocalIntentProvider, IntentWorker, VoiceFlow, DeviceCommandExecutor, PcmGain; mặc định không tạo inference worker khi chưa có provider | Test double, không có nhận dạng/model mới trên S3 |
| Local result → app | Giữ session, result, descriptor JPEG; tải/lưu ảnh đã chụp, không chụp lần hai; REPEAT dùng cache Android | Metadata mở rộng được ghi trong contract/V2; nhánh chip thực chưa chạy |
| Media/Gallery | Save có URI, chống lưu trùng, lỗi disk tách lỗi TCP; decode nền và giới hạn JPEG/pixel; fallback app-owned storage Android 8/9 | Android 8/9 chưa chạy thiết bị/API tương ứng; fallback không được coi là Gallery công cộng |
| Playback | Dùng TTS Việt/preset có sẵn; giữ kết quả tác vụ khi audio lỗi; busy/Cancel/session; gain saturating, khoảng an toàn đuôi TX | Gain boost có thể bẹt peak; chưa đo clipping/độ trễ hoặc nghe loa thật |
| Transport | Deadline bao gồm blocking write, đóng socket khi hỏng framing; watchdog không đóng nhầm request sau; kiểm tra audio V2; partial frame/WAIT_ANDROID có hạn; không cho client mới chiếm phiên | Chưa là xác thực mạng hoàn chỉnh, dùng trong môi trường thử nghiệm có kiểm soát |
| Build/handoff | Build firmware mặc định app-only, contract tích hợp và hash artifact | Không erase/merge/flash model mặc định; chưa nghiệm thu sản phẩm |

Sau các lần sửa đã review ownership/lifetime, cancellation, session, side effect và timeout; thêm regression cho các lỗi phát hiện. Không đổi BLE UUID, HELLO|2, port 5000 hay framing V2. Descriptor ảnh và mã REPLAY_ON_ANDROID là bổ sung được mô tả tại [contract](local-ai-integration-contract.md) và [V2](protocol-v2-draft.md).

## 3. Kết quả kiểm thử bản cuối

| Kiểm tra | Kết quả | Bằng chứng/phạm vi |
|---|---|---|
| Android unit | 61/61, 0 failure/error | `android/app/build/test-results/testDebugUnitTest/` |
| Android lint | 0 error, 20 warning, 2 hint | `android/app/build/reports/lint-results-debug.txt`; không tuyên bố zero-warning |
| Android build | Thành công | testDebugUnitTest, lintDebug, assembleDebug, assembleDebugAndroidTest, offline/no-configuration-cache |
| Instrumentation bản APK cuối | 28/28, 35.314 giây | Pixel_8, emulator-5554; không chạy trên điện thoại thật |
| Native voice contract | PASS, C++17 warnings-as-errors | State/deadline wraparound, provider chưa cấu hình, confidence/enum, executor/gain/replay |
| Native intent worker | PASS, C++17 warnings-as-errors | Không tạo task khi disabled; metadata copy, ownership, hủy trong inference/kết quả muộn; dùng FreeRTOS stubs single-step |
| Backend regression | 12/12, 3.102 giây | unittest/mock, mã nguồn backend giữ nguyên; không chứng minh Gemini thật hoạt động |
| Firmware Arduino | Thành công | 1,715,358 byte chương trình (54%); globals 103,748 byte (31%); đây không phải số đo RAM runtime |

28 instrumentation gồm ManualVoiceFlowTest (7), ReplyPlaybackTest (3), OfflineReadingTest (10), RuntimeLifecycleTest (2), VietnameseTtsSmokeTest (5), LocalSpeechSmokeTest (1). Có test 20 phiên manual liên tiếp, event cũ/đến sớm, Cancel trong lúc chờ AI, local-photo chụp một lần, repeat không gọi AI/STT/tổng hợp lại. Các kiểm tra phiên dùng fake transport/provider; native STT/TTS smoke chạy runtime trên máy ảo, không chứng minh chất lượng mic/loa kính.

Hai native executable là host harness, không phải native model inference trên S3. Chưa có corpus giọng nói thật/soak phần cứng hoặc số đo RAM/stack/largest free block/latency. Không coi 20 phiên máy ảo là soak test kính.

## 4. Artifact

| Artifact | Đường dẫn trong dự án | Kích thước |
|---|---|---|
| APK Android ARM64 debug | `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 319,512,962 byte ≈ 304.71 MiB |
| Firmware app image | `firmware/build/voice-local/AiVisionGlasses.ino.bin` | 1,715,504 byte |

SHA-256 APK ARM64:

```text
36B6AA481E5E497A80BDA46146F351251ADC1831CD92B5FB61A917E1B05791A3
```

SHA-256 firmware app image:

```text
CFCFFFB9AE8DDC98C67077926EB404ABA062DE15742FAD834D20F9CD7EA494AE
```

APK lớn vì kèm tài nguyên/model offline đã có; đây là debug build, chưa là bản release phân phối. Firmware FQBN: `esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=esp_sr_16,PSRAM=opi,FlashMode=qio`. Các header build đã được đối chiếu với source cuối (bỏ directive `#line` do Arduino thêm).

**App image không phải full factory image.** Trước khi nạp cần xác minh board, partition/bootloader và app slot đang dùng, rồi chọn quy trình nạp đúng. Không dùng file `.with-model.bin` cũ còn trong thư mục build; bản build mặc định này không cập nhật artifact đó và không cho phép suy ra model đã được nạp. Không cài APK bằng cách vượt chặn cài đặt của điện thoại.

## 5. Chạy lại kiểm thử

Từ `android/`, với JAVA_HOME trỏ tới JBR của Android Studio:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-configuration-cache --console=plain
```

Từ root dự án:

```powershell
.\firmware\build-local-voice.ps1
g++ -std=c++17 -Wall -Wextra -Werror firmware/tests/voice_contract_test.cpp -o firmware/build/voice-contract-test.exe
.\firmware\build\voice-contract-test.exe
g++ -std=c++17 -Wall -Wextra -Werror -I firmware/tests/stubs firmware/tests/intent_worker_test.cpp -o firmware/build/intent-worker-test.exe
.\firmware\build\intent-worker-test.exe
```

Backend regression: từ `backend/` chạy `python -m unittest -v test_server.py`. Instrumentation chạy trên máy ảo bằng Android Studio/adb với test runner `com.example.ai_vision.test/androidx.test.runner.AndroidJUnitRunner`, chọn sáu class nêu ở mục 3. Phải chỉ định đúng serial máy ảo, không tự cài lên điện thoại.

## 6. Checklist bắt buộc trên kính thật

- [ ] Xác minh artifact/partition và nạp riêng app đúng cấu hình, giữ model data; ghi firmware/APK hash đã dùng.
- [ ] Đo IDLE không đọc/feed mic; WAKE AI mở RX, endpoint/Cancel tắt RX; beep sau dừng mic vẫn phát, không mất TX.
- [ ] Đo mic PCM thực 16 kHz mono S16LE, không lẫn audio đuôi loa; thử im lặng, giới hạn thu, 20+ phiên, Cancel liên tiếp.
- [ ] Test beep và giọng Việt riêng; nghe/đo đầu-đuôi, clipping, thời gian bắt đầu, RAM/stack/largest free block. Mục tiêu xác nhận dưới một giây chưa được đo.
- [ ] Capture/lưu/retry: ảnh đã lưu không lưu trùng hay báo chụp thất bại vì TTS; thử lỗi disk và TCP giữa tải/phát.
- [ ] Reset/EOF/partial frame/Wi-Fi yếu/background/reconnect không tự chạy lại side effect và không phát nội dung phiên cũ.
- [ ] Android 8/9: thử fallback lưu ảnh trên thiết bị/API tương ứng; không giả định đã vào Gallery công cộng.
- [ ] Provider thực: adapter/runtime, ngưỡng đã hiệu chỉnh, cooperative Cancel/deadline, tài nguyên và corpus giọng nói; đo sai/đúng cho từng intent.
- [ ] Gemini thật kiểm tra khóa/hạn mức riêng khi được phép; 401/429 hiện không được giải quyết bằng firmware/TTS.

Chỉ sau các kiểm tra phần cứng liên quan mới đánh dấu luồng kính đạt. Wake word tiếng Việt, AI model/training/quantization trên chip, AEC/barge-in, sensor pin, nút vật lý và mở rộng SD/video/backend ngoài phạm vi bàn giao này.

## 7. Triển khai board và điện thoại thật cùng ngày

- Điện thoại `25098PN5AC` (pandora), Android 16, arm64-v8a: `adb install -r` APK ARM64 trả Success, giữ dữ liệu app; lastUpdateTime 29/09/2026 13:35:35. MainActivity đã được mở, process app được ghi nhận; không có AndroidRuntime error trong kiểm tra riêng process mới ở thời điểm đó. Không cài APK instrumentation hoặc vượt chặn cài đặt.
- Cổng COM6, USB-SERIAL CH340: esptool xác nhận ESP32-S3 revision v0.2, flash 16 MB, embedded PSRAM 8 MB; Secure Boot/Flash Encryption disabled.
- Trước ghi đã sao lưu đủ 16,777,216 byte vào `firmware/build/backups/board-COM6-20260929-133642/flash-before-460800.bin`. SHA-256 `841F374CE5C31DE88AAE94F4BC0A9009BF32EA7FC78A07B964C288DB43FD249F`. Lần đọc 921600 baud bị corrupt data; lần đọc lại 460800 baud thành công (447.6 giây). **Backup có thể chứa thông tin Wi-Fi/NVS; không commit/chia sẻ công khai.**
- Partition đọc từ backup khớp từng byte với partition build. OTA data khớp `boot_app0.bin`, sequence 1 chọn app0; app1 chưa có image. Chỉ ghi `AiVisionGlasses.ino.bin` tại `0x10000`; vùng erase thực tế `0x10000..0x1B2FFF`, nằm trong app0 3 MB. Không ghi bootloader, partition table, OTA/NVS, filesystem hoặc model; không erase-all.
- Flash write trả `Hash of data verified`; lần `verify-flash` độc lập trả `Verification successful (digest matched)`. Artifact/hash giữ nguyên như mục 4. Model partition trong backup trước ghi có SHA-256 `46D0E4D57801A40FBF2969CC7699B89BC6A1A197CB9623333D59DE68CFB69476`; đây là hash trước nạp, không phải phép đọc lại model sau nạp. Command ghi/vùng erase không chạm model tại `0xC10000`.

### Quan sát log boot và phiên thật sau nạp

```text
[CAMERA] OV2640 QVGA JPEG: ready (0x0000), PSRAM=1
[AUDIO] I2S mic: initialized, BCLK=14 WS=21 DIN=47 DOUT=1
[AUDIO] I2S speaker TX duplex (MAX98357A DIN=1): ready
[AUDIO] PCM playback ring: 65536 bytes (PSRAM)
[AUDIO] Speaker self-test tone played
[WAKE] Hi ESP loaded; MultiNet/LLM not loaded
[SD] Mount FAILED (1-bit SDMMC)
[READY] BLE requested, camera=1 mic=1 speaker=1 sd=0
[V2] Session started, capabilities=PHOTO,AUDIO_IN,AUDIO_OUT
```

Log Hi ESP loaded chỉ cho biết AFE/model có sẵn được khởi tạo; bản này không quảng cáo WAKE_EVENT và không đọc/feed mic ở IDLE để wake tự động. Phải bấm WAKE AI để thu.

Trong cửa sổ serial 20 giây đã quan sát BLE provisioning → TCP V2, hai media JPEG (5704 và 3405 byte) được chuyển `delivered=1`, hai playback 34922 byte kết thúc trong 1142 ms/lượt, `underruns=0`. Đây là số liệu truyền/I2S của các thao tác trong phiên, **không phải xác nhận Gallery đã lưu, tiếng Việt đọc đúng, loa nghe sạch hay mic/STT đã đạt**.

Đo RAM tại boot: internal 231916/largest 172020/PSRAM 8384064 byte. Sau audio init: internal 82284/largest 38900/PSRAM 7916016 byte. Khi TCP connected: internal 27140/largest 18420/PSRAM 7911380 byte; cuối playback quan sát internal 26396/largest 17396 byte. Đây là snapshot của bản chạy thật, chưa là đo peak/stack/soak. Headroom RAM nội đã hạn chế; người tích hợp Local AI phải đo thêm và không mặc định model/task mới sẽ vừa.

Không thấy panic/reset lặp trong cửa sổ quan sát này. SD báo `sdmmc_card_init failed (0x107)`, capability không có VIDEO; chưa kết luận thẻ thiếu/hỏng hay wiring chỉ từ log. Tạm bỏ video và test local/camera/mic/loa trước. Cổng serial đã được đóng sau quan sát; người dùng có thể mở Serial Monitor 115200 nếu cần.

**Bước tiếp theo:** thực hiện [checklist hướng dẫn test thật](test-kinh-that-2026-09-29.md), gửi kết quả nghe/thu/transcript. Không đánh dấu checklist nghiệm thu phần cứng đạt chỉ từ flash/install/log.
