package io.github.timske6.simpleme.storage;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Identifies one kind of item: item + metadata/damage + NBT. GregTech items differ by metadata,
 * tools and many modded items by NBT, so all three are part of the key.
 */
public final class ItemKey {

    public final Item item;
    public final int meta;
    /** Never an empty compound: items without NBT have {@code null}. */
    private final NBTTagCompound tag;
    private final int hash;
    private int maxStackSize = -1;

    private ItemKey(Item item, int meta, NBTTagCompound tag) {
        this.item = item;
        this.meta = meta;
        this.tag = tag;
        this.hash = (Item.getIdFromItem(item) * 31 + meta) * 31 + (tag == null ? 0 : tag.hashCode());
    }

    public static ItemKey of(ItemStack stack) {
        return new ItemKey(stack.getItem(), stack.getItemDamage(), normalize(stack.getTagCompound(), true));
    }

    private static NBTTagCompound normalize(NBTTagCompound tag, boolean copy) {
        if (tag == null || tag.hasNoTags()) {
            return null;
        }
        return copy ? (NBTTagCompound) tag.copy() : tag;
    }

    public ItemStack toStack(int count) {
        ItemStack stack = new ItemStack(item, count, meta);
        if (tag != null) {
            stack.setTagCompound((NBTTagCompound) tag.copy());
        }
        return stack;
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.getItem() != item || stack.getItemDamage() != meta) {
            return false;
        }
        NBTTagCompound other = normalize(stack.getTagCompound(), false);
        return tag == null ? other == null : tag.equals(other);
    }

    public boolean hasTag() {
        return tag != null;
    }

    public NBTTagCompound tag() {
        return tag;
    }

    public int maxStackSize() {
        if (maxStackSize < 0) {
            maxStackSize = Math.max(1, toStack(1).getMaxStackSize());
        }
        return maxStackSize;
    }

    /** Saved by registry name (not numeric id), so it survives id changes between mod versions. */
    public NBTTagCompound writeToNBT() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("id", Item.itemRegistry.getNameForObject(item));
        nbt.setInteger("Damage", meta);
        if (tag != null) {
            nbt.setTag("tag", tag.copy());
        }
        return nbt;
    }

    /** @return the key, or null if the item no longer exists (e.g. its mod was removed) */
    public static ItemKey readFromNBT(NBTTagCompound nbt) {
        Object item = Item.itemRegistry.getObject(nbt.getString("id"));
        if (!(item instanceof Item)) {
            return null;
        }
        NBTTagCompound tag = nbt.hasKey("tag", 10) ? nbt.getCompoundTag("tag") : null;
        return new ItemKey((Item) item, nbt.getInteger("Damage"), normalize(tag, true));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemKey)) {
            return false;
        }
        ItemKey other = (ItemKey) o;
        return hash == other.hash && item == other.item
            && meta == other.meta
            && (tag == null ? other.tag == null : tag.equals(other.tag));
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return Item.itemRegistry.getNameForObject(item) + "@" + meta + (tag == null ? "" : tag.toString());
    }
}
