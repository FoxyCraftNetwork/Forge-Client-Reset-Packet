package gg.chaldea.client.reset.packet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gg.chaldea.client.reset.packet.VanillaHubBypass;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * On a non-NeoForge server, a mod that sends a channel the server never offered makes NeoForge throw out of
 * {@code send}. Drop that packet instead. NeoForge peers still hit the original check.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class MixinClientCommonPacketListener {

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void clientresetpacket$dropUnnegotiatedPayload(Packet<?> packet, CallbackInfo ci) {
		ClientCommonPacketListener self = (ClientCommonPacketListener) (Object) this;
		ICommonPacketListener common = (ICommonPacketListener) self;
		if (!VanillaHubBypass.isActive(common.getConnection())) {
			return;
		}
		try {
			NetworkRegistry.checkPacket(packet, self);
		} catch (UnsupportedOperationException refused) {
			VanillaHubBypass.noteDroppedPayload(common.getConnection(), refused.getMessage());
			ci.cancel();
		}
	}
}
