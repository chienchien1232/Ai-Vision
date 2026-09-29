# Ai-Vision

**Bản hiện hành 29/09/2026 — firmware + app, chưa tích hợp Local AI trên chip:** [bàn giao cho lập trình viên AI](docs/ban-giao-cho-lap-trinh-vien-ai-2026-09-29.md), [bàn giao build/test](docs/ban-giao-firmware-app-2026-09-29.md), [contract tích hợp](docs/local-ai-integration-contract.md). Nhấn WAKE AI trên app để thu mic kính; IDLE không đọc/feed mic chờ wake word. Provider mặc định chưa cấu hình nên PCM về Android STT offline. Các mô tả Hi ESP bên dưới là trạng thái bản trước, không phải chế độ mặc định của bản mới. App/firmware mới đã cài/nạp; bản sửa playback và giới hạn nghiệm thu ở [báo cáo audio](docs/audio-jitter-fix-2026-09-29.md). Kiểm tra Gemini lúc 16:43 cùng ngày gặp upstream 503 dù đường app/backend và token đạt; không coi PASS cloud trước đó là trạng thái hiện tại.

Bốn chức năng chốt 28/09/2026: [kế hoạch chi tiết](docs/ke-hoach-bon-chuc-nang-2026-09-28.md), [kế hoạch Word](docs/Ke_hoach_hoan_thien_4_chuc_nang_kinh_AI_28-09-2026.docx) và [bàn giao APK/kết quả kiểm thử](docs/ban-giao-bon-chuc-nang-2026-09-28.md). Đã thêm OCR offline tiếng Việt/Anh, đọc tiếp/xoay/phát lại không gọi Gemini; 53 unit, 21 test máy ảo offline và 12 backend test đạt. APK OCR mới đã cài/mở trên điện thoại. **Chưa nghiệm thu mic/OCR/giọng trên kính thật; Gemini thật được hoãn.**

Luồng local-first mới (28/09/2026): [bản triển khai và điều kiện nghiệm thu](docs/local-first-implementation-2026-09-28.md). Wake Hi ESP chạy trên chip khi có phân vùng/model; STT tiếng Việt/Anh và TTS chạy offline trên Android; Gemini chỉ dùng cho câu ngoài nhóm local. Chưa xác nhận bản mới đạt nghiệm thu trên kính thật.

TTS tiếng Việt mới: [model tích hợp trong app, audio xác nhận sẵn và Phát lại](docs/vietnamese-offline-tts.md). Không cần giọng Việt của Xiaomi/Google TTS; lỗi phát tiếng không làm mất kết quả ảnh/video/AI đã hoàn tất. Tiếng Anh giữ TTS hệ thống offline.

Ai-Vision là dự án kính AI sử dụng GOOUUU ESP32-S3-CAM với camera OV2640 và ứng dụng Android đồng hành. Kính là nơi người dùng tương tác bằng giọng nói; Android quản lý kết nối, media, xử lý phù hợp trên điện thoại và gọi cloud AI khi cần.

Trạng thái hiện tại: app Android và sketch Arduino trong `firmware/arduino/AiVisionGlasses/` dùng **TCP V2 duy nhất** sau BLE provisioning/hotspot. BLE → TCP → PING/Status/ảnh JPEG đã được thử trên điện thoại và ESP32-S3 thật. Loa, mic, video và backend AI còn cần nghiệm thu riêng; build thành công không chứng minh chất lượng âm thanh hoặc phần cứng.

Đường chạy/giới hạn hiện hành: [firmware Arduino](firmware/arduino/README.md), [giao thức V2](docs/protocol-v2-draft.md), [thiết lập](docs/setup.md). Các mục V1 bên dưới được giữ làm lịch sử kế hoạch, không phải hướng dẫn kết nối hiện tại.

## Kế hoạch V1 ban đầu (lịch sử)

