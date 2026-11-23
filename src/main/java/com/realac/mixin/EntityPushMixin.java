package com.realac.mixin;

import com.realac.VanishManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityPushMixin {
    @Inject(method = "push", at = @At("HEAD"), cancellable = true)
    private void clientcheck$cancelPush(Entity entity, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && VanishManager.isVanished(player.getUUID())) {
            ci.cancel();
        }
    }
}
