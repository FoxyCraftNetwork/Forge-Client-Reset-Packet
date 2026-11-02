package net.foxmcloud.clientresetpacket.mixin;

import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Client-only (listed under "client" in mixins.clientresetpacket.json).
 * If the connection drops while the client is still on the "Negotiating..." screen left by a reset
 * (e.g. mismatched mods or a timeout on the new backend), show the disconnect reason instead of
 * leaving the player stuck on that screen.
 */
@Mixin(Connection.class)
public class MixinNetworkManager {

    @Shadow private Channel channel;
    @Shadow private Component disconnectedReason;

    /**
     * @author danorris709 / FoxyCraftNetwork
     * @reason Leave the reset's negotiating screen when the connection closes.
     */
    @Overwrite
    public void disconnect(Component reason) {
        if (this.channel.isOpen()) {
            this.channel.close().awaitUninterruptibly();
            this.disconnectedReason = reason;

            if (Minecraft.getInstance().screen instanceof GenericDirtMessageScreen dirtMessageScreen
                    && dirtMessageScreen.getTitle() instanceof TranslatableComponent translatable
                    && translatable.getKey().equals("connect.negotiating")) {
                Minecraft.getInstance().setScreen(
                        new DisconnectedScreen(new JoinMultiplayerScreen(new TitleScreen()), new TextComponent(""), disconnectedReason));
            }
        }
    }
}
