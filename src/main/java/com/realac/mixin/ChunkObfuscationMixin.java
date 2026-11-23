package com.realac.mixin;

import com.realac.OreObfuscator;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ChunkObfuscationMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static LevelChunk realac$obfuscateChunk(LevelChunk chunk) {
        return OreObfuscator.obfuscate(chunk);
    }
}
