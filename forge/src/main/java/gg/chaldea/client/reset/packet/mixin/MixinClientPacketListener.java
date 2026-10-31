package gg.chaldea.client.reset.packet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gg.chaldea.client.reset.packet.ReconfigurationReset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;

/**
 * Hooks the vanilla "go back to configuration" packet, which is how proxies switch servers on 1.20.2+.
 * Both injections run on the render thread: vanilla re-schedules the handler there before reaching clearClientLevel.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

	private static final String CLEAR_CLIENT_LEVEL = "Lnet/minecraft/client/Minecraft;clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V";

	@Inject(method = "handleConfigurationStart", at = @At(value = "INVOKE", target = CLEAR_CLIENT_LEVEL, shift = At.Shift.BEFORE))
	private void clientresetpacket$beforeClear(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
		ClientPacketListener self = (ClientPacketListener) (Object) this;
		ReconfigurationReset.beforeClear(Minecraft.getInstance(), self.getConnection());
	}

	@Inject(method = "handleConfigurationStart", at = @At(value = "INVOKE", target = CLEAR_CLIENT_LEVEL, shift = At.Shift.AFTER))
	private void clientresetpacket$afterClear(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
		ClientPacketListener self = (ClientPacketListener) (Object) this;
		ReconfigurationReset.afterClear(self.getConnection());
	}
}
