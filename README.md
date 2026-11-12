# ServerStatus

A Paper plugin that publishes and periodically updates a Discord embed with the
Minecraft server's status. The embed includes the MOTD, online player count and
names, average and highest online-player ping, TPS, server version, and uptime.
It does not report memory or disk usage.

## Requirements

- Paper 1.21.4 or newer
- Java 21 or newer
- A Discord bot with access to the target text channel

## Build

Run `./gradlew build` (or `gradle build`). The shaded plugin jar is created at
`build/libs/server-status-1.0.0.jar`.

## Configure

1. Create a bot in the Discord Developer Portal and invite it to your server.
   Grant it **View Channel**, **Send Messages**, and **Embed Links** permissions.
2. Start the Paper server once with the plugin installed, then stop it.
3. Edit `plugins/ServerStatus/config.yml`:
   - `discord.bot-token`: the bot token
   - `discord.channel-id`: the ID of the target text channel
   - `discord.update-interval-seconds`: update interval, at least 15 seconds
4. Start the server. The plugin sends the first embed and stores its message ID
   so later updates edit that message instead of creating duplicates.

Keep the bot token private and do not commit the generated config file. To
replace the status message, clear `discord.message-id` and restart the server.

## Configure the embed

All embed content and presentation options are under `discord.embed`:

- Set the title, clickable title URL, description, `#RRGGBB` color, online text,
  starting/offline text and colors, timestamp, author, footer, image, and
  thumbnail.
- Use `field-order` to reorder or omit fields. Each entry is configured under
  `fields`; set `enabled: false` to hide it, or add your own field with a name,
  value template, and inline setting.
- Templates support `{status}`, `{player_count}`, `{players}`, `{max_players}`,
  `{online_players}`, `{average_ping}`, `{highest_ping}`, `{ping}`, `{tps}`,
  `{version}`, `{uptime}`, and `{motd}`. These work in titles, descriptions,
  field names/values, author, and footer.

Restart the server after editing the config for changes to take effect. Discord
limits are enforced for field count and individual field lengths.

On startup, the bot publishes a starting status, then changes to online after
`discord.startup-delay-seconds`. When the server stops cleanly, the plugin
attempts to update the embed to offline and waits up to five seconds for
Discord to confirm the update. A forced process kill, crash, or loss of network
connectivity can prevent that final update.

Player ping is the average and highest ping reported by Paper for currently
online players; it is not an external network probe of the server host.
