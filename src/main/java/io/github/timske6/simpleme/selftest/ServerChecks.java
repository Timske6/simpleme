package io.github.timske6.simpleme.selftest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.oredict.OreDictionary;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.block.TerminalContents;
import io.github.timske6.simpleme.block.TileTerminal;
import io.github.timske6.simpleme.storage.ItemKey;
import io.github.timske6.simpleme.storage.TerminalStorage;

/** Server side checks of the self-test. All methods run on the server thread. */
final class ServerChecks {

    /** Ore dictionary names of typical GregTech items (skipped if not present). */
    static final String[] GT_ORES = { "plateSteel", "ingotSteel", "circuitBasic", "circuitGood", "plateCopper",
        "ingotCopper", "wireGt01Tin", "screwIron", "gearGtSmallSteel", "dustTin", "ingotBronze", "plateBronze",
        "stickIron", "rotorSteel", "cellEmpty" };

    private ServerChecks() {}

    // ------------------------------------------------------------------------------------------
    // Pure storage logic
    // ------------------------------------------------------------------------------------------

    static void storageLogic(Results r) {
        TerminalStorage storage = new TerminalStorage(() -> {});
        ItemKey stone = ItemKey.of(new ItemStack(Blocks.stone));
        r.check(storage.insert(stone, 100) == 100 && storage.getAmount(stone) == 100, "storage: insert");
        r.check(storage.extract(stone, 30) == 30 && storage.extract(stone, 500) == 70, "storage: extract");
        r.check(storage.isEmpty(), "storage: empty entries are removed");
        r.check(storage.insert(stone, Long.MAX_VALUE) == TerminalStorage.MAX_PER_TYPE, "storage: cap per type");
        r.check(storage.insert(stone, 1) == 0, "storage: no overflow");

        ItemStack named = new ItemStack(Items.diamond);
        named.setStackDisplayName("Shiny");
        storage.insert(ItemKey.of(new ItemStack(Items.diamond)), 5);
        storage.insert(ItemKey.of(named), 2);
        r.check(
            storage.getAmount(ItemKey.of(new ItemStack(Items.diamond))) == 5
                && storage.getAmount(ItemKey.of(named)) == 2,
            "storage: items with NBT are kept separate");

        ItemKey meta1 = ItemKey.of(new ItemStack(Items.dye, 1, 4));
        ItemKey meta2 = ItemKey.of(new ItemStack(Items.dye, 1, 15));
        storage.insert(meta1, 3);
        storage.insert(meta2, 4);
        r.check(storage.getAmount(meta1) == 3 && storage.getAmount(meta2) == 4, "storage: metadata variants separate");

        ItemStack filled = new ItemStack(SimpleME.terminal);
        new TerminalContents(java.util.UUID.randomUUID(), 1, 1).writeTo(filled);
        r.check(storage.insert(ItemKey.of(filled), 1) == 0, "storage: filled terminal is rejected (no nesting)");
        r.check(
            storage.insert(ItemKey.of(new ItemStack(SimpleME.terminal)), 1) == 1,
            "storage: empty terminal allowed");
    }

    static void nbtRoundTrip(Results r) {
        TerminalStorage storage = new TerminalStorage(() -> {});
        List<ItemStack> stacks = testStacks();
        long amount = 1;
        for (ItemStack stack : stacks) {
            storage.insert(ItemKey.of(stack), amount++);
        }
        storage.insert(ItemKey.of(new ItemStack(Items.iron_ingot)), 123_456_789_000L);
        NBTTagList list = storage.writeToNBT();
        TerminalStorage loaded = new TerminalStorage(() -> {});
        loaded.readFromNBT(list);
        boolean same = loaded.typeCount() == storage.typeCount();
        for (Map.Entry<ItemKey, Long> entry : storage.view()
            .entrySet()) {
            same &= loaded.getAmount(entry.getKey()) == entry.getValue();
        }
        r.check(same, "save/load: all entries survive", storage.typeCount() + " types incl. GregTech items");

        NBTTagCompound ghost = new NBTTagCompound();
        ghost.setString("id", "removedmod:ghost_item");
        ghost.setInteger("Damage", 0);
        ghost.setLong("Count", 5);
        list.appendTag(ghost);
        TerminalStorage lenient = new TerminalStorage(() -> {});
        lenient.readFromNBT(list);
        r.check(lenient.typeCount() == storage.typeCount(), "save/load: items of removed mods are skipped, rest loads");
    }

