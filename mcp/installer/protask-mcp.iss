; ProTask MCP Service - Inno Setup 向导式安装包脚本
; 编译：ISCC.exe installer\protask-mcp.iss   （或 npm run package-setup）
; 设计：Setup 向导收集后端地址/账号/密码 → 部署文件 → 复用 install.ps1 安装核心
;       （登录校验 / Node 运行时检测下载 / 凭证写入 / MCP 握手冒烟测试 / 生成配置片段）

#define MyAppName "ProTask MCP Service"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "ProTask"

[Setup]
AppId={{7B1E9C4A-52D8-4F63-9A21-C3E5D8F0B712}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
; 按用户安装（无需管理员权限），与 install.ps1 的安装目录保持一致
DefaultDirName={localappdata}\ProTaskMCP
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputBaseFilename=ProTaskMCP-Setup
OutputDir=output
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName={#MyAppName}
; 卸载时清理运行时产物（creds.json、配置片段、便携版 Node）
UninstallFilesDir={app}\.uninstall

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\dist-bundle\protask-mcp.mjs"; DestDir: "{app}"; Flags: ignoreversion
Source: "install.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "SETUP-README.txt"; DestDir: "{app}"; Flags: ignoreversion isreadme

[UninstallDelete]
Name: "{app}"; Type: filesandordirs

[Code]
var
  ConfigPage: TInputQueryWizardPage;

procedure InitializeWizard;
begin
  ConfigPage := CreateInputQueryPage(wpWelcome,
    'ProTask Connection',
    'Connect to your ProTask server',
    'Enter the ProTask backend address and your account. ' +
    'The installer will verify the login before installing.');
  ConfigPage.Add('Backend URL (e.g. http://192.168.1.10:8000)', False);
  ConfigPage.Add('Username', False);
  ConfigPage.Add('Password', True);
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = ConfigPage.ID then
  begin
    if Trim(ConfigPage.Values[0]) = '' then
    begin
      MsgBox('Backend URL is required.', mbError, MB_OK);
      Result := False;
    end
    else if Trim(ConfigPage.Values[1]) = '' then
    begin
      MsgBox('Username is required.', mbError, MB_OK);
      Result := False;
    end
    else if ConfigPage.Values[2] = '' then
    begin
      MsgBox('Password is required.', mbError, MB_OK);
      Result := False;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Cmd, ScriptPath: String;
  ResultCode: Integer;
begin
  if CurStep = ssPostInstall then
  begin
    WizardForm.StatusLabel.Caption := 'Configuring MCP service (login check, runtime, smoke test)...';
    ScriptPath := ExpandConstant('{app}\install.ps1');
    Cmd := Format('-NoProfile -ExecutionPolicy Bypass -File "%s" -BaseUrl "%s" -Username "%s" -Password "%s"', [
      ScriptPath,
      Trim(ConfigPage.Values[0]),
      Trim(ConfigPage.Values[1]),
      ConfigPage.Values[2]]);
    // 显示控制台窗口，让用户看到登录校验/冒烟测试进度
    if not Exec('powershell.exe', Cmd, '', SW_SHOW, ewWaitUntilTerminated, ResultCode) then
      MsgBox('Failed to launch the configuration script. Please run install.ps1 in the install directory manually.', mbError, MB_OK)
    else if ResultCode <> 0 then
      MsgBox('Configuration failed (login check or smoke test). You can rerun install.ps1 in the install directory to retry.', mbError, MB_OK);
  end;
end;

[Run]
Filename: "notepad.exe"; Parameters: """{app}\mcp-config-snippet.json"""; \
  Description: "View the AI Agent config snippet (mcp-config-snippet.json)"; \
  Flags: postinstall skipifsilent nowait shellexec
