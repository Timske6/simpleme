package io.github.timske6.simpleme.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.storage.ItemKey;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: changes of the terminal contents. With {@code reset} the client replaces its whole
 * view, otherwise each entry sets the new count of one item type (0 = removed). Large updates are split
 * into several packets of at most {@link #MAX_ENTRIES} entries.
 */
public class PacketStorageSync implements IMessage {

    public static final int MAX_ENTRIES = 256;

    public int windowId;
    public boolean reset;
    public final List<ItemKey> keys = new ArrayList<>();
    public final List<Long> counts = new ArrayList<>();

    public PacketStorageSync() {}

    public PacketStorageSync(int windowId, boolean reset) {
        this.windowId = windowId;
        this.reset = reset;
    }

    public void add(ItemKey key, long count) {
        keys.add(key);
        counts.add(count);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        reset = buf.readBoolean();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            ItemStack stack = ByteBufUtils.readItemStack(buf);
            long count = buf.readLong();
            if (stack != null && stack.getItem() != null) {
                add(ItemKey.of(stack), count);
            }
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        buf.writeBoolean(reset);
        buf.writeInt(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            ByteBufUtils.writeItemStack(
                buf,
                keys.get(i)
                    .toStack(1));
            buf.writeLong(counts.get(i));
        }
    }

    public static class Handler implements IMessageHandler<PacketStorageSync, IMessage> {

        @Override
        public IMessage onMessage(final PacketStorageSync message, MessageContext ctx) {
            SimpleME.proxy.handleStorageSync(message);
            return null;
        }
    }
}
