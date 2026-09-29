package io.github.timske6.simpleme.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.oredict.OreDictionary;

import cpw.mods.fml.common.FMLCommonHandler;
import io.github.timske6.simpleme.block.TileTerminal;
import io.github.timske6.simpleme.network.Network;
import io.github.timske6.simpleme.network.PacketStorageClick;
import io.github.timske6.simpleme.network.PacketStorageSync;
import io.github.timske6.simpleme.storage.ItemKey;
import io.github.timske6.simpleme.storage.TerminalStorage;

/**
 * Container of the ME terminal: a virtual item grid (synced with custom packets), a 3x3 crafting grid
 * that refills itself from the storage, and the player inventory.
 */
public class ContainerTerminal extends Container {

    public static final int RESULT_SLOT = 0;
    public static final int GRID_START = 1;
    public static final int GRID_END = 10;
    public static final int INV_START = 10;
    public static final int HOTBAR_START = 37;
    public static final int INV_END = 46;

    public static final int BUTTON_CLEAR_GRID = 0;

    public static final int COLUMNS = 9;
    public static final int MIN_ROWS = 3;
    public static final int MAX_ROWS = 8;
    public static final int DEFAULT_ROWS = 5;

    /** Layout positions (relative to the GUI origin). */
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    public static final int CRAFT_GRID_X = 30;
    public static final int RESULT_X = 124;
    /** GUI height without the item rows. */
    public static final int FIXED_HEIGHT = 176;

    public final int rows;
    public final int craftingY;
    public final int inventoryLabelY;
    public final int inventoryY;
    public final int guiHeight;

    public final InventoryCrafting craftMatrix = new InventoryCrafting(this, 3, 3);
    public final InventoryCraftResult craftResult = new InventoryCraftResult();

    private final EntityPlayer player;
    private final TileTerminal terminal;
    private final boolean serverSide;

    // server side sync state
    private final Map<ItemKey, Long> lastSent = new HashMap<>();
    private long lastSentVersion = Long.MIN_VALUE;
    private int lastSyncTick = -1;
    private boolean suppressCraftUpdates;
    private boolean needsFullResync;

    // client side mirror of the storage
    private final Map<ItemKey, Long> clientItems = new LinkedHashMap<>();
    private int clientRevision;

