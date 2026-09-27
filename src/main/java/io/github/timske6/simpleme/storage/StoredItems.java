package io.github.timske6.simpleme.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Immutable snapshot of a terminal's contents. Used as item data component (so a broken
 * terminal keeps its items) and as the entry format for network sync.
 */
public record StoredItems(List<Entry> entries) {
	public static final StoredItems EMPTY = new StoredItems(List.of());

	public static final Codec<StoredItems> CODEC = Entry.CODEC.listOf().xmap(StoredItems::new, StoredItems::entries);
	public static final StreamCodec<RegistryFriendlyByteBuf, StoredItems> STREAM_CODEC =
		Entry.STREAM_CODEC.apply(ByteBufCodecs.list()).map(StoredItems::new, StoredItems::entries);

	public StoredItems {
		entries = List.copyOf(entries);
	}

	public long totalCount() {
		long total = 0;
		for (Entry entry : entries) {
			total += entry.count();
		}
		return total;
	}

	public record Entry(ItemVariant variant, long count) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ItemVariant.CODEC.fieldOf("variant").forGetter(Entry::variant),
			Codec.LONG.fieldOf("count").forGetter(Entry::count)
		).apply(instance, Entry::new));

		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
			ItemVariant.PACKET_CODEC, Entry::variant,
			ByteBufCodecs.VAR_LONG, Entry::count,
			Entry::new
		);
	}
}
