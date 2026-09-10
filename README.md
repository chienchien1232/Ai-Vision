# Ai-Vision

Kinh AI: ESP32-S3 Sense + Android + Backend (moc AI).

## Cau truc
- `android/` — [Nguoi 3] App: UI, core (Command/Event/State/Router), device (BLE/Wi-Fi/Fake), media, voice (STT), ai, speech (TTS)
- `firmware/` — ESP-IDF
  - `core, connectivity, protocol` — [Nguoi 1]
  - `board, camera, audio, recorder, local_ai` — [Nguoi 2]
  - `main/app_main.cpp` — [Nguoi 1 tich hop], `sdkconfig.defaults` — [Nguoi 1, Nguoi 2 review]
- `backend/` — [Nguoi 3, tao o moc AI]
- `tools/device_simulator/` — [Nguoi 3, chi khi can]
- `tests/integration/` — [Nguoi 3 khoi tao, ca nhom gop case]
- `docs/` — protocol [1+3], hardware [2], setup [moi nguoi], test-checklist [ca nhom]

## Bat dau
Xem `docs/setup.md`, `docs/protocol.md`, `docs/hardware.md`.
