package io.github.timske6.simpleme.item;

import io.github.timske6.simpleme.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

/** Sneak + right click on a terminal links the chest instead of placing it. */
public class TransferChestItem extends BlockItem {
	public TransferChestItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		InteractionResult linked = StorageLinking.linkToTerminal(context);
		return linked != InteractionResult.PASS ? linked : super.useOn(context);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(Component.translatable("tooltip.simpleme.transfer_chest").withStyle(ChatFormatting.GRAY));
		StorageLinking.appendTooltip(stack.get(ModRegistry.LINK), builder);
	}
}
