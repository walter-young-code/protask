==========================================================
  ProTask MCP Service - Setup Package
==========================================================

[What this setup does]
1. Installs the MCP service (protask-mcp.mjs) to your user profile
   (%LOCALAPPDATA%\ProTaskMCP). No administrator rights required.
2. Wizard page "ProTask Connection": enter your backend URL,
   username and password. The installer verifies the login.
3. After files are copied, the installer automatically:
   - detects Node.js >= 18, or downloads a portable runtime
     (you do NOT need to install Node yourself)
   - writes the credential file (creds.json)
   - runs an MCP handshake smoke test
   - generates the AI Agent config snippet

[Connect your AI Agent]
When setup finishes, check "View the AI Agent config snippet" to open
mcp-config-snippet.json. Paste its content into your AI Agent's MCP
settings. The snippet is also kept in the install directory.

[Uninstall]
Use Windows Settings > Apps > ProTask MCP Service > Uninstall.
Everything (service files, credentials, portable runtime) is removed.

[Notes]
- The service runs via stdio: your AI Agent launches it on demand.
  It is NOT a resident process, opens no ports, and does not start
  with Windows.
- All writes pass through the format guard to prevent polluted data.
