$ErrorActionPreference = "Stop"
$root = "C:\WORK\IQOO_Finale\Sethu\presentation"
$out = "$root\assets\render"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $out -Filter *.png | Remove-Item -Force
$ppt = New-Object -ComObject PowerPoint.Application
try {
    $pres = $ppt.Presentations.Open("$root\Sethu_Presentation.pptx", $true, $false, $false)
    $pres.Export($out, "PNG", 1920, 1080)
    "slides: " + $pres.Slides.Count
    $pres.Close()
} finally {
    $ppt.Quit()
}
Get-ChildItem $out -Filter *.png | Select-Object Name, Length
