package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.storage.ItemGroups;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the item groups of the connected network that the player sees. Like the storage
 * sync, a long list is split over several packets (items can carry a lot of data, e.g. written
 * books): {@code first} starts a new list, the client shows it once {@code last} has arrived.
 */
public record GroupListPayload(int containerId, boolean first, boolean last, List<Entry> groups) implements CustomPacketPayload {
	/** Items per packet, the same bound as for the storage sync. */
	public static final int MAX_ITEMS_PER_PACKET = StorageSyncPayload.MAX_ENTRIES;
	public static final Type<GroupListPayload> TYPE = new Type<>(SimpleME.id("group_list"));

	public static final StreamCodec<RegistryFriendlyByteBuf, GroupListPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GroupListPayload::containerId,
		ByteBufCodecs.BOOL, GroupListPayload::first,
		ByteBufCodecs.BOOL, GroupListPayload::last,
		Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), GroupListPayload::groups,
		GroupListPayload::new
	);

	/** The packets for a whole list: whole groups, at most {@link #MAX_ITEMS_PER_PACKET} items each (a single group may hold more). */
	public static List<GroupListPayload> split(int containerId, List<Entry> groups) {
		List<List<Entry>> batches = new ArrayList<>();
		List<Entry> batch = new ArrayList<>();
		int items = 0;
		for (Entry group : groups) {
			if (!batch.isEmpty() && items + group.items().size() > MAX_ITEMS_PER_PACKET) {
				batches.add(batch);
				batch = new ArrayList<>();
				items = 0;
			}
			batch.add(group);
			items += group.items().size();
		}
		batches.add(batch);
		List<GroupListPayload> payloads = new ArrayList<>(batches.size());
		for (int i = 0; i < batches.size(); i++) {
			payloads.add(new GroupListPayload(containerId, i == 0, i == batches.size() - 1, List.copyOf(batches.get(i))));
		}
		return payloads;
	}

	/**
	 * @param canManage the player made the group and may rename, share, delete and change it
	 * @param hidden the player chose not to show it
	 */
	public record Entry(UUID id, String name, String ownerName, boolean shared, boolean canManage, boolean hidden, List<ItemVariant> items) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, Entry::id,
			ByteBufCodecs.stringUtf8(ItemGroups.MAX_NAME_LENGTH * 4), Entry::name,
			ByteBufCodecs.PLAYER_NAME, Entry::ownerName,
			ByteBufCodecs.BOOL, Entry::shared,
			ByteBufCodecs.BOOL, Entry::canManage,
			ByteBufCodecs.BOOL, Entry::hidden,
			ItemVariant.PACKET_CODEC.apply(ByteBufCodecs.list(ItemGroups.MAX_ITEMS)), Entry::items,
			Entry::new
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
