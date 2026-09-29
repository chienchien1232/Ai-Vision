param(
    [string]$InputDocx = 'D:/Project/Ai-Vision/docs/Ke_hoach_hoan_thien_4_chuc_nang_kinh_AI_28-09-2026.docx',
    [string]$OutputPdf = 'D:/Project/Ai-Vision/build/document-qa/four-feature-plan.pdf'
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $InputDocx).Path
$output = [IO.Path]::GetFullPath($OutputPdf)
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($output)) -Force | Out-Null
$word = $null
$document = $null
try {
    # Dedicated automation instance; never attach to the user's open documents.
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    $document = $word.Documents.Open($source, $false, $true, $false)
    $document.ExportAsFixedFormat($output, 17)
    Write-Output ('Word PDF exported: ' + $output)
} finally {
    if ($document) { $document.Close(0); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($document) }
    if ($word) { $word.Quit(0); [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) }
}