- Kết nối, PING và GET_STATUS có timeout, xử lý lỗi và hủy tác vụ. TCP V1 là giao thức dòng tuần tự, chưa có requestId.
- Nhận wake word và lệnh đơn giản; chụp ảnh, bắt đầu/dừng quay và ghi âm.
- BLE phục vụ điều khiển và trạng thái; Wi-Fi local truyền media.
- Tác vụ local không gọi cloud khi không cần thiết.
- Câu hỏi cần hiểu ảnh: chụp ảnh mới → Android → cloud AI → câu trả lời → TTS.
- Hỗ trợ định hướng vi-VN/en-US; xác nhận riêng khả năng ngôn ngữ của model chạy trên kính.

Đây là mục tiêu lịch sử, không phải danh sách tính năng đã hoàn thành hoặc wire format đang chạy. Firmware hiện hành là sketch Arduino trong repository; `firmware/main` là khung ESP-IDF cũ.

## Phân công nhiệm vụ

Các nhãn Người 1, Người 2, Người 3 đại diện cho vai trò; nhóm điền tên người đảm nhiệm khi chốt nhân sự.

| Thành viên | Nhiệm vụ chính | Khu vực code | Đầu ra cần bàn giao |
|---|---|---|---|
| Người 1 — Firmware và kết nối | Môi trường ESP-IDF, boot/log, trạng thái firmware, BLE/Wi-Fi, parser command/event, timeout và reconnect | firmware/main; firmware/components/core, connectivity, protocol | Firmware build được; hướng dẫn nạp; lệnh và phản hồi đúng protocol; log kiểm thử kết nối |
| Người 2 — Phần cứng, media và AI local | Xác nhận board/pin, camera, mic, microSD, ghi âm/video, wake word và lệnh local | firmware/components/board, camera, audio, recorder, local_ai | API module và định dạng dữ liệu; hướng dẫn kiểm tra; số đo tài nguyên và kết quả trên board |
| Người 3 — Android, AI và tích hợp | UI/ViewModel, transport, command router, lưu media, voice trên app, cloud AI, TTS; tích hợp với firmware | android; backend khi tới mốc cloud; tools/device_simulator nếu cần | APK, hướng dẫn chạy, kiểm thử app và luồng đầu cuối; xử lý lỗi và khôi phục |

### File dùng chung

| File hoặc khu vực | Người phụ trách và review |
|---|---|
| docs/protocol.md | Người 1 và Người 3 cùng thống nhất; Người 2 review phần media/audio |
| docs/hardware.md | Người 2 cập nhật; Người 1 đối chiếu cấu hình firmware |
| docs/setup.md | Mỗi người ghi phần mình; người khác thử làm theo |
| docs/test-checklist.md và tests/integration | Cả nhóm bổ sung; Người 3 tổng hợp luồng đầu cuối |
| firmware/CMakeLists.txt, sdkconfig.defaults | Người 1 điều phối; Người 2 review thay đổi liên quan phần cứng, bộ nhớ và model |
| README.md | Nhóm cập nhật phân công, phạm vi và cách bắt đầu qua PR |

## Nguyên tắc làm việc và tránh ghi đè code

