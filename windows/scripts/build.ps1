param([switch]$Installer)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$output = Join-Path $root 'artifacts'
dotnet test "$root/tests/CinemaIQ.Tests" -c Release --nologo
if ($LASTEXITCODE) { throw 'Windows Core tests failed' }
& "$PSScriptRoot/configure.ps1"
dotnet publish "$root/src/CinemaIQ.Windows" -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -o "$output/publish" --nologo
if ($LASTEXITCODE) { throw 'Windows publish failed' }
$assets = Get-Content -Raw "$root/src/CinemaIQ.Windows/obj/project.assets.json" | ConvertFrom-Json -AsHashtable
foreach ($library in $assets.libraries.Keys) {
 if ($library -match '^Microsoft\.(NETCore|WindowsDesktop)\.App\.Runtime\.win-x64/') {
  foreach ($packageRoot in $assets.packageFolders.Keys) {
   $package = Join-Path $packageRoot $library.ToLowerInvariant()
   if (Test-Path $package) {
    foreach ($notice in Get-ChildItem $package -File | Where-Object { $_.Name -match 'LICENSE|THIRD.?PARTY.?NOTICES' }) {
     Copy-Item $notice.FullName (Join-Path "$output/publish/Assets/Licenses" ($library.Split('/')[0] + '-' + $notice.Name)) -Force
    }
   }
  }
 }
}
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
