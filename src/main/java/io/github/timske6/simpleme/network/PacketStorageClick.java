package io.github.timske6.simpleme.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.github.timske6.simpleme.menu.ContainerTerminal;
import io.netty.buffer.ByteBuf;

/** Client → server: the player clicked on an item (or an empty cell) of the terminal grid. */
public class PacketStorageClick implements IMessage {

    /** Left click, empty cursor: take up to a full stack onto the cursor. */
    public static final int TAKE_STACK = 0;
    /** Right click, empty cursor: take half a stack. */
    public static final int TAKE_HALF = 1;
    /** Shift + left click: move a full stack into the player inventory. */
    public static final int QUICK_MOVE = 2;
    /** Left click with an item on the cursor: store the whole cursor stack. */
    public static final int INSERT_ALL = 3;
    /** Right click with an item on the cursor: store a single item. */
    public static final int INSERT_ONE = 4;
    /** Middle click in creative: copy a full stack without taking it out. */
    public static final int CLONE = 5;
    /** Shift + right click, empty cursor: take a single item. */
    public static final int TAKE_ONE = 6;

    public int windowId;
    public ItemStack item;
    public int action;

    public PacketStorageClick() {}

    public PacketStorageClick(int windowId, ItemStack item, int action) {
        this.windowId = windowId;
        this.item = item;
        this.action = action;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        item = ByteBufUtils.readItemStack(buf);
        action = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        ByteBufUtils.writeItemStack(buf, item);
        buf.writeByte(action);
    }

    public static class Handler implements IMessageHandler<PacketStorageClick, IMessage> {

        @Override
        public IMessage onMessage(final PacketStorageClick message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            MainThread.server(() -> {
                if (player.openContainer instanceof ContainerTerminal
                    && player.openContainer.windowId == message.windowId) {
                    ((ContainerTerminal) player.openContainer).handleStorageClick(player, message.item, message.action);
                }
            });
            return null;
        }
    }
}
