# Protocol — Nguoi 1 + 3, Nguoi 2 review

Giao thuc giua Android va Firmware (BLE / Wi-Fi).

## Event tu kinh -> app
- `EVT:connected:<name>`
- `EVT:frame:<bytes>`
- `EVT:error:<msg>`

## Lenh tu app -> kinh
- `CMD:take_photo`
- `CMD:start_record:<path>`
- `CMD:stop_record`

Parser o `firmware/components/protocol/parser.cpp` phai khop tai lieu nay.
Android gui/nhan o `device/DeviceRepository.kt`.
