# Giao thức Ai-Vision V1

## Hợp đồng đang dùng: BLE cấp Wi-Fi, TCP truyền lệnh và ảnh

### Bổ sung V1 cho app Android (26/09/2026)

Tài liệu giao việc gốc không có UUID và định dạng bản tin BLE. App hiện triển khai **đề xuất tương thích Nordic UART** dưới đây; người làm firmware Arduino cần dùng đúng các giá trị này hoặc báo lại UUID/định dạng thực tế để sửa app trước khi nghiệm thu trên board.

| Thành phần | Giá trị |
|---|---|
| BLE advertise name | ASCII `Ai-Vision Glasses` |
| Service UUID | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| Android → ESP32, write with response | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| ESP32 → Android, notify | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| Android gửi Wi-Fi | `WIFI|<base64url(UTF-8 SSID)>|<base64url(UTF-8 password)>\n` |
| ESP32 báo Wi-Fi thành công | `WIFI_CONNECTED|<IPv4>|5000`, LF tùy chọn |
| ESP32 báo lỗi qua BLE | `ERROR|<mã lỗi>\n` |

BLE là luồng byte: app yêu cầu MTU 185 rồi chia gói ghi thành các đoạn tối đa `MTU-3` byte, chờ callback thành công trước đoạn tiếp theo. Firmware phải nối các đoạn đến LF mới giải mã UTF-8/Base64 URL-safe; thông báo ngược có thể chia thành nhiều notify. Không log SSID/password. App bật local-only hotspot do Android cấp SSID/password, không nhập IP thủ công. Local-only hotspot không cấp Internet cho kính.

Sau `WIFI_CONNECTED`, app mở một TCP socket tới IP được báo. TCP dùng UTF-8 và LF, giữ socket qua nhiều lệnh, chỉ gửi một lệnh tại một thời điểm. App giới hạn header 128 byte, JPEG 5 MiB, đọc đúng `size` byte rồi mới đọc lệnh kế. Lỗi TCP hoặc phản hồi sai đóng socket; người dùng bấm Connect để tạo phiên mới.

Trạng thái kiểm chứng: Android build được; TCP được thử với server giả lập trong unit test. BLE/hotspot và camera thật **chưa được thử trên board**, nên UUID và payload trên chưa được coi là hợp đồng đã xác nhận từ firmware.

[Nguồn chốt giữa Android và ESP32](giao_viec_esp32_ai_smart_glasses_v1.docx). BLE dùng để tìm kính, cấp cấu hình hotspot và nhận địa chỉ; Android không nhập IP ESP32 thủ công và không dùng `localhost` để kết nối chip.

1. ESP32 quảng bá BLE tên `Ai-Vision Glasses`; Android kết nối BLE.
2. Điện thoại bật hotspot; Android gửi SSID/password hotspot cho ESP32 qua BLE.
3. ESP32 vào hotspot, báo qua BLE `WIFI_CONNECTED|<IP>|5000`.
4. Android dùng IP/port vừa nhận để mở TCP; gửi `PING\n`, nhận `PONG\n` (chấp nhận CRLF ở phản hồi), giữ socket cho các lệnh tiếp theo.
5. Bước ảnh sau này: `CAPTURE\n` → `IMAGE|<size>|<width>|<height>|JPEG\n` + đúng `<size>` byte JPEG. Lỗi chụp: `ERROR|CAPTURE_FAILED\n`. Đọc header đến LF rồi đọc đúng số byte; TCP không có ranh giới gói.

Các giá trị BLE ở bảng là đề xuất của app vì tài liệu nguồn chưa quy định. `GET_STATUS\n` → `STATUS|WIFI=1|RSSI=-45\n` đã có parser ở app; firmware cần trả đúng hai trường này.

Các mục JSON bên dưới là **đề xuất cũ**, không phải wire format của firmware Arduino hiện tại. Không gửi JSON tới TCP server chỉ hiểu lệnh dạng dòng.

## Phụ lục: bản nháp JSON cũ

Tài liệu dùng chung cho Android và firmware GOOUUU ESP32-S3-CAM. Phạm vi đầu tiên: kết nối, PING và GET_STATUS. Người viết firmware thực hiện xử lý thiết bị; Android gửi yêu cầu, kiểm tra phản hồi và hiển thị.

