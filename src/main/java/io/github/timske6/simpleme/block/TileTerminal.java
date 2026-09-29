package io.github.timske6.simpleme.block;

import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import io.github.timske6.simpleme.storage.ItemKey;
import io.github.timske6.simpleme.storage.StorageData;
import io.github.timske6.simpleme.storage.TerminalStorage;

/**
 * The terminal block entity. It only knows the id of its storage; the items live in {@link StorageData}.
 * For automation (hoppers, GregTech conveyors, item pipes, AE2 export buses) it looks like an inventory
 * with one always-empty slot that swallows everything put into it. Nothing can be pulled out that way.
 */
public class TileTerminal extends TileEntity implements ISidedInventory {

    private static final int[] SLOTS = { 0 };

    private UUID storageId;
    private TerminalStorage storage;

    /** The storage of this terminal. Server side only. */
    public TerminalStorage getStorage() {
        if (storage == null) {
            if (worldObj == null || worldObj.isRemote) {
                throw new IllegalStateException("Terminal storage is only available on the server");
            }
            if (storageId == null) {
                storageId = UUID.randomUUID();
                markDirty();
            }
            storage = StorageData.get(worldObj, storageId)
                .storage();
        }
        return storage;
    }

    public UUID getStorageId() {
        return storageId;
    }

    public void setStorageId(UUID id) {
        storageId = id;
        storage = null;
        markDirty();
    }

    /** Summary for the dropped item, or null if the terminal is empty. Server side only. */
    public TerminalContents contents() {
        if (storageId == null) {
            return null;
        }
        TerminalStorage storage = getStorage();
        return storage.isEmpty() ? null : new TerminalContents(storageId, storage.typeCount(), storage.totalCount());
    }

    @Override
    public boolean canUpdate() {
        return false;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        storageId = null;
        storage = null;
        if (nbt.hasKey("StorageId", 8)) {
            try {
                storageId = UUID.fromString(nbt.getString("StorageId"));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        if (storageId != null) {
            nbt.setString("StorageId", storageId.toString());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Insert-only inventory for automation
    // ------------------------------------------------------------------------------------------

    private boolean canAccept(ItemStack stack) {
        return stack != null && stack.getItem() != null && TerminalStorage.canStore(ItemKey.of(stack));
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return SLOTS;
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) {
        return canAccept(stack);
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) {
        return false;
    }

    @Override
    public int getSizeInventory() {
        return 1;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        return null;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        return null;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (stack == null || worldObj == null || worldObj.isRemote) {
            return;
        }
        ItemStack rest = stack.copy();
        if (canAccept(rest)) {
            getStorage().insertStack(rest);
        }
        if (rest.stackSize > 0) {
            // Something ignored isItemValidForSlot: never delete it, drop it instead.
            EntityItem entity = new EntityItem(worldObj, xCoord + 0.5, yCoord + 1.2, zCoord + 0.5, rest);
            worldObj.spawnEntityInWorld(entity);
        }
    }

    @Override
    public String getInventoryName() {
        return "container.simpleme.terminal";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return false;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return canAccept(stack);
    }
}
