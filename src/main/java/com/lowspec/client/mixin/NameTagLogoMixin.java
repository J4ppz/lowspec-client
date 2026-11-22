package com.lowspec.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.lowspec.client.Friends;
import com.lowspec.client.LowSpecClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

@Mixin(EntityRenderer.class)
public abstract class NameTagLogoMixin {

    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true, require = 0)
    private void lowspec$logo(Entity entity, CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        if (original == null) return;

        // your own tag
        if (entity == Minecraft.getInstance().player) {
            if (LowSpecClient.nameTagOn()) cir.setReturnValue(LowSpecClient.withLogo(original));
            return;
        }

        // other players who are also running LowSpec on this server
        if (entity instanceof Player p && Friends.showLogos()
                && Friends.isModUser(p.getName().getString())) {
            cir.setReturnValue(LowSpecClient.withLogo(original));
        }
    }
}
