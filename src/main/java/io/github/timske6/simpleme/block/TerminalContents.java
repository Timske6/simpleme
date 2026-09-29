package io.github.timske6.simpleme.block;

import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Item data of a filled terminal: which storage it belongs to, plus a small summary for the tooltip.
 * Stored as {@code {SimpleME:{StorageId:"<uuid>",Types:..,Total:..}}}, so admins can restore a lost terminal
 * with {@code /give <player> simpleme:terminal 1 0 {SimpleME:{StorageId:"<uuid>"}}}.
 */
public final class TerminalContents {

    public static final String TAG = "SimpleME";

    public final UUID id;
    public final int types;
    public final long total;

    public TerminalContents(UUID id, int types, long total) {
        this.id = id;
        this.types = types;
        this.total = total;
    }

    /** @return the contents, or null if the tag does not describe a filled terminal */
    public static TerminalContents read(NBTTagCompound itemTag) {
        if (itemTag == null || !itemTag.hasKey(TAG, 10)) {
            return null;
        }
        NBTTagCompound data = itemTag.getCompoundTag(TAG);
        try {
            UUID id = UUID.fromString(data.getString("StorageId"));
            return new TerminalContents(id, data.getInteger("Types"), data.getLong("Total"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static TerminalContents read(ItemStack stack) {
        return stack == null ? null : read(stack.getTagCompound());
    }

    public void writeTo(ItemStack stack) {
        NBTTagCompound data = new NBTTagCompound();
        data.setString("StorageId", id.toString());
        data.setInteger("Types", types);
        data.setLong("Total", total);
        if (!stack.hasTagCompound()) {
            stack.setTagCompound(new NBTTagCompound());
        }
        stack.getTagCompound()
            .setTag(TAG, data);
    }
}