    /** Vanilla items with metadata and NBT plus GregTech items from the ore dictionary. */
    static List<ItemStack> testStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(new ItemStack(Blocks.wool, 1, 14));
        stacks.add(new ItemStack(Items.diamond_pickaxe, 1, 321));
        ItemStack named = new ItemStack(Items.iron_sword);
        named.setStackDisplayName("Excalibur");
        stacks.add(named);
        ItemStack book = new ItemStack(Items.enchanted_book);
        Items.enchanted_book.addEnchantment(book, new net.minecraft.enchantment.EnchantmentData(34, 3));
        stacks.add(book);
        for (String ore : GT_ORES) {
            List<ItemStack> ores = OreDictionary.getOres(ore);
            if (!ores.isEmpty()) {
                ItemStack stack = ores.get(0)
                    .copy();
                if (stack.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                    stack.setItemDamage(0);
                }
                stacks.add(stack);
            }
        }
        return stacks;
    }

    // ------------------------------------------------------------------------------------------
    // World interaction
    // ------------------------------------------------------------------------------------------

    static TileTerminal placeTerminal(World world, int x, int y, int z) {
        world.setBlock(x, y, z, SimpleME.terminal, 3, 3);
        return (TileTerminal) world.getTileEntity(x, y, z);
    }

    static ItemStack collectTerminalDrop(World world, int x, int y, int z) {
        List<?> entities = world.getEntitiesWithinAABB(
            EntityItem.class,
            AxisAlignedBB.getBoundingBox(x - 3, y - 3, z - 3, x + 4, y + 4, z + 4));
        ItemStack found = null;
        for (Object object : entities) {
            EntityItem entity = (EntityItem) object;
            ItemStack stack = entity.getEntityItem();
            if (stack != null && stack.getItem() == SimpleME.terminalItem()) {
                found = stack.copy();
                entity.setDead();
            }
        }
        return found;
    }

    static void breakAndPlace(Results r, World world, EntityPlayerMP player, int x, int y, int z) {
        TileTerminal te = placeTerminal(world, x, y, z);
        te.getStorage()
            .insert(ItemKey.of(new ItemStack(Items.gold_ingot)), 500);
        for (ItemStack stack : testStacks()) {
            te.getStorage()
                .insert(ItemKey.of(stack), 7);
        }
        int types = te.getStorage()
            .typeCount();

        // survival player breaks it by hand
        player.setGameType(WorldSettings.GameType.SURVIVAL);
        player.theItemInWorldManager.tryHarvestBlock(x, y, z);
        r.check(world.getBlock(x, y, z) != SimpleME.terminal, "break: block removed by player");
        ItemStack drop = collectTerminalDrop(world, x, y, z);
        TerminalContents contents = TerminalContents.read(drop);
        r.check(
            contents != null && contents.types == types,
            "break: dropped item keeps the storage",
            contents == null ? "no contents" : contents.types + " types");

        // place it again like a player would
        if (drop != null) {
            drop.tryPlaceItemIntoWorld(player, world, x, y - 1, z, 1, 0.5F, 1.0F, 0.5F);
            TileEntity placed = world.getTileEntity(x, y, z);
            boolean restored = placed instanceof TileTerminal && ((TileTerminal) placed).getStorage()
                .typeCount() == types
                && ((TileTerminal) placed).getStorage()
                    .getAmount(ItemKey.of(new ItemStack(Items.gold_ingot))) == 500;
            r.check(restored, "place: contents restored after placing again");
        }

        // creative mode: still drops a filled terminal
        player.setGameType(WorldSettings.GameType.CREATIVE);
        player.theItemInWorldManager.tryHarvestBlock(x, y, z);
        ItemStack creativeDrop = collectTerminalDrop(world, x, y, z);
        r.check(TerminalContents.read(creativeDrop) != null, "break: creative mode drops a filled terminal");

        // explosion (still in creative, so the test player takes no damage)
        TileTerminal exploded = placeTerminal(world, x, y, z);
        exploded.getStorage()
            .insert(ItemKey.of(new ItemStack(Items.emerald)), 42);
        world.newExplosion(null, x + 0.5, y + 0.5, z + 0.5, 3.0F, false, true);
        ItemStack explosionDrop = collectTerminalDrop(world, x, y, z);
        r.check(TerminalContents.read(explosionDrop) != null, "break: explosion drops a filled terminal");
        player.setGameType(WorldSettings.GameType.SURVIVAL);
        // the explosion left a crater: put ground back for the next checks
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy < 0; dy++) {
                    if (world.isAirBlock(x + dx, y + dy, z + dz)) {
                        world.setBlock(x + dx, y + dy, z + dz, Blocks.stone);
                    }
                }
            }
        }

        // an empty terminal drops a plain, stackable item
        placeTerminal(world, x, y, z).getStorage();
        player.theItemInWorldManager.tryHarvestBlock(x, y, z);
        ItemStack emptyDrop = collectTerminalDrop(world, x, y, z);
        r.check(
            emptyDrop != null && !emptyDrop.hasTagCompound(),
            "break: empty terminal drops a plain item",
            String.valueOf(emptyDrop));
    }

    /** Terminal with hoppers above (feeding) and below (must not pull anything out). */
    static TileTerminal setUpHoppers(World world, int x, int y, int z) {
        world.setBlock(x, y, z, Blocks.hopper, 0, 3);
        TileTerminal te = placeTerminal(world, x, y + 1, z);
        te.getStorage()
            .insert(ItemKey.of(new ItemStack(Items.diamond)), 3);
        world.setBlock(x, y + 2, z, Blocks.hopper, 0, 3);
        TileEntityHopper above = (TileEntityHopper) world.getTileEntity(x, y + 2, z);
        above.setInventorySlotContents(0, new ItemStack(Items.stick, 5));
        return te;
    }

    static void checkHoppers(Results r, World world, int x, int y, int z) {
        TileTerminal te = (TileTerminal) world.getTileEntity(x, y + 1, z);
        TileEntityHopper below = (TileEntityHopper) world.getTileEntity(x, y, z);
        TerminalStorage storage = te.getStorage();
        r.check(
            storage.getAmount(ItemKey.of(new ItemStack(Items.stick))) == 5,
            "hopper: inserts into the terminal",
            "sticks=" + storage.getAmount(ItemKey.of(new ItemStack(Items.stick))));
        boolean belowEmpty = true;
        for (int i = 0; i < below.getSizeInventory(); i++) {
            belowEmpty &= below.getStackInSlot(i) == null;
        }
        r.check(
            belowEmpty && storage.getAmount(ItemKey.of(new ItemStack(Items.diamond))) == 3,
            "hopper: cannot pull items out");
    }

    /** Fills the terminal used for the GUI checks. Returns the number of item types. */
    static int fillUiTerminal(TileTerminal te) {
        TerminalStorage storage = te.getStorage();
        storage.insert(ItemKey.of(new ItemStack(Blocks.stone)), 12_345);
        storage.insert(ItemKey.of(new ItemStack(Items.iron_ingot)), 1_500_000);
        storage.insert(ItemKey.of(new ItemStack(Blocks.log)), 64);
        storage.insert(ItemKey.of(new ItemStack(Items.diamond)), 50);
        storage.insert(ItemKey.of(new ItemStack(Blocks.glass_pane)), 16);
        storage.insert(ItemKey.of(new ItemStack(Items.redstone)), 64);
        storage.insert(ItemKey.of(chestForRecipe()), 4);
        storage.insert(ItemKey.of(new ItemStack(TestItems.hammer)), 1);
        storage.insert(ItemKey.of(new ItemStack(Items.clay_ball)), 10);
        storage.insert(ItemKey.of(new ItemStack(Items.milk_bucket)), 3);
        storage.insert(ItemKey.of(new ItemStack(TestItems.token)), 5);
        for (ItemStack stack : testStacks()) {
            storage.insert(ItemKey.of(stack), 33);
        }
        for (int i = 0; i < 3000; i++) {
            ItemStack paper = new ItemStack(Items.paper);
            paper.setStackDisplayName("Paper #" + i);
            storage.insert(ItemKey.of(paper), i + 1);
        }
        return storage.typeCount();
    }

    static ItemStack chestForRecipe() {
        List<ItemStack> chests = OreDictionary.getOres("chestWood");
        if (!chests.isEmpty()) {
            ItemStack chest = chests.get(0)
                .copy();
            if (chest.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                chest.setItemDamage(0);
            }
            return chest;
        }
        return new ItemStack(Blocks.chest);
    }

    static int count(EntityPlayerMP player, Item item) {
        int count = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() == item) {
                count += stack.stackSize;
            }
        }
        return count;
    }
}