    public ContainerTerminal(EntityPlayer player, TileTerminal terminal, int rows) {
        this.player = player;
        this.terminal = terminal;
        this.serverSide = !player.worldObj.isRemote;
        this.rows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
        this.craftingY = GRID_Y + this.rows * 18 + 8;
        this.inventoryLabelY = this.craftingY + 54 + 3;
        this.inventoryY = this.inventoryLabelY + 11;
        this.guiHeight = this.inventoryY + 58 + 18 + 6;

        addSlotToContainer(new TerminalCraftingSlot(player, RESULT_X, craftingY + 18));
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                addSlotToContainer(new Slot(craftMatrix, x + y * 3, CRAFT_GRID_X + x * 18, craftingY + y * 18));
            }
        }
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 9; x++) {
                addSlotToContainer(new Slot(player.inventory, x + y * 9 + 9, 8 + x * 18, inventoryY + y * 18));
            }
        }
        for (int x = 0; x < 9; x++) {
            addSlotToContainer(new Slot(player.inventory, x, 8 + x * 18, inventoryY + 58));
        }
        onCraftMatrixChanged(craftMatrix);
    }

    public boolean isServerSide() {
        return serverSide;
    }

    private boolean storageAvailable() {
        return serverSide && terminal != null && !terminal.isInvalid();
    }

    private TerminalStorage storage() {
        return terminal.getStorage();
    }

    // ------------------------------------------------------------------------------------------
    // Validity / lifecycle
    // ------------------------------------------------------------------------------------------

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (!serverSide) {
            return true;
        }
        if (terminal == null || terminal.isInvalid()
            || terminal.getWorldObj()
                .getTileEntity(terminal.xCoord, terminal.yCoord, terminal.zCoord) != terminal) {
            return false;
        }
        return player.getDistanceSq(terminal.xCoord + 0.5, terminal.yCoord + 0.5, terminal.zCoord + 0.5) <= 64.0;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (serverSide) {
            suppressCraftUpdates = true;
            try {
                returnGridToStorage(player);
            } finally {
                suppressCraftUpdates = false;
            }
        }
    }

    private void returnGridToStorage(EntityPlayer player) {
        for (int i = 0; i < craftMatrix.getSizeInventory(); i++) {
            ItemStack stack = craftMatrix.getStackInSlotOnClosing(i);
            if (stack != null) {
                storeOrGive(stack, player);
            }
        }
    }

    /** Puts a stack into the storage; what does not fit goes to the player (or is dropped). */
    private void storeOrGive(ItemStack stack, EntityPlayer player) {
        if (storageAvailable()) {
            storage().insertStack(stack);
        }
        if (stack.stackSize > 0 && !player.inventory.addItemStackToInventory(stack)) {
            player.dropPlayerItemWithRandomChoice(stack, false);
        }
    }

    /** "Buttons" are sent with the vanilla enchant packet (like the enchanting table does). */
    @Override
    public boolean enchantItem(EntityPlayer player, int id) {
        if (id == BUTTON_CLEAR_GRID) {
            if (serverSide) {
                suppressCraftUpdates = true;
                try {
                    returnGridToStorage(player);
                } finally {
                    suppressCraftUpdates = false;
                }
                updateCraftingResult();
                detectAndSendChanges();
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Storage sync (server → client)
    // ------------------------------------------------------------------------------------------

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        syncStorage();
    }

    private void syncStorage() {
        if (!storageAvailable() || !(player instanceof EntityPlayerMP)) {
            return;
        }
        TerminalStorage storage = storage();
        if (storage.getVersion() == lastSentVersion) {
            return;
        }
        // At most once per tick, no matter how many clicks arrive; the rest is sent next tick.
        int tick = MinecraftServer.getServer()
            .getTickCounter();
        if (tick == lastSyncTick) {
            return;
        }
        lastSyncTick = tick;
        boolean reset = lastSentVersion == Long.MIN_VALUE;
        lastSentVersion = storage.getVersion();

        List<ItemKey> changedKeys = new ArrayList<>();
        List<Long> changedCounts = new ArrayList<>();
        Map<ItemKey, Long> current = storage.view();
        if (reset) {
            lastSent.clear();
        }
        for (Map.Entry<ItemKey, Long> entry : current.entrySet()) {
            Long previous = lastSent.get(entry.getKey());
            if (reset || previous == null || previous.longValue() != entry.getValue()) {
                changedKeys.add(entry.getKey());
                changedCounts.add(entry.getValue());
                lastSent.put(entry.getKey(), entry.getValue());
            }
        }
        if (!reset) {
            Iterator<Map.Entry<ItemKey, Long>> iterator = lastSent.entrySet()
                .iterator();
            while (iterator.hasNext()) {
                ItemKey key = iterator.next()
                    .getKey();
                if (!current.containsKey(key)) {
                    changedKeys.add(key);
                    changedCounts.add(0L);
                    iterator.remove();
                }
            }
        }

        EntityPlayerMP target = (EntityPlayerMP) player;
        if (reset && changedKeys.isEmpty()) {
            Network.CHANNEL.sendTo(new PacketStorageSync(windowId, true), target);
        }
        for (int start = 0; start < changedKeys.size(); start += PacketStorageSync.MAX_ENTRIES) {
            PacketStorageSync packet = new PacketStorageSync(windowId, reset && start == 0);
            int end = Math.min(changedKeys.size(), start + PacketStorageSync.MAX_ENTRIES);
            for (int i = start; i < end; i++) {
                packet.add(changedKeys.get(i), changedCounts.get(i));
            }
            Network.CHANNEL.sendTo(packet, target);
        }
    }

    /** Client: apply a sync packet to the local mirror. */
    public void applySync(PacketStorageSync packet) {
        if (packet.reset) {
            clientItems.clear();
        }
        for (int i = 0; i < packet.keys.size(); i++) {
            long count = packet.counts.get(i);
            if (count <= 0) {
                clientItems.remove(packet.keys.get(i));
            } else {
                clientItems.put(packet.keys.get(i), count);
            }
        }
        clientRevision++;
    }

    /** Client: the synced storage contents. */
    public Map<ItemKey, Long> getClientItems() {
        return Collections.unmodifiableMap(clientItems);
    }

    /** Client: changes every time the synced contents change. */
    public int getClientRevision() {
        return clientRevision;
    }

    // ------------------------------------------------------------------------------------------
    // Clicks on the virtual item grid (client → server)
    // ------------------------------------------------------------------------------------------

    public void handleStorageClick(EntityPlayerMP player, ItemStack template, int action) {
        if (!storageAvailable() || !canInteractWith(player)) {
            return;
        }
        TerminalStorage storage = storage();
        ItemStack carried = player.inventory.getItemStack();
        ItemKey key = template == null || template.getItem() == null ? null : ItemKey.of(template);

        switch (action) {
            case PacketStorageClick.TAKE_STACK:
            case PacketStorageClick.TAKE_HALF:
            case PacketStorageClick.TAKE_ONE: {
                if (carried != null || key == null) {
                    return;
                }
                int available = (int) Math.min(storage.getAmount(key), key.maxStackSize());
                if (available <= 0) {
                    return;
                }
                int amount = action == PacketStorageClick.TAKE_HALF ? (available + 1) / 2
                    : action == PacketStorageClick.TAKE_ONE ? 1 : available;
                player.inventory.setItemStack(storage.extractStack(key, amount));
                break;
            }
            case PacketStorageClick.QUICK_MOVE: {
                if (key == null) {
                    return;
                }
                ItemStack stack = storage.extractStack(key, key.maxStackSize());
                if (stack == null) {
                    return;
                }
                mergeItemStack(stack, INV_START, INV_END, true);
                if (stack.stackSize > 0) {
                    storeOrGive(stack, player);
                }
                break;
            }
            case PacketStorageClick.INSERT_ALL: {
                if (carried == null) {
                    return;
                }
                storage.insertStack(carried);
                if (carried.stackSize <= 0) {
                    player.inventory.setItemStack(null);
                }
                break;
            }
            case PacketStorageClick.INSERT_ONE: {
                if (carried == null) {
                    return;
                }
                ItemStack single = carried.copy();
                single.stackSize = 1;
                storage.insertStack(single);
                if (single.stackSize == 0) {
                    carried.stackSize--;
                }
                if (carried.stackSize <= 0) {
                    player.inventory.setItemStack(null);
                }
                break;
            }
            case PacketStorageClick.CLONE: {
                if (!player.capabilities.isCreativeMode || carried != null
                    || key == null
                    || storage.getAmount(key) <= 0) {
                    return;
                }
                player.inventory.setItemStack(key.toStack(key.maxStackSize()));
                break;
            }
            default:
                return;
        }
        player.updateHeldItem();
        detectAndSendChanges();
    }

    // ------------------------------------------------------------------------------------------
    // Shift-clicking
    // ------------------------------------------------------------------------------------------

    /**
     * During a window click, vanilla assumes the client predicted everything and suppresses slot updates
     * ({@code isChangingQuantityOnly}). The server does more than the client can predict (shift-clicks into the
     * storage, refilling the grid after crafting), so after such a click it sends the whole container once.
     */
    @Override
    public ItemStack slotClick(int slotId, int clickedButton, int mode, EntityPlayer player) {
        needsFullResync = false;
        ItemStack result = super.slotClick(slotId, clickedButton, mode, player);
        if (needsFullResync && player instanceof EntityPlayerMP) {
            needsFullResync = false;
            ((EntityPlayerMP) player).sendContainerAndContentsToPlayer(this, getInventory());
        }
        return result;
    }

    /**
     * Always returns null: the client does not predict shift-clicks, and returning null on both sides keeps
     * the click in sync. The server moves the items and resends the container (see {@link #slotClick}).
     */
    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (!serverSide) {
            return null;
        }
        needsFullResync = true;
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) {
            return null;
        }
        if (index == RESULT_SLOT) {
            craftIntoInventory(player, slot);
            return null;
        }

        ItemStack stack = slot.getStack();
        int before = stack.stackSize;
        if (storageAvailable()) {
            storage().insertStack(stack);
        }
        if (stack.stackSize == before) {
            // Not storable (e.g. a filled terminal): vanilla inventory <-> hotbar moves.
            if (index < GRID_END) {
                mergeItemStack(stack, INV_START, INV_END, false);
            } else if (index < HOTBAR_START) {
                mergeItemStack(stack, HOTBAR_START, INV_END, false);
            } else {
                mergeItemStack(stack, INV_START, HOTBAR_START, false);
            }
        }
        if (stack.stackSize <= 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }
        return null;
    }

    /**
     * Shift-click on the result: crafts a full stack of output (or as much as the grid holds, if that is
     * more), pulling ingredients from the storage. Stops when the inventory is full.
     */
    private void craftIntoInventory(EntityPlayer player, Slot slot) {
        ItemStack first = slot.getStack()
            .copy();
        int perCraft = Math.max(1, first.stackSize);
        int byStack = Math.max(1, first.getMaxStackSize() / perCraft);
        int byGrid = 0;
        for (int i = 0; i < craftMatrix.getSizeInventory(); i++) {
            ItemStack stack = craftMatrix.getStackInSlot(i);
            if (stack != null) {
                byGrid = byGrid == 0 ? stack.stackSize : Math.min(byGrid, stack.stackSize);
            }
        }
        int limit = Math.max(byStack, byGrid);

        for (int crafted = 0; crafted < limit; crafted++) {
            ItemStack result = slot.getStack();
            if (result == null || !result.isItemEqual(first) || !ItemStack.areItemStackTagsEqual(result, first)) {
                break;
            }
            ItemStack moving = result.copy();
            if (!mergeItemStack(moving, INV_START, INV_END, true)) {
                break;
            }
            ItemStack taken = slot.decrStackSize(result.stackSize);
            slot.onPickupFromSlot(player, taken);
            if (moving.stackSize > 0) {
                // Did not fully fit: keep the rest in the storage instead of dropping it.
                storeOrGive(moving, player);
            }
        }
    }

    /** Double-click collecting must not pull crafting results. */
    @Override
    public boolean func_94530_a(ItemStack stack, Slot slot) {
        return slot.inventory != craftResult && super.func_94530_a(stack, slot);
    }

    // ------------------------------------------------------------------------------------------
    // Crafting
    // ------------------------------------------------------------------------------------------

    @Override
    public void onCraftMatrixChanged(IInventory inventory) {
        if (!suppressCraftUpdates) {
            updateCraftingResult();
        }
    }

    private void updateCraftingResult() {
        craftResult.setInventorySlotContents(
            0,
            CraftingManager.getInstance()
                .findMatchingRecipe(craftMatrix, player.worldObj));
    }

    private ItemStack[] snapshotGrid() {
        ItemStack[] snapshot = new ItemStack[craftMatrix.getSizeInventory()];
        for (int i = 0; i < snapshot.length; i++) {
            ItemStack stack = craftMatrix.getStackInSlot(i);
            snapshot[i] = stack == null ? null : stack.copy();
        }
        return snapshot;
    }

    /** Like vanilla, but container items that leave the grid (e.g. empty buckets) go into the storage. */
    private void consumeIngredients(EntityPlayer player) {
        for (int i = 0; i < craftMatrix.getSizeInventory(); i++) {
            ItemStack ingredient = craftMatrix.getStackInSlot(i);
            if (ingredient == null) {
                continue;
            }
            craftMatrix.decrStackSize(i, 1);
            if (!ingredient.getItem()
                .hasContainerItem(ingredient)) {
                continue;
            }
            ItemStack container = ingredient.getItem()
                .getContainerItem(ingredient);
            if (container == null) {
                continue;
            }
            if (container.isItemStackDamageable() && container.getItemDamage() > container.getMaxDamage()) {
                MinecraftForge.EVENT_BUS.post(new PlayerDestroyItemEvent(player, container));
                continue;
            }
            boolean staysInGrid = !ingredient.getItem()
                .doesContainerItemLeaveCraftingGrid(ingredient);
            if (staysInGrid && craftMatrix.getStackInSlot(i) == null) {
                // e.g. GregTech tools: they take damage and stay in their slot
                craftMatrix.setInventorySlotContents(i, container);
            } else {
                storeOrGive(container, player);
            }
        }
    }

    /**
     * After crafting: top the grid back up to what it was, pulling from the storage. A slot that now holds
     * something else (a tool that took damage, a remainder) is left alone.
     */
    private void refillGrid(ItemStack[] before) {
        TerminalStorage storage = storage();
        for (int i = 0; i < before.length; i++) {
            ItemStack wanted = before[i];
            if (wanted == null) {
                continue;
            }
            ItemKey key = ItemKey.of(wanted);
            ItemStack current = craftMatrix.getStackInSlot(i);
            if (current != null && !key.matches(current)) {
                continue;
            }
            int missing = wanted.stackSize - (current == null ? 0 : current.stackSize);
            if (missing <= 0) {
                continue;
            }
            ItemStack refill = storage.extractStack(key, missing);
            if (refill == null) {
                continue;
            }
            if (current == null) {
                craftMatrix.setInventorySlotContents(i, refill);
            } else {
                current.stackSize += refill.stackSize;
            }
        }
    }

    private final class TerminalCraftingSlot extends SlotCrafting {

        TerminalCraftingSlot(EntityPlayer player, int x, int y) {
            super(player, craftMatrix, craftResult, 0, x, y);
        }

        @Override
        public void onPickupFromSlot(EntityPlayer player, ItemStack stack) {
            if (!storageAvailable()) {
                super.onPickupFromSlot(player, stack);
                return;
            }
            // the client only predicts vanilla crafting, not the refill from storage
            needsFullResync = true;
            ItemStack[] before = snapshotGrid();
            suppressCraftUpdates = true;
            try {
                FMLCommonHandler.instance()
                    .firePlayerCraftingEvent(player, stack, craftMatrix);
                onCrafting(stack);
                consumeIngredients(player);
                refillGrid(before);
            } finally {
                suppressCraftUpdates = false;
            }
            updateCraftingResult();
        }
    }

    // ------------------------------------------------------------------------------------------
    // NEI recipe transfer: fill the grid from the storage (and the player inventory)
    // ------------------------------------------------------------------------------------------

    public void fillGrid(EntityPlayerMP player, List<List<ItemStack>> candidates, boolean max) {
        if (!storageAvailable() || !canInteractWith(player) || candidates.size() != 9) {
            return;
        }
        TerminalStorage storage = storage();
        suppressCraftUpdates = true;
        try {
            returnGridToStorage(player);

            // Choose one concrete item per slot: the matching one we have the most of. Plain items first;
            // items with extra data (renamed, enchanted, ...) only if nothing plain is there.
            ItemKey[] chosen = new ItemKey[9];
            Map<ItemKey, Integer> uses = new HashMap<>();
            for (int slot = 0; slot < 9; slot++) {
                List<ItemKey> keys = availableKeys(storage, player, candidates.get(slot));
                ItemKey best = bestKey(storage, player, keys, uses, false);
                if (best == null) {
                    best = bestKey(storage, player, keys, uses, true);
                }
                if (best != null) {
                    chosen[slot] = best;
                    uses.merge(best, 1, Integer::sum);
                }
            }

            int amount = 1;
            if (max) {
                long sets = Long.MAX_VALUE;
                for (ItemKey key : uses.keySet()) {
                    sets = Math.min(sets, available(storage, player, key) / uses.get(key));
                    sets = Math.min(sets, key.maxStackSize());
                }
                amount = (int) Math.max(1, Math.min(64, sets == Long.MAX_VALUE ? 1 : sets));
            }

            for (int slot = 0; slot < 9; slot++) {
                if (chosen[slot] != null) {
                    ItemStack taken = take(storage, player, chosen[slot], amount);
                    if (taken != null) {
                        craftMatrix.setInventorySlotContents(slot, taken);
                    }
                }
            }
        } finally {
            suppressCraftUpdates = false;
        }
        updateCraftingResult();
        detectAndSendChanges();
    }

    private static ItemKey bestKey(TerminalStorage storage, EntityPlayer player, List<ItemKey> keys,
        Map<ItemKey, Integer> uses, boolean allowTagged) {
        ItemKey best = null;
        long bestAvailable = 0;
        for (ItemKey key : keys) {
            if (key.hasTag() && !allowTagged && !isTool(key)) {
                continue;
            }
            long available = available(storage, player, key) - uses.getOrDefault(key, 0);
            if (available > bestAvailable) {
                best = key;
                bestAvailable = available;
            }
        }
        return best;
    }

    /**
     * Tools (e.g. GregTech hammers and wrenches, which use metadata for the tool type and NBT for material and
     * damage) are unstackable; for them NBT is normal and they match by item + metadata.
     */
    private static boolean isTool(ItemKey key) {
        return key.item.isDamageable() || key.maxStackSize() == 1;
    }

    private static boolean candidateMatches(ItemStack candidate, ItemKey key) {
        if (candidate == null || candidate.getItem() != key.item) {
            return false;
        }
        int meta = candidate.getItemDamage();
        if (meta != OreDictionary.WILDCARD_VALUE && meta != key.meta && !key.item.isDamageable()) {
            return false;
        }
        boolean candidateHasTag = candidate.hasTagCompound() && !candidate.getTagCompound()
            .hasNoTags();
        if (!candidateHasTag) {
            // the recipe accepts any variant; which one is preferred is decided in bestKey
            return true;
        }
        // the recipe wants specific data; tools match by item + meta (their NBT holds damage/stats)
        return candidate.getTagCompound()
            .equals(key.tag()) || isTool(key);
    }

    private static List<ItemKey> availableKeys(TerminalStorage storage, EntityPlayer player,
        List<ItemStack> candidates) {
        List<ItemKey> keys = new ArrayList<>();
        for (ItemStack candidate : candidates) {
            for (ItemKey key : storage.keys()) {
                if (candidateMatches(candidate, key) && !keys.contains(key)) {
                    keys.add(key);
                }
            }
            for (ItemStack stack : player.inventory.mainInventory) {
                if (stack != null) {
                    ItemKey key = ItemKey.of(stack);
                    if (candidateMatches(candidate, key) && !keys.contains(key)) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    private static long available(TerminalStorage storage, EntityPlayer player, ItemKey key) {
        long amount = storage.getAmount(key);
        for (ItemStack stack : player.inventory.mainInventory) {
            if (key.matches(stack)) {
                amount += stack.stackSize;
            }
        }
        return amount;
    }

    /** Takes items of the key: storage first, then the player inventory. */
    private static ItemStack take(TerminalStorage storage, EntityPlayer player, ItemKey key, int amount) {
        int got = (int) storage.extract(key, amount);
        for (int i = 0; i < player.inventory.mainInventory.length && got < amount; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (key.matches(stack)) {
                int n = Math.min(amount - got, stack.stackSize);
                stack.stackSize -= n;
                got += n;
                if (stack.stackSize <= 0) {
                    player.inventory.mainInventory[i] = null;
                }
            }
        }
        return got > 0 ? key.toStack(got) : null;
    }
}
