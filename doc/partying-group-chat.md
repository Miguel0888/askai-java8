# Partying — decentralized LAN group chat

`Partying` is the third interaction mode next to `Yapping` (direct bot chat) and `Questing`
(agent work). It reuses the existing chat transcript and composer and routes submissions to a
group-chat session instead of Ollama.

```text
💬 Yapping   → direct chat with the bot
🗺 Questing  → agent-driven work
👥 Partying  → decentralized group chat with people and bots
```

## Module split

```text
group-chat-api      domain objects and transport ports (no Swing, no JGroups)
group-chat-jgroups  discovery, membership, encryption and the room protocol (JGroups 4.2.x)
askai-app           mode routing, party session, mentions, colors, settings
```

The public API (`GroupChatTransport`, `GroupChatListener`, …) never exposes JGroups types.

## Discovery and security

- All AskAI instances on the same LAN find each other via UDP multicast — no central server.
- When multicast is blocked, disable automatic discovery in Settings and list manual peer
  addresses (`host` or `host:port`); the transport then uses TCP with explicit peers.
- Joining a room is authenticated with the room secret; traffic is encrypted with a key derived
  from that secret (PBKDF2 → AES). Knowing only the cluster/room name does not permit joining.
- The participant identity is a locally persisted UUID plus profile (display name, preferred
  color, bot capability). IP addresses are never used as identity.

## Mentions and the bot

- `@` in the composer completes current participants and the logical room bot `@AskAI`.
- Human mentions only mark/notify the addressed participant; they never invoke the bot.
- The bot answers only when explicitly mentioned (default policy; it can be turned off).
- Several peers may be able to host the bot. For each addressed message, one host is elected
  deterministically, publishes a claim before running the model, and exactly one logical
  response is accepted per addressed message — duplicates from partitions or races are
  discarded. If the elected host disappears, the remaining peers elect a replacement.
- The model request runs through the existing AskAI chat runtime (`OllamaService`), not through
  a direct group-chat → Ollama coupling.

## Colors

Participants render with the same logical color on every peer. The palette is versioned and has
fixed light/dark variants; preferences are honored when the color is free, collisions resolve
deterministically (sorted participant IDs), and a departed participant's color stays reserved
for a short lease so a quick rejoin keeps it.

## History — known limitation

Each client keeps a local append-only room log. On join/reconnect, missing messages are
requested from reachable peers and deduplicated by message ID.

**Unavoidable limitation:** there is no server. If no currently reachable participant has an
old message and the joining client has no local copy, that history cannot be reconstructed.

## Troubleshooting

Settings → Partying → *Network diagnostics* writes a report about multicast-capable network
interfaces to the Technical details panel. Typical issues: Windows firewall prompts (allow Java
on private networks), VPN adapters grabbing the default route, and Wi-Fi networks with client
isolation. When multicast cannot be made to work, use manual peers.
