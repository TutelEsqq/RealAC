package com.realac;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OreObfuscator {
    // per player -> per chunk -> revealed block positions (packed longs)
    private static final Map<UUID, Map<Long, LongSet>> REVEALED = new ConcurrentHashMap<>();

    private OreObfuscator() {
    }

    public static boolean isOre(BlockState state) {
        return state.is(Tags.Blocks.ORES);
    }

    // called from ChunkObfuscationMixin for every outgoing chunk packet
    public static LevelChunk obfuscate(LevelChunk original) {
        if (!AnticheatServer.ORE_OBFUSCATION_ENABLED.get()
                || !(original.getLevel() instanceof ServerLevel level)
                || level.dimension() == Level.END) {
            return original;
        }

        LevelChunkSection[] sections = original.getSections();
        LevelChunkSection[] replaced = null;

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || !section.getStates().maybeHas(OreObfuscator::isOre)) {
                continue;
            }
            int bottomY = level.getMinBuildHeight() + i * 16;
            PalettedContainer<BlockState> states = section.getStates().copy();
            boolean changed = false;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = states.get(x, y, z);
                        if (isOre(state) && isFullySurrounded(original,
                                original.getPos().getMinBlockX() + x, bottomY + y,
                                original.getPos().getMinBlockZ() + z)) {
                            states.getAndSet(x, y, z, disguiseFor(level.dimension(), bottomY + y));
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                if (replaced == null) {
                    replaced = sections.clone();
                }
                replaced[i] = new LevelChunkSection(states, section.getBiomes());
            }
        }

        if (replaced == null) {
            return original;
        }

        LevelChunk copy = new LevelChunk(level, original.getPos(), original.getUpgradeData(),
                (net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block>) original.getBlockTicks(), (net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid>) original.getFluidTicks(), original.getInhabitedTime(),
                replaced, null, original.getBlendingData());
        for (var entry : original.getHeightmaps()) {
            Heightmap heightmap = copy.getOrCreateHeightmapUnprimed(entry.getKey());
            entry.getValue().setRawData(copy, entry.getKey(), entry.getValue().getRawData());
        }
        copy.getBlockEntities().putAll(original.getBlockEntities());
        copy.setLightCorrect(original.isLightCorrect());
        return copy;
    }

    private static boolean isFullySurrounded(LevelChunk chunk, int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            pos.set(x + offset[0], y + offset[1], z + offset[2]);
            if (chunk.getBlockState(pos).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static BlockState disguiseFor(ResourceKey<Level> dimension, int y) {
        if (dimension == Level.NETHER) {
            return Blocks.NETHERRACK.defaultBlockState();
        }
        return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    // reveal real ores near players
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!AnticheatServer.ORE_OBFUSCATION_ENABLED.get()
                || !(event.getEntity() instanceof ServerPlayer player)
                || player.tickCount % 5 != 0) {
            return;
        }
        ServerLevel level = player.serverLevel();
        int radius = AnticheatServer.ORE_REVEAL_RADIUS.get();
        BlockPos center = player.blockPosition();
        Map<Long, LongSet> byChunk = REVEALED.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    if (!isOre(state)) {
                        continue;
                    }
                    long chunkKey = ChunkPos.asLong(pos);
                    LongSet revealed = byChunk.computeIfAbsent(chunkKey, k -> new LongOpenHashSet());
                    long posKey = pos.asLong();
                    if (revealed.add(posKey)) {
                        player.connection.send(new ClientboundBlockUpdatePacket(pos.immutable(), state));
                    }
                }
            }
        }
    }

    public static void onChunkUnwatch(ChunkWatchEvent.UnWatch event) {
        Map<Long, LongSet> byChunk = REVEALED.get(event.getPlayer().getUUID());
        if (byChunk != null) {
            byChunk.remove(event.getPos().toLong());
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        REVEALED.remove(event.getEntity().getUUID());
    }
}
