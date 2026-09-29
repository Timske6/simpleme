package io.github.timske6.simpleme.storage;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

/**
 * The contents of one terminal, saved as its own file in the world: {@code <world>/data/simpleme_<id>.dat}.
 * Block and item only carry the id, so a filled terminal item stays small no matter how much it holds.
 */
public class StorageData extends WorldSavedData {

    private static final String PREFIX = "simpleme_";

    private final TerminalStorage storage = new TerminalStorage(this::markDirty);
    /** Whether a file for this storage exists (loaded from or written to disk). */
    private boolean persisted;

    /** Called by {@link MapStorage} via reflection. */
    public StorageData(String name) {
        super(name);
    }

    public TerminalStorage storage() {
        return storage;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        storage.readFromNBT(nbt.getTagList("Items", 10));
        persisted = true;
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        nbt.setTag("Items", storage.writeToNBT());
        persisted = true;
    }

    /**
     * A terminal that was only opened while empty needs no file. A storage that already has a file
     * must still be saved when it becomes empty, otherwise the old items would come back (dupe).
     */
    @Override
    public boolean isDirty() {
        return super.isDirty() && (persisted || !storage.isEmpty());
    }

    /** Server side only. The map storage is shared by all dimensions. */
    public static StorageData get(World world, UUID id) {
        MapStorage mapStorage = world.mapStorage;
        String name = PREFIX + id;
        StorageData data = (StorageData) mapStorage.loadData(StorageData.class, name);
        if (data == null) {
            data = new StorageData(name);
            mapStorage.setData(name, data);
        }
        return data;
    }
}
