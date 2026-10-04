package io.github.timske6.simpleme;

import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.NetworkActionPayload;
import io.github.timske6.simpleme.network.NetworkActions;
import io.github.timske6.simpleme.network.NetworkListPayload;
import io.github.timske6.simpleme.network.StorageClickPayload;
import io.github.timske6.simpleme.network.StorageSyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleME implements ModInitializer {
	public static final String MOD_ID = "simpleme";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModRegistry.init();

		PayloadTypeRegistry.clientboundPlay().registerLarge(StorageSyncPayload.TYPE, StorageSyncPayload.CODEC, StorageSyncPayload.MAX_SIZE);
		PayloadTypeRegistry.serverboundPlay().register(StorageClickPayload.TYPE, StorageClickPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(NetworkListPayload.TYPE, NetworkListPayload.CODEC, StorageSyncPayload.MAX_SIZE);
		PayloadTypeRegistry.serverboundPlay().register(NetworkActionPayload.TYPE, NetworkActionPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(StorageClickPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.containerMenu instanceof TerminalMenu menu && menu.containerId == payload.containerId()) {
				menu.handleStorageClick(player, payload.variant(), payload.action());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(NetworkActionPayload.TYPE, (payload, context) -> NetworkActions.handle(context.player(), payload));
	}
}
