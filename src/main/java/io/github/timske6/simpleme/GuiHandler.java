package io.github.timske6.simpleme;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import cpw.mods.fml.common.network.IGuiHandler;
import io.github.timske6.simpleme.block.TileTerminal;
import io.github.timske6.simpleme.menu.ContainerTerminal;

public class GuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        TileEntity te = world.getTileEntity(x, y, z);
        if (id == SimpleME.GUI_TERMINAL && te instanceof TileTerminal) {
            return new ContainerTerminal(player, (TileTerminal) te, ContainerTerminal.DEFAULT_ROWS);
        }
        return null;
    }

    /**
     * The client screen does not need the block entity (all contents come from the server), so the screen also
     * opens when the block update has not reached the client yet (e.g. on a laggy server).
     */
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == SimpleME.GUI_TERMINAL) {
            TileEntity te = world.getTileEntity(x, y, z);
            return SimpleME.proxy.createClientGui(player, te instanceof TileTerminal ? (TileTerminal) te : null);
        }
        return null;
    }
}
