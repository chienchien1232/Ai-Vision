# Firmware Arduino cho AI-Vision Glasses

## Bản hiện hành 29/09/2026: app wake, không tích hợp Local AI mới

Bản audio tiếp theo dùng full-preload PCM rồi mới phát, Android gửi trên IO và tăng deadline END theo thời lượng. Xem [báo cáo sửa jitter/artifact mới](../../docs/audio-jitter-fix-2026-09-29.md); cập nhật app cùng firmware trước khi thử câu dài. Model/partition không đổi.

Nhấn **WAKE AI — nói trên mic kính** trên Android để bắt đầu thu. IDLE không đọc/feed mic tìm wake word; không quảng cáo WAKE_EVENT. Provider chưa cấu hình chuyển PCM về STT offline Android. Không sửa/train/thay model WakeNet có sẵn. Build mặc định `./firmware/build-local-voice.ps1` chỉ tạo **app image**, không merge/nạp model. Không dùng full image cũ để cập nhật bản này.

Xem [bàn giao và hash](../../docs/ban-giao-firmware-app-2026-09-29.md), [contract Local AI](../../docs/local-ai-integration-contract.md) và [hướng dẫn test kính thật](../../docs/test-kinh-that-2026-09-29.md). Trước nạp phải sao lưu, đọc partition/OTA và xác minh app slot; không đoán offset hoặc erase flash. Các mục 28/09 bên dưới là lịch sử, không phải hướng dẫn nạp/chế độ wake mặc định hiện hành.

## Bản local-first 28/09/2026

WakeNet Hi ESP + WebRTC VAD đã thêm trong `AiVisionGlasses/MicPipeline.h`. Một task đọc I2S RX, một task fetch AFE; không dùng ESP_SR.begin, không tải MultiNet. Kính vẫn cần Android cho STT/định tuyến tiếng Việt/Anh. Wake không đồng nghĩa nhận câu tự do trên chip; "kính ơi" chưa có model.

Chạy từ thư mục dự án: `./firmware/build-local-voice.ps1`. Script chỉ build với Arduino ESP32 3.3.11, partition **esp_sr_16**, OPI PSRAM và đóng gói `firmware/build/voice-local/AiVisionGlasses.with-model.bin` có model tại **0xC10000**. Không nạp board. Stock `AiVisionGlasses.ino.merged.bin`/`flash_args` của Arduino không chứa model, không dùng chúng để nghiệm thu wake. Chuyển partition từ app3M_fat9M_16MB sang esp_sr_16 thay bố cục flash; cần sao lưu dữ liệu flash cũ và được phép trước khi nạp. File SD không nằm trong flash.

Sau nạp có cho phép: kiểm tra Serial `[WAKE] Hi ESP loaded`, capability `WAKE_EVENT`, RAM/PSRAM, rồi app gửi SET_VOICE_CONFIG. Đọc mic kết thúc sau khoảng lặng ~700 ms hoặc 8 giây; nút diagnostic giữ cửa sổ 8 giây. STOP_LISTENING không đóng TCP. Mất TCP cố finalize AVI; video giữ descriptor tới ACK_MEDIA sau khi Gallery lưu thành công, file SD không bị xóa tự động. Wake/mic/timing/video của bản mới vẫn cần nghiệm thu trên board thật.

Hợp đồng hiện hành: [V2](../../docs/protocol-v2-draft.md). Kết quả/giới hạn: [bản triển khai](../../docs/local-first-implementation-2026-09-28.md).

## Ghi chú prototype trước 28/09 (lịch sử)

Phần dưới mô tả bản trước khi có mic-owner/WakeNet và STT offline; không dùng mô tả cloud STT hay partition không model để nghiệm thu bản mới.

Mở `AiVisionGlasses/AiVisionGlasses.ino` bằng Arduino IDE và nạp cho ESP32-S3-CAM (CAM-24090201 / GOOUUU, camera OV2640). Đây là sketch độc lập; `firmware/main` là khung ESP-IDF cũ và không bị thay đổi.

## Điều kiện nạp

- Đã build với Arduino-ESP32 **3.3.11** trên ESP32-S3, Flash 16 MB, PSRAM OPI 8 MB. FQBN kiểm thử: `esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=app3M_fat9M_16MB,PSRAM=opi,FlashMode=qio`.
- Trong Arduino IDE chọn **Flash 16MB**, **PSRAM OPI**, **3MB APP / 9MB FATFS** (hoặc partition app ≥ 3 MB). Bản firmware V2 hiện lớn hơn partition app mặc định 1,310,720 byte; thông báo "Sketch too big" ở cấu hình mặc định là lỗi chọn partition, không phải tràn RAM lúc chạy.
- Kiểm tra chân OV2640 và I2S theo `docs/hardware.md` trước khi nối dây. Mic INMP441: BCLK 14, WS 21, SD 47, L/R nối GND. MAX98357A: DIN 1, duplex một port I2S chung BCLK/WS với mic (TX cấu hình y hệt RX: 32-bit mono; firmware tự dịch mẫu 16-bit của app lên 16 bit cao); app phát TTS ra loa qua V2 AUDIO_OUT.
- Quay video cần **thẻ microSD FAT32 cắm sẵn trước khi boot**, đấu 1-bit SDMMC: CLK 39, CMD 38, D0 40. Không có thẻ hoặc mount lỗi thì kính không quảng cáo capability VIDEO và `START_VIDEO` trả `SD_MISSING`.
- Không cần `secrets.h` hoặc tên/mật khẩu Wi-Fi cố định. Điện thoại tạo local-only hotspot; app cấp credentials qua BLE mỗi lần kết nối.

