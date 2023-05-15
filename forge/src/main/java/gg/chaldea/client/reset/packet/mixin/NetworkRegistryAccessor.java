package gg.chaldea.client.reset.packet.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

@Mixin(NetworkRegistry.class)
public interface NetworkRegistryAccessor {

    @Invoker
    static Map<ResourceLocation, String> callValidateServerChannels(final Map<ResourceLocation, String> channels) {
        throw new UnsupportedOperationException();
    }
}
