package gg.chaldea.client.reset.packet;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

final class VoiceChatReset {
    private static final String CLIENT_MANAGER = "de.maxhenkel.voicechat.voice.client.ClientManager";
    private static final String CLIENT_VOICECHAT = "de.maxhenkel.voicechat.voice.client.ClientVoicechat";
    private static final AtomicBoolean RESETTING = new AtomicBoolean();

    private VoiceChatReset() {
    }

    static void disconnect() {
        if (!RESETTING.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> managerType = Class.forName(CLIENT_MANAGER);
            Object manager = managerType.getMethod("instance").invoke(null);
            Method onDisconnect = managerType.getDeclaredMethod("onDisconnect");
            onDisconnect.setAccessible(true);
            onDisconnect.invoke(manager);
        } catch (ClassNotFoundException ignored) {
            // Simple Voice Chat is optional.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Older SVC builds may not expose ClientManager.onDisconnect().
            closeLegacyClient();
        } catch (LinkageError error) {
            // A different/incompatible SVC build must never crash the client reset.
            ClientReset.logger.debug(ClientReset.RESETMARKER, "Simple Voice Chat client reset is incompatible", error);
        } finally {
            // The SVC client has been fully detached. The next Minecraft PLAY
            // connection will create a fresh client through its normal join hook.
            RESETTING.set(false);
        }
    }

    private static void closeLegacyClient() {
        try {
            Class<?> type = Class.forName(CLIENT_VOICECHAT);
            Method getClient = type.getMethod("getInstance");
            Object instance = getClient.invoke(null);
            if (instance != null) {
                type.getMethod("disconnect").invoke(instance);
            }
        } catch (ClassNotFoundException ignored) {
            // Simple Voice Chat is optional.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            ClientReset.logger.debug(ClientReset.RESETMARKER, "Simple Voice Chat client reset was not available", exception);
        } catch (LinkageError error) {
            ClientReset.logger.debug(ClientReset.RESETMARKER, "Simple Voice Chat client reset is incompatible", error);
        }
    }
}