## 1 Trạng thái triển khai

- Bản model Kotlin/FakeGlassTransport/ViewModel được mô tả dưới đây đã bị gỡ khi Android được đưa về khung trống; không còn là trạng thái app hiện tại.
- Chưa có: serializer JSON, decoder lỗi, BLE/Wi-Fi và firmware giao tiếp thật.
- Phần JSON và truyền dữ liệu dưới đây là hợp đồng đề xuất để triển khai tiếp, không phải tính năng đã chạy trên app.
- Nguồn model: android/app/src/main/java/com/example/ai_vision/device/protocol/.

## 2 Model của bản nháp cũ

| Model | Thuộc tính |
|---|---|
| GlassCommand | requestId: String; name: CommandName |
| GlassEvent | requestId: String; name: EventName; status: GlassStatus? = null |
| GlassStatus | deviceName: String; firmwareVersion: String |

| Command | Event thành công | Dữ liệu |
|---|---|---|
| PING | Pong | status bỏ qua hoặc null |
| GET_STATUS | Status | status bắt buộc có deviceName và firmwareVersion |

Tên phân biệt chữ hoa/chữ thường: giữ PING, GET_STATUS, Pong, Status đúng code hiện tại. Nếu đổi, cập nhật cả hai phía và kiểm thử cùng lúc. Connect/disconnect là thao tác quản lý kết nối, không phải command JSON.

## 3 Luồng và requestId

```text
UI → ViewModel → GlassTransport → Firmware
                                   ↓
UI ← ViewModel ← GlassTransport ← Phản hồi
```

1. App tạo UUID mới cho mỗi yêu cầu, dạng chuỗi 36 ký tự có dấu gạch nối.
2. Firmware sao chép nguyên requestId vào phản hồi; không tạo mã mới.
3. App chỉ cho một command đang chờ tại một thời điểm.
4. App chỉ báo thành công khi mã và loại phản hồi khớp yêu cầu. Status phải có dữ liệu đầy đủ.
5. Phản hồi đến sau timeout/hủy hoặc khi không có yêu cầu đang chờ được bỏ qua.
6. Khi có transport mạng, transport lọc phản hồi cũ theo requestId trước khi trả cho ViewModel. Nếu transport vẫn trả nhầm event, ViewModel hiện sẽ báo Invalid response.

## 4 Định dạng JSON V1 dự kiến

UTF-8 không BOM, mỗi bản tin là một JSON object. v và kind thuộc lớp truyền dữ liệu, chưa có trong model Kotlin; serializer/decoder phải thêm và kiểm tra chúng.

### Command

| Trường | Kiểu | Quy định |
|---|---|---|
| v | Integer | Bắt buộc, bằng 1 |
| kind | String | Bắt buộc, bằng command |
| requestId | String | Bắt buộc, UUID hợp lệ |
| name | String | PING hoặc GET_STATUS |

Hai lệnh hiện chưa có tham số. Không cần payload rỗng.

### Event thành công

| Trường | Kiểu | Quy định |
|---|---|---|
| v | Integer | Bằng 1 |
| kind | String | Bằng event |
| requestId | String | Giống yêu cầu |
| name | String | Pong hoặc Status |
| status | Object hoặc null | Status cần object; Pong bỏ qua hoặc null |

Trong status, deviceName và firmwareVersion là chuỗi không rỗng. Không trả giá trị đo giả như pin 100% nếu phần cứng chưa đo được.

### PING

App gửi:
```json
{"v":1,"kind":"command","requestId":"123e4567-e89b-42d3-a456-426614174000","name":"PING"}
```
Firmware trả:
```json
{"v":1,"kind":"event","requestId":"123e4567-e89b-42d3-a456-426614174000","name":"Pong","status":null}
```

### GET_STATUS

App gửi:
```json
{"v":1,"kind":"command","requestId":"123e4567-e89b-42d3-a456-426614174001","name":"GET_STATUS"}
```
Firmware trả, với tên và phiên bản minh họa:
```json
{"v":1,"kind":"event","requestId":"123e4567-e89b-42d3-a456-426614174001","name":"Status","status":{"deviceName":"GOOUUU ESP32-S3-CAM","firmwareVersion":"0.1.0"}}
```
Fake Android hiện dùng FakeGlass và fake-1.0. Đây không phải thông tin đã đọc từ chip.

