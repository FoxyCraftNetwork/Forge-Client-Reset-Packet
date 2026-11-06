package gg.chaldea.client.reset.packet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gg.chaldea.client.reset.packet.VanillaHubBypass;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;

/**
 * After the vanilla-server refusal is lifted, a modded codec can still fail to read a vanilla or Paper packet and
 * close the connection. Skip that decode error only. Handler exceptions and NeoForge connections still disconnect.
 */
@Mixin(Connection.class)
public abstract class MixinConnection {

	@Inject(method = "exceptionCaught", at = @At("HEAD"), cancellable = true)
	private void clientresetpacket$ignoreVanillaDecodeError(ChannelHandlerContext context, Throwable cause, CallbackInfo ci) {
		Connection self = (Connection) (Object) this;
		if (!VanillaHubBypass.isActive(self) || !VanillaHubBypass.isDecodeFailure(cause)) {
			return;
		}
		VanillaHubBypass.noteDecodeFailure(self, cause);
		ci.cancel();
	}
}
