package com.lowspec.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.lowspec.client.Cosmetics;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;

@Mixin(AbstractClientPlayer.class)
public abstract class SkinCapeMixin {

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true, require = 0)
    private void lowspec$cape(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin replaced = Cosmetics.apply((AbstractClientPlayer) (Object) this, cir.getReturnValue());
        if (replaced != null) cir.setReturnValue(replaced);
    }
}
