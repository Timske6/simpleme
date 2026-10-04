package io.github.timske6.simpleme.storage;

import io.github.timske6.simpleme.ModRegistry;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.InsertionOnlyStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.item.ItemStack;

/**
 * The actual item storage of a terminal: unlimited item types, each with a (long) count.
 */
public class TerminalStorage {
	/** Upper bound per item type (about 9.2 quintillion). All additions are checked against it, so counts never overflow. */
	public static final long MAX_PER_TYPE = Long.MAX_VALUE;

	private final Object2LongLinkedOpenHashMap<ItemVariant> items = new Object2LongLinkedOpenHashMap<>();
	private final Runnable changeListener;
	private final HopperInsertStorage insertStorage = new HopperInsertStorage();
	private long version;

	public TerminalStorage(Runnable changeListener) {
		this.changeListener = changeListener;
	}

	/** Items that must never go into a terminal (a terminal that itself holds items would nest storages). */
	public static boolean canStore(ItemVariant variant) {
		if (variant.isBlank()) {
			return false;
		}
		if (variant.getItem() != ModRegistry.TERMINAL_ITEM) {
			return true;
		}
		ItemStack stack = variant.toStack();
		TerminalContents contents = stack.get(ModRegistry.STORAGE);
		StoredItems legacy = stack.get(ModRegistry.LEGACY_STORED_ITEMS);
		return (contents == null || contents.types() == 0) && (legacy == null || legacy.entries().isEmpty());
	}

	public long getAmount(ItemVariant variant) {
		return items.getLong(variant);
	}

	public long insert(ItemVariant variant, long amount) {
		if (amount <= 0 || !canStore(variant)) {
			return 0;
		}
		long current = items.getLong(variant);
		long inserted = Math.min(amount, MAX_PER_TYPE - current);
		if (inserted <= 0) {
			return 0;
		}
		items.put(variant, current + inserted);
		changed();
		return inserted;
	}

	public long extract(ItemVariant variant, long amount) {
		if (amount <= 0 || variant.isBlank()) {
			return 0;
		}
		long current = items.getLong(variant);
		long extracted = Math.min(amount, current);
		if (extracted <= 0) {
			return 0;
		}
		if (extracted == current) {
			items.removeLong(variant);
		} else {
			items.put(variant, current - extracted);
		}
		changed();
		return extracted;
	}

	/** Inserts as much of the stack as possible and shrinks it by the inserted amount. */
	public void insertStack(ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		long inserted = insert(ItemVariant.of(stack), stack.getCount());
		stack.shrink((int) inserted);
	}

	/** Extracts up to {@code max} items of the variant as a stack (may be empty). */
	public ItemStack extractStack(ItemVariant variant, int max) {
		long extracted = extract(variant, max);
		return extracted > 0 ? variant.toStack((int) extracted) : ItemStack.EMPTY;
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

	public Object2LongMap<ItemVariant> view() {
		return Object2LongMaps.unmodifiable(items);
	}

	public List<StoredItems.Entry> entries() {
		List<StoredItems.Entry> list = new ArrayList<>(items.size());
		for (Object2LongMap.Entry<ItemVariant> entry : items.object2LongEntrySet()) {
			list.add(new StoredItems.Entry(entry.getKey(), entry.getLongValue()));
		}
		return list;
	}

	public StoredItems toComponent() {
		return new StoredItems(entries());
	}

	public void load(Iterable<StoredItems.Entry> entries) {
		items.clear();
		for (StoredItems.Entry entry : entries) {
			if (!entry.variant().isBlank() && entry.count() > 0) {
				long current = items.getLong(entry.variant());
				items.put(entry.variant(), entry.count() > MAX_PER_TYPE - current ? MAX_PER_TYPE : current + entry.count());
			}
		}
		version++;
	}

	/** Storage exposed to hoppers and pipes (Fabric Transfer API). Insertion only, so a hopper below cannot drain it. */
	public Storage<ItemVariant> insertionStorage() {
		return insertStorage;
	}

	private void changed() {
		version++;
		changeListener.run();
	}

	/**
	 * Transaction-aware insertion. Inserts are queued and only applied when the outermost
	 * transaction commits, so we never have to snapshot the (potentially huge) item map.
	 */
	private final class HopperInsertStorage extends SnapshotParticipant<Integer> implements InsertionOnlyStorage<ItemVariant> {
		private final List<ItemVariant> pendingVariants = new ArrayList<>();
		private final LongArrayList pendingAmounts = new LongArrayList();

		@Override
		public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			if (maxAmount <= 0 || !canStore(resource)) {
				return 0;
			}
			long pending = 0;
			for (int i = 0; i < pendingVariants.size(); i++) {
				if (pendingVariants.get(i).equals(resource)) {
					pending += pendingAmounts.getLong(i);
				}
			}
			long accepted = Math.min(maxAmount, MAX_PER_TYPE - getAmount(resource) - pending);
			if (accepted <= 0) {
				return 0;
			}
			updateSnapshots(transaction);
			pendingVariants.add(resource);
			pendingAmounts.add(accepted);
			return accepted;
		}

		@Override
		protected Integer createSnapshot() {
			return pendingVariants.size();
		}

		@Override
		protected void readSnapshot(Integer size) {
			while (pendingVariants.size() > size) {
				pendingVariants.removeLast();
				pendingAmounts.removeLong(pendingAmounts.size() - 1);
			}
		}

		@Override
		protected void onFinalCommit() {
			for (int i = 0; i < pendingVariants.size(); i++) {
				TerminalStorage.this.insert(pendingVariants.get(i), pendingAmounts.getLong(i));
			}
			pendingVariants.clear();
			pendingAmounts.clear();
		}

		@Override
		public long getVersion() {
			return version;
		}
	}
}
