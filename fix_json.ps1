$files = @(
  "Y:\AppData\Android_Studio\projects\ECHO+\app\src\main\java\com\example\echo\MainActivity.kt",
  "Y:\AppData\Android_Studio\projects\ECHO+\app\src\main\res\layout\page_protocol.xml",
  "Y:\AppData\Android_Studio\projects\ECHO+\app\src\main\res\values\strings.xml",
  "Y:\AppData\Android_Studio\projects\ECHO+\app\src\main\res\drawable\bg_decoration.xml"
)
foreach ($f in $files) {
  $bytes = [System.IO.File]::ReadAllBytes($f)
  $txt = [System.Text.Encoding]::UTF8.GetString($bytes)
  $origLen = $bytes.Length
  $jsonIdx = $txt.IndexOf('{$mid')
  if ($jsonIdx -ge 0) {
    $clean = $txt.Substring(0, $jsonIdx).TrimEnd()
    $cleanBytes = [System.Text.Encoding]::UTF8.GetBytes($clean)
    [System.IO.File]::WriteAllBytes($f, $cleanBytes)
    Write-Host "Fixed $f : $origLen -> $($cleanBytes.Length)"
  } else {
    Write-Host "Clean $f"
  }
}
