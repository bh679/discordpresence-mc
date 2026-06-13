# Discord Presence

A lightweight **NeoForge 1.21.1** server mod that posts a Discord message when a player
joins, then keeps it live with reactions:

- 📨 **On join** — posts `{player} started the game` to a Discord channel via a webhook.
- 🟢 **While online** — a bot adds an online reaction to that message…
- ⚪ **On logout** — …and removes it.
- 💀 **On death** — the bot adds a death reaction to the join message.
- 💬 **Two-way chat** — in-game chat relays to Discord under each player's name, and Discord
  **replies (or thread messages) on a player's message** relay back into the game.

It hooks only vanilla player events, so it works on any NeoForge server — and in singleplayer.
It's also bundled into [Dungeon Train](https://github.com/bh679/dungeon-train-mc).

## Why a webhook *and* a bot?

Discord **webhooks can post messages but cannot add reactions** — only a **bot token** can.
So Discord Presence uses the webhook to post and the bot to react. The same bot also opens a
**gateway** (WebSocket) connection to read Discord replies for two-way chat — something webhooks
fundamentally cannot do. All with zero external dependencies: just the JDK plus NeoForge's Gson.

## Setup

All settings live in `config/discordpresence-server.toml` (created on first server start).
The mod is **off until a webhook URL is set**.

### 1. Create a webhook
Server Settings → Integrations → Webhooks → New Webhook → pick a channel → **Copy Webhook URL**.
Put it in `webhookUrl`.

### 2. Create a bot (for the reactions)
1. <https://discord.com/developers/applications> → **New Application**.
2. **Bot** → **Reset Token** → copy it into `botToken`.
3. **OAuth2 → URL Generator** → scope `bot`, permissions **View Channel**, **Read Message
   History**, **Add Reactions** → open the URL → add the bot to the **same server** as the
   webhook's channel.

Reactions need no privileged intents. **Two-way chat does** — see below.

### 3. Enable two-way chat (optional)
1. **Developer Portal → your app → Bot → Privileged Gateway Intents** → turn on **MESSAGE
   CONTENT INTENT**. Without it, relayed Discord messages arrive blank (the mod logs a one-time
   warning and the gateway refuses to connect with close code `4014`).
2. Leave `relayDiscordToGame` / `relayGameToDiscord` on (the defaults).
3. **Singleplayer / LAN:** the first launch shows a one-time title-screen prompt to enable
   network features — choose **Enable**. Dedicated servers skip this (on by default). Change it
   anytime via `networkConsent` in `discordpresence-client.toml`.

Discord → game is **targeted**: a Discord message relays in-game only when it **replies to**, or
is posted in a **thread started from**, a message the mod posted for a player (the join notice or
a relayed chat line). General channel chatter is ignored, and the bot's/webhook's own messages
are never echoed back.

### Config reference

| Key | Default | Meaning |
|---|---|---|
| `webhookUrl` | `""` | Webhook URL. Blank disables the mod. **Secret.** |
| `botToken` | `""` | Bot token for reactions. Blank → messages post without reactions. **Secret.** |
| `joinMessageTemplate` | `🎮 **{player}** started the game` | `{player}` → player name. |
| `onlineEmoji` | `🟢` | Reaction added while online, removed on logout. |
| `deathEmoji` | `💀` | Reaction added on death. |
| `relayDiscordToGame` | `true` | Relay Discord replies/threads into in-game chat (needs the bot token + Message Content intent). |
| `relayGameToDiscord` | `true` | Relay in-game chat to Discord through the webhook. |
| `discordToGameFormat` | `<{user}> {msg}` | How a relayed Discord message reads in-game. `{user}` = author, `{msg}` = text. |

> `discordpresence-server.toml` holds secrets and is **server-side only** (never sent to
> clients). Don't commit it. A separate `discordpresence-client.toml` stores only your one-time
> network-access choice (`networkConsent`) — no secrets.

## Build

```bash
./gradlew build        # -> build/libs/discordpresence-neoforge-<version>.jar
./gradlew runServer    # dev dedicated server
./gradlew runClient    # dev client (single-player integrated server)
```

## License

[PolyForm Shield 1.0.0](LICENSE) — © Brennan Hatton.
