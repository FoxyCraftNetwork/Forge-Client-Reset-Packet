package gg.chaldea.client.reset.packet;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Client Reset Packet for Minecraft 1.20.6 - 1.21.1 (Forge 50, 51 and 52), one jar.
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

	/**
	 * No-arg constructor on purpose: Forge 51 (1.21) has no constructor injection, Forge 50.1.17+ and 52.1.0+ inject
	 * {@code FMLJavaModLoadingContext}, and every one of them still accepts a no-arg constructor. The display test
	 * is registered through whichever mod loading context this Forge build has, looked up at runtime.
	 */
	public ClientReset() {
		// Client-side only: never require it on the server, and never mark servers as incompatible.
		registerDisplayTest();
		if (FMLEnvironment.dist == Dist.CLIENT) {
			MinecraftForge.EVENT_BUS.register(OculusCompat.class);
			OculusCompat.registerTickListener();
		}
	}

	private static void registerDisplayTest() {
		Supplier<IExtensionPoint.DisplayTest> test = () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true);
		// ModLoadingContext.get() exists on Forge 50-52; FMLJavaModLoadingContext.get() is the fallback.
		for (String owner : new String[] {"net.minecraftforge.fml.ModLoadingContext", "net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext"}) {
			try {
				Class<?> cls = Class.forName(owner);
				Object ctx = cls.getMethod("get").invoke(null);
				Method register = ctx.getClass().getMethod("registerExtensionPoint", Class.class, Supplier.class);
				register.invoke(ctx, IExtensionPoint.DisplayTest.class, test);
				return;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				logger.debug(RESETMARKER, "Could not register the display test through {}", owner, e);
			}
		}
		// Only affects the server list ping icon; the mod still works.
		logger.warn(RESETMARKER, "Could not register the client-only display test on this Forge build");
	}
}
