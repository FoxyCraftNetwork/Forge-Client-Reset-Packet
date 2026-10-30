package gg.chaldea.client.reset.packet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import io.netty.util.AttributeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.filters.NetworkFilters;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.registries.RegistryManager;

/**
 * Makes a vanilla re-configuration (the way proxies switch servers on 1.20.2+) leave the NeoForge client in the same
 * state as a fresh connection, so the next backend can be NeoForge, another modded server, or plain vanilla/Paper.
 *
 * <p>On {@code ClientboundStartConfigurationPacket} vanilla calls {@code Minecraft#clearClientLevel}. Everything below
 * only happens on {@code Minecraft#disconnect} or on a brand-new {@link Connection}:</p>
 * <ul>
 *     <li>{@code LevelEvent.Unload} for the old client level (NeoForge 21.1 fired it from clearClientLevel, 21.2+ no
 *     longer does).</li>
 *     <li>{@code ClientPlayerNetworkEvent.LoggingOut} (NeoForge resets WORLD-scoped config caches here, other mods
 *     drop per-server state).</li>
 *     <li>{@link RegistryManager#revertToFrozen()}: otherwise the previous NeoForge server's registry snapshot (numeric
 *     ids for blocks, items, ...) stays applied. A vanilla backend sends no snapshot, so its chunks and items would be
 *     decoded with the old server's ids.</li>
 *     <li>The {@link ChannelAttributes} on the Netty channel: negotiated payload setup, connection type, ad-hoc
 *     ({@code minecraft:register}) and common ({@code c:register}) channels. Stale values make the client believe the
 *     new backend still accepts the previous backend's channels.</li>
 *     <li>NeoForge's packet filters ({@link NetworkFilters}): e.g. the packet splitter negotiated with a NeoForge server
 *     would stay in the pipeline while talking to a vanilla one. NeoForge re-injects the right filters once the new
 *     backend has been negotiated.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class ReconfigurationReset {

	/** True between {@link #beforeClear} and {@link #afterClear} (render thread only). */
	private static boolean resetting;

	private ReconfigurationReset() {
	}

	/** Render thread, inside clearClientLevel after the screen change, while the old level is still set. */
	public static void onLevelClosing(ClientLevel level) {
		if (!resetting || level == null) {
			return;
		}
		try {
			NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
		} catch (RuntimeException e) {
			ClientReset.logger.error(ClientReset.RESETMARKER, "A mod threw while handling the level unload event for the server switch", e);
		}
	}

	/** Render thread, right before {@code Minecraft#clearClientLevel} during re-configuration. */
	public static void beforeClear(Minecraft mc, Connection connection) {
		resetting = true;
		ClientReset.logger.info(ClientReset.RESETMARKER, "Server asked the client to re-configure (proxy server switch); resetting client state.");
		// Shaders off before the level and render pipeline are torn down (issues #3/#12).
		OculusCompat.beforeClear();
		try {
			ClientHooks.firePlayerLogout(mc.gameMode, mc.player);
		} catch (RuntimeException e) {
			ClientReset.logger.error(ClientReset.RESETMARKER, "A mod threw while handling the logout event for the server switch", e);
		}
	}

	/** Render thread, right after clearClientLevel. The new backend's negotiation repopulates everything cleared here. */
	public static void afterClear(Connection connection) {
		try {
			if (!connection.isMemoryConnection()) {
				RegistryManager.revertToFrozen();
			}
			resetChannelState(connection);
			ClientReset.logger.info(ClientReset.RESETMARKER, "Client reset complete, continuing configuration with the new server.");
		} catch (RuntimeException | LinkageError e) {
			ClientReset.logger.error(ClientReset.RESETMARKER, "Failed to reset the client for the server switch, closing connection", e);
			connection.disconnect(Component.translatable("clientresetpacket.disconnect.reset_failed"));
		} finally {
			resetting = false;
		}
	}

	/** Drops every NeoForge per-connection attribute and packet filter, like a new connection. */
	private static void resetChannelState(Connection connection) {
		if (connection.channel() == null) {
			return;
		}
		NetworkFilters.cleanIfNecessary(connection);
		// Reflect over the keys so a NeoForge version that adds a key gets reset too.
		int cleared = 0;
		for (Field field : ChannelAttributes.class.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) && AttributeKey.class.isAssignableFrom(field.getType())) {
				try {
					field.setAccessible(true);
					connection.channel().attr((AttributeKey<?>) field.get(null)).set(null);
					cleared++;
				} catch (ReflectiveOperationException | RuntimeException e) {
					ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not reset NeoForge channel attribute {}", field.getName(), e);
				}
			}
		}
		ClientReset.logger.info(ClientReset.RESETMARKER, "Cleared {} NeoForge connection attributes and the packet filters", cleared);
	}
}
