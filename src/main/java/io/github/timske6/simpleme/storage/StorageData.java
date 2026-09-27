package io.github.timske6.simpleme.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import io.github.timske6.simpleme.SimpleME;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The contents of one terminal storage, saved as its own file in the world:
 * {@code <world>/data/simpleme/storage/<id>.dat}. Only written when it changed.
 */
public final class StorageData extends SavedData {
	/** Entries that fail to load (e.g. items of a removed mod) are skipped instead of dropping the whole storage. */
	public static final Codec<List<StoredItems.Entry>> LENIENT_ENTRIES = Codec.of(
		StoredItems.Entry.CODEC.listOf(),
		Codec.PASSTHROUGH.listOf().map(StorageData::decodeEach)
	);

	private static final Codec<StorageData> CODEC = LENIENT_ENTRIES.xmap(StorageData::fromEntries, StorageData::toEntries);

	private final TerminalStorage storage = new TerminalStorage(this::setDirty);
	/** Whether a file for this storage exists (loaded from or written to disk). */
	private boolean persisted;

	public StorageData() {
	}

	private static StorageData fromEntries(List<StoredItems.Entry> entries) {
		StorageData data = new StorageData();
		data.storage.load(entries);
		data.persisted = true;
		return data;
	}

	private static List<StoredItems.Entry> toEntries(StorageData data) {
		data.persisted = true;
		return data.storage.entries();
	}

	private static List<StoredItems.Entry> decodeEach(List<Dynamic<?>> values) {
		List<StoredItems.Entry> entries = new ArrayList<>(values.size());
		for (Dynamic<?> value : values) {
			DataResult<StoredItems.Entry> result = StoredItems.Entry.CODEC.parse(value);
			result.resultOrPartial(error -> SimpleME.LOGGER.warn("Skipping unreadable stored item: {}", error)).ifPresent(entries::add);
		}
		return entries;
	}

	/**
	 * A terminal that was only opened while empty does not need a file. A storage that already
	 * has a file must still be saved when it becomes empty, otherwise the old items would come back.
	 */
	@Override
	public boolean isDirty() {
		return super.isDirty() && (this.persisted || !this.storage.isEmpty());
	}

	public TerminalStorage storage() {
		return storage;
	}

	public static StorageData get(MinecraftServer server, UUID id) {
		return server.getDataStorage().computeIfAbsent(type(id));
	}

	private static SavedDataType<StorageData> type(UUID id) {
		// No vanilla data fixer applies to mod data; Fabric API allows null here.
		return new SavedDataType<>(SimpleME.id("storage/" + id), StorageData::new, CODEC, null);
	}
}
