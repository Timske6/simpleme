package io.github.timske6.simpleme.client;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.menu.NetworkMenu;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.NetworkListPayload;
import io.github.timske6.simpleme.network.StorageSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

public class SimpleMEClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(ModRegistry.TERMINAL_MENU, TerminalScreen::new);
		MenuScreens.register(ModRegistry.WIRELESS_TERMINAL_MENU, TerminalScreen::new);
		MenuScreens.register(ModRegistry.TRANSFER_CHEST_MENU, TransferChestScreen::new);

		// Show as many item rows as fit on the screen.
		TerminalMenu.clientRows = () -> {
			int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
			return (screenHeight - 12 - TerminalMenu.FIXED_HEIGHT) / 18;
		};

		ClientPlayNetworking.registerGlobalReceiver(StorageSyncPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof TerminalMenu menu && menu.containerId == payload.containerId()) {
				menu.applySync(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(NetworkListPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof NetworkMenu menu && context.player().containerMenu.containerId == payload.containerId()) {
				menu.networkState().apply(payload);
			}
		});
	}
}
