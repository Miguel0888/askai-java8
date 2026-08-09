# AskAI als ChatGPT-Connector

AskAI kann — wie Pyloros — direkt als eigene App/Connector in ChatGPT eingebunden werden. Dafür
serviert AskAI selbst einen öffentlichen MCP-Endpoint samt OAuth-2.0-Server; TLS terminiert am
externen Apache-Reverse-Proxy.

## Architektur

```
ChatGPT ──HTTPS──> Apache (anderer Rechner, TLS)  ──HTTP──> AskAI :8082
                   https://askai.example.com            /oauth/authorize
                                                            /oauth/token
                                                            /.well-known/oauth-authorization-server
                                                            /.well-known/oauth-protected-resource
                                                            /askai   (MCP JSON-RPC, Bearer)
```

- **OAuth**: authorization_code + PKCE (S256, Kompatibilitätsmodus ohne Challenge), refresh_token-Grant.
  Redirect-URIs sind auf `chatgpt.com` / `chat.openai.com` beschränkt. Access-Token 1 h,
  Refresh-Token 30 Tage — persistiert in
  `<workspaces>/com.aresstack.askai.research/chatgpt-connector/oauth-refresh-tokens.json`,
  eine ChatGPT-Verbindung überlebt also AskAI-Neustarts.
- **MCP**: Protokoll 2025-03-26; `initialize`, `tools/list`, `tools/call` (POST JSON-RPC) und
  GET-SSE-Discovery — derselbe Hybridvertrag, den der bewährte Pyloros-Connector spricht.
- **Tools**: exakt die drei Driving-Tools der Bot-Steuerung — `run_command`, `session_state`,
  `chat_history` (siehe `research-bot-control.md`). Aufgelöst zur Laufzeit gegen die aktuelle
  produktive Research-Session; ohne Session liefern die Tools den MCP-Fehler "no session".

## Konfiguration (AskAI → Configuration → Runtime)

| Feld | Bedeutung |
|---|---|
| ChatGPT connector | Default **AUS** — öffentlicher Listener nur nach bewusster Entscheidung |
| Public origin | z. B. `https://askai.example.com` (wird in den OAuth-Metadaten annonciert) |
| Connector port | lokaler Klartext-HTTP-Port (Default 8082); der Proxy-Rechner muss ihn erreichen |
| Client-ID / Secret | das OAuth-Client-Paar, das auch in ChatGPT eingetragen wird |

Gilt für NEUE Sessions; der Listener ist app-weit (ein Port), Sessions docken ihr Gateway an.

## Apache-Delegation (auf dem TLS-Rechner)

```apache
<VirtualHost *:443>
    ServerName askai.example.com
    SSLEngine on
    # ... Zertifikat wie für current-car.com ...
    ProxyPreserveHost On
    ProxyPass        / http://<askai-rechner>:8082/
    ProxyPassReverse / http://<askai-rechner>:8082/
</VirtualHost>
```

Subdomain-DNS auf denselben Anschluss zeigen lassen; Firewall des AskAI-Rechners muss Port 8082
für den Proxy-Rechner öffnen.

## ChatGPT-Seite

Neuen Connector anlegen mit:

- **URL**: `https://askai.example.com/askai`
- **OAuth Client-ID / Client-Secret**: wie in den AskAI-Settings
- Authorization-/Token-Endpoint entdeckt ChatGPT selbst über `/.well-known/oauth-authorization-server`.

Danach erscheinen `run_command`, `session_state` und `chat_history` als Tools; der Workflow steht in
den Tool-Beschreibungen (erst `session_state`, dann `run_command`, Verlauf über `chat_history`).

## Sicherheit

- Der Listener spricht nur Klartext-HTTP — er gehört hinter den TLS-Proxy, nicht direkt ins Internet.
- Ohne vollständige Konfiguration (Origin + Client-Paar) startet der Listener nicht.
- Jeder MCP-Zugriff verlangt ein live ausgestelltes Bearer-Token; 401 trägt den
  `WWW-Authenticate`-Hinweis für die Re-Authentifizierung.
