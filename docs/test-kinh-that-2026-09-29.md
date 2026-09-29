# Test kính thật với bản firmware/app 29/09/2026

**Cập nhật audio:** xem [test ngắt quãng/phát lại](audio-jitter-fix-2026-09-29.md#test-nghe-sau-cập-nhật). Dùng app + firmware tương ứng; deadline END 15 giây của app cũ không đủ cho câu dài preload. Kết quả nghe bản mới vẫn chờ xác nhận thực tế.

Đã nạp riêng app0 trên ESP32-S3 COM6, verify-flash đạt; APK ARM64 đã cài cập nhật/mở trên điện thoại Android 16. Boot có camera/mic/speaker; đã quan sát TCP/media/playback, chưa xác nhận chất lượng nghe/thu. **SD đang mount lỗi, chưa có VIDEO**, tạm bỏ test video. Chi tiết backup/hash/log: [báo cáo triển khai](ban-giao-firmware-app-2026-09-29.md#7-triển-khai-board-và-điện-thoại-thật-cùng-ngày).

## Bắt đầu

Bản này dùng nút WAKE AI trên app, **không tự nghe Hi ESP/Kính ơi**. Mic thuộc kính; STT/TTS offline chạy trên điện thoại. Provider Local AI trên chip chưa được tích hợp. Không cần backend/token Gemini cho các bài test local bên dưới.

1. Cấp nguồn ổn định cho board, mic và amplifier, kiểm tra mass chung. Không đấu lại dây khi đang cấp nguồn. Loa MAX98357A nối SPK+/SPK−, không nối SPK− với GND; INMP441 dùng 3V3, không cấp 5V.
2. Mở Ai-Vision Glasses; **tắt Demo without glasses**. Bật Bluetooth, cấp quyền Nearby/Bluetooth/Wi-Fi app yêu cầu. Giữ điện thoại gần kính.
3. Chọn **Language: vi-VN**, tắt **Cho phép Gemini với câu ngoài nhóm local** trong đợt test đầu và bấm **Lưu cài đặt**. Không xóa token/key hiện có.
4. Bấm **Connect**, chờ Connected. App tạo hotspot và tự tìm kính qua BLE; không nhập IP. Nếu Wi-Fi/debug không dây mất kết nối khi app mở hotspot, đó chưa phải bằng chứng firmware hỏng.
5. Không tắt Wi-Fi/Bluetooth hoặc bật chế độ máy bay trong khi thử kết nối kính. Muốn thử không Internet, giữ hotspot/Bluetooth và tắt dữ liệu di động sau khi kết nối; thao tác này có thể làm ADB không dây ngắt, không ảnh hưởng quyền cài app đã hoàn tất.

## Thứ tự test

Mỗi bước chỉ chuyển tiếp khi trạng thái hết busy. Âm thanh nghe phải từ **loa kính**, không dùng Phone mic demo để thay bài test mic kính.

| Bước | Thao tác | Đạt khi |
|---|---|---|
| 1. Kết nối | PING rồi Status | PING đáp ứng; camera/mic/speaker khả dụng. `mic=1` chỉ là khởi tạo driver, chưa chứng minh tín hiệu mic |
| 2. Đường loa | Test loa bằng beep | Nghe tone ngắn rõ từ kính, không rè/đứt; app thoát busy |
| 3. TTS Việt | Test giọng Việt offline | Nghe tiếng Việt từ kính; không cần Google/Xiaomi voice Việt hoặc Gemini. Lần đầu có thể phải khởi tạo model, chưa cam kết dưới một giây |
| 4. Lệnh chữ | Nhập `xin chào`, Send; tiếp theo `mấy giờ rồi`; `tính 2,5 cộng 3` | App trả lời local, phép tính 5.5; kính đọc; không cần backend. Mỗi câu chờ phát xong |
| 5. Camera/lưu | Capture, kiểm tra preview rồi Gallery | Có ảnh mới trên Android; bấm lưu lại không tạo bản trùng cho cùng ảnh. Nếu tiếng lỗi, ảnh đã lưu vẫn còn |
| 6. Mic tín hiệu | Mic diagnostics (8s, no STT); nói vài giây rồi im lặng | Có PCM/peak/RMS thay đổi theo tiếng nói; near-clipped không cao liên tục. Số đo riêng chưa chứng minh STT đúng |
| 7. Mic → STT → local | WAKE AI — nói trên mic kính; chờ LISTENING rồi nói `xin chào`, im lặng cho endpoint | Transcript đúng/đủ, phản hồi Việt từ kính, Voice về IDLE. Tiếp tục `mấy giờ rồi`, `chụp ảnh` (mỗi câu phải bấm WAKE AI riêng) |
| 8. OCR | Đưa kính gần chữ lớn, sáng, giữ yên; Chụp và đọc chữ | Có văn bản và tiếng đọc. Đọc tiếp/Xoay ảnh và đọc lại không chụp mới; QVGA chưa phù hợp chữ nhỏ xa |
| 9. Replay | Phát lại phản hồi gần nhất | Phát nội dung đã có; không chụp ảnh/gọi Gemini lần nữa |
| 10. Cancel | WAKE AI rồi Cancel action; sau đó test beep và WAKE AI lại. Thử Cancel khi đang phát phản hồi dài | Không phát nội dung phiên cũ; app về IDLE, phiên kế tiếp hoạt động. Không có nghe chen/barge-in bằng giọng khi loa đang phát |
| 11. Liên tiếp | Lặp 20 phiên: chào/giờ/chụp, lần lượt và chờ kết thúc | Không kẹt busy, không ảnh trùng ngoài các lần chụp chủ ý, không tiếng của phiên trước |
| 12. Kết nối lại | Disconnect → Connect, PING rồi beep | Kết nối và playback mới hoạt động, không tự chạy lại chụp hoặc phát phản hồi cũ |

Phép tính bằng giọng còn phụ thuộc transcript dạng chữ số. Nếu STT ra “hai cộng ba” thay vì “2 cộng 3”, bản router hiện chưa hỗ trợ đầy đủ số bằng chữ; kiểm tra bài tính bằng ô chữ trước, không quy ngay thành lỗi mic/loa.

Video chỉ thử thêm khi Status có VIDEO và SD đã mount: Record video khoảng 3–5 giây → Stop video → kiểm tra Gallery. Không thử thu mic/playback trong lúc ghi video. Không có SD thì SD_MISSING là vấn đề riêng, không phải TTS hỏng.

## Khi lỗi, khoanh vùng trước

- Không Connect: ghi nguyên thông báo app, kiểm tra Demo đã tắt, quyền/Bluetooth/board nguồn; chưa kiểm tra AI ở bước này.
- Beep không có tiếng: dừng bài test TTS/STT. Kiểm tra nguồn amplifier, BCLK14/WS21/DIN1, mass chung và speaker; không tăng volume để chữa wiring sai.
- Beep tốt nhưng TTS lỗi: ghi thông báo model/tổng hợp/audio; không cần cài voice Việt của Google để sửa model tích hợp.
- Mic toàn 0/gần 0: kiểm tra VDD3V3/GND, SCK14/WS21/SD47, L/R xuống GND, hướng mic. Peak cao sát cực đại khi yên hoặc near-clipped cao có thể là wiring/kênh/nguồn/gain; không tự chỉnh chân nếu chưa đối chiếu board.
- Mic có tín hiệu nhưng transcript sai: gửi câu đã nói, transcript, peak/RMS và môi trường khoảng cách/ồn. STT chạy trên điện thoại, không phải model chip.
- Ảnh đã lưu nhưng phát tiếng lỗi: kiểm tra Gallery, không chụp lại để chữa audio. Replay không tiêu thụ thêm Gemini.
- 401/429: backend/key/hạn mức riêng; để Gemini tắt và tiếp tục local test. Không phát key/token trong ảnh hoặc log gửi người khác.

## Gửi lại kết quả

Gửi theo mẫu: `Bước số … / thông báo app … / nghe được hay không … / transcript … / thao tác trước lỗi …`. Với lỗi mic gửi peak/RMS/near-clipped; với lỗi loa mô tả rè/đứt/mất đầu hoặc đuôi. Bản này chỉ được đánh dấu nghiệm thu phần cứng sau khi nghe/thu và đo thực tế; nạp/cài thành công không đồng nghĩa mọi bài test đã đạt.