## 5 Lỗi

Hợp đồng lỗi này chưa có trong enum EventName. Decoder transport thật nhận kind = error và chuyển thành exception có mã/thông báo để ViewModel hiển thị; không ánh xạ lỗi thành Pong hoặc Status.

```json
{"v":1,"kind":"error","requestId":"123e4567-e89b-42d3-a456-426614174001","code":"UNKNOWN_COMMAND","message":"Command is not supported"}
```

| Mã | Ý nghĩa |
|---|---|
| INVALID_REQUEST | JSON và requestId đọc được nhưng thiếu trường hoặc sai kiểu/giá trị |
| UNSUPPORTED_VERSION | v khác 1 |
| UNKNOWN_COMMAND | Tên lệnh chưa hỗ trợ |
| BUSY | Firmware đang xử lý yêu cầu khác |
| INTERNAL_ERROR | Lỗi xử lý nội bộ |

Nếu JSON hỏng hoặc thiếu requestId hợp lệ: bỏ bản tin, ghi log; không bịa mã phản hồi. App kết thúc chờ bằng timeout. Chấp nhận trường bổ sung chưa dùng nhưng vẫn kiểm tra các trường bắt buộc. Không phụ thuộc thứ tự khóa JSON.

Phản hồi có đúng mã nhưng sai event hoặc thiếu dữ liệu Status dẫn tới Invalid response. Không cập nhật glassStatus bằng dữ liệu không hợp lệ. Lỗi command không tự đồng nghĩa thiết bị mất kết nối; transport phải xác nhận trạng thái liên kết.

## 6 Timeout hủy và thử lại

| Thao tác | Giới hạn hiện tại | Hết hạn |
|---|---|---|
| connect | 5 giây | Dọn kết nối, DeviceState.Error, cho Retry |
| send | 3 giây | ActionState.Error, không báo Success |

Khi tích hợp BLE, connect chỉ thành công khi gửi lệnh và nhận event được, không chỉ khi vừa mở liên kết. Giới hạn 5 giây là cấu hình khởi đầu cần đo trên phần cứng. Timeout send bao gồm gửi và chờ phản hồi.

- TimeoutCancellationException phải được bắt trước CancellationException: timeout báo lỗi; hủy thông thường được ném tiếp để kết thúc coroutine.
- Disconnect hủy connectionJob và commandJob trước; ngắt transport; đặt Disconnected + Idle; xóa glassStatus.
- Hủy chờ ở app không bảo đảm firmware đã dừng xử lý. Mốc này chưa có CANCEL; phản hồi phiên đã hủy phải bị bỏ qua.
- Không tự retry. Người dùng thử lại thì tạo requestId mới.
- PING và GET_STATUS là thao tác đọc, có thể thực hiện lại. Trước khi thêm lệnh chụp/quay/ghi, phải thiết kế chống thực thi trùng nếu muốn retry tự động.

## 7 Đóng gói bản tin khi triển khai BLE

Đề xuất cho bước BLE, chưa áp dụng trong FakeGlassTransport:

- Mỗi JSON truyền trên một dòng, kết thúc bằng LF (byte 0x0A); không pretty-print trên đường truyền.
- Bên nhận gom byte tới LF rồi mới giải mã UTF-8 và JSON. Một callback BLE không nhất thiết chứa trọn JSON.
- Chia bản tin thành chunk theo dung lượng GATT thực tế; gửi theo thứ tự, không xen kẽ bản tin trên cùng chiều truyền. Các thao tác ghi GATT phải được xếp tuần tự.
- Giới hạn mỗi bản tin 4096 byte, không tính LF. Khi quá giới hạn, bỏ tới LF kế tiếp rồi bắt đầu lại.
- Bản tin dở dang quá 3 giây từ byte đầu: reset kết nối và buffer để tránh ghép dữ liệu cũ vào bản tin mới. Xóa buffer khi disconnect.
- Không truyền ảnh/video/audio trong các bản tin điều khiển này.

