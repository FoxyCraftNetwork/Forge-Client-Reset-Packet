package gg.chaldea.client.reset.packet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ForgeEventFactoryClient;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.network.ConnectionType;
import net.minecraftforge.network.NetworkContext;
import net.minecraftforge.registries.GameData;

/**
 * Makes a vanilla re-configuration (the way proxies switch servers on 1.20.2+) clean up the client the same way
 * a real disconnect does on Forge 52+.
 *
 * <p>When the proxy sends {@code ClientboundStartConfigurationPacket}, vanilla calls
 * {@code Minecraft#clearClientLevel}. Forge only hooks {@code Minecraft#disconnect}, so on a server switch:</p>
 * <ul>
 *     <li>{@code ClientPlayerNetworkEvent.LoggingOut} and {@code LevelEvent.Unload} never fire, although
 *     {@code LoggingIn} fires again for the next server. Mods that cache per-server or per-level state
 *     (shader packs, custom player models, minimaps, ...) never clean it up.</li>
 *     <li>Registries are not reverted to the frozen (local) state before the next server's registry snapshot
 *     is injected.</li>
 *     <li>The connection's {@link NetworkContext} keeps the previous server's connection type, channel lists and
 *     mod list. Most importantly {@code sentChannels} still holds every channel the client already announced, so
 *     the client never sends {@code minecraft:register} to the new backend, and a switch from a modded to a
 *     vanilla backend still counts as "modded" (server configs are not reset to their defaults).</li>
 * </ul>
 * <p>This is the 1.21.1 equivalent of what the old reset packet did when it cleared the client before
 * re-running the FML handshake.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ReconfigurationReset {

	/** True between {@link #beforeClear} and {@link #afterClear} (render thread only). */
	private static boolean resetting;

	private ReconfigurationReset() {
	}

	public static boolean isResetting() {
		return resetting;
	}

	/** Render thread, right before {@code Minecraft#clearClientLevel} during re-configuration. */
	public static void beforeClear(Minecraft mc, Connection connection) {
		resetting = true;
		ClientReset.logger.info(ClientReset.RESETMARKER, "Server asked the client to re-configure (proxy server switch); resetting client state.");
		// Shaders off before the level and render pipeline are torn down (issues #3/#12).
		OculusCompat.beforeClear();
		// Forge fires this from Minecraft#disconnect but not from clearClientLevel.
		try {
			ForgeEventFactoryClient.firePlayerLogout(mc.gameMode, mc.player);
		} catch (RuntimeException e) {
			ClientReset.logger.error(ClientReset.RESETMARKER, "A mod threw while handling the logout event for the server switch", e);
		}
	}

	/** Render thread, inside clearClientLevel after the screen change, while the old level is still set. */
	public static void onLevelClosing(ClientLevel level) {
		if (!resetting || level == null) {
			return;
		}
		try {
			ForgeEventFactory.onLevelUnload(level);
		} catch (RuntimeException e) {
			ClientReset.logger.error(ClientReset.RESETMARKER, "A mod threw while handling the level unload event for the server switch", e);
		}
	}

	/**
	 * Render thread, right after clearClientLevel. Anything the new server sends during configuration is either
	 * handled on the network thread without touching this state, or queued behind this task on the render thread.
	 */
	public static void afterClear(Connection connection) {
		try {
			if (!connection.isMemoryConnection()) {
				// Same as ForgeHooksClient#handleClientLevelClosing on a real disconnect.
				GameData.revertToFrozen();
			}
			resetNetworkContext(connection);
			ClientReset.logger.info(ClientReset.RESETMARKER, "Client reset complete, continuing configuration with the new server.");
		} catch (RuntimeException | LinkageError e) {
			ClientReset.logger.error(ClientReset.RESETMARKER, "Failed to reset the client for the server switch, closing connection", e);
			connection.disconnect(Component.translatable("clientresetpacket.disconnect.reset_failed"));
		} finally {
			resetting = false;
		}
	}

	/** Puts the connection's Forge network state back to what a fresh connection starts with. */
	private static void resetNetworkContext(Connection connection) {
		NetworkContext ctx = NetworkContext.get(connection);
		// Every member is optional and reset on its own: Forge builds differ in which ones exist (e.g.
		// remoteChannelsSize only exists in some 52.x builds), and one missing member must not stop the rest.
		int done = 0;
		done += step("setConnectionType", () -> {
			Method setType = NetworkContext.class.getDeclaredMethod("setConnectionType", ConnectionType.class);
			setType.setAccessible(true);
			setType.invoke(ctx, ConnectionType.VANILLA);
		});
		for (String name : new String[] {"remoteChannels", "sentChannels", "modList", "channelList"}) {
			done += step(name, () -> clear(ctx, name));
		}
		done += step("remoteChannelsSize", () -> {
			Field size = NetworkContext.class.getDeclaredField("remoteChannelsSize");
			size.setAccessible(true);
			size.setLong(ctx, 0L);
		});
		done += step("mismatchData", () -> {
			Field mismatch = NetworkContext.class.getDeclaredField("mismatchData");
			mismatch.setAccessible(true);
			mismatch.set(ctx, null);
		});
		if (done == 0) {
			// Not fatal: registries were already reverted, the next server just may not see our channel list.
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not reset any of Forge's network context; channel registration with the new server may be incomplete");
		} else {
			ClientReset.logger.debug(ClientReset.RESETMARKER, "Reset {} of 8 Forge network context members", done);
		}
	}

	@FunctionalInterface
	private interface ResetStep {
		void run() throws ReflectiveOperationException;
	}

	/** Runs one reset step; returns 1 if it worked, 0 if the member doesn't exist on this Forge build or failed. */
	private static int step(String member, ResetStep step) {
		try {
			step.run();
			return 1;
		} catch (NoSuchFieldException | NoSuchMethodException e) {
			ClientReset.logger.debug(ClientReset.RESETMARKER, "NetworkContext.{} doesn't exist on this Forge build; skipping it", member);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not reset NetworkContext.{}", member, e);
		}
		return 0;
	}

	private static void clear(NetworkContext ctx, String name) throws ReflectiveOperationException {
		Field field = NetworkContext.class.getDeclaredField(name);
		field.setAccessible(true);
		Object value = field.get(ctx);
		if (value instanceof Collection<?> collection) {
			collection.clear();
		} else if (value instanceof Map<?, ?> map) {
			map.clear();
		}
	}
}