1. Mỗi đầu việc có một người phụ trách, phạm vi file và tiêu chí hoàn thành rõ ràng trước khi code.
2. Mỗi người dùng bản clone riêng và nhánh riêng. Không cùng sửa trực tiếp trên một thư mục làm việc được chia sẻ.
3. Không code, push hoặc force-push trực tiếp vào main. Main giữ bản đã review và kiểm thử; develop dùng tích hợp sau khi nhóm tạo và thống nhất sử dụng. Không coi nhánh snapshot là bản đã được nghiệm thu.
4. Tạo nhánh ngắn theo nhiệm vụ, ví dụ feature/android-get-status, feature/esp32-ble-ping, feature/camera-capture. Một PR tập trung vào một mục tiêu.
5. Trước khi sửa file do người khác phụ trách, trao đổi và thống nhất ai sửa. Không tự thay toàn bộ file, copy đè thư mục hoặc xóa code người khác để làm code của mình chạy.
6. Trước khi bắt đầu và trước khi gửi PR, cập nhật nhánh tích hợp. Lưu hoặc commit thay đổi đang làm trước khi đồng bộ; không dùng thao tác xóa thay đổi để giải quyết xung đột.
7. Khi gặp conflict, đọc cả hai thay đổi và phối hợp với tác giả. Không chọn toàn bộ ours/theirs nếu chưa hiểu tác động. Sau khi giải quyết phải build và kiểm tra lại.
8. Thay đổi protocol phải được Người 1 và Người 3 thống nhất trước khi merge. Khi đổi trường dữ liệu, tên command/event hoặc định dạng media, cập nhật hai phía, ví dụ và kiểm thử liên quan.
9. Không đổi tên package, cấu trúc thư mục, phiên bản công cụ hoặc format toàn dự án trong PR chức năng nếu chưa được nhóm thống nhất. Thay đổi nền tảng làm bằng PR riêng.
10. Không commit local.properties, cache, thư mục build, khóa API, mật khẩu Wi-Fi hoặc khóa ký ứng dụng. Mỗi máy tự cấu hình thông tin riêng.
11. Code đủ dùng cho mốc hiện tại. UI chỉ hiển thị/gửi yêu cầu; transport xử lý giao tiếp; camera, audio và AI có trách nhiệm riêng. Không thêm lớp hoặc tối ưu khi chưa có nhu cầu và số đo.
12. Mỗi PR cần ít nhất một thành viên khác review; thay đổi vùng của ai cần người đó review. Không merge khi còn lỗi build, kiểm thử bắt buộc thất bại hoặc chưa rõ ảnh hưởng tới luồng chung.

## Lịch làm việc hằng tuần

Lịch theo giờ Việt Nam; giờ cụ thể được nhóm thống nhất. README ghi quy trình, không tự tạo lịch nhắc hoặc gửi thông báo.

### Tối thứ Hai — Lên kế hoạch code cả tuần

Cả nhóm cùng:

1. Xem kết quả review Chủ nhật, lỗi còn lại và phụ thuộc giữa Android/firmware/media.
2. Chọn mục tiêu demo cuối tuần vừa sức; ưu tiên hoàn tất luồng đang làm.
3. Chia nhiệm vụ cho từng người: đầu ra, thư mục/file, nhánh, thời hạn và người review.
4. Chốt giao thức/API chung trước khi các bên code độc lập. Xác định file dùng chung để tránh sửa trùng.
5. Chốt các ca kiểm thử phải chạy vào Chủ nhật; ghi phần nào dùng giả lập, phần nào cần board.
6. Ghi kế hoạch bằng GitHub Issues hoặc tài liệu kế hoạch tuần để mọi người cùng theo dõi.

Mẫu một đầu việc:

| Mục | Nội dung cần điền |
|---|---|
| Mục tiêu | Chức năng hoặc lỗi cụ thể |
| Người thực hiện và reviewer | Ai viết, ai kiểm tra |
| Phạm vi | Thư mục/file được sửa và API liên quan |
| Nhánh | Tên nhánh của đầu việc |
| Phụ thuộc | Cần đầu ra của ai; dữ liệu mẫu nào |
| Tiêu chí đạt | Thao tác kiểm tra và kết quả mong đợi |
| Hạn bàn giao | Thời điểm gửi PR trước buổi review Chủ nhật |

### Trong tuần — Code và kiểm tra từng phần

- Làm trên nhánh riêng theo kế hoạch; cập nhật khi có thay đổi phạm vi hoặc bị chặn.
- Khi thay đổi ảnh hưởng người khác, báo ngay trong issue/PR, không chờ tới Chủ nhật.
- Chia commit nhỏ và có ý nghĩa. Trước PR, tự build và kiểm tra phần mình.
- PR ghi: đã thay đổi gì, vì sao, cách kiểm tra, kết quả và phần còn chưa kiểm chứng.
- Có thể review/merge PR đạt yêu cầu trong tuần; Chủ nhật là buổi kiểm tra tổng hợp.