Trước khi code BLE, cần chốt service UUID, command characteristic, response characteristic, kiểu write/notify hoặc indicate và trình tự đăng ký nhận dữ liệu. Chưa chọn các giá trị này trong tài liệu hiện tại; phải cập nhật tại đây khi hai phía bắt đầu BLE.

## 8 Checklist nghiệm thu

| Ca thử | Kết quả |
|---|---|
| PING | Pong cùng requestId |
| GET_STATUS | Status có tên thiết bị và phiên bản |
| Sai requestId | Không báo thành công cho yêu cầu đang chờ |
| Thiếu status | Invalid response |
| Quá 3 giây | Timeout; bỏ phản hồi muộn |
| Disconnect đang chờ | Không có kết quả cũ; xóa thông tin thiết bị |
| Retry sau lỗi kết nối | Bắt đầu phiên mới được |
| Command không hỗ trợ | Firmware trả UNKNOWN_COMMAND |
| JSON chia nhiều chunk | Chỉ xử lý khi ghép đủ |
| JSON hỏng/quá dài | Không crash hoặc tràn buffer |

Các ca JSON/BLE chỉ đánh dấu đạt sau khi có serializer và firmware thật. Không dùng kiểm thử fake để nghiệm thu phần cứng.

## 9 Thứ tự triển khai

1. Hoàn tất GET_STATUS và hiển thị thông tin trên Android.
2. Viết serializer/decoder; kiểm thử ví dụ, lỗi, null và sai phiên bản.
3. Chốt UUID và đặc tính GATT ở mục 7.
4. Firmware triển khai parser, PING, GET_STATUS.
5. Android triển khai BleTransport và ghép/tách bản tin.
6. Kiểm tra đầu cuối trên board trước khi mở rộng media.

Trong thời gian chưa có board, bản thử mic/camera điện thoại tiếp tục dùng model command/event và adapter riêng. Không bắt buộc đi qua JSON chỉ để gọi xử lý local trong cùng app.

## Bản TCP thử nghiệm PING trên ESP32

Android đã nối WifiGlassTransport với màn hình qua luồng hotspot/BLE. IP phải lấy từ thông báo BLE `WIFI_CONNECTED|<IP>|5000`; không nhập IP thủ công hay dùng localhost khi chạy trên điện thoại.

- Wire format hiện tại: UTF-8, Android gửi PING + LF; ESP32 trả PONG + LF. Cũng chấp nhận PONG + CRLF.
- Giữ cùng socket cho nhiều lần PING; firmware không được đóng ngay sau accept.
- Đây là bước thử TCP trước JSON V1 ở các mục trên. Không gửi JSON tới firmware chỉ hỗ trợ PING dạng dòng.
- Bản TCP dòng hiện tại không có requestId trong PING/PONG. App chỉ gửi một lệnh và chờ một phản hồi trên socket tại một thời điểm.
- Sau timeout, EOF, phản hồi sai hoặc lỗi I/O, socket bị đóng để không nhận nhầm phản hồi muộn; bấm Retry để kết nối lại.
- I/O chạy ngoài main thread. WifiGlassTransport hiện dùng connect timeout 4 giây và read timeout 2.5 giây; ViewModel chưa được nối vào transport.
- WifiGlassTransport.disconnect đóng socket; phần hủy tác vụ ViewModel/onCleared sẽ bổ sung khi nối UI.
- GET_STATUS và CAPTURE đã có ở Android; chỉ đánh dấu hoạt động trên kính khi firmware trả đúng định dạng và đã thử trên board.
- Chưa có heartbeat hay tự reconnect; mất mạng khi đang rảnh được phát hiện ở lần PING tiếp theo. Có thể bấm Disconnect để kết thúc ngay.

Cách thử sau khi BLE provisioning hoàn chỉnh: bật hotspot → BLE tìm kính và gửi Wi-Fi config → nhận IP từ ESP32 → TCP Connect → PING nhiều lần → Disconnect → Connect lại. Thử mất hotspot, tắt ESP32 khi đang PING và firmware trả sai dòng. Không dùng GET_STATUS để nghiệm thu ở bước này.
