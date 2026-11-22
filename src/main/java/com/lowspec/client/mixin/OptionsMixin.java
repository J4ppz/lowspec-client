package com.lowspec.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.lowspec.client.LowSpecClient;

import net.minecraft.client.Options;

@Mixin(Options.class)
public abstract class OptionsMixin {

    @Inject(method = "getEffectiveRenderDistance", at = @At("HEAD"), cancellable = true, require = 0)
    private void lowspec$oneChunk(CallbackInfoReturnable<Integer> cir) {
        if (LowSpecClient.oneChunk()) {
            cir.setReturnValue(1);
        }
    }
}
