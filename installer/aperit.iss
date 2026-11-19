; The Windows installer for APERIT (Inno Setup 6). Built by `.\run.ps1 dist`, which passes the version:
;   ISCC.exe /DAppVersion=0.4.0 installer\aperit.iss   ->  dist\APERIT-Setup-0.4.0.exe
;
; It installs for the current user only (%LOCALAPPDATA%\Programs\APERIT), so it asks for no administrator
; rights. APERIT keeps its board, memory and keys in that folder (data\, vault\): uninstalling removes the
; program and leaves them, and installing a newer version over it keeps them.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{EC7C1672-AACA-4237-92FA-EA8FA0524B65}
AppName=APERIT
AppVersion={#AppVersion}
AppVerName=APERIT {#AppVersion}
AppPublisher=fidenit
AppPublisherURL=https://github.com/fidenit/aperit
AppSupportURL=https://github.com/fidenit/aperit/issues
AppUpdatesURL=https://github.com/fidenit/aperit/releases
VersionInfoVersion={#AppVersion}
VersionInfoProductName=APERIT
DefaultDirName={autopf}\APERIT
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\dist
OutputBaseFilename=APERIT-Setup-{#AppVersion}
SetupIconFile=..\assets\aperit.ico
UninstallDisplayIcon={app}\aperit.exe
UninstallDisplayName=APERIT
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
ShowLanguageDialog=auto

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\target\release\aperit.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\NOTICE"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\APERIT"; Filename: "{app}\aperit.exe"
Name: "{autodesktop}\APERIT"; Filename: "{app}\aperit.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\aperit.exe"; Description: "{cm:LaunchProgram,APERIT}"; Flags: nowait postinstall skipifsilent
