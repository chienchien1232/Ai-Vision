# Kế hoạch hoàn thiện bốn chức năng kính AI

Ngày chốt 28/09/2026. Phạm vi giữ nguyên ESP32-S3, OV2640, INMP441 và MAX98357A. Hoàn thiện trợ lý giọng nói và đọc chữ; không thêm Bluetooth audio, video hoặc model nhận diện khác trong đợt này.

## 1 Phạm vi và kiến trúc

| Chức năng | Kính ESP32-S3 | Điện thoại Android | Backend |
| --- | --- | --- | --- |
| Wake word | WakeNet Hi ESP và thu câu nói | Nhận PCM sau wake | Không dùng |
| Chụp ảnh hỏi giờ tính toán | Camera và mic | STT offline, router local, Gallery và TTS offline | Không dùng |
| Đọc chữ | Chụp ảnh mới | OCR Latin đóng gói, chia đoạn, TTS và phát lại | Không dùng |
| Hỏi cảnh | Chụp ảnh mới | Gửi đúng ảnh và câu hỏi, hiển thị và đọc trả lời | Gemini tạo nội dung |

Tiếng Việt tự do được nhận dạng trên điện thoại, không phải trên S3. Hi ESP là wake word thật; chữ Kính ơi trong transcript không thay thế model wake. Chưa đưa Kính ơi vào tiêu chí hoàn tất.

## 2 Trạng thái ban đầu

Firmware đã nạp lên board flash 16 MB và PSRAM 8 MB. Boot báo camera, mic, loa và model Hi ESP khởi tạo; đây chưa phải nghiệm thu chất lượng thu phát. RAM nội trống ghi nhận khoảng 79 KB, nên không thêm model nặng đồng thời.

App có STT Việt Anh offline, lệnh local, chụp lưu Gallery, hỏi ảnh qua backend và TTS Việt VAIS1000 đóng gói. Baseline có 41 unit test và 11 instrumentation test trên máy ảo đã đạt. APK đã cài mở trên điện thoại, nhưng bộ test native trên điện thoại bị hệ điều hành chặn cài; không vượt chặn này.

Gemini lần kiểm tra trước trả upstream 401 UNAUTHENTICATED. Không được gọi đây là lỗi hạn mức: 401 là xác thực, 429 mới là hạn mức. Được hoãn kiểm tra Gemini thật và tiếp tục local, nhưng không đánh dấu cloud đã nghiệm thu. SD lỗi 0x107 không chặn bốn chức năng đang chốt vì ảnh truyền trực tiếp về điện thoại.

## 3 Thứ tự triển khai và vòng debug

Mỗi chức năng đi qua vòng viết test, sửa code, chạy test riêng, đọc log, sửa lỗi, chạy lại test riêng và hồi quy toàn bộ. Không kết thúc chỉ vì một lượt chạy đạt. Ghi môi trường fake, máy ảo, điện thoại và kính thật riêng biệt.

### Giai đoạn 1 Wake và giọng nói local

Rà soát CONFIG_VOICE, sự kiện wake, một chủ sở hữu mic, session và chống thực thi trùng. Giữ wake bị chặn khi loa phát; chưa có AEC hoặc nghe chen. Test transcript có dấu, không dấu, câu phủ định, phép tính âm thập phân và chia không. Hủy không được thực thi lệnh cũ sau khi kết nối lại.

Nghiệm thu thực tế dự kiến: 30 lượt Hi ESP trong phòng yên và 30 lượt có tiếng nền, ghi số nhận đúng và thời gian; tối thiểu 30 phút không chủ động gọi để đo kích hoạt nhầm. Mục tiêu đề xuất ít nhất 27 trên 30 lượt yên tĩnh và không có lệnh ngoài ý muốn. Các mục tiêu chưa được coi là đạt trước khi đo.

### Giai đoạn 2 Chụp ảnh giờ và tính toán

Giữ Capture là tác vụ độc lập với phát tiếng. Ảnh đã lưu không được báo chụp thất bại khi TTS hoặc loa lỗi. Phát lại chỉ dùng nội dung và PCM đã có. Giờ ngày lấy từ điện thoại; phép tính dùng handler local, không dùng Gemini để phân loại.

Nghiệm thu: ít nhất 10 chu kỳ giọng nói chụp lưu Gallery phát xác nhận, 10 câu giờ ngày và 20 phép tính gồm âm, thập phân, chia không. Thử mất TCP lúc ảnh đang truyền, Cancel, kết nối lại và không gửi lặp lệnh chụp.

### Giai đoạn 3 OCR offline

Thêm TextReader interface và LocalTextReader dùng com.google.mlkit:text-recognition:16.0.1, bản Latin có model kèm APK. Không dùng thư viện tải model qua Play Services lúc chạy. Chụp ảnh mới, giải mã JPEG có giới hạn kích thước, nhận dạng trên worker, giữ văn bản đầy đủ trong RAM rồi chia đoạn để đọc.

