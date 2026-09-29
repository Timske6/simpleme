package io.github.timske6.simpleme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import io.github.timske6.simpleme.block.TileTerminal;
import io.github.timske6.simpleme.client.GuiTerminal;
import io.github.timske6.simpleme.menu.ContainerTerminal;
import io.github.timske6.simpleme.network.MainThread;
import io.github.timske6.simpleme.network.PacketStorageSync;

public class ClientProxy extends CommonProxy {

    /** A sync can arrive a moment before the screen is open; it waits up to this many ticks. */
    private static final int SYNC_WAIT_TICKS = 200;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        FMLCommonHandler.instance()
            .bus()
            .register(new MainThread.ClientTicker());
    }

    @Override
    public Object createClientGui(EntityPlayer player, TileTerminal terminal) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        // show as many item rows as fit on the screen
        int rows = (resolution.getScaledHeight() - 12 - ContainerTerminal.FIXED_HEIGHT) / 18;
        return new GuiTerminal(new ContainerTerminal(player, terminal, rows));
    }

    @Override
    public void handleStorageSync(PacketStorageSync packet) {
        MainThread.client(new ApplySync(packet));
    }

    private static final class ApplySync implements Runnable {

        private final PacketStorageSync packet;
        private int waited;

        ApplySync(PacketStorageSync packet) {
            this.packet = packet;
        }

        @Override
        public void run() {
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null && player.openContainer instanceof ContainerTerminal
                && player.openContainer.windowId == packet.windowId) {
                ((ContainerTerminal) player.openContainer).applySync(packet);
            } else if (player != null && waited++ < SYNC_WAIT_TICKS) {
                MainThread.client(this);
            }
        }
    }
}
