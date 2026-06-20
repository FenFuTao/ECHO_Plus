$c = [System.IO.File]::ReadAllText('app\src\main\res\layout\page_protocol.xml', [System.Text.Encoding]::UTF8)  
$c = $c.Replace('ZXBoZW1lcmFs','')  
[System.IO.File]::WriteAllText('app\src\main\res\layout\page_protocol.xml', $c, [System.Text.Encoding]::UTF8)  
$c2 = [System.IO.File]::ReadAllText('app\src\main\java\com\example\echo\MainActivity.kt', [System.Text.Encoding]::UTF8)  
$c2 = $c2.Replace('ZXBoZW1lcmFs','')  
[System.IO.File]::WriteAllText('app\src\main\java\com\example\echo\MainActivity.kt', $c2, [System.Text.Encoding]::UTF8)  
Write-Host DONE  
