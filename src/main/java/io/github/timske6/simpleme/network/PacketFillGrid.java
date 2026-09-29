package io.github.timske6.simpleme.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.github.timske6.simpleme.menu.ContainerTerminal;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: NEI recipe transfer. For each of the 9 grid slots a list of acceptable items
 * (the recipe's alternatives, e.g. all kinds of planks). The server fills the grid from the storage.
 */
public class PacketFillGrid implements IMessage {

    /** Limits what a client can make the server look at. */
    public static final int MAX_CANDIDATES = 64;

    public int windowId;
    public boolean max;
    public final List<List<ItemStack>> slots = new ArrayList<>();

    public PacketFillGrid() {}

    public PacketFillGrid(int windowId, List<List<ItemStack>> slots, boolean max) {
        this.windowId = windowId;
        this.max = max;
        this.slots.addAll(slots);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        max = buf.readBoolean();
        for (int slot = 0; slot < 9; slot++) {
            int count = Math.min(buf.readUnsignedByte(), MAX_CANDIDATES);
            List<ItemStack> candidates = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ItemStack stack = ByteBufUtils.readItemStack(buf);
                if (stack != null) {
                    candidates.add(stack);
                }
            }
            slots.add(candidates);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeBoolean(max);
        for (int slot = 0; slot < 9; slot++) {
            List<ItemStack> candidates = slot < slots.size() ? slots.get(slot) : new ArrayList<>();
            int count = Math.min(candidates.size(), MAX_CANDIDATES);
            buf.writeByte(count);
            for (int i = 0; i < count; i++) {
                ByteBufUtils.writeItemStack(buf, candidates.get(i));
            }
        }
    }

    public static class Handler implements IMessageHandler<PacketFillGrid, IMessage> {

        @Override
        public IMessage onMessage(final PacketFillGrid message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            MainThread.server(() -> {
                if (player.openContainer instanceof ContainerTerminal
                    && player.openContainer.windowId == message.windowId) {
                    ((ContainerTerminal) player.openContainer).fillGrid(player, message.slots, message.max);
                }
            });
            return null;
        }
    }
}
