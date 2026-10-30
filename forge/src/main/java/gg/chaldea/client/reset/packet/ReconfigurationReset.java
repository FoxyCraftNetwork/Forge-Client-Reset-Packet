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
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.network.ConnectionType;
import net.minecraftforge.network.NetworkContext;
import net.minecraftforge.registries.GameData;

/**
 * Makes a vanilla re-configuration (the way proxies switch servers on 1.20.2+) clean up the client the same way
 * a real disconnect does on Forge 52.
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
			ForgeHooksClient.firePlayerLogout(mc.gameMode, mc.player);
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
			MinecraftForge.EVENT_BUS.post(new LevelEvent.Unload(level));
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
		try {
			Method setType = NetworkContext.class.getDeclaredMethod("setConnectionType", ConnectionType.class);
			setType.setAccessible(true);
			setType.invoke(ctx, ConnectionType.VANILLA);
			clear(ctx, "remoteChannels");
			clear(ctx, "sentChannels");
			clear(ctx, "modList");
			clear(ctx, "channelList");
			try {
				// Only some Forge builds track this counter (missing on e.g. 56.x/57.x/59.x).
				Field size = NetworkContext.class.getDeclaredField("remoteChannelsSize");
				size.setAccessible(true);
				size.setLong(ctx, 0L);
			} catch (NoSuchFieldException ignored) {
			}
			Field mismatch = NetworkContext.class.getDeclaredField("mismatchData");
			mismatch.setAccessible(true);
			mismatch.set(ctx, null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Not fatal: registries were already reverted, the next server just may not see our channel list.
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not reset Forge's network context; channel registration with the new server may be incomplete", e);
		}
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
