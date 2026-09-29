param(
    [string]$Port = 'COM6',
    [int]$Seconds = 20,
    [switch]$ResetBoard
)
$ErrorActionPreference = 'Stop'
if ($Seconds -lt 1 -or $Seconds -gt 60) { throw 'Serial capture must be between 1 and 60 seconds' }
$serialLink = [System.IO.Ports.SerialPort]::new($Port, 115200, [System.IO.Ports.Parity]::None, 8, [System.IO.Ports.StopBits]::One)
$serialLink.ReadTimeout = 250
$serialLink.DtrEnable = $false
$serialLink.RtsEnable = $false
try {
    $serialLink.Open()
    if ($ResetBoard) {
        $serialLink.RtsEnable = $true
        Start-Sleep -Milliseconds 100
        $serialLink.RtsEnable = $false
    }
    $serialDeadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $serialDeadline) {
        try { Write-Output $serialLink.ReadLine().TrimEnd([char]13) }
        catch [System.TimeoutException] { }
    }
} finally {
    if ($serialLink.IsOpen) { $serialLink.Close() }
    $serialLink.Dispose()
}
