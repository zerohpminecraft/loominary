package net.zerohpminecraft.mixin;

import net.minecraft.screen.AnvilScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AnvilScreenHandler.class)
public interface AnvilScreenHandlerAccessor {

    @Invoker("updateResult")
    void invokeUpdateResult();
}