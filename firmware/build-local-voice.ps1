param(
    [string]$ArduinoCli = "$env:LOCALAPPDATA/Programs/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe",
    [string]$EspTool = "$env:LOCALAPPDATA/Arduino15/packages/esp32/tools/esptool_py/5.3.1/esptool.exe",
    [switch]$IncludeExistingWakeModel
)
$ErrorActionPreference = 'Stop'
$firmwareBuild = Join-Path $PSScriptRoot 'build/voice-local'
$firmwareSketch = Join-Path $PSScriptRoot 'arduino/AiVisionGlasses'
$firmwareFqbn = 'esp32:esp32:esp32s3:FlashSize=16M,PartitionScheme=esp_sr_16,PSRAM=opi,FlashMode=qio'
if (!(Test-Path -LiteralPath $ArduinoCli) -or ($IncludeExistingWakeModel -and !(Test-Path -LiteralPath $EspTool))) {
    throw 'Install Arduino ESP32 3.3.11 / esptool 5.3.1, or supply -ArduinoCli and -EspTool paths.'
}
& $ArduinoCli compile --fqbn $firmwareFqbn --build-path $firmwareBuild --jobs 4 $firmwareSketch
if ($LASTEXITCODE -ne 0) { throw 'Firmware compilation failed' }
if (!$IncludeExistingWakeModel) {
    Write-Output 'Build only, firmware application image. No model merge, serial access, or flashing.'
    Write-Output 'Existing with-model.bin is not refreshed. Do not use an old full image for this build.'
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $firmwareBuild 'AiVisionGlasses.ino.bin')
    return
}
$firmwareModel = Join-Path $firmwareBuild 'srmodels.bin'
if (!(Test-Path -LiteralPath $firmwareModel)) { throw 'Missing srmodels.bin: ESP_SR packaging dependency was not discovered' }
# Arduino stock merged.bin/flash_args omit upload.extra_flags (the model).
# Build a separate full image explicitly including Hi ESP model at 0xC10000.
& $EspTool --chip esp32s3 merge-bin --output (Join-Path $firmwareBuild 'AiVisionGlasses.with-model.bin') --pad-to-size 16MB --flash-mode keep --flash-freq keep --flash-size keep `
    0x0 (Join-Path $firmwareBuild 'AiVisionGlasses.ino.bootloader.bin') `
    0x8000 (Join-Path $firmwareBuild 'AiVisionGlasses.ino.partitions.bin') `
    0xe000 (Join-Path $firmwareBuild 'boot_app0.bin') `
    0x10000 (Join-Path $firmwareBuild 'AiVisionGlasses.ino.bin') `
    0xC10000 $firmwareModel
if ($LASTEXITCODE -ne 0) { throw 'Firmware/model packaging failed' }
Write-Output 'Build only: no serial port is opened and nothing is flashed.'
Get-FileHash -Algorithm SHA256 -LiteralPath $firmwareModel,(Join-Path $firmwareBuild 'AiVisionGlasses.with-model.bin')
