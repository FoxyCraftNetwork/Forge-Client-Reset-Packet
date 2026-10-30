package gg.chaldea.client.reset.packet;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

/**
 * Soft-dependency bridge to Oculus / Iris shaders.
 *
 * Players reported (issue #3) that switching servers with shaders enabled can fail, while turning shaders
 * off before the switch and back on afterwards always works. This class automates exactly that workaround,
 * on the render thread, through Iris' public API ({@code net.irisshaders.iris.api.v0}), so we never link
 * against a shader mod at compile time and do nothing when none is installed.
 *
 * On Forge 1.21.1 there is no official Oculus build; unofficial ports (e.g. "ForgeOculus") ship the same
 * Iris API. Detection is by the API class rather than a mod id so any port that ships it works.
 */
@OnlyIn(Dist.CLIENT)
public final class OculusCompat {

	private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";
	// Iris main class moved packages between Oculus 1.6.x and 1.7+/1.8.
	private static final String[] IRIS_MAIN = {"net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"};
	/** Ticks to wait in the new world before turning shaders back on (lets chunks/registries settle). */
	private static final int RESTORE_DELAY_TICKS = 40;
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);

	private static Boolean present;
	private static volatile boolean restorePending;
	private static int ticksInWorld;
	private static boolean shutdownHookAdded;

	private OculusCompat() {
	}

	/** EventBus 7 (Forge 1.21.6+): listeners are registered per event on each event's own BUS. */
	static void register() {
		TickEvent.ClientTickEvent.Post.BUS.addListener(OculusCompat::onClientTick);
		ScreenEvent.Opening.BUS.addListener(OculusCompat::onScreenOpening);
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

	private static void toast(String messageKey) {
		try {
			SystemToast.addOrUpdate(Minecraft.getInstance().getToastManager(), TOAST,
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
			boolean enabled = (boolean) config.getClass().getMethod("areShadersEnabled").invoke(config);
			if (!enabled) {
				return;
			}
			ClientReset.logger.info(ClientReset.RESETMARKER, "Shaders are on; turning them off for the server switch");
			restorePending = true;
			ticksInWorld = 0;
			addShutdownHook();
			config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
			toast("clientresetpacket.toast.shaders_paused");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not pause shaders for the server switch", e);
		}
	}

	/** Render thread only. Re-enables shaders if we turned them off. */
	private static void restore() {
		if (!restorePending) {
			return;
		}
		restorePending = false;
		try {
			Object config = apiConfig();
			config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, true);
			ClientReset.logger.info(ClientReset.RESETMARKER, "Shaders turned back on after the server switch");
			toast("clientresetpacket.toast.shaders_restored");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not re-enable shaders after the server switch", e);
			toast("clientresetpacket.toast.shaders_restore_failed");
		}
	}

	/**
	 * The "loading terrain" screen is ReceivingLevelScreen up to 1.21.8 and LevelLoadingScreen from 1.21.9. Compare by
	 * name (Mojang names at runtime on 1.20.6+) so one jar works on both sides of that rename.
	 */
	private static boolean isLevelLoadingScreen(Object screen) {
		if (screen == null) {
			return false;
		}
		String name = screen.getClass().getName();
		return name.equals("net.minecraft.client.gui.screens.ReceivingLevelScreen")
				|| name.equals("net.minecraft.client.gui.screens.LevelLoadingScreen");
	}

	/** Re-enable once the player has been in the new world for a moment. */
	static void onClientTick(TickEvent.ClientTickEvent.Post event) {
		if (!restorePending) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.getConnection() == null || isLevelLoadingScreen(mc.screen)) {
			ticksInWorld = 0;
			return;
		}
		if (++ticksInWorld >= RESTORE_DELAY_TICKS) {
			restore();
		}
	}

	/** If the switch failed and the player ended up back in the menus, put shaders back right away. */
	static void onScreenOpening(ScreenEvent.Opening event) {
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
