package io.github.timske6.simpleme.menu;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Decides which items go into which crafting grid slot when a recipe is filled in from outside
 * (the JEI "+" button). Shared by the client (to show missing items) and the server (to move them).
 */
public final class RecipeFill {
	public static final int GRID_SIZE = 9;
	/** Upper bound for the items one slot may accept (tags like #logs have a few dozen). */
	public static final int MAX_ALTERNATIVES = 512;

	private RecipeFill() {
	}

	/**
	 * @param variants the item per grid slot, {@code null} for slots that stay empty or cannot be filled
	 * @param sets how many times the recipe can be filled (0 if anything is missing)
	 * @param missing the grid slots for which nothing is available
	 */
	public record Plan(@Nullable ItemVariant[] variants, int sets, IntList missing) {
	}

	/** Everything that can be used for filling: the storage, the player inventory and what is in the grid right now. */
	public static Object2LongMap<ItemVariant> available(Object2LongMap<ItemVariant> storage, Inventory inventory, Iterable<ItemStack> grid, ItemStack locked) {
		Object2LongOpenHashMap<ItemVariant> available = new Object2LongOpenHashMap<>(storage);
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			addStack(available, inventory.getItem(i), locked);
		}
		for (ItemStack stack : grid) {
			addStack(available, stack, locked);
		}
		return available;
	}

	private static void addStack(Object2LongOpenHashMap<ItemVariant> available, ItemStack stack, ItemStack locked) {
		if (!stack.isEmpty() && stack != locked) {
			available.addTo(ItemVariant.of(stack), stack.getCount());
		}
	}

	/** Whether {@code variant} may be used where the recipe shows {@code wanted}. */
	public static boolean accepts(ItemVariant wanted, ItemVariant variant) {
		if (wanted.hasComponents()) {
			// the recipe asks for this exact item (e.g. a specific potion)
			return wanted.equals(variant);
		}
		// plain item: any variant, unless it is damaged, enchanted or renamed (same rule as the recipe book)
		return variant.isOf(wanted.getItem()) && (!variant.hasComponents() || Inventory.isUsableForCrafting(variant.toStack()));
	}

	public static boolean accepts(List<ItemVariant> alternatives, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		ItemVariant variant = ItemVariant.of(stack);
		for (ItemVariant wanted : alternatives) {
			if (accepts(wanted, variant)) {
				return true;
			}
		}
		return false;
	}

	public static Plan plan(List<List<ItemVariant>> slots, Object2LongMap<ItemVariant> available) {
		Map<Item, List<ItemVariant>> byItem = new HashMap<>();
		for (Object2LongMap.Entry<ItemVariant> entry : available.object2LongEntrySet()) {
			if (entry.getLongValue() > 0) {
				byItem.computeIfAbsent(entry.getKey().getItem(), item -> new ArrayList<>()).add(entry.getKey());
			}
		}

		ItemVariant[] variants = new ItemVariant[GRID_SIZE];
		IntList missing = new IntArrayList();
		Object2IntOpenHashMap<ItemVariant> demand = new Object2IntOpenHashMap<>();
		for (int i = 0; i < Math.min(slots.size(), GRID_SIZE); i++) {
			List<ItemVariant> alternatives = slots.get(i);
			if (alternatives.isEmpty()) {
				continue;
			}
			// Pick the candidate that is left the most of; slots that share an item then spread over all of its kinds.
			ItemVariant best = null;
			long bestAmount = 0;
			int bestDemand = 0;
			for (ItemVariant candidate : candidates(alternatives, byItem)) {
				long amount = available.getLong(candidate);
				int used = demand.getInt(candidate);
				if (amount > used && (best == null || amount * (bestDemand + 1) > bestAmount * (used + 1))) {
					best = candidate;
					bestAmount = amount;
					bestDemand = used;
				}
			}
			if (best == null) {
				missing.add(i);
				continue;
			}
			variants[i] = best;
			demand.addTo(best, 1);
		}

		int sets = 0;
		if (missing.isEmpty() && !demand.isEmpty()) {
			sets = Integer.MAX_VALUE;
			for (Object2IntOpenHashMap.Entry<ItemVariant> entry : demand.object2IntEntrySet()) {
				ItemVariant variant = entry.getKey();
				sets = (int) Math.min(sets, available.getLong(variant) / entry.getIntValue());
				sets = Math.min(sets, variant.toStack().getMaxStackSize());
			}
		}
		return new Plan(variants, sets, missing);
	}

	/** The available variants that fit one slot, in the order the recipe lists them. */
	private static Set<ItemVariant> candidates(List<ItemVariant> alternatives, Map<Item, List<ItemVariant>> byItem) {
		Set<ItemVariant> candidates = new LinkedHashSet<>();
		for (ItemVariant wanted : alternatives) {
			List<ItemVariant> sameItem = byItem.get(wanted.getItem());
			if (sameItem == null) {
				continue;
			}
			// the plain item first, so stacks with extra data are only used when needed
			ItemVariant plain = ItemVariant.of(wanted.getItem());
			if (sameItem.contains(plain) && accepts(wanted, plain)) {
				candidates.add(plain);
			}
			for (ItemVariant variant : sameItem) {
				if (accepts(wanted, variant)) {
					candidates.add(variant);
				}
			}
		}
		return candidates;
	}
}
