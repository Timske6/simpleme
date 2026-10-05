package io.github.timske6.simpleme.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.timske6.simpleme.SimpleME;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Item groups of all networks: {@code <world>/data/simpleme/groups.dat}. A group is a named list of
 * item types of one network, shown in the group panel of the terminal. It belongs to the player who
 * made it and is private until it is shared with everybody who can use the network.
 */
public final class ItemGroups extends SavedData {
	public static final int MAX_NAME_LENGTH = 32;
	/** Per player and network. */
	public static final int MAX_GROUPS = 32;
	public static final int MAX_ITEMS = 128;

	/** Items that fail to load (e.g. of a removed mod) are skipped instead of dropping the whole group. */
	private static final Codec<List<ItemVariant>> LENIENT_ITEMS = Codec.of(
		ItemVariant.CODEC.listOf(),
		Codec.PASSTHROUGH.listOf().map(ItemGroups::decodeEach)
	);

	private static final Codec<Group> GROUP_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Group::id),
		UUIDUtil.CODEC.fieldOf("network").forGetter(Group::network),
		Codec.STRING.fieldOf("name").forGetter(Group::name),
		UUIDUtil.CODEC.fieldOf("owner").forGetter(Group::owner),
		Codec.STRING.optionalFieldOf("owner_name", "").forGetter(Group::ownerName),
		Codec.BOOL.optionalFieldOf("shared", false).forGetter(Group::isShared),
		LENIENT_ITEMS.optionalFieldOf("items", List.of()).forGetter(Group::items),
		UUIDUtil.CODEC.listOf().optionalFieldOf("hidden_by", List.of()).forGetter(group -> List.copyOf(group.hiddenBy))
	).apply(instance, Group::new));

	/** A group that fails to load is skipped (and logged) instead of losing all groups of the world. */
	private static final Codec<List<Group>> LENIENT_GROUPS = Codec.of(
		GROUP_CODEC.listOf(),
		Codec.PASSTHROUGH.listOf().map(ItemGroups::decodeGroups)
	);
	/** The file format (public for tests and tools). */
	public static final Codec<ItemGroups> CODEC = LENIENT_GROUPS.xmap(ItemGroups::fromList, groups -> List.copyOf(groups.groups.values()));
	private static final SavedDataType<ItemGroups> TYPE = new SavedDataType<>(SimpleME.id("groups"), ItemGroups::new, CODEC, null);

	private final Map<UUID, Group> groups = new LinkedHashMap<>();
	/** Per network: changes with every change of its groups, so open terminals know when to send them again. Not saved. */
	private final Object2IntOpenHashMap<UUID> versions = new Object2IntOpenHashMap<>();

	public ItemGroups() {
	}

	private static ItemGroups fromList(List<Group> list) {
		ItemGroups data = new ItemGroups();
		for (Group group : list) {
			data.groups.put(group.id(), group);
		}
		return data;
	}

	private static List<Group> decodeGroups(List<Dynamic<?>> values) {
		List<Group> groups = new ArrayList<>(values.size());
		for (Dynamic<?> value : values) {
			GROUP_CODEC.parse(value).resultOrPartial(error -> SimpleME.LOGGER.warn("Skipping unreadable item group: {}", error)).ifPresent(groups::add);
		}
		return groups;
	}

	private static List<ItemVariant> decodeEach(List<Dynamic<?>> values) {
		List<ItemVariant> items = new ArrayList<>(values.size());
		for (Dynamic<?> value : values) {
			DataResult<ItemVariant> result = ItemVariant.CODEC.parse(value);
			result.resultOrPartial(error -> SimpleME.LOGGER.warn("Skipping unreadable group item: {}", error)).ifPresent(items::add);
		}
		return items;
	}

	public static ItemGroups get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Changes whenever a group of the network changes (created, renamed, shared, filled, shown or hidden, deleted). */
	public int version(UUID network) {
		return this.versions.getInt(network);
	}

	private void changed(UUID network) {
		this.versions.addTo(network, 1);
		this.setDirty();
	}

	public @Nullable Group find(@Nullable UUID id) {
		return id == null ? null : this.groups.get(id);
	}

	/** The groups of a network the player sees: own ones and shared ones, oldest first. */
	public List<Group> visible(UUID network, Player player) {
		List<Group> list = new ArrayList<>();
		for (Group group : this.groups.values()) {
			if (group.network().equals(network) && group.canSee(player)) {
				list.add(group);
			}
		}
		return list;
	}

	/** All groups of a network, no matter who may see them. */
	public List<Group> all(UUID network) {
		List<Group> list = new ArrayList<>();
		for (Group group : this.groups.values()) {
			if (group.network().equals(network)) {
				list.add(group);
			}
		}
		return list;
	}

	public int ownedCount(UUID network, Player player) {
		int count = 0;
		for (Group group : this.groups.values()) {
			if (group.network().equals(network) && group.owner().equals(player.getUUID())) {
				count++;
			}
		}
		return count;
	}

	/** @param id chosen by the client, so it can start editing the new group right away */
	public @Nullable Group create(UUID id, UUID network, Player owner, String name) {
		if (this.groups.containsKey(id)) {
			return null;
		}
		Group group = new Group(id, network, cleanName(name), owner.getUUID(), owner.getPlainTextName(), false, List.of(), List.of());
		this.groups.put(id, group);
		this.changed(network);
		return group;
	}

	public void rename(Group group, String name) {
		group.name = cleanName(name);
		this.changed(group.network());
	}

	public void setShared(Group group, boolean shared) {
		group.shared = shared;
		this.changed(group.network());
	}

	public void delete(Group group) {
		this.groups.remove(group.id());
		this.changed(group.network());
	}

	/** Groups of a deleted network go with it. */
	public void deleteNetwork(UUID network) {
		if (this.groups.values().removeIf(group -> group.network().equals(network))) {
			this.changed(network);
		}
	}

	public boolean addItem(Group group, ItemVariant item) {
		if (item.isBlank() || group.items.contains(item) || group.items.size() >= MAX_ITEMS) {
			return false;
		}
		group.items.add(item);
		this.changed(group.network());
		return true;
	}

	public void removeItem(Group group, ItemVariant item) {
		if (group.items.remove(item)) {
			this.changed(group.network());
		}
	}

	/** Every player picks for themselves which of the groups they see are shown. */
	public void setHidden(Group group, Player player, boolean hidden) {
		if (hidden ? group.hiddenBy.add(player.getUUID()) : group.hiddenBy.remove(player.getUUID())) {
			this.changed(group.network());
		}
	}

	public static String cleanName(String name) {
		String cleaned = Networks.cleanName(name);
		return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
	}

	public static final class Group {
		private final UUID id;
		private final UUID network;
		private String name;
		private final UUID owner;
		private final String ownerName;
		private boolean shared;
		private final List<ItemVariant> items;
		private final Set<UUID> hiddenBy;

		private Group(UUID id, UUID network, String name, UUID owner, String ownerName, boolean shared, List<ItemVariant> items, List<UUID> hiddenBy) {
			this.id = id;
			this.network = network;
			this.name = name;
			this.owner = owner;
			this.ownerName = ownerName;
			this.shared = shared;
			this.items = new ArrayList<>(items);
			this.hiddenBy = new HashSet<>(hiddenBy);
		}

		public UUID id() {
			return this.id;
		}

		public UUID network() {
			return this.network;
		}

		public String name() {
			return this.name;
		}

		public UUID owner() {
			return this.owner;
		}

		public String ownerName() {
			return this.ownerName;
		}

		public boolean isShared() {
			return this.shared;
		}

		/** Read only; changed through {@link ItemGroups#addItem} and {@link ItemGroups#removeItem}. */
		public List<ItemVariant> items() {
			return Collections.unmodifiableList(this.items);
		}

		public boolean isHiddenFor(Player player) {
			return this.hiddenBy.contains(player.getUUID());
		}

		public boolean canSee(Player player) {
			return this.shared || this.owner.equals(player.getUUID());
		}

		/** Rename, share, delete and change the items: only the player who made the group. */
		public boolean canManage(Player player) {
			return this.owner.equals(player.getUUID());
		}
	}
}
