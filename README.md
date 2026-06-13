# Discord Presence

A lightweight **NeoForge 1.21.1** server mod that posts a Discord message when a player
joins, then keeps it live with reactions:

- 📨 **On join** — posts `{player} started the game` to a Discord channel via a webhook.
- 🟢 **While online** — a bot adds an online reaction to that message…
- ⚪ **On logout** — …and removes it.
- 💀 **On death** — the bot adds a death reaction to the join message.
- 🔗 **Account linking** — players verify their Discord with `/discordpresence link`, so progress can be tied to a real Discord identity.

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

Message-Content / privileged intents are **not** needed for reactions — but the **Message
Content intent _is_ required for account linking** (see below), and the bot reading replies
for two-way chat will need it too.

### 3. Account linking (optional)

Let players prove they own both their Minecraft and Discord accounts, so progress can be
attributed to a real Discord identity.

1. **Enable the Message Content intent:** dev portal → your app → **Bot** → turn on
   **Message Content Intent**. (Free toggle for bots in <100 servers; Discord approval is
   required at 100+.) Without it the bot reads empty message text and codes never match.
2. **Pick a link channel**, copy its ID (Discord → Developer Mode on → right-click the channel
   → **Copy Channel ID**), and set it as `linkChannelId`.
3. **Permit the bot** in that channel: **View Channel**, **Read Message History**, and — if
   `deleteCodeMessage` is `true` — **Manage Messages**.

**How a player links:** run `/discordpresence link` in-game → copy the one-time code → post it
in the link channel within `linkCodeTtlMinutes`. The bot matches it, links the accounts, and
confirms in-game. `/discordpresence status` shows the current state; `/discordpresence unlink`
clears it. (Alias: `/dp`.)

### Config reference

| Key | Default | Meaning |
|---|---|---|
| `webhookUrl` | `""` | Webhook URL. Blank disables the mod. **Secret.** |
| `botToken` | `""` | Bot token for reactions. Blank → messages post without reactions. **Secret.** |
| `joinMessageTemplate` | `🎮 **{player}** started the game` | `{player}` → player name. |
| `onlineEmoji` | `🟢` | Reaction added while online, removed on logout. |
| `deathEmoji` | `💀` | Reaction added on death. |
| `linkChannelId` | `""` | Channel the bot polls for `/discordpresence link` codes. Blank disables linking. Needs the **Message Content intent**. |
| `linkCodeTtlMinutes` | `10` | How long a link code stays valid before expiring. |
| `deleteCodeMessage` | `true` | Delete the player's code message once matched (needs Manage Messages). |

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