Router phân biệt Đọc chữ trước mặt tôi và Trước mặt tôi có gì. Lệnh đọc chữ không gọi Gemini; lệnh giải thích nội dung hoặc cảnh vẫn là câu hỏi AI. Có nút đọc chữ, đọc tiếp, xoay ảnh và nhận dạng lại ảnh vừa chụp. Phát lại không OCR, chụp hoặc gọi AI lại. Không có chữ phải báo rõ, không tạo văn bản giả.

TTS vẫn giới hạn 30 giây mỗi đoạn. Chia theo từ và câu; nếu một đoạn vẫn quá dài, giữ chữ và cảnh báo, không phát PCM bị cắt giữa câu. Cancel bỏ kết quả phiên cũ và không giải phóng bitmap hoặc native recognizer trong lúc tác vụ đang dùng.

Nghiệm thu tự động: router local, chia đoạn không mất chữ, JPEG hỏng, ảnh trắng, ảnh chữ Việt Anh, xoay 90 độ, hủy lúc OCR và TTS lỗi. Nghiệm thu thật: nhãn lớn, trang sách và biển chữ ở nhiều khoảng cách ánh sáng. Camera hiện QVGA nên không cam kết đọc chữ nhỏ; tăng độ phân giải phải kiểm chứng bộ đệm camera và RAM trước khi đổi firmware.

### Giai đoạn 4 Hỏi cảnh và hoàn thiện tích hợp

Giữ ảnh mới gắn với request session; không dùng ảnh cũ của câu hỏi trước. Backend là nơi duy nhất giữ khóa Gemini. Phân biệt lỗi token app, xác thực Gemini, hạn mức và mất mạng. Không retry tự động hoặc tự chuyển OCR sang Gemini.

Test bằng AiClient giả lập cho ảnh mới, session, lỗi 401 và 429, Cancel và sau đó chạy tiếp lệnh local. Hoãn probe cloud thật theo chỉ đạo nếu khóa hoặc hạn mức lỗi. Muốn nghiệm thu cloud cần một lượt hỏi cảnh thật thành công và kiểm tra chất lượng nội dung; không suy diễn cảnh từ response demo.

## 4 Cấu trúc code và trách nhiệm

Android core chịu trách nhiệm định tuyến và quy tắc local cloud; vision chịu trách nhiệm OCR và chia văn bản; speech chịu trách nhiệm PCM và TTS; DeviceController điều phối phiên và UI, không chứa thuật toán OCR. Các interface cho phép thay model mà không đổi giao thức kính.

Firmware chịu trách nhiệm wake, mic, camera, TCP và I2S. Không sửa pin hoặc partition chỉ để thêm OCR Android. Backend chịu trách nhiệm Gemini, auth, giới hạn request và lỗi cloud. Người kiểm thử phần cứng xác nhận chất lượng mic, loa, wake, camera, pin và nhiệt; không gán tên hoặc thời hạn cá nhân khi chưa thống nhất.

## 5 Điều kiện bàn giao

1. Build APK và test APK được; test riêng ít nhất hai lượt sau sửa và hồi quy toàn bộ đạt; backend test không gọi Gemini thật.
2. Không có crash, native object bị giải phóng sớm, kết quả session cũ hoặc tác vụ có hiệu ứng phụ tự chạy lại.
3. APK có model OCR, STT và TTS cần thiết. Không có khóa Gemini trong APK hoặc tài liệu; không thay hoặc xóa token người dùng.
4. Tắt Internet nhưng giữ kết nối kính vẫn chạy wake lệnh local và OCR. Giữ lỗi phát tiếng tách khỏi kết quả tác vụ.
5. Giao APK có checksum dung lượng, tài liệu chạy test, log kết quả và danh sách phần chưa nghiệm thu. Không gọi bản dùng máy ảo là sản phẩm đã nghiệm thu trên kính thật.
6. Phần cloud được phép ghi HOÃN khi lỗi xác thực hoặc hạn mức; vẫn phải rõ chức năng hỏi cảnh chưa đạt nghiệm thu thật.

## 6 Nguồn và hồ sơ kết quả

- ML Kit OCR bundled: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Ngôn ngữ OCR: https://developers.google.com/ml-kit/vision/text-recognition/v2/languages
- WakeNet: https://docs.espressif.com/projects/esp-sr/en/latest/esp32s3/wake_word_engine/README.html
- Baseline phần cứng và phần mềm: docs/local-first-implementation-2026-09-28.md
- TTS và giấy phép: docs/vietnamese-offline-tts.md
- Giao thức hiện tại: docs/protocol-v2-draft.md
- Kết quả của đợt này cập nhật tại docs/ban-giao-bon-chuc-nang-2026-09-28.md sau kiểm thử.
