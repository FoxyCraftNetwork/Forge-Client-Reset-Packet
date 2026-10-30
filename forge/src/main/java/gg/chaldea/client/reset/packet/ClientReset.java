package gg.chaldea.client.reset.packet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Client Reset Packet for Minecraft 1.21.3 (Forge 53.x).
 *
 * <p>Up to 1.20.1 a proxy had to send a custom "reset" login packet so that the Forge client would tear down its
 * session and run the FML handshake again for the next backend server. Since 1.20.2 Minecraft has a vanilla
 * configuration phase and the proxy can send {@code ClientboundStartConfigurationPacket} to move the client back
 * into it, where Forge 52 runs its whole handshake (mod list, channels, registry sync, config sync) as
 * configuration tasks. Velocity does this natively, so the reset packet is no longer needed.</p>
 *
 * <p>What vanilla re-configuration does not do is the client-side cleanup Forge performs on a real disconnect
 * (see {@link ReconfigurationReset}). This mod adds that cleanup, plus the Oculus/Iris shader workaround, so a
 * server switch behaves like a clean reconnect. It is client-only and does nothing on a server.</p>
 */
@Mod(ClientReset.MOD_ID)
public class ClientReset {

	public static final String MOD_ID = "clientresetpacket";
	static final Logger logger = LogManager.getLogger();
	static final Marker RESETMARKER = MarkerManager.getMarker("RESETPACKET").setParents(MarkerManager.getMarker("FMLNETWORK"));

	public ClientReset(FMLJavaModLoadingContext context) {
		// Client-side only: never require it on the server, and never mark servers as incompatible.
		context.registerExtensionPoint(IExtensionPoint.DisplayTest.class, IExtensionPoint.DisplayTest.IGNORE_ALL_VERSION);
		if (FMLEnvironment.dist == Dist.CLIENT) {
			MinecraftForge.EVENT_BUS.register(OculusCompat.class);
		}
	}
}
