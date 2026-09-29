# Sửa ngắt quãng phản hồi dài — 29/09/2026

## Hiện tượng và căn cứ

Người dùng nghe beep và giọng Việt offline ổn, nhưng phản hồi Gemini đôi lúc ngắt. Phát lại cùng câu thì vị trí ngắt thay đổi. App tổng hợp toàn bộ PCM trước khi gửi; Gemini không cung cấp audio trực tiếp trong lúc loa phát.

Đã tái hiện bằng regression: khi dispatcher gọi bị chặn, transport cũ dừng gửi giữa các chunk. Test `callerUiStallDoesNotPausePcmTransfer` thất bại trước sửa và đạt sau sửa. Đây là lỗi scheduling đã chứng minh bằng test, chưa chứng minh mọi lần ngắt trên kính đều có cùng nguyên nhân; chưa thu được log một lượt phát lỗi thực tế.

## Thay đổi

- Android giữ toàn bộ `playAudio()` trên `Dispatchers.IO`, không quay lại UI giữa các chunk. Giữ kiểm tra session/seq, Cancel và xử lý framing hiện có.
- Firmware nhận đủ toàn bộ PCM và `AUDIO_OUT_END` hợp lệ rồi mới phát. Trễ truyền Wi-Fi hoặc ACK không còn làm thiếu nguồn PCM giữa câu đang phát.
- Câu ngắn dùng buffer boot 64 KiB (fallback 16 KiB). Câu dài cấp phát đúng kích thước trong PSRAM, tối đa 960000 byte / 30 giây; buffer tạm giải phóng sau kết thúc, Cancel hoặc mất TCP. Thiếu bộ nhớ trả `AUDIO_BUFFER_UNAVAILABLE` trước khi phát, không tự chuyển sang streaming.
- Android chờ END theo thời lượng audio + 5 giây, tối thiểu 8 giây, tối đa 35 giây. Firmware hủy phiên chưa phát nếu không có tiến triển 10 giây, kể cả nhận đủ PCM nhưng thiếu END.
- Serial bổ sung `full-preload`, `receiveMs` và `maxFeedGapMs`. Chỉ số cuối đo khoảng cách giữa các lượt feed main-loop, không phải phép đo trực tiếp DMA underrun.

Không đổi PCM16 mono 16 kHz, BLE/framing V2, model TTS, backend, khóa API, model Local AI, GPIO hoặc partition. Không thêm task playback để tránh tăng stack SRAM trong cấu hình đang có ít internal heap khi TCP hoạt động.

Đánh đổi: nạp đủ câu trước khi bắt đầu nghe có thể tăng thời gian chờ đầu câu. Chưa đo độ trễ hoặc RAM đỉnh trên điện thoại/kính thật cho bản sửa này; chưa cam kết dưới một giây. Scheduling I2S/main-loop vẫn cần kiểm tra thực tế.

## Kiểm thử và artifact

- Android unit: 63/63 đạt, gồm regression dispatcher bị chặn và deadline câu 30 giây.
- Instrumentation emulator: 28/28 đạt (22.939 giây), gồm voice/replay/OCR/runtime/TTS/STT. Không cài APK instrumentation lên điện thoại thật.
- Native C++17 warnings-as-errors: `voice_contract_test`, `intent_worker_test`, `playback_policy_test` đều đạt. Worker dùng FreeRTOS stubs; policy test không chứng minh malloc/I2S/scheduling trên S3.
- Android build/unit/lint/assemble debug + test APK đạt; lint 0 lỗi, 20 cảnh báo, 2 hints.
- Firmware build đạt: chương trình 1715538 byte (54%), globals 103772 byte (31%). App image 1715680 byte.
- APK ARM64 SHA-256: `A1F696D364FDC4FF5A01EDFB5840D2DF1DBA3724A6D15A41AD53691B61D9DAD0`.
- APK ARM64: 319512962 byte (304.71 MiB).
- Firmware app SHA-256: `17DC21F1D89DECE1AAF29F131FD76890ED362C9FF0D72FFF9160B70CA5862E80`.

