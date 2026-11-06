package gg.chaldea.client.reset.packet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.AttributeKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.negotiation.NegotiationResult;

/**
 * Remembers that the current server did not speak NeoForge during configuration, and only then softens the
 * disconnects that would otherwise stop the client from joining.
 *
 * <p>The flag is set from NeoForge's non-NeoForge configuration path and cleared when a server switch starts or when
 * a real NeoForge negotiation completes. Nothing in this class runs for a NeoForge peer.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VanillaHubBypass {

	private static final AttributeKey<State> STATE = AttributeKey.valueOf("clientresetpacket:vanilla_hub");
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);
	private static final int DECODE_LOG_LIMIT = 5;
	/** Connection currently inside the non-NeoForge configuration method, so a failed channel check can see it. */
	private static final ThreadLocal<Connection> NEGOTIATING = new ThreadLocal<>();

	private VanillaHubBypass() {
	}

	public static void beginNegotiation(Connection connection) {
		NEGOTIATING.set(connection);
	}

	public static void endNegotiation() {
		NEGOTIATING.remove();
	}

	/** Called only after the listener has already been classified as a non-NeoForge peer. */
	public static void activate(Connection connection) {
		State state = state(connection, true);
		if (state == null || !state.fresh) {
			return;
		}
		state.fresh = false;
		ClientReset.logger.warn(ClientReset.RESETMARKER,
				"Non-NeoForge server detected. Staying connected past NeoForge's vanilla-server negotiation check. Mods that require NeoForge on the server may not work.");
		try {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> {
				try {
					SystemToast.addOrUpdate(minecraft.getToasts(), TOAST,
							Component.translatable("clientresetpacket.toast.title"),
							Component.translatable("clientresetpacket.toast.vanilla_hub"));
				} catch (RuntimeException ignored) {
					// The connection matters more than the notice.
				}
			});
		} catch (RuntimeException ignored) {
			// Same as above: a missing toast must not abort configuration.
		}
	}

	/** Drop the flag so the next backend is judged on its own negotiation. */
	public static void clear(Connection connection) {
		if (connection == null || connection.channel() == null) {
			return;
		}
		connection.channel().attr(STATE).set(null);
	}

	public static boolean isActive(Connection connection) {
		return state(connection, false) != null;
	}

	/**
	 * @return true when this failed check should be treated as success. A successful check stays successful.
	 *         A failure on any other connection stays a failure.
	 */
	public static boolean allowFailedNegotiation(NegotiationResult result) {
		if (result.success()) {
			return true;
		}
		Connection connection = NEGOTIATING.get();
		if (!isActive(connection)) {
			return false;
		}
		logOnce(connection, "channels", () -> {
			Map<ResourceLocation, Component> reasons = result.failureReasons();
			int shown = 0;
			StringBuilder ids = new StringBuilder();
			for (ResourceLocation id : reasons.keySet()) {
				if (shown == 8) {
					ids.append(", ...");
					break;
				}
				if (shown > 0) {
					ids.append(", ");
				}
				ids.append(id);
				shown++;
			}
			ClientReset.logger.warn(ClientReset.RESETMARKER,
					"{} client network channels are required by installed mods and are absent on this server ({}). Joining anyway.",
					reasons.size(), ids);
		});
		return true;
	}

	public static boolean keepConnection(Connection connection, String reason) {
		if (!isActive(connection)) {
			return false;
		}
		logOnce(connection, reason, () -> ClientReset.logger.warn(ClientReset.RESETMARKER,
				"NeoForge wanted to disconnect from this non-NeoForge server ({}). Staying connected. Mods that need a NeoForge server may not work.",
				reason));
		return true;
	}

	public static void noteDroppedPayload(Connection connection, String detail) {
		if (!isActive(connection)) {
			return;
		}
		logOnce(connection, "drop:" + detail, () -> ClientReset.logger.warn(ClientReset.RESETMARKER,
				"Not sending a modded payload to a non-NeoForge server: {}", detail));
	}

	/**
	 * Packet-frame decode errors only. A handler that throws after a packet was decoded still disconnects, on a
	 * vanilla hub and on a NeoForge server.
	 */
	public static boolean isDecodeFailure(Throwable thrown) {
		boolean decode = false;
		Throwable current = thrown;
		while (current != null) {
			String typeName = current.getClass().getName();
			if (typeName.endsWith("SkipPacketException")
					|| current instanceof CorruptedFrameException
					|| current instanceof TooLongFrameException) {
				return false;
			}
			if (current instanceof DecoderException) {
				decode = true;
			}
			String message = current.getMessage();
			if (message != null && (message.contains("Failed decoding")
					|| message.contains("Failed to decode")
					|| message.contains("Packet was larger"))) {
				decode = true;
			}
			Throwable next = current.getCause();
			current = next == current ? null : next;
		}
		return decode;
	}

	public static void noteDecodeFailure(Connection connection, Throwable thrown) {
		State state = state(connection, false);
		if (state == null) {
			return;
		}
		int count = ++state.decodeLogs;
		if (count <= DECODE_LOG_LIMIT) {
			ClientReset.logger.warn(ClientReset.RESETMARKER,
					"Ignoring a packet decode error from a non-NeoForge server: {}", thrown.toString());
			ClientReset.logger.debug(ClientReset.RESETMARKER, "Packet decode error", thrown);
		} else if (count == DECODE_LOG_LIMIT + 1) {
			ClientReset.logger.warn(ClientReset.RESETMARKER,
					"Further packet decode errors from this non-NeoForge server will not be logged.");
		}
	}

	private static void logOnce(Connection connection, String key, Runnable log) {
		State state = state(connection, false);
		if (state == null || !state.logged.add(key)) {
			return;
		}
		log.run();
	}

	private static State state(Connection connection, boolean create) {
		if (connection == null || connection.channel() == null) {
			return null;
		}
		var attribute = connection.channel().attr(STATE);
		State state = attribute.get();
		if (state == null && create) {
			state = new State();
			attribute.set(state);
		}
		return state;
	}

	private static final class State {
		private boolean fresh = true;
		private int decodeLogs;
		private final Set<String> logged = ConcurrentHashMap.newKeySet();
	}
}
