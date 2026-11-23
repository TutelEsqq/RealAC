package com.realac;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AnticheatServer {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> REQUIRED_VERSION =
            BUILDER.comment("Anticheat version clients must run. Mismatch = kick.")
                    .define("requiredVersion", "2.1.0");
    public static final ModConfigSpec.IntValue HEARTBEAT_INTERVAL_SECONDS =
            BUILDER.comment("Seconds between heartbeat challenges sent to each client.")
                    .defineInRange("heartbeatIntervalSeconds", 30, 5, 600);
    public static final ModConfigSpec.IntValue HEARTBEAT_TIMEOUT_SECONDS =
            BUILDER.comment("Seconds a client has to answer a challenge before it counts as missed.")
                    .defineInRange("heartbeatTimeoutSeconds", 45, 10, 300);
    public static final ModConfigSpec.IntValue HEARTBEAT_MAX_MISSED =
            BUILDER.comment("How many consecutive missed heartbeats are tolerated before the player is kicked.")
                    .defineInRange("heartbeatMaxMissed", 3, 1, 10);
    public static final ModConfigSpec.BooleanValue KICK_ON_DETECTION =
            BUILDER.comment("Kick players when their client reports blacklisted software. If false, only alert operators.")
                    .define("kickOnDetection", true);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRUSTED_PLAYERS =
            BUILDER.comment("Usernames (case-insensitive) that are never kicked by the anticheat. Detections are still reported to operators.")
                    .defineList("trustedPlayers", List.of(), () -> "Steve", o -> o instanceof String);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_MOD_IDS =
            BUILDER.comment("Mod ids the client must report as cheats.")
                    .defineList("blacklistedModIds",
                            List.of("wurst", "meteor-client", "aristois", "liquidbounce", "inertia",
                                    "futureclient", "rusherhack-client", "pyroclient", "impact", "sigma", "baritone",
                                    "xaerominimap", "xaeroworldmap", "journeymap"),
                            () -> "cheat-mod-id",
                            o -> o instanceof String);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_PACKAGES =
            BUILDER.comment("Java package prefixes scanned inside jars on the client.")
                    .defineList("blacklistedPackages",
                            List.of("net.wurstclient", "meteordevelopment", "org.aristois", "net.ccbluex.liquidbounce",
                                    "baritone", "me.zeroeightsix", "rusherhack", "com.sigmaclient", "net.impactclient",
                                    "xaero", "journeymap"),
                            () -> "com.example.cheat",
                            o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue ORE_OBFUSCATION_ENABLED =
            BUILDER.comment("Hide ores in chunk packets as stone/deepslate/netherrack until the player is close.")
                    .define("oreObfuscationEnabled", true);
    public static final ModConfigSpec.IntValue ORE_REVEAL_RADIUS =
            BUILDER.comment("Distance in blocks at which real ores are revealed to the player.")
                    .defineInRange("oreRevealRadius", 2, 1, 6);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private record Pending(String nonce, long deadlineTick) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_CHALLENGE_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> MISSED = new ConcurrentHashMap<>();

    private AnticheatServer() {
    }

    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendChallenge(player);
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        cleanup(event.getEntity().getUUID());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Pending pending = PENDING.get(player.getUUID());
            if (pending != null) {
                if (now > pending.deadlineTick()) {
                    int missed = MISSED.merge(player.getUUID(), 1, Integer::sum);
                    PENDING.remove(player.getUUID());
                    if (missed >= HEARTBEAT_MAX_MISSED.get()) {
                        if (isTrusted(player)) {
                            alertOps(server, Component.literal("§e[Real Anticheat] Trusted player "
                                    + player.getGameProfile().getName() + " missed " + missed
                                    + " heartbeats (would be kicked)"));
                            MISSED.put(player.getUUID(), 0);
                        } else {
                            alertOps(server, Component.literal("§c[Real Anticheat] Kicked " + player.getGameProfile().getName()
                                    + ": heartbeat timeout (" + missed + " missed in a row)"));
                            player.connection.disconnect(kickMessage(
                                    "Anticheat heartbeat failed", "anticheat missing, outdated or modified"));
                            cleanup(player.getUUID());
                        }
                    } else {
                        alertOps(server, Component.literal("§e[Real Anticheat] " + player.getGameProfile().getName()
                                + " missed heartbeat " + missed + "/" + HEARTBEAT_MAX_MISSED.get() + " (lag?)"));
                        NEXT_CHALLENGE_TICK.put(player.getUUID(), now + 10 * 20L);
                    }
                }
                continue;
            }
            long next = NEXT_CHALLENGE_TICK.getOrDefault(player.getUUID(), 0L);
            if (now >= next) {
                sendChallenge(player);
            }
        }
    }

    private static void sendChallenge(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        long now = server.getTickCount();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        PENDING.put(player.getUUID(), new Pending(nonce, now + HEARTBEAT_TIMEOUT_SECONDS.get() * 20L));
        NEXT_CHALLENGE_TICK.put(player.getUUID(), now + HEARTBEAT_INTERVAL_SECONDS.get() * 20L);
        PacketDistributor.sendToPlayer(player, new RealACMod.HandshakePayload(
                nonce,
                REQUIRED_VERSION.get(),
                List.copyOf(BLACKLISTED_MOD_IDS.get()),
                List.copyOf(BLACKLISTED_PACKAGES.get())));
    }

    public static void handleHandshakeResponse(RealACMod.HandshakeResponsePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || player.getServer() == null) {
                return;
            }
            Pending pending = PENDING.get(player.getUUID());
            boolean nonceOk = pending != null
                    && pending.nonce().equals(payload.nonce())
                    && AnticheatCrypto.challengeHash(payload.nonce()).equals(payload.hash());
            if (!nonceOk) {
                // late or malformed response — count as a miss instead of instant kick
                int missed = MISSED.merge(player.getUUID(), 1, Integer::sum);
                PENDING.remove(player.getUUID());
                if (missed >= HEARTBEAT_MAX_MISSED.get()) {
                    if (isTrusted(player)) {
                        MISSED.put(player.getUUID(), 0);
                        NEXT_CHALLENGE_TICK.put(player.getUUID(),
                                player.getServer().getTickCount() + 10 * 20L);
                    } else {
                        player.connection.disconnect(kickMessage("Invalid anticheat response", "handshake mismatch"));
                        cleanup(player.getUUID());
                    }
                } else {
                    NEXT_CHALLENGE_TICK.put(player.getUUID(),
                            player.getServer().getTickCount() + 10 * 20L);
                }
                return;
            }
            PENDING.remove(player.getUUID());
            MISSED.remove(player.getUUID());
            if (!REQUIRED_VERSION.get().equals(payload.clientVersion())) {
                if (isTrusted(player)) {
                    alertOps(player.getServer(), Component.literal("§e[Real Anticheat] Trusted player "
                            + player.getGameProfile().getName() + " has outdated anticheat "
                            + payload.clientVersion() + " (would be kicked)"));
                    return;
                }
                player.connection.disconnect(kickMessage("Outdated anticheat version",
                        "required " + REQUIRED_VERSION.get() + ", you have " + payload.clientVersion()));
                cleanup(player.getUUID());
                alertOps(player.getServer(), Component.literal("§c[Real Anticheat] Kicked "
                        + player.getGameProfile().getName() + ": outdated anticheat " + payload.clientVersion()));
            }
        });
    }

    public static void handleCheatReport(RealACMod.CheatReportPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || player.getServer() == null) {
                return;
            }
            Component alert = Component.literal("§c[Real Anticheat] " + player.getGameProfile().getName()
                    + " blacklisted software detected: §f" + payload.detail());
            alertOps(player.getServer(), alert);
            if (KICK_ON_DETECTION.get() && !isTrusted(player)) {
                player.connection.disconnect(kickMessage("Restricted modification detected!", payload.detail()));
                cleanup(player.getUUID());
            }
        });
    }

    public static boolean isTrusted(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        for (String trusted : TRUSTED_PLAYERS.get()) {
            if (trusted.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static Component kickMessage(String title, String detail) {
        return Component.literal("§c[Real Anticheat]\n§f" + title + "\n§f(" + detail + ")");
    }

    public static void alertOps(MinecraftServer server, Component message) {
        server.sendSystemMessage(message);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) {
                p.sendSystemMessage(message);
            }
        }
    }

    private static void cleanup(UUID id) {
        PENDING.remove(id);
        NEXT_CHALLENGE_TICK.remove(id);
        MISSED.remove(id);
    }
}
