package io.github.timske6.simpleme.item;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TransferChestBlockEntity;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.menu.WirelessTerminalHost;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Opens an ME network from anywhere: no range limit, works in every dimension, no terminal block
 * needed. The network is chosen in its GUI or taken from a terminal with sneak + right click.
 */
public class WirelessTerminalItem extends Item {
	public WirelessTerminalItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		InteractionResult linked = StorageLinking.linkToTerminal(context);
		if (linked != InteractionResult.PASS) {
			return linked;
		}
		// Sneak + right click on a transfer chest: connect the chest to the same network.
		StorageLink link = context.getItemInHand().get(ModRegistry.LINK);
		Player player = context.getPlayer();
		if (link != null && player != null && player.isSecondaryUseActive()
			&& context.getLevel().getBlockEntity(context.getClickedPos()) instanceof TransferChestBlockEntity chest) {
			if (context.getLevel() instanceof ServerLevel level) {
				Networks.Network network = Networks.get(level.getServer()).find(link.id());
				if (network != null && network.canAccess(player)) {
					chest.setNetwork(network);
					player.sendOverlayMessage(Component.translatable("message.simpleme.chest_linked", network.name()).withStyle(ChatFormatting.GREEN));
				}
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer) {
			open(serverPlayer, hand);
		}
		return InteractionResult.SUCCESS;
	}

	/** Opens the terminal GUI; without a usable network it shows the network list right away. */
	public static void open(ServerPlayer player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		MinecraftServer server = player.level().getServer();
		StorageLink link = stack.get(ModRegistry.LINK);
		if (link != null) {
			// keep the name in the tooltip up to date, forget deleted networks
			Networks.Network network = Networks.get(server).find(link.id());
			if (network == null) {
				stack.remove(ModRegistry.LINK);
			} else if (!network.name().equals(link.name())) {
				stack.set(ModRegistry.LINK, StorageLink.of(network));
			}
		}
		Component title = stack.getHoverName();
		player.openMenu(new ExtendedMenuProvider<InteractionHand>() {
			@Override
			public InteractionHand getScreenOpeningData(ServerPlayer player) {
				return hand;
			}

			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
				return new TerminalMenu(ModRegistry.WIRELESS_TERMINAL_MENU, containerId, inventory, new WirelessTerminalHost(server, player, hand));
			}
		});
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		StorageLinking.appendTooltip(stack.get(ModRegistry.LINK), builder);
	}
}
