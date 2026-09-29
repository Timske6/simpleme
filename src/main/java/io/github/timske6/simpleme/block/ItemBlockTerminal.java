package io.github.timske6.simpleme.block;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class ItemBlockTerminal extends ItemBlock {

    public ItemBlockTerminal(Block block) {
        super(block);
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        TerminalContents contents = TerminalContents.read(stack);
        if (contents != null) {
            tooltip.add(
                EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                    "tooltip.simpleme.stored",
                    String.format("%,d", contents.total),
                    contents.types));
        } else {
            tooltip.add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("tooltip.simpleme.empty"));
        }
    }
}
