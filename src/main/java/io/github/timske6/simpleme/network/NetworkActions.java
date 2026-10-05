package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.menu.NetworkMenu;
import io.github.timske6.simpleme.storage.ItemGroups;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageData;
import io.github.timske6.simpleme.storage.TerminalStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Server side of the network panel. */
public final class NetworkActions {
	/** Keeps a single player from filling the world data with networks. */
	public static final int MAX_NETWORKS_PER_PLAYER = 64;

	private NetworkActions() {
	}

	public static void handle(ServerPlayer player, NetworkActionPayload payload) {
		if (!(player.containerMenu instanceof NetworkMenu menu) || player.containerMenu.containerId != payload.containerId()
			|| player.isSpectator() || !player.containerMenu.stillValid(player)) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		Networks networks = Networks.get(server);
		Networks.Network target = networks.find(payload.network().orElse(null));
		switch (payload.action()) {
			case NetworkActionPayload.CREATE -> {
				long owned = networks.accessible(player).stream().filter(network -> player.getUUID().equals(network.owner())).count();
				if (owned >= MAX_NETWORKS_PER_PLAYER) {
					player.sendOverlayMessage(Component.translatable("message.simpleme.network.too_many", MAX_NETWORKS_PER_PLAYER).withStyle(ChatFormatting.RED));
				} else {
					menu.connectNetwork(player, networks.create(player, payload.text()).id());
				}
			}
			case NetworkActionPayload.CONNECT -> {
				if (target != null && target.canAccess(player)) {
					menu.connectNetwork(player, target.id());
				}
			}
			case NetworkActionPayload.DISCONNECT -> menu.connectNetwork(player, null);
			case NetworkActionPayload.RENAME -> {
				if (target != null && target.canManage(player)) {
					networks.rename(target, payload.text());
				}
			}
			case NetworkActionPayload.TOGGLE_PUBLIC -> {
				if (target != null && target.owner() != null && target.canManage(player)) {
					networks.setPublic(target, !target.isPublic());
				}
			}
			case NetworkActionPayload.DELETE -> {
				if (target != null && target.canManage(player)) {
					if (StorageData.get(server, target.id()).storage().isEmpty()) {
						networks.delete(target);
						ItemGroups.get(server).deleteNetwork(target.id());
					} else {
						player.sendOverlayMessage(Component.translatable("message.simpleme.network.not_empty").withStyle(ChatFormatting.RED));
					}
				}
			}
			default -> {
			}
		}
		sendList(player, payload.containerId(), menu.networkId());
	}

	public static void sendList(ServerPlayer player, int containerId, @Nullable UUID current) {
		MinecraftServer server = player.level().getServer();
		Networks networks = Networks.get(server);
		List<NetworkListPayload.Entry> entries = new ArrayList<>();
		for (Networks.Network network : networks.accessible(player)) {
			TerminalStorage storage = StorageData.get(server, network.id()).storage();
			entries.add(new NetworkListPayload.Entry(network.id(), network.name(), network.ownerName(), network.isPublic(),
				network.canManage(player), network.owner() != null, storage.typeCount(), storage.totalCount()));
		}
		Networks.Network connected = networks.find(current);
		boolean usable = connected != null && connected.canAccess(player);
		ServerPlayNetworking.send(player, new NetworkListPayload(containerId,
			usable ? Optional.of(connected.id()) : Optional.empty(), usable ? connected.name() : "", entries));
	}
}
