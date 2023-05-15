package gg.chaldea.client.reset.packet.mixin;

import com.google.common.collect.Maps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.network.*;
import net.minecraftforge.registries.ForgeRegistry;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static net.minecraftforge.registries.ForgeRegistry.REGISTRIES;

@Mixin(HandshakeHandler.class)
public class MixinFMLHandshakeHandler {


    @Shadow(remap = false) @Final static Marker FMLHSMARKER;
    @Shadow(remap = false) @Final private static Logger LOGGER;

    @Shadow(remap = false) private Set<ResourceLocation> registriesToReceive;
    @Shadow(remap = false) private Map<ResourceLocation, ForgeRegistry.Snapshot> registrySnapshots;

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    void handleServerModListOnClient(HandshakeMessages.S2CModList serverModList, Supplier<NetworkEvent.Context> c) {
        LOGGER.debug(FMLHSMARKER, "Logging into server with mod list [{}]", String.join(", ", serverModList.getModList()));
        c.get().setPacketHandled(true);
        NetworkConstantsAccessor.getHandshakeChannel().reply(new HandshakeMessages.C2SModListReply(), c.get());
        LOGGER.debug(FMLHSMARKER, "Accepted server connection");
        // Set the modded marker on the channel so we know we got packets
        c.get().getNetworkManager().channel().attr(NetworkConstantsAccessor.getFmlNetversion()).set(NetworkConstants.NETVERSION);
        c.get().getNetworkManager().channel().attr(NetworkConstantsAccessor.getFmlConnectionData())
                .set(new ConnectionData.ModMismatchData(serverModList.getModList(), serverModList.getChannels())); //FIXME

        this.registriesToReceive = new HashSet<>(serverModList.getRegistries());
        this.registrySnapshots = Maps.newHashMap();
        LOGGER.debug(REGISTRIES, "Expecting {} registries: {}", ()->this.registriesToReceive.size(), ()->this.registriesToReceive);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite(remap = false)
    void handleClientModListOnServer(HandshakeMessages.C2SModListReply clientModList, Supplier<NetworkEvent.Context> c) {
        LOGGER.debug(FMLHSMARKER, "Received client connection with modlist [{}]", String.join(", ", clientModList.getModList()));
        final Map<ResourceLocation, String> mismatchedChannels = NetworkRegistryAccessor.callValidateServerChannels(clientModList.getChannels());
        c.get().getNetworkManager().channel().attr(NetworkConstantsAccessor.getFmlConnectionData()).set(new FMLConnectionData(clientModList.getModList(), clientModList.getChannels())); //FIXME
        c.get().setPacketHandled(true);
        if (!mismatchedChannels.isEmpty()) {
            LOGGER.error(FMLHSMARKER, "Terminating connection with client, mismatched mod list");
            c.get().getNetworkManager().send(new ClientboundLoginDisconnectPacket(Component.literal("Connection closed - mismatched mod channel list")));
            ((NetworkEvent.Context)c.get()).getNetworkManager().disconnect(Component.literal("Connection closed - mismatched mod channel list"));
        } else {
            LOGGER.debug(FMLHSMARKER, "Accepted client connection mod list");
        }
    }
}
