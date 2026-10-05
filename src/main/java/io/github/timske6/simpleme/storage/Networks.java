package io.github.timske6.simpleme.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.timske6.simpleme.SimpleME;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * All ME networks of the world: {@code <world>/data/simpleme/networks.dat}. A network is a named
 * storage; its items are in the storage file with the same id (see {@link StorageData}).
 * Terminals, wireless terminals and transfer chests only point to a network.
 */
public final class Networks extends SavedData {
	public static final int MAX_NAME_LENGTH = 32;

	private static final Codec<Network> NETWORK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Network::id),
		Codec.STRING.fieldOf("name").forGetter(Network::name),
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(network -> Optional.ofNullable(network.owner())),
		Codec.STRING.optionalFieldOf("owner_name", "").forGetter(Network::ownerName),
		Codec.BOOL.optionalFieldOf("public", false).forGetter(Network::isPublic)
	).apply(instance, (id, name, owner, ownerName, isPublic) -> new Network(id, name, owner.orElse(null), ownerName, isPublic)));

	private static final Codec<Networks> CODEC = NETWORK_CODEC.listOf().xmap(Networks::fromList, networks -> List.copyOf(networks.networks.values()));
	private static final SavedDataType<Networks> TYPE = new SavedDataType<>(SimpleME.id("networks"), Networks::new, CODEC, null);

	private final Map<UUID, Network> networks = new LinkedHashMap<>();
	/** Changes with every change (not saved), so open menus send the network list again. */
	private int version;

	public Networks() {
	}

	private static Networks fromList(List<Network> list) {
		Networks data = new Networks();
		for (Network network : list) {
			data.networks.put(network.id(), network);
		}
		return data;
	}

	public static Networks get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	/** The storage of a network, or {@code null} if the network does not exist (any more). No access check. */
	public static @Nullable TerminalStorage storage(MinecraftServer server, @Nullable UUID id) {
		return id != null && get(server).find(id) != null ? StorageData.get(server, id).storage() : null;
	}

	public int version() {
		return this.version;
	}

	@Override
	public void setDirty() {
		super.setDirty();
		this.version++;
	}

	public @Nullable Network find(@Nullable UUID id) {
		return id == null ? null : this.networks.get(id);
	}

	/** A new private network of the player (or a public one without owner, if {@code owner} is null). */
	public Network create(@Nullable Player owner, String name) {
		UUID id = UUID.randomUUID();
		Network network = owner != null
			? new Network(id, cleanName(name), owner.getUUID(), owner.getPlainTextName(), false)
			: new Network(id, cleanName(name), null, "", true);
		this.networks.put(id, network);
		this.setDirty();
		return network;
	}

	/**
	 * Storages of terminals from before networks existed become public networks without owner, so
	 * everybody who could use the terminal before still can.
	 */
	public Network adoptLegacy(UUID id, String name) {
		Network network = this.networks.get(id);
		if (network == null) {
			network = new Network(id, cleanName(name), null, "", true);
			this.networks.put(id, network);
			this.setDirty();
		}
		return network;
	}

	public void rename(Network network, String name) {
		network.name = cleanName(name);
		this.setDirty();
	}

	public void setPublic(Network network, boolean isPublic) {
		network.isPublic = isPublic;
		this.setDirty();
	}

	public void delete(Network network) {
		this.networks.remove(network.id());
		this.setDirty();
	}

	/** Networks the player can use, sorted: own first, then by name. */
	public List<Network> accessible(Player player) {
		List<Network> list = new ArrayList<>();
		for (Network network : this.networks.values()) {
			if (network.canAccess(player)) {
				list.add(network);
			}
		}
		list.sort(Comparator.<Network, Boolean>comparing(network -> !player.getUUID().equals(network.owner()))
			.thenComparing(network -> network.name().toLowerCase(Locale.ROOT)));
		return list;
	}

	public static String cleanName(String name) {
		// no formatting codes or control characters in names
		String cleaned = name.replace("§", "").replaceAll("\\p{Cntrl}", "").strip();
		if (cleaned.length() > MAX_NAME_LENGTH) {
			cleaned = cleaned.substring(0, MAX_NAME_LENGTH);
		}
		return cleaned.isEmpty() ? "ME" : cleaned;
	}

	public static final class Network {
		private final UUID id;
		private String name;
		private final @Nullable UUID owner;
		private final String ownerName;
		private boolean isPublic;

		private Network(UUID id, String name, @Nullable UUID owner, String ownerName, boolean isPublic) {
			this.id = id;
			this.name = name;
			this.owner = owner;
			this.ownerName = ownerName;
			this.isPublic = isPublic;
		}

		public UUID id() {
			return this.id;
		}

		public String name() {
			return this.name;
		}

		/** {@code null} for networks taken over from old terminals: they belong to everybody. */
		public @Nullable UUID owner() {
			return this.owner;
		}

		public String ownerName() {
			return this.ownerName;
		}

		public boolean isPublic() {
			return this.isPublic || this.owner == null;
		}

		/** Use the network: see and take items, connect terminals, chests and wireless terminals. */
		public boolean canAccess(Player player) {
			return this.isPublic() || player.getUUID().equals(this.owner);
		}

		/** Rename and delete; switching public/private additionally needs an owner. */
		public boolean canManage(Player player) {
			return this.owner == null || player.getUUID().equals(this.owner);
		}
	}
}
