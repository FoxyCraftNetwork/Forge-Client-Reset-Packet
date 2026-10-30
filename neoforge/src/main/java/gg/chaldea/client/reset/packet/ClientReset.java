package gg.chaldea.client.reset.packet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client Reset Packet for NeoForge.
 *
 * <p>Since 1.20.2 proxies such as Velocity switch servers by sending the vanilla
 * {@code ClientboundStartConfigurationPacket}, which moves the client back into the configuration phase where the next
 * backend negotiates with it from scratch. NeoForge keeps part of its per-connection state (negotiated payload
 * channels, connection type, ad-hoc/common channels, packet filters) on the Netty channel and only reverts synced
 * registries on a real disconnect, so across a switch the client carries the previous server's state into the next
 * one. That is what breaks switches between NeoForge and vanilla backends. {@link ReconfigurationReset} puts all of that
 * back to the state of a fresh connection at the start of every switch.</p>
 *
 * <p>Client-only: the entry point is only constructed on the physical client, and the mod is not needed on the
 * server (see {@code displayTest} in neoforge.mods.toml).</p>
 */
@Mod(value = ClientReset.MOD_ID, dist = Dist.CLIENT)
public class ClientReset {

	public static final String MOD_ID = "clientresetpacket";
	static final Logger logger = LogManager.getLogger();
	static final Marker RESETMARKER = MarkerManager.getMarker("RESETPACKET");

	public ClientReset() {
		NeoForge.EVENT_BUS.register(OculusCompat.class);
	}
}
