#ifndef PublishDir
 #define PublishDir "..\artifacts\publish"
#endif
#ifndef OutputDir
 #define OutputDir "..\artifacts\installer"
#endif
[Setup]
AppId={{4A29944E-EBE0-47F8-92AD-B07288D04527}
AppName=Cinema iQ
AppVersion=0.1.0
AppPublisher=3hasnawi
AppPublisherURL=https://github.com/3hasnawi/Cinema-IQ-Releases
DefaultDirName={localappdata}\Programs\CinemaIQ
DefaultGroupName=Cinema iQ
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19041
UninstallDisplayIcon={app}\CinemaIQ.Windows.exe
SetupIconFile=..\src\CinemaIQ.Windows\Assets\brand.ico
OutputDir={#OutputDir}
OutputBaseFilename=Cinema-iQ-Windows-0.1.0-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{autoprograms}\Cinema iQ"; Filename: "{app}\CinemaIQ.Windows.exe"
Name: "{autodesktop}\Cinema iQ"; Filename: "{app}\CinemaIQ.Windows.exe"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked
[Run]
Filename: "{app}\CinemaIQ.Windows.exe"; Description: "Launch Cinema iQ"; Flags: nowait postinstall skipifsilent
