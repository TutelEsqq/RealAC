package com.realac;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(RealACMod.MOD_ID)
public class RealACMod {
    public static final String MOD_ID = "realac";

    // checkedPlayer UUID -> command sender UUID
    private static final Map<UUID, UUID> PENDING = new ConcurrentHashMap<>();

    public RealACMod(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::registerCommand);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(this::onInvulnerabilityCheck);
        NeoForge.EVENT_BUS.addListener(AnticheatServer::onLogin);
        NeoForge.EVENT_BUS.addListener(AnticheatServer::onLogout);
        NeoForge.EVENT_BUS.addListener(AnticheatServer::onServerTick);
        NeoForge.EVENT_BUS.addListener(OreObfuscator::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(OreObfuscator::onChunkUnwatch);
        NeoForge.EVENT_BUS.addListener(OreObfuscator::onLogout);
        container.registerConfig(ModConfig.Type.SERVER, AnticheatServer.SPEC, "realac-server.toml");
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        // request: server -> client (handler is client-only, guard with dist check)
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registrar.playToClient(RequestPayload.TYPE, RequestPayload.CODEC, RealACClient::handleRequest);
        } else {
            registrar.playToClient(RequestPayload.TYPE, RequestPayload.CODEC, (payload, ctx) -> {});
        }
        // response: client -> server
        registrar.playToServer(ResponsePayload.TYPE, ResponsePayload.CODEC, RealACMod::handleResponse);
        // anticheat handshake (challenge) + reports
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registrar.playToClient(HandshakePayload.TYPE, HandshakePayload.CODEC, AnticheatClient::handleHandshake);
        } else {
            registrar.playToClient(HandshakePayload.TYPE, HandshakePayload.CODEC, (payload, ctx) -> {});
        }
        registrar.playToServer(HandshakeResponsePayload.TYPE, HandshakeResponsePayload.CODEC,
                AnticheatServer::handleHandshakeResponse);
        registrar.playToServer(CheatReportPayload.TYPE, CheatReportPayload.CODEC,
                AnticheatServer::handleCheatReport);
    }

    private void registerCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("realac")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        List.of("Names", "Binaries", "Classes"), builder))
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                    String mode = StringArgumentType.getString(ctx, "mode");
                                    if (!List.of("names", "binaries", "classes").contains(mode.toLowerCase())) {
                                        ctx.getSource().sendFailure(Component.literal("Mode must be Names, Binaries or Classes"));
                                        return 0;
                                    }
                                    if (ctx.getSource().getEntity() instanceof ServerPlayer sender) {
                                        PENDING.put(target.getUUID(), sender.getUUID());
                                    }
                                    PacketDistributor.sendToPlayer(target,
                                            new RequestPayload(mode.toLowerCase()));
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "Requested " + mode + " from " + target.getGameProfile().getName()));
                                    return 1;
                                }))));

        event.getDispatcher().register(Commands.literal("vanish")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> toggleVanish(ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> toggleVanish(EntityArgument.getPlayer(ctx, "player")))));
    }

    private static int toggleVanish(ServerPlayer player) {
        boolean nowVanished = !VanishManager.isVanished(player.getUUID());
        VanishManager.setVanished(player.getUUID(), nowVanished);
        player.setInvisible(nowVanished);

        MinecraftServer server = player.getServer();
        if (server == null) {
            return 0;
        }

        if (nowVanished) {
            Component fakeLeave = Component.translatable("multiplayer.player.left", player.getDisplayName())
                    .withStyle(ChatFormatting.YELLOW);
            ClientboundPlayerInfoRemovePacket removePacket =
                    new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID()));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!p.getUUID().equals(player.getUUID())) {
                    p.connection.send(removePacket);
                }
                p.sendSystemMessage(fakeLeave);
            }
        } else {
            Component fakeJoin = Component.translatable("multiplayer.player.joined", player.getDisplayName())
                    .withStyle(ChatFormatting.YELLOW);
            ClientboundPlayerInfoUpdatePacket addPacket =
                    ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!p.getUUID().equals(player.getUUID())) {
                    p.connection.send(addPacket);
                }
                p.sendSystemMessage(fakeJoin);
            }
        }
        return 1;
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            List<UUID> hidden = VanishManager.getVanished().stream()
                    .filter(id -> !id.equals(player.getUUID()))
                    .toList();
            if (!hidden.isEmpty()) {
                player.connection.send(new ClientboundPlayerInfoRemovePacket(hidden));
            }
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && VanishManager.isVanished(player.getUUID())) {
            VanishManager.setVanished(player.getUUID(), false);
        }
    }

    private void onInvulnerabilityCheck(EntityInvulnerabilityCheckEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && VanishManager.isVanished(player.getUUID())) {
            event.setInvulnerable(true);
        }
    }

    private static void handleResponse(ResponsePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            UUID senderId = PENDING.remove(ctx.player().getUUID());
            Component message = Component.literal(
                    "§a[" + ctx.player().getGameProfile().getName() + "]§r\n" + payload.data());
            if (ctx.player().getServer() != null) {
                if (senderId != null) {
                    ServerPlayer sender = ctx.player().getServer().getPlayerList().getPlayer(senderId);
                    if (sender != null) {
                        sender.sendSystemMessage(message);
                        return;
                    }
                }
                // fallback: log to console if sender went offline
                ctx.player().getServer().sendSystemMessage(message);
            }
        });
    }

    public record RequestPayload(String mode) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "request"));
        public static final StreamCodec<FriendlyByteBuf, RequestPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestPayload::mode, RequestPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ResponsePayload(String data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ResponsePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "response"));
        public static final StreamCodec<FriendlyByteBuf, ResponsePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ResponsePayload::data, ResponsePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HandshakePayload(String nonce, String requiredVersion, List<String> blacklistedModIds,
                                   List<String> blacklistedPackages) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HandshakePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "handshake"));
        private static final StreamCodec<FriendlyByteBuf, List<String>> STRING_LIST =
                ByteBufCodecs.collection(java.util.ArrayList::new, ByteBufCodecs.STRING_UTF8);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HandshakePayload::nonce,
                ByteBufCodecs.STRING_UTF8, HandshakePayload::requiredVersion,
                STRING_LIST, HandshakePayload::blacklistedModIds,
                STRING_LIST, HandshakePayload::blacklistedPackages,
                HandshakePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record HandshakeResponsePayload(String nonce, String hash,
                                           String clientVersion) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HandshakeResponsePayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "handshake_response"));
        public static final StreamCodec<FriendlyByteBuf, HandshakeResponsePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HandshakeResponsePayload::nonce,
                ByteBufCodecs.STRING_UTF8, HandshakeResponsePayload::hash,
                ByteBufCodecs.STRING_UTF8, HandshakeResponsePayload::clientVersion,
                HandshakeResponsePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CheatReportPayload(String detail) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CheatReportPayload> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "cheat_report"));
        public static final StreamCodec<FriendlyByteBuf, CheatReportPayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, CheatReportPayload::detail, CheatReportPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
