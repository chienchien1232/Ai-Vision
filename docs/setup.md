# Setup — moi nguoi viet phan minh

## Nguoi 1: firmware core
```
idf.py set-target esp32s3
idf.py build flash monitor
```

## Nguoi 2: board/camera/audio
- Cau hinh chan trong `sdkconfig.defaults`
- Test chup + mic truoc khi tich hop

## Nguoi 3: android
- Mo `android/` bang Android Studio
- Cap quyen Camera, Mic, Bluetooth tren may that
