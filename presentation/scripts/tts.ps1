Add-Type -AssemblyName System.Speech
$root = "C:\WORK\IQOO_Finale\Sethu\presentation"
$texts = Get-Content "$root\scripts\narration.json" -Raw | ConvertFrom-Json
$syn = New-Object System.Speech.Synthesis.SpeechSynthesizer
$syn.SelectVoice("Microsoft Zira Desktop")
$syn.Rate = -1
$syn.Volume = 100
for ($i = 0; $i -lt $texts.Count; $i++) {
    $f = "$root\assets\audio\narr_$($i+1).wav"
    $syn.SetOutputToWaveFile($f)
    $syn.Speak($texts[$i])
    $syn.SetOutputToNull()
}
$syn.Dispose()
Get-ChildItem "$root\assets\audio\narr_*.wav" | Select-Object Name, Length