APK cập nhật đã được ADB báo `Success` trên điện thoại thật. ADB không dây từng mất kết nối sau cài rồi xuất hiện lại; đã mở app, khôi phục reverse và kiểm tra backend trên điện thoại đạt. Khóa/token không thay đổi trong bản sửa này.

Backup trước cập nhật: `firmware/build/backups/audio-fix-20260929-143736/app-before.bin` và `layout-before.bin`. Đã đọc mới partition/OTA từ COM6, khớp build và chọn app0; không dùng full image cũ.

## Triển khai thật bản audio

- Đã nạp app0 tại `0x10000`, chỉ erase vùng app cần ghi (`0x10000..0x1b2fff`); không nạp bootloader/partition/model/NVS. `write-flash` kiểm tra hash đạt, sau đó `verify-flash` độc lập báo digest matched. Lần gọi verify đầu có tùy chọn `--no-progress` không được esptool hỗ trợ; đã gọi lại đúng cú pháp và đạt, không ghi lại flash vì lỗi CLI đó.
- Boot sau nạp: camera=1, mic=1, speaker=1; buffer 65536 byte PSRAM; WakeNet cũ load, MultiNet/LLM không load. Audio-ready internal heap 82272 byte, largest 38900, PSRAM 7916016 byte. Đây là snapshot boot, không phải RAM đỉnh lúc phát câu dài.
- SD vẫn mount lỗi `0x107`, sd=0. Không xuất hiện panic trong cửa sổ đọc serial 25 giây; chưa có lượt PCM từ app trong cửa sổ đó nên chưa đo được `receiveMs`/`maxFeedGapMs` hoặc xác nhận hết ngắt.
- Backend trên PC kiểm tra health trả `{"ok":true}`. Sau khi ADB không dây xuất hiện lại, đã khôi phục reverse `tcp:8080`, mở app thành công và curl từ điện thoại trả HTTP 200. URL lưu trong app vẫn `http://127.0.0.1:8080`, token mã hóa còn tồn tại, cloud bật. Không thay URL/token; không gọi Gemini thêm trong lượt sửa audio này. USB chỉ là phương án nếu ADB không dây tiếp tục mất kết nối khi app tạo hotspot.

App + firmware mới đã triển khai, nhưng nghiệm thu nghe phản hồi Gemini và độ trễ/RAM khi phát câu dài vẫn chờ test trên kính thật.

## Test nghe sau cập nhật

1. Mở app, tắt demo, chọn vi-VN, Connect lại vì nạp firmware làm kính khởi động lại.
2. Thử **Test loa bằng beep**, rồi **Test giọng Việt offline**; mỗi lượt chờ hết busy.
3. Bật Gemini và hỏi một câu để có phản hồi mới. Cập nhật app làm mất cache RAM cũ; cần câu mới trước khi dùng **Phát lại**.
4. Phát lại cùng câu ba lần. Phát lại dùng cache, không gọi Gemini hoặc chụp lại. Ghi nhận chờ đầu câu và còn ngắt giữa câu không; nếu có, cho biết vị trí có thay đổi không.
5. Thử Cancel trong lúc chờ nạp và trong lúc nói; sau cleanup thử lại câu ngắn. Không được phát nội dung phiên đã hủy.
6. Khi thu serial, đối chiếu `full-preload`, `receiveMs`, `maxFeedGapMs`, elapsed và lỗi TCP/I2S. Không coi thiếu log lỗi là đã nghiệm thu âm thanh.

Nếu app mở hotspot làm ADB/Wi-Fi tới PC mất kết nối, backend loopback qua ADB reverse có thể không còn dùng được. Bật lại Wireless debugging trên cùng mạng PC hoặc dùng USB debugging được cho phép, rồi khôi phục `adb reverse tcp:8080 tcp:8080`. Không tự sửa firewall hoặc mở backend ra mạng trong lượt này. Test local không cần backend.

SD mount lỗi và wake/Local AI trên chip là vấn đề riêng, không được coi đã sửa bởi bản audio này. Hướng dẫn toàn luồng: [test kính thật](test-kinh-that-2026-09-29.md).
