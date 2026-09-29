# Phần cứng AI Smart Glasses V1

Board được chọn: **GOOUUU ESP32-S3-CAM**, module ESP32-S3-WROOM-1 (schematic trong tài liệu ghi N8). Camera của dự án là **OV2640** theo thông tin nhóm cung cấp; board có cổng FPC 24 chân. Nguồn trên header: **3V3, 5V, GND**.

Nguồn tham chiếu trong dự án:

- [DOCX sơ đồ chân nguyên bản](so_do_chan_GOOUUU_ESP32_S3_CAM.docx)
- [Ảnh pinout trích từ DOCX](GOOUUU_ESP32_S3_CAM_pinout.jpg)

Các bảng dưới đây chép lại từ DOCX nhóm cung cấp. Chúng là **tham chiếu thiết kế**, chưa thay thế việc đối chiếu trực tiếp schematic/board trước khi đấu dây hoặc nạp firmware. Không tự đổi mapping camera theo một board ESP32-S3-CAM khác.

## Chân camera

| Tín hiệu camera | GPIO |
|---|---:|
| SIOD / SDA | 4 |
| SIOC / SCL | 5 |
| VSYNC | 6 |
| HREF | 7 |
| XCLK | 15 |
| D0 / Y2 | 11 |
| D1 / Y3 | 9 |
| D2 / Y4 | 8 |
| D3 / Y5 | 10 |
| D4 / Y6 | 12 |
| D5 / Y7 | 18 |
| D6 / Y8 | 17 |
| D7 / Y9 | 16 |
| PCLK | 13 |

Khi camera hoạt động, xem GPIO **4–13, 15–18** trong bảng trên là đã được sử dụng; không phân cho mic, loa hoặc cảm biến khác.

## Chân onboard và giao tiếp cần lưu ý

| Nhóm | GPIO / chức năng theo tài liệu |
|---|---|
| USB native | GPIO19 = D+, GPIO20 = D− |
| MicroSD | GPIO38 = CMD, GPIO39 = CLK, GPIO40 = DATA |
| PSRAM | GPIO35, GPIO36, GPIO37 theo pinout nhà sản xuất |
| BOOT | GPIO0 |
| LED WS2812 | GPIO48 |
| UART0 | GPIO43 = TX, GPIO44 = RX |
| JTAG | GPIO42 = MTMS, GPIO41 = MTDI, GPIO40 = MTDO, GPIO39 = MTCK; GPIO3 = JTAG_EN |
| Khác | GPIO45 = VSPI, GPIO46 = LOG; GPIO2 có LED ON |

GPIO39/40 xuất hiện ở cả SD và JTAG trong tài liệu: cần kiểm tra chế độ sử dụng trước khi bật hai chức năng này. Không lấy chân USB, PSRAM, BOOT hoặc camera làm GPIO mở rộng chỉ vì chúng có mặt trên header.

## Phân bổ I²S đề xuất cho prototype

Đây là **phương án đề xuất trong DOCX**, chưa phải chân cố định của board và chưa được xác nhận bằng phép thử thực tế.

| GPIO / nguồn | Vai trò | Nối tới |
|---|---|---|
| GPIO14 | I²S BCLK | INMP441 SCK + MAX98357A BCLK |
| GPIO21 | I²S WS / LRCLK | INMP441 WS + MAX98357A LRC |
| GPIO47 | Mic data IN | INMP441 SD → ESP32 |
| GPIO1 | Speaker data OUT | ESP32 → MAX98357A DIN |
| 3V3 | Nguồn mic | INMP441 VDD |
| 5V | Nguồn amplifier | MAX98357A VIN |
| GND | Mass chung | INMP441 GND + MAX98357A GND |

INMP441: nối L/R xuống GND để chọn kênh trái; không cấp 5 V vào VDD. Loa nối giữa SPK+ và SPK− của MAX98357A; không nối SPK− xuống GND.

## Việc cần xác nhận trên board

- Đối chiếu board revision và schematic thực tế với ảnh pinout trước khi hàn/nối dây.
- Xác nhận camera OV2640 hoạt động với mapping trên, PSRAM và microSD dùng đúng chân.
- Thử riêng mic/loa trên chân I²S đề xuất rồi mới tích hợp cùng camera, SD, BLE/Wi-Fi.
- Ghi kết quả đo và phiên bản firmware/board sau khi kiểm tra; hiện chưa đánh dấu các chân I²S đề xuất là đã nghiệm thu.