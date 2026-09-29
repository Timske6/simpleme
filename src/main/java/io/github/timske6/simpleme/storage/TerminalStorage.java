package io.github.timske6.simpleme.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.block.TerminalContents;

/** The actual item storage of a terminal: unlimited item types, each with a (long) count. */
public class TerminalStorage {

    /** Upper bound per item type, far below Long.MAX_VALUE so adding to a count never overflows. */
    public static final long MAX_PER_TYPE = Long.MAX_VALUE / 16;

    private final Map<ItemKey, Long> items = new LinkedHashMap<>();
    private final Runnable changeListener;
    private long version;

    public TerminalStorage(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    /** A terminal that itself holds items must not go into a terminal (it would nest storages). */
    public static boolean canStore(ItemKey key) {
        if (key == null || key.item == null) {
            return false;
        }
        return key.item != SimpleME.terminalItem() || TerminalContents.read(key.tag()) == null;
    }

    public long getAmount(ItemKey key) {
        Long amount = items.get(key);
        return amount == null ? 0 : amount;
    }

    public long insert(ItemKey key, long amount) {
        if (amount <= 0 || !canStore(key)) {
            return 0;
        }
        long current = getAmount(key);
        long inserted = Math.min(amount, MAX_PER_TYPE - current);
        if (inserted <= 0) {
            return 0;
        }
        items.put(key, current + inserted);
        changed();
        return inserted;
    }

    public long extract(ItemKey key, long amount) {
        if (amount <= 0 || key == null) {
            return 0;
        }
        long current = getAmount(key);
        long extracted = Math.min(amount, current);
        if (extracted <= 0) {
            return 0;
        }
        if (extracted == current) {
            items.remove(key);
        } else {
            items.put(key, current - extracted);
        }
        changed();
        return extracted;
    }

    /** Inserts as much of the stack as possible and shrinks its stack size by the inserted amount. */
    public void insertStack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || stack.getItem() == null) {
            return;
        }
        long inserted = insert(ItemKey.of(stack), stack.stackSize);
        stack.stackSize -= (int) inserted;
    }

    /** Extracts up to {@code max} items as a stack, or null if none are stored. */
    public ItemStack extractStack(ItemKey key, int max) {
        long extracted = extract(key, max);
        return extracted > 0 ? key.toStack((int) extracted) : null;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int typeCount() {
        return items.size();
    }

    public long totalCount() {
        long total = 0;
        for (long count : items.values()) {
            total = count > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + count;
        }
        return total;
    }

    public long getVersion() {
        return version;
    }

    public Map<ItemKey, Long> view() {
        return Collections.unmodifiableMap(items);
    }

    public List<ItemKey> keys() {
        return new ArrayList<>(items.keySet());
    }

    public NBTTagList writeToNBT() {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<ItemKey, Long> entry : items.entrySet()) {
            NBTTagCompound nbt = entry.getKey()
                .writeToNBT();
            nbt.setLong("Count", entry.getValue());
            list.appendTag(nbt);
        }
        return list;
    }

    /** Loads entries; ones that cannot be read (e.g. items of a removed mod) are skipped, not the whole storage. */
    public void readFromNBT(NBTTagList list) {
        items.clear();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound nbt = list.getCompoundTagAt(i);
            try {
                ItemKey key = ItemKey.readFromNBT(nbt);
                long count = nbt.getLong("Count");
                if (key != null && count > 0) {
                    items.put(key, Math.min(MAX_PER_TYPE, getAmount(key) + count));
                } else if (key == null) {
                    SimpleME.LOG.warn("Skipping unknown stored item {}", nbt.getString("id"));
                }
            } catch (RuntimeException e) {
                SimpleME.LOG.warn("Skipping unreadable stored item {}", nbt, e);
            }
        }
        version++;
    }

    private void changed() {
        version++;
        changeListener.run();
    }
}
