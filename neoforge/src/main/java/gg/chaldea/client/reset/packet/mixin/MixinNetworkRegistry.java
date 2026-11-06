package gg.chaldea.client.reset.packet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gg.chaldea.client.reset.packet.VanillaHubBypass;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.negotiation.NegotiationResult;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * The client overload of {@code initializeOtherConnection} is NeoForge's "this server is not NeoForge" path.
 * Every change here is limited to that path, or to a connection already marked by it.
 */
@Mixin(NetworkRegistry.class)
public abstract class MixinNetworkRegistry {

	private static final String OTHER_CONNECTION = "initializeOtherConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;)V";
	private static final String NEOFORGE_CONNECTION = "initializeNeoForgeConnection(Lnet/minecraft/network/protocol/configuration/ClientConfigurationPacketListener;Lnet/neoforged/neoforge/network/registration/NetworkPayloadSetup;)V";
	private static final String MODDED_PAYLOAD = "handleModdedPayload(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";

	@Inject(method = OTHER_CONNECTION, at = @At("HEAD"))
	private static void clientresetpacket$beginOther(ClientConfigurationPacketListener listener, CallbackInfo ci) {
		ICommonPacketListener common = (ICommonPacketListener) listener;
		Connection connection = common.getConnection();
		VanillaHubBypass.beginNegotiation(connection);
		ConnectionType type = common.getConnectionType();
		if (type != null && type.isOther()) {
			VanillaHubBypass.activate(connection);
		}
	}

	@Inject(method = OTHER_CONNECTION, at = @At("RETURN"))
	private static void clientresetpacket$endOther(ClientConfigurationPacketListener listener, CallbackInfo ci) {
		VanillaHubBypass.endNegotiation();
	}

	/**
	 * A failed check means "this client has channels the vanilla server will never offer". The rest of the method
	 * still has to run: default server configs, packet filters, and the register payload. Returning success does that.
	 * The server overload of this method is a different target and is left alone.
	 */
	@Redirect(method = OTHER_CONNECTION, at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/negotiation/NegotiationResult;success()Z"))
	private static boolean clientresetpacket$missingServerChannelsAreOk(NegotiationResult result) {
		return VanillaHubBypass.allowFailedNegotiation(result);
	}

	/** A NeoForge negotiation supersedes any earlier non-NeoForge guess on this connection. */
	@Inject(method = NEOFORGE_CONNECTION, at = @At("HEAD"))
	private static void clientresetpacket$neoForgeServer(ClientConfigurationPacketListener listener, NetworkPayloadSetup setup, CallbackInfo ci) {
		VanillaHubBypass.clear(((ICommonPacketListener) listener).getConnection());
	}

	@Redirect(method = MODDED_PAYLOAD, at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;disconnect(Lnet/minecraft/network/chat/Component;)V"))
	private static void clientresetpacket$ignoreUnexpectedModdedPayload(Connection connection, Component reason) {
		String detail = reason == null ? "unexpected modded payload" : reason.getString();
		if (VanillaHubBypass.keepConnection(connection, detail)) {
			return;
		}
		connection.disconnect(reason);
	}

	@Redirect(method = "checkCommonVersion", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/extensions/ICommonPacketListener;disconnect(Lnet/minecraft/network/chat/Component;)V"))
	private static void clientresetpacket$ignoreCommonVersionMismatch(ICommonPacketListener listener, Component reason) {
		if (VanillaHubBypass.keepConnection(listener.getConnection(), "common channel version")) {
			return;
		}
		listener.disconnect(reason);
	}
}
