package gg.chaldea.client.reset.packet;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * Soft-dependency bridge to Oculus / Iris shaders.
 *
 * Players reported (issue #3) that switching servers with shaders enabled can fail, while turning shaders
 * off before the switch and back on afterwards always works. This class automates exactly that workaround,
 * on the render thread, through Iris' public API ({@code net.irisshaders.iris.api.v0}), so we never link
 * against a shader mod at compile time and do nothing when none is installed.
 *
 * On NeoForge this is Iris itself (Iris ships NeoForge builds). Detection is by the Iris API class rather than a
 * mod id, so ports that ship the same API work too.
 */
@OnlyIn(Dist.CLIENT)
public final class OculusCompat {

	private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";
	private static final String IRIS_API_CONFIG = "net.irisshaders.iris.api.v0.IrisApiConfig";
	/** Failed re-enable attempts before giving up (the shutdown hook still writes the setting back). */
	private static final int MAX_RESTORE_ATTEMPTS = 3;
	// Iris main class moved packages between Oculus 1.6.x and 1.7+/1.8.
	private static final String[] IRIS_MAIN = {"net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"};
	/** Ticks to wait in the new world before turning shaders back on (lets chunks/registries settle). */
	private static final int RESTORE_DELAY_TICKS = 40;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);

	private static Boolean present;
	private static volatile boolean restorePending;
	private static int ticksInWorld;
	private static int restoreAttempts;
	private static boolean shutdownHookAdded;

	private OculusCompat() {
	}

	private static boolean present() {
		if (present == null) {
			try {
				Class.forName(IRIS_API, false, OculusCompat.class.getClassLoader());
				present = true;
			} catch (ClassNotFoundException | LinkageError e) {
				present = false;
			}
		}
		return present;
	}

	private static Object apiConfig() throws ReflectiveOperationException {
		Class<?> api = Class.forName(IRIS_API);
		Object instance = api.getMethod("getInstance").invoke(null);
		return api.getMethod("getConfig").invoke(instance);
	}

	/** Look methods up on the public IrisApiConfig interface, not on the (internal, possibly non-public) impl class. */
	private static Method configMethod(String name, Class<?>... params) throws ReflectiveOperationException {
		return Class.forName(IRIS_API_CONFIG).getMethod(name, params);
	}

	private static boolean shadersEnabled(Object config) throws ReflectiveOperationException {
		return (boolean) configMethod("areShadersEnabled").invoke(config);
	}

	private static void setShadersEnabledAndApply(Object config, boolean enabled) throws ReflectiveOperationException {
		configMethod("setShadersEnabledAndApply", boolean.class).invoke(config, enabled);
	}

	private static void toast(String messageKey) {
		try {
			SystemToast.addOrUpdate(Minecraft.getInstance().getToasts(), TOAST,
					Component.translatable("clientresetpacket.toast.title"), Component.translatable(messageKey));
		} catch (RuntimeException ignored) {
			// Purely informational.
		}
	}

	/** Must be called on the render thread, before the client level is cleared. */
	static void beforeClear() {
		if (!present() || restorePending) {
			return;
		}
		try {
			Object config = apiConfig();
			boolean enabled = shadersEnabled(config);
			if (!enabled) {
				return;
			}
			ClientReset.logger.info(ClientReset.RESETMARKER, "Shaders are on; turning them off for the server switch");
			restorePending = true;
			ticksInWorld = 0;
			restoreAttempts = 0;
			addShutdownHook();
			setShadersEnabledAndApply(config, false);
			toast("clientresetpacket.toast.shaders_paused");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not pause shaders for the server switch", e);
		}
	}

	/**
	 * Re-enables shaders if we turned them off. Callable from any thread (e.g. Forge's handshake opens
	 * DisconnectedScreen on the Netty thread): the actual work is always handed to the render thread.
	 * restorePending is only cleared once the restore succeeded, the player took over, or we gave up.
	 */
	private static void restore() {
		if (!restorePending) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread()) {
			mc.execute(OculusCompat::restore);
			return;
		}
		try {
			Object config = apiConfig();
			if (shadersEnabled(config)) {
				// Shaders are no longer off as we left them: the player changed the setting during the switch.
				restorePending = false;
				ClientReset.logger.info(ClientReset.RESETMARKER, "Shaders were re-enabled during the server switch; leaving them as they are");
				return;
			}
			setShadersEnabledAndApply(config, true);
			restorePending = false;
			ClientReset.logger.info(ClientReset.RESETMARKER, "Shaders turned back on after the server switch");
				toast("clientresetpacket.toast.shaders_restored");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			ticksInWorld = 0;
			if (++restoreAttempts >= MAX_RESTORE_ATTEMPTS) {
				restorePending = false;
				ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not re-enable shaders after the server switch; giving up", e);
				toast("clientresetpacket.toast.shaders_restore_failed");
			} else {
				ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not re-enable shaders after the server switch; will retry", e);
			}
		}
	}

	/** The player opened the shader pack screen during the switch: they decide, we no longer restore. */
	private static boolean playerTookOver(Minecraft mc) {
		if (mc.screen != null && mc.screen.getClass().getName().endsWith("ShaderPackScreen")) {
			restorePending = false;
			ClientReset.logger.info(ClientReset.RESETMARKER, "Shader settings opened during the server switch; leaving shaders to the player");
			return true;
		}
		return false;
	}

	/** Re-enable once the player has been in the new world for a moment. */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (!restorePending) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (playerTookOver(mc)) {
			return;
		}
		if (mc.level == null || mc.player == null || mc.getConnection() == null || mc.screen instanceof ReceivingLevelScreen) {
			ticksInWorld = 0;
			return;
		}
		if (++ticksInWorld >= RESTORE_DELAY_TICKS) {
			restore();
		}
	}

	/** If the switch failed and the player ended up back in the menus, put shaders back right away. */
	@SubscribeEvent
	public static void onScreenOpening(ScreenEvent.Opening event) {
		if (restorePending && (event.getNewScreen() instanceof TitleScreen
				|| event.getNewScreen() instanceof JoinMultiplayerScreen
				|| event.getNewScreen() instanceof DisconnectedScreen)) {
			restore();
		}
	}

	/**
	 * setShadersEnabledAndApply(false) writes the shader mod's properties file. If the game closes or crashes before
	 * we restore, write the setting back (file only, no OpenGL) so the player's shaders are not left off.
	 */
	private static void addShutdownHook() {
		if (shutdownHookAdded) {
			return;
		}
		shutdownHookAdded = true;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (!restorePending) {
				return;
			}
			for (String name : IRIS_MAIN) {
				try {
					Object irisConfig = Class.forName(name).getMethod("getIrisConfig").invoke(null);
					irisConfig.getClass().getMethod("setShadersEnabled", boolean.class).invoke(irisConfig, true);
					Method save = irisConfig.getClass().getMethod("save");
					save.invoke(irisConfig);
					return;
				} catch (Throwable ignored) {
				}
			}
		}, "ClientResetPacket-ShaderRestore"));
	}
}
