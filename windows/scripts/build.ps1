param([switch]$Installer)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$output = Join-Path $root 'artifacts'
dotnet test "$root/tests/CinemaIQ.Tests" -c Release --nologo
if ($LASTEXITCODE) { throw 'Windows Core tests failed' }
& "$PSScriptRoot/configure.ps1"
dotnet publish "$root/src/CinemaIQ.Windows" -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -o "$output/publish" --nologo
if ($LASTEXITCODE) { throw 'Windows publish failed' }
foreach ($file in @('CinemaIQ.Windows.exe','coreclr.dll','libvlc/win-x64/libvlc.dll','libvlc/win-x64/libvlccore.dll','Assets/VideoLAN-LGPL-2.1.txt','Assets/Fonts/OFL.txt')) {
 if (-not (Test-Path (Join-Path "$output/publish" $file))) { throw "Missing packaged component: $file" }
}
if ($Installer) {
 $compiler = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6/ISCC.exe'
 if (-not (Test-Path $compiler)) { choco install innosetup --version=6.4.3 --yes --no-progress; if ($LASTEXITCODE) { throw 'Inno Setup installation failed' } }
 & $compiler "/DPublishDir=$output/publish" "/DOutputDir=$output/installer" "$root/installer/CinemaIQ.iss"
 if ($LASTEXITCODE) { throw 'Single EXE installer generation failed' }
 Get-FileHash "$output/installer/Cinema-iQ-Windows-0.1.0-Setup.exe" -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  Cinema-iQ-Windows-0.1.0-Setup.exe" } | Set-Content "$output/installer/SHA256SUMS.txt"
}
