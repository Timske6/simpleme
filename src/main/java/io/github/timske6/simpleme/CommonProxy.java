package io.github.timske6.simpleme;

import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.github.timske6.simpleme.block.TileTerminal;
import io.github.timske6.simpleme.network.PacketStorageSync;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}

    public void init(FMLInitializationEvent event) {}

    /** Client only: creates the terminal screen. */
    public Object createClientGui(EntityPlayer player, TileTerminal terminal) {
        return null;
    }

    /** Client only: applies storage contents sent by the server. */
    public void handleStorageSync(PacketStorageSync packet) {}
}
