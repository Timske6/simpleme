package io.github.timske6.simpleme.block;

import java.util.ArrayList;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.github.timske6.simpleme.SimpleME;

/**
 * The terminal block. Breaking it (by hand, tool, explosion or machine) always drops a terminal item that
 * keeps the storage id, so nothing is ever lost.
 */
public class BlockTerminal extends BlockContainer {

    private IIcon front;
    private IIcon side;
    private IIcon top;

    public BlockTerminal() {
        super(Material.iron);
        setHardness(1.5F);
        setResistance(6.0F);
        setStepSound(soundTypeMetal);
        setBlockName("simpleme.terminal");
        setCreativeTab(CreativeTabs.tabDecorations);
        setHarvestLevel("pickaxe", 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileTerminal();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (!world.isRemote) {
            player.openGui(SimpleME.instance, SimpleME.GUI_TERMINAL, world, x, y, z);
        }
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int facing = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        int[] metaForFacing = { 2, 5, 3, 4 };
        world.setBlockMetadataWithNotify(x, y, z, metaForFacing[facing], 2);

        TerminalContents contents = TerminalContents.read(stack);
        TileEntity te = world.getTileEntity(x, y, z);
        if (contents != null && te instanceof TileTerminal) {
            ((TileTerminal) te).setStorageId(contents.id);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Drops: the tile entity must still exist when the drop is created
    // ------------------------------------------------------------------------------------------

    /** Creates the dropped item; filled terminals carry their storage id. */
    public ItemStack createDrop(World world, int x, int y, int z) {
        ItemStack stack = new ItemStack(this);
        TileEntity te = world.getTileEntity(x, y, z);
        if (!world.isRemote && te instanceof TileTerminal) {
            TerminalContents contents = ((TileTerminal) te).contents();
            if (contents != null) {
                contents.writeTo(stack);
            }
        }
        return stack;
    }

    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean willHarvest) {
        if (willHarvest) {
            // Removed in harvestBlock, after the drop was created.
            return true;
        }
        if (!world.isRemote && player.capabilities.isCreativeMode) {
            ItemStack drop = createDrop(world, x, y, z);
            if (TerminalContents.read(drop) != null) {
                // Like shulker boxes: creative mode still drops a filled terminal, so no items are lost.
                dropBlockAsItem(world, x, y, z, drop);
            }
        }
        return world.setBlockToAir(x, y, z);
    }

    @Override
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int meta) {
        super.harvestBlock(world, player, x, y, z, meta);
        world.setBlockToAir(x, y, z);
    }

    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<>();
        drops.add(createDrop(world, x, y, z));
        return drops;
    }

    /** Explosions only drop blocks with a chance; a terminal must always drop. */
    @Override
    public void dropBlockAsItemWithChance(World world, int x, int y, int z, int meta, float chance, int fortune) {
        if (!world.isRemote) {
            for (ItemStack stack : getDrops(world, x, y, z, meta, fortune)) {
                dropBlockAsItem(world, x, y, z, stack);
            }
        }
    }

    /** Silk touch would drop a plain block without the storage id. */
    @Override
    public boolean canSilkHarvest(World world, EntityPlayer player, int x, int y, int z, int metadata) {
        return false;
    }

    /** Breaking by hand must drop it too, otherwise the items would be orphaned. */
    @Override
    public boolean canHarvestBlock(EntityPlayer player, int meta) {
        return true;
    }

    @Override
    public int quantityDropped(java.util.Random random) {
        return 1;
    }

    @Override
    public Item getItemDropped(int meta, java.util.Random random, int fortune) {
        return Item.getItemFromBlock(this);
    }

    // ------------------------------------------------------------------------------------------
    // Textures
    // ------------------------------------------------------------------------------------------

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        front = register.registerIcon(SimpleME.MODID + ":terminal_front");
        side = register.registerIcon(SimpleME.MODID + ":terminal_side");
        top = register.registerIcon(SimpleME.MODID + ":terminal_top");
        blockIcon = side;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        int facing = meta < 2 ? 3 : meta;
        if (side == facing) {
            return front;
        }
        return side < 2 ? top : this.side;
    }
}
