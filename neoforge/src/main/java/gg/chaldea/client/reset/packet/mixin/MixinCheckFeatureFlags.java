package gg.chaldea.client.reset.packet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import gg.chaldea.client.reset.packet.VanillaHubBypass;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.configuration.CheckFeatureFlags;

/**
 * Runs only from the non-NeoForge configuration path. NeoForge-to-NeoForge feature-flag mismatches use a different method.
 */
@Mixin(CheckFeatureFlags.class)
public abstract class MixinCheckFeatureFlags {

	@Inject(method = "handleVanillaServerConnection", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;disconnect(Lnet/minecraft/network/chat/Component;)V"), cancellable = true)
	private static void clientresetpacket$keepModdedFlags(ClientConfigurationPacketListener listener, CallbackInfoReturnable<Boolean> cir) {
		if (VanillaHubBypass.keepConnection(((ICommonPacketListener) listener).getConnection(), "modded feature flags")) {
			cir.setReturnValue(true);
		}
	}
}
