package gg.chaldea.client.reset.packet.mixin;

import io.netty.util.AttributeKey;
import net.minecraftforge.network.ConnectionData;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NetworkConstants.class)
public interface NetworkConstantsAccessor {
    @Accessor("handshakeChannel")
    static SimpleChannel getHandshakeChannel() {
        throw new AssertionError();
    }

    @Accessor("FML_NETVERSION")
    static AttributeKey<String> getFmlNetversion() {
        throw new AssertionError();
    }

    @Accessor("FML_MOD_MISMATCH_DATA")
    static AttributeKey<ConnectionData.ModMismatchData> getFmlConnectionData() {
        throw new AssertionError();
    }
}
