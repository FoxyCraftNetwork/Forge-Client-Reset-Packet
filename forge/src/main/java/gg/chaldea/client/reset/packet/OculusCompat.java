package gg.chaldea.client.reset.packet;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

/**
 * Soft-dependency bridge to Oculus (Iris for Forge).
 *
 * Players reported (issue #3) that switching servers with shaders enabled can fail, while turning shaders
 * off before the switch and back on afterwards always works. This class automates exactly that workaround,
 * on the render thread, through Iris' public API (net.irisshaders.iris.api.v0), so we never link against
 * Oculus at compile time and do nothing when it is not installed.
 */
@OnlyIn(Dist.CLIENT)
public final class OculusCompat {

	private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";
	// Iris main class moved packages between Oculus 1.6.x and 1.7+/1.8.
	private static final String[] IRIS_MAIN = {"net.irisshaders.iris.Iris", "net.coderbot.iris.Iris"};
	/** Ticks to wait in the new world before turning shaders back on (lets chunks/registries settle). */
	private static final int RESTORE_DELAY_TICKS = 40;

	private static volatile boolean restorePending;
	private static int ticksInWorld;
	private static boolean shutdownHookAdded;

	private OculusCompat() {
	}

	private static boolean present() {
		return ModList.get() != null && ModList.get().isLoaded("oculus");
	}

	private static Object apiConfig() throws ReflectiveOperationException {
		Class<?> api = Class.forName(IRIS_API);
		Object instance = api.getMethod("getInstance").invoke(null);
		return api.getMethod("getConfig").invoke(instance);
	}

	/** Must be called on the render thread, before Minecraft#clearLevel. */
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
			ClientReset.logger.info(ClientReset.RESETMARKER, "Oculus shaders are on; turning them off for the server switch");
			restorePending = true;
			ticksInWorld = 0;
			addShutdownHook();
			config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
		} catch (ClassNotFoundException ignored) {
			// Oculus without the Iris API (very old build): nothing we can do.
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not pause Oculus shaders for the reset", e);
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
			ClientReset.logger.info(ClientReset.RESETMARKER, "Oculus shaders turned back on after the server switch");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			ClientReset.logger.warn(ClientReset.RESETMARKER, "Could not re-enable Oculus shaders after the reset", e);
		}
	}

	/** Re-enable once the player has been in the new world for a moment. */
	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		if (!restorePending || event.phase != TickEvent.Phase.END) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
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
	 * setShadersEnabledAndApply(false) writes oculus.properties. If the game closes or crashes before we restore,
	 * write the setting back (file only, no OpenGL) so the player's shaders are not left off.
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
		}, "ClientResetPacket-OculusRestore"));
	}
}