### Chủ nhật — Review code và test luồng

1. Tác giả trình bày ngắn thay đổi và demo; reviewer kiểm tra diff, trách nhiệm module và protocol.
2. Chạy build của các phần đã triển khai. Kiểm thử trên bản tích hợp sau merge, không chỉ trên nhánh cá nhân.
3. Test luồng chính, lỗi, timeout, thao tác trùng, hủy, mất kết nối và kết nối lại theo chức năng đã có.
4. Ghi PASS/FAIL và môi trường thử: fake, điện thoại, máy ảo hay board thật. Thiếu phần cứng thì ghi chưa kiểm tra, không đánh dấu PASS.
5. Với lỗi FAIL: ghi cách tái hiện, người xử lý và mức độ ảnh hưởng. Chỉ đưa bản đã đạt kiểm tra thống nhất lên main.
6. Tổng kết mục tiêu đạt/chưa đạt và đưa phần còn lại vào kế hoạch tối thứ Hai tiếp theo.

## Tiêu chí hoàn thành một nhiệm vụ

- Code build được theo hướng dẫn và không phá chức năng liên quan.
- Đạt các ca kiểm thử đã chốt; có bằng chứng hoặc cách tái hiện kết quả.
- File tài liệu/protocol liên quan đã cập nhật.
- Có reviewer đồng ý, không còn xung đột chưa giải quyết.
- Ghi rõ giới hạn: chưa có board, chưa thử chạy nền, hoặc đang dùng dữ liệu giả lập.

## Mở và build Android

Trong Android Studio, mở thư mục android/, không mở thư mục gốc Ai-Vision như một Android project.

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

Package hiện tại là com.example.ai_vision. Tên com/team/smartglasses trong kế hoạch ban đầu chỉ là minh họa, chưa đổi package sang tên đó.

## Cấu trúc và tài liệu

- android/: ứng dụng Android Compose và kiểm thử.
- firmware/: mã firmware và các component.
- backend/: [backend AI tối giản](backend/README.md); giữ khóa Gemini ngoài APK.
- tools/device_simulator/: sẽ tạo khi cần trình giả lập độc lập.
- tests/integration/: sẽ tạo khi có luồng tích hợp.
- [Thiết lập môi trường](docs/setup.md).
- [Giao thức app và kính](docs/protocol.md).
- [Tài liệu giao việc ESP32 BLE/Wi-Fi/TCP](docs/giao_viec_esp32_ai_smart_glasses_v1.docx).
- [Thông tin phần cứng](docs/hardware.md).
- [Sơ đồ chân GOOUUU ESP32-S3-CAM](docs/so_do_chan_GOOUUU_ESP32_S3_CAM.docx).
- [Checklist kiểm thử](docs/test-checklist.md).
- [Kế hoạch chốt bốn chức năng wake local OCR và hỏi cảnh](docs/ke-hoach-bon-chuc-nang-2026-09-28.md).
- [Kế hoạch Word](docs/Ke_hoach_hoan_thien_4_chuc_nang_kinh_AI_28-09-2026.docx).

## Ưu tiên triển khai hiện tại

1. Người làm ESP32 xác nhận UUID, BLE write/notify và gói cấp Wi-Fi trong docs/protocol.md, hoặc gửi thông số firmware đang dùng để Android đổi theo.
2. Thử trên điện thoại và board thật: hotspot → BLE → nhận IP → TCP PING/GET_STATUS → CAPTURE/JPEG; ghi PASS/FAIL vào docs/test-checklist.md.
3. Kiểm tra lưu JPEG vào Gallery, mất kết nối và reconnect trên điện thoại thật.
4. Sau đó mới thử mic điện thoại → STT tiếng Việt offline → hiển thị câu nói và ánh xạ command.
5. Firmware xác nhận board GOOUUU, camera OV2640 và sơ đồ chân trước khi nghiệm thu media.

Mỗi tuần chọn một phần cụ thể trong các ưu tiên trên; không triển khai tất cả cùng lúc.
