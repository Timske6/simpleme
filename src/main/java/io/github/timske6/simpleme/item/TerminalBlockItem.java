package io.github.timske6.simpleme.item;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.storage.StoredItems;
import io.github.timske6.simpleme.storage.TerminalContents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

public class TerminalBlockItem extends BlockItem {
	public TerminalBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		TerminalContents contents = stack.get(ModRegistry.STORAGE);
		StoredItems legacy = stack.get(ModRegistry.LEGACY_STORED_ITEMS);
		if (contents != null && contents.types() > 0) {
			builder.accept(Component.translatable("tooltip.simpleme.stored", String.format("%,d", contents.total()), contents.types())
				.withStyle(ChatFormatting.GRAY));
		} else if (legacy != null && !legacy.entries().isEmpty()) {
			builder.accept(Component.translatable("tooltip.simpleme.stored", String.format("%,d", legacy.totalCount()), legacy.entries().size())
				.withStyle(ChatFormatting.GRAY));
		} else {
			builder.accept(Component.translatable("tooltip.simpleme.empty").withStyle(ChatFormatting.DARK_GRAY));
		}
	}
}
