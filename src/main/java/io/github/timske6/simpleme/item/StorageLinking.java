package io.github.timske6.simpleme.item;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import org.jspecify.annotations.Nullable;

/** Connecting wireless terminals and transfer chests to a network. */
public final class StorageLinking {
	private StorageLinking() {
	}

	/** Sneak + right click on a placed terminal: the item in hand gets the terminal's network. */
	public static InteractionResult linkToTerminal(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null || !player.isSecondaryUseActive()
			|| !(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof TerminalBlockEntity terminal)) {
			return InteractionResult.PASS;
		}
		if (context.getLevel() instanceof ServerLevel level) {
			Networks.Network network = Networks.get(level.getServer()).find(terminal.getNetworkId());
			if (network == null || !network.canAccess(player)) {
				player.sendOverlayMessage(Component.translatable("message.simpleme.terminal_no_network").withStyle(ChatFormatting.RED));
			} else {
				context.getItemInHand().set(ModRegistry.LINK, StorageLink.of(network));
				player.sendOverlayMessage(Component.translatable("message.simpleme.linked", network.name()).withStyle(ChatFormatting.GREEN));
			}
		}
		return InteractionResult.SUCCESS;
	}

	public static void appendTooltip(@Nullable StorageLink link, Consumer<Component> builder) {
		if (link != null) {
			builder.accept(Component.translatable("tooltip.simpleme.linked", link.name()).withStyle(ChatFormatting.GRAY));
		} else {
			builder.accept(Component.translatable("tooltip.simpleme.not_linked").withStyle(ChatFormatting.RED));
		}
	}
}
