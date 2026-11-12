package dev.cosmixmc.serverstatus;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class ServerStatusPlugin extends JavaPlugin {
    private static final int MAX_FIELD_LENGTH = 1024;
    private static final int MAX_FIELD_NAME_LENGTH = 256;
    private static final int MAX_DESCRIPTION_LENGTH = 4096;
    private static final int MAX_FIELDS = 25;
    private static final Color DEFAULT_EMBED_COLOR = new Color(67, 181, 129);
    private static final Color DEFAULT_STARTING_COLOR = new Color(254, 231, 92);
    private static final Color DEFAULT_OFFLINE_COLOR = new Color(237, 66, 69);
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private final AtomicBoolean discordReady = new AtomicBoolean();
    private final Object publishLock = new Object();
    private long startedAtMillis;
    private long updateIntervalTicks;
    private long startupDelayTicks;
    private String channelId;
    private Color embedColor;
    private Color startingColor;
    private Color offlineColor;
    private volatile String messageId;
    private volatile TextChannel statusChannel;
    private volatile ServerStatus latestStatus;
    private volatile boolean shuttingDown;
    private CompletableFuture<Void> pendingUpdates = CompletableFuture.completedFuture(null);
    private JDA discord;
    private BukkitTask startupTask;
    private BukkitTask updateTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startedAtMillis = System.currentTimeMillis();
        embedColor = readEmbedColor();
        startingColor = readEmbedColor("discord.embed.starting-color", "#FEE75C", DEFAULT_STARTING_COLOR);
        offlineColor = readEmbedColor("discord.embed.offline-color", "#ED4245", DEFAULT_OFFLINE_COLOR);
        latestStatus = readServerStatus();

        String token = getConfig().getString("discord.bot-token", "").trim();
        channelId = getConfig().getString("discord.channel-id", "").trim();
        messageId = getConfig().getString("discord.message-id", "").trim();
        long intervalSeconds = getConfig().getLong("discord.update-interval-seconds", 60);

        if (token.isEmpty() || channelId.isEmpty()) {
            getLogger().warning("Discord is not configured. Set discord.bot-token and discord.channel-id in config.yml.");
            return;
        }
        if (intervalSeconds < 15) {
            getLogger().warning("discord.update-interval-seconds must be at least 15; using 60 seconds.");
            intervalSeconds = 60;
        }
        updateIntervalTicks = intervalSeconds * 20L;
        long startupDelaySeconds = getConfig().getLong("discord.startup-delay-seconds", 10);
        if (startupDelaySeconds < 1) {
            getLogger().warning("discord.startup-delay-seconds must be at least 1; using 10 seconds.");
            startupDelaySeconds = 10;
        }
        startupDelayTicks = startupDelaySeconds * 20L;

        try {
            discord = JDABuilder.createDefault(token)
                    .addEventListeners(new DiscordListener())
                    .build();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not start the Discord bot. Check the configured bot token.", exception);
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (startupTask != null) {
            startupTask.cancel();
        }
        if (updateTask != null) {
            updateTask.cancel();
        }
        publishOfflineAndWait();
        if (discord != null) {
            discord.shutdownNow();
        }
    }

    private void startStatusUpdates() {
        if (!discordReady.compareAndSet(false, true)) {
            return;
        }

        statusChannel = discord.getTextChannelById(channelId);
        if (statusChannel == null) {
            discordReady.set(false);
            getLogger().severe("The configured Discord channel was not found or is not a text channel. Check discord.channel-id and bot access.");
            return;
        }

        latestStatus = readServerStatus();
        publishStatus(ServerPhase.STARTING);
        startupTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (shuttingDown) {
                return;
            }
            latestStatus = readServerStatus();
            publishStatus(ServerPhase.ONLINE);
            updateTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> publishStatus(ServerPhase.ONLINE),
                updateIntervalTicks,
                updateIntervalTicks
            );
        }, startupDelayTicks);
    }

    private void publishStatus(ServerPhase phase) {
        if (shuttingDown || !isEnabled() || statusChannel == null) {
            return;
        }

        latestStatus = readServerStatus();
        enqueueStatusUpdate(createStatusEmbed(latestStatus, phase));
    }

    private void publishOfflineAndWait() {
        TextChannel channel = statusChannel;
        if (channel == null) {
            return;
        }

        ServerStatus lastStatus = latestStatus;
        if (lastStatus == null) {
            lastStatus = readServerStatus();
        }
        ServerStatus offlineStatus = new ServerStatus(
                0,
                lastStatus.maxPlayers,
                0,
                0,
                "No players online",
                lastStatus.motd,
                lastStatus.version,
                0,
                Duration.ofMillis(System.currentTimeMillis() - startedAtMillis)
        );

        CompletableFuture<Void> update = enqueueStatusUpdate(createStatusEmbed(offlineStatus, ServerPhase.OFFLINE));
        try {
            update.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            getLogger().log(Level.SEVERE, "Interrupted while waiting for the Discord offline status update.", exception);
        } catch (ExecutionException exception) {
            getLogger().log(Level.SEVERE, "Could not publish the Discord offline status update.", exception.getCause());
        } catch (TimeoutException exception) {
            getLogger().log(Level.WARNING, "Timed out waiting for the Discord offline status update.", exception);
        }
    }

    private CompletableFuture<Void> enqueueStatusUpdate(EmbedBuilder embed) {
        synchronized (publishLock) {
            pendingUpdates = pendingUpdates
                    .handle((ignored, failure) -> {
                        if (failure != null) {
                            getLogger().log(Level.SEVERE, "A previous Discord status update failed.", failure);
                        }
                        return null;
                    })
                    .thenCompose(ignored -> updateDiscordMessage(embed));
            return pendingUpdates;
        }
    }

    private CompletableFuture<Void> updateDiscordMessage(EmbedBuilder embed) {
        TextChannel channel = statusChannel;
        if (channel == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Discord status channel is unavailable."));
        }

        String existingMessageId = messageId;
        if (existingMessageId.isBlank()) {
            return channel.sendMessageEmbeds(embed.build())
                    .submit()
                    .thenAccept(this::rememberMessage);
        }

        return channel.retrieveMessageById(existingMessageId)
                .submit()
                .thenCompose(message -> message.editMessageEmbeds(embed.build()).submit())
                .thenApply(ignored -> null);
    }

    private void rememberMessage(Message message) {
        messageId = message.getId();
        if (isEnabled() && !shuttingDown) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!shuttingDown) {
                    getConfig().set("discord.message-id", messageId);
                    saveConfig();
                }
            });
        }
    }

    private ServerStatus readServerStatus() {
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        int totalPing = 0;
        int maxPing = 0;
        StringBuilder playerNames = new StringBuilder();

        for (Player player : players) {
            int ping = Math.max(player.getPing(), 0);
            totalPing += ping;
            maxPing = Math.max(maxPing, ping);
            if (!playerNames.isEmpty()) {
                playerNames.append(", ");
            }
            playerNames.append(player.getName());
        }

        String motd = Objects.requireNonNullElse(ChatColor.stripColor(Bukkit.getMotd()), "");
        return new ServerStatus(
                players.size(),
                Bukkit.getMaxPlayers(),
                players.isEmpty() ? 0 : totalPing / players.size(),
                maxPing,
                playerNames.isEmpty() ? "No players online" : truncate(playerNames.toString(), MAX_FIELD_LENGTH),
                truncate(motd, MAX_DESCRIPTION_LENGTH),
                Bukkit.getBukkitVersion(),
                Bukkit.getTPS()[0],
                Duration.ofMillis(System.currentTimeMillis() - startedAtMillis)
        );
    }

    private EmbedBuilder createStatusEmbed(ServerStatus status, ServerPhase phase) {
        String pingSummary = status.onlinePlayers == 0
                ? "No players online"
                : "Average: " + status.averagePing + " ms | Highest: " + status.highestPing + " ms";
        Map<String, String> placeholders = new HashMap<>();
        String statusTextPath = "discord.embed." + phase.configKey + "-status-text";
        placeholders.put("status", Objects.requireNonNullElse(
                getConfig().getString(statusTextPath, phase.defaultText),
                phase.defaultText
        ));
        placeholders.put("player_count", Integer.toString(status.onlinePlayers));
        placeholders.put("players", Integer.toString(status.onlinePlayers));
        placeholders.put("max_players", Integer.toString(status.maxPlayers));
        placeholders.put("online_players", status.playerNames);
        placeholders.put("average_ping", Integer.toString(status.averagePing));
        placeholders.put("highest_ping", Integer.toString(status.highestPing));
        placeholders.put("ping", pingSummary);
        placeholders.put("tps", String.format(Locale.ROOT, "%.1f", Math.min(status.tps, 20.0)));
        placeholders.put("version", status.version);
        placeholders.put("uptime", formatDuration(status.uptime));
        placeholders.put("motd", status.motd.isBlank() ? "No MOTD configured." : status.motd);

        Color phaseColor = switch (phase) {
            case STARTING -> startingColor;
            case ONLINE -> embedColor;
            case OFFLINE -> offlineColor;
        };
        EmbedBuilder embed = new EmbedBuilder().setColor(phaseColor);

        String title = render(getConfig().getString("discord.embed.title", "Minecraft Server Status"), placeholders);
        String titleUrl = render(getConfig().getString("discord.embed.url", ""), placeholders);
        if (!title.isBlank()) {
            if (titleUrl.isBlank()) {
                embed.setTitle(truncate(title, 256));
            } else {
                embed.setTitle(truncate(title, 256), titleUrl);
            }
        }

        String description = render(getConfig().getString("discord.embed.description", "{motd}"), placeholders);
        if (!description.isBlank()) {
            embed.setDescription(truncate(description, MAX_DESCRIPTION_LENGTH));
        }

        String authorName = render(getConfig().getString("discord.embed.author.name", ""), placeholders);
        if (!authorName.isBlank()) {
            embed.setAuthor(
                    truncate(authorName, 256),
                    nonBlank(render(getConfig().getString("discord.embed.author.url", ""), placeholders)),
                    nonBlank(render(getConfig().getString("discord.embed.author.icon-url", ""), placeholders))
            );
        }

        String footerText = render(getConfig().getString("discord.embed.footer.text", ""), placeholders);
        if (!footerText.isBlank()) {
            embed.setFooter(
                    truncate(footerText, 2048),
                    nonBlank(render(getConfig().getString("discord.embed.footer.icon-url", ""), placeholders))
            );
        }

        String thumbnailUrl = render(getConfig().getString("discord.embed.thumbnail-url", ""), placeholders);
        if (!thumbnailUrl.isBlank()) {
            embed.setThumbnail(thumbnailUrl);
        }
        String imageUrl = render(getConfig().getString("discord.embed.image-url", ""), placeholders);
        if (!imageUrl.isBlank()) {
            embed.setImage(imageUrl);
        }
        if (getConfig().getBoolean("discord.embed.timestamp", true)) {
            embed.setTimestamp(Instant.now());
        }

        List<String> fieldOrder = getConfig().getStringList("discord.embed.field-order");
        if (fieldOrder.size() > MAX_FIELDS) {
            getLogger().warning("discord.embed.field-order has more than 25 entries; only the first 25 fields can be displayed.");
        }
        int fieldsAdded = 0;
        for (String key : fieldOrder) {
            if (fieldsAdded == MAX_FIELDS) {
                break;
            }
            String path = "discord.embed.fields." + key;
            if (!getConfig().isConfigurationSection(path)) {
                getLogger().warning("Ignoring unknown embed field '" + key + "' in discord.embed.field-order.");
                continue;
            }
            if (!getConfig().getBoolean(path + ".enabled", true)) {
                continue;
            }

            String name = render(getConfig().getString(path + ".name", key), placeholders);
            String value = render(getConfig().getString(path + ".value", ""), placeholders);
            if (name.isBlank()) {
                name = key;
            }
            if (value.isBlank()) {
                value = "\u200B";
            }
            embed.addField(
                    truncate(name, MAX_FIELD_NAME_LENGTH),
                    truncate(value, MAX_FIELD_LENGTH),
                    getConfig().getBoolean(path + ".inline", false)
            );
            fieldsAdded++;
        }
        return embed;
    }

    private Color readEmbedColor() {
        return readEmbedColor("discord.embed.color", "#43B581", DEFAULT_EMBED_COLOR);
    }

    private Color readEmbedColor(String path, String defaultValue, Color fallback) {
        String configured = Objects.requireNonNullElse(getConfig().getString(path, defaultValue), defaultValue).trim();
        if (!configured.matches("#[0-9a-fA-F]{6}")) {
            getLogger().warning(path + " must be a #RRGGBB value; using " + defaultValue + ".");
            return fallback;
        }
        return new Color(Integer.parseInt(configured.substring(1), 16));
    }

    private static String render(String template, Map<String, String> placeholders) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(Objects.requireNonNullElse(template, ""));
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = placeholders.get(matcher.group(1));
            if (replacement == null) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String nonBlank(String value) {
        return value.isBlank() ? null : value;
    }

    private static String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private record ServerStatus(
            int onlinePlayers,
            int maxPlayers,
            int averagePing,
            int highestPing,
            String playerNames,
            String motd,
            String version,
            double tps,
            Duration uptime
    ) { }

    private enum ServerPhase {
        STARTING("starting", "🟡 Starting"),
        ONLINE("online", "🟢 Online"),
        OFFLINE("offline", "🔴 Offline");

        private final String configKey;
        private final String defaultText;

        ServerPhase(String configKey, String defaultText) {
            this.configKey = configKey;
            this.defaultText = defaultText;
        }
    }

    private final class DiscordListener extends ListenerAdapter {
        @Override
        public void onReady(@NotNull ReadyEvent event) {
            if (isEnabled()) {
                Bukkit.getScheduler().runTask(ServerStatusPlugin.this, ServerStatusPlugin.this::startStatusUpdates);
            }
        }
    }
}