## Luồng khớp app hiện tại

1. Kính quảng bá BLE với tên **Ai-Vision Glasses**, service/RX/TX UUID cố định trong sketch. App ghi từng đoạn của `WIFI|base64url(SSID)|base64url(password)\n` vào RX. Kính ghép dòng, giải mã, nối hotspot và mở TCP 5000 trước khi notify `WIFI_CONNECTED|<IPv4>|5000\n` trên TX. Không chuyển ảnh/âm thanh qua BLE.
2. TCP **chỉ dùng V2**: app gửi `HELLO|2\n`, kính trả `HELLO|2|PHOTO,AUDIO_IN,AUDIO_OUT,VIDEO\n` (chỉ quảng cáo capability khởi tạo được; VIDEO cần camera và SD). Lệnh V1 bị từ chối bằng `ERROR|V2_REQUIRED`. Sau HELLO, mỗi frame gồm JSON header + `\n` + đúng `payloadBytes` byte nhị phân.
3. Ảnh: `TAKE_PHOTO` → `PHOTO_CAPTURED` → `GET_MEDIA` → `MEDIA_CHUNK` tuần tự → `MEDIA_END`; app tự lưu JPEG vào Gallery. Mic: `START_LISTENING` (language bắt buộc, wakeWord tùy chọn) → `LISTENING_STARTED` → thu khoảng 8 giây PCM_S16LE mono 16 kHz vào 256 KB PSRAM → gửi `AUDIO_CHUNK` từng khối tối đa 16 KB → `AUDIO_END`. Thu trước, gửi sau để TCP chậm không làm rơi mẫu mic. PCM lấy từ 24-bit I2S bằng dịch 16 bit, không khuếch đại 4 lần như bản cũ. Serial in peak/RMS/near-clipped và thời gian I2S/TCP để kiểm tra mic thật.
4. Loa: `PLAY_AUDIO` → `AUDIO_READY` → `AUDIO_OUT_CHUNK` tối đa 8 KB, PCM_S16LE mono 16 kHz → `AUDIO_OUT_ACK` sau khi khối đã vào vòng đệm PCM (64 KB PSRAM, dự phòng 16 KB RAM nội), **không** đợi I2S phát xong. Kính tích trước tối đa 0,5 giây rồi phát các khối 16 ms liên tục; app tiếp tục gửi trong lúc kính phát. `AUDIO_OUT_END` → `AUDIO_PLAYED` sau khi vòng đệm rỗng và chờ đuôi DMA 120 ms; `CANCEL` bỏ các khối chưa phát. Serial báo `underruns` nếu hết dữ liệu lâu hơn khoảng đệm DMA. App có nút **Test glasses speaker** phát tone 1 giây trực tiếp qua V2, không cần backend/TTS.
5. Video: `START_VIDEO` → `VIDEO_STARTED` → kính ghi MJPEG AVI lên SD với nhịp 10 fps, tối đa 60 giây/15 MiB/800 frame → `STOP_VIDEO` → `VIDEO_STOPPED` → `GET_MEDIA` tải `.avi`. STT/LLM nằm ở app/backend, không chạy trên kính. Wake-word detection on-device chưa có model.

## Kiểm tra sau nạp

Mở Serial Monitor 115200. Khi boot, kính phát một beep ngắn và in `[AUDIO] Speaker self-test tone played`; `[MIC] Boot I2S probe` kiểm tra tốc độ đọc, còn `[MEM]` cho biết RAM trống. Dùng app Connect → PING/Status/Capture → **Test glasses speaker**. Nếu beep khởi động cũng rè thì kiểm tra nguồn 5V MAX98357A, GND chung, dây BCLK/WS/DIN và cách nối SPK+/SPK− trước; nếu beep sạch nhưng tone trong app rè thì đối chiếu TCP/I2S và log `[AUDIO-OUT]`. Nút **Mic diagnostics (8s, no STT)** báo peak/RMS/near-clipped cạnh nút mà không gửi audio lên backend. Nút **Record glasses → text (8s)** thu âm, gửi đến backend STT và điền chữ vào ô Command or question; người dùng bấm Send riêng để chạy lệnh/AI. `mic=1` chỉ xác nhận I2S khởi tạo, không khẳng định tín hiệu INMP441 đúng.

Lưu ý: BLE provisioning hiện không yêu cầu pairing/bonding và TCP không mã hóa; chỉ dùng trong môi trường prototype/hotspot tin cậy. Trước khi phát hành cần bổ sung xác thực, mã hóa và xử lý credentials phù hợp.
