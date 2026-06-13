# Discord Presence

A lightweight **NeoForge 1.21.1** server mod that posts a Discord message when a player
joins, then keeps it live with reactions:

- 📨 **On join** — posts `{player} started the game` to a Discord channel via a webhook.
- 🟢 **While online** — a bot adds an online reaction to that message…
- ⚪ **On logout** — …and removes it.
- 💀 **On death** — the bot adds a death reaction to the join message.

It hooks only vanilla player events, so it works on any NeoForge server. It's also bundled
into [Dungeon Train](https://github.com/bh679/dungeon-train-mc).

> **Roadmap:** two-way chat — relaying Discord replies back into in-game chat — is planned.
> The bot introduced here for reactions is the foundation for it.

## Why a webhook *and* a bot?

Discord **webhooks can post messages but cannot add reactions** — only a **bot token** can.
So Discord Presence uses the webhook to post and the bot to react. (The same bot will later
read replies for two-way chat, which webhooks fundamentally cannot do.)

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

Message-Content / privileged intents are **not** needed for reactions (they'll only be
required later for two-way chat).

### Config reference

| Key | Default | Meaning |
|---|---|---|
| `webhookUrl` | `""` | Webhook URL. Blank disables the mod. **Secret.** |
| `botToken` | `""` | Bot token for reactions. Blank → messages post without reactions. **Secret.** |
| `joinMessageTemplate` | `🎮 **{player}** started the game` | `{player}` → player name. |
| `onlineEmoji` | `🟢` | Reaction added while online, removed on logout. |
| `deathEmoji` | `💀` | Reaction added on death. |

> `discordpresence-server.toml` holds secrets and is **server-side only** (never sent to
> clients). Don't commit it.

## Build

```bash
./gradlew build        # -> build/libs/discordpresence-neoforge-<version>.jar
./gradlew runServer    # dev dedicated server
./gradlew runClient    # dev client (single-player integrated server)
```

## License

[PolyForm Shield 1.0.0](LICENSE) — © Brennan Hatton.
