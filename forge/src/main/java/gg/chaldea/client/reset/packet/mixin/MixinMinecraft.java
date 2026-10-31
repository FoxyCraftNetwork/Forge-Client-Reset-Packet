package gg.chaldea.client.reset.packet.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gg.chaldea.client.reset.packet.ReconfigurationReset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Fires Forge's level-unload event at the same point Minecraft#disconnect does (after the screen change and
 * forced tick, before the level reference is dropped), but only while a re-configuration reset is running.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

	@Shadow @Nullable public ClientLevel level;

	@Inject(method = "clearClientLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;onDisconnected()V"))
	private void clientresetpacket$onLevelClosing(CallbackInfo ci) {
		ReconfigurationReset.onLevelClosing(this.level);
	}
}
