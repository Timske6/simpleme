package io.github.timske6.simpleme.network;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.github.timske6.simpleme.SimpleME;

public final class Network {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(SimpleME.MODID);

    private Network() {}

    public static void init() {
        CHANNEL.registerMessage(PacketStorageClick.Handler.class, PacketStorageClick.class, 0, Side.SERVER);
        CHANNEL.registerMessage(PacketFillGrid.Handler.class, PacketFillGrid.class, 1, Side.SERVER);
        CHANNEL.registerMessage(PacketStorageSync.Handler.class, PacketStorageSync.class, 2, Side.CLIENT);
    }
}
