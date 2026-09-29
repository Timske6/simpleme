package io.github.timske6.simpleme.selftest;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.block.TileTerminal;
import io.github.timske6.simpleme.client.GuiTerminal;
import io.github.timske6.simpleme.menu.ContainerTerminal;
import io.github.timske6.simpleme.network.MainThread;
import io.github.timske6.simpleme.network.Network;
import io.github.timske6.simpleme.network.PacketFillGrid;
import io.github.timske6.simpleme.network.PacketStorageClick;
import io.github.timske6.simpleme.storage.ItemKey;
import io.github.timske6.simpleme.storage.TerminalStorage;

/**
 * Automated in-game test, only in self-test builds and only active with {@code -Dsimpleme.selftest=true}.
 * Creates a flat test world, runs all checks (server logic, GUI, NEI transfer, crafting, reload), takes
 * screenshots, writes {@code simpleme-selftest.txt} into the game folder and closes the game.
 */
public final class SelfTest {

    private static final String WORLD = "simpleme-selftest";
    private static final int TIMEOUT_TICKS = 20 * 60 * 25;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Results results = new Results();
    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;
    private int stepTicks;
    private int totalTicks;
    private boolean started;
    private boolean finished;

    // shared state between the steps
    private int baseX;
    private int baseY;
    private int baseZ;
    private int uiTypes;
    private long ironBefore;
    private long diamondsBefore;

    /** Called from the mod's preInit (self-test builds only). */
    public static void preInit() {
        if (Boolean.getBoolean("simpleme.selftest")) {
            TestItems.register();
        }
    }

    /** Called from the mod's init (self-test builds only). */
    public static void init() {
        if (!Boolean.getBoolean("simpleme.selftest")) {
            return;
        }
        TestItems.registerRecipes();
        if (FMLCommonHandler.instance()
            .getSide()
            .isClient()) {
            SelfTest test = new SelfTest();
            test.buildSteps();
            FMLCommonHandler.instance()
                .bus()
                .register(test);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(test);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) {
            return;
        }
        totalTicks++;
        // the test window usually has no focus: never pause the game because of that
        mc.gameSettings.pauseOnLostFocus = false;
        if (!started) {
            // Wait until the game has finished loading and shows its title screen. Modpacks like GTNH replace the
            // vanilla main menu (CustomMainMenu), so any screen without a world counts.
            if (mc.theWorld == null && mc.currentScreen != null && totalTicks > 200) {
                started = true;
                stepTicks = 0;
                steps.get(0).start.run();
            }
            return;
        }
        if (totalTicks > TIMEOUT_TICKS) {
            results.check(false, "whole test finished in time");
            finish();
            return;
        }
        Step step = steps.get(stepIndex);
        stepTicks++;
        boolean done;
        try {
            done = step.done.getAsBoolean();
        } catch (Throwable t) {
            results.error(step.name, t);
            done = true;
        }
        if (!done && stepTicks > step.timeout) {
            results.check(false, step.name, "timed out after " + step.timeout + " ticks");
            done = true;
        }
        if (done) {
            stepIndex++;
            stepTicks = 0;
            if (stepIndex >= steps.size()) {
                finish();
                return;
            }
            Step next = steps.get(stepIndex);
            try {
                next.start.run();
            } catch (Throwable t) {
                results.error(next.name, t);
            }
        }
    }

    /** Diagnostics: log who closes or replaces the terminal screen, and never let the pause menu stop the test. */
    @SubscribeEvent
    public void onGuiOpen(net.minecraftforge.client.event.GuiOpenEvent event) {
        if (!started || finished) {
            return;
        }
        if (mc.currentScreen instanceof GuiTerminal && !(event.gui instanceof GuiTerminal)) {
            SimpleME.LOG.warn(
                "[selftest] terminal screen replaced by {} during step '{}'",
                event.gui,
                stepIndex < steps.size() ? steps.get(stepIndex).name : "?",
                new Throwable("screen change"));
        }
        if (event.gui instanceof net.minecraft.client.gui.GuiIngameMenu) {
            event.setCanceled(true);
        }
    }

    private void finish() {
        finished = true;
        results.write(new File(mc.mcDataDir, "simpleme-selftest.txt"));
        if (!Boolean.getBoolean("simpleme.selftest.keepopen")) {
            mc.shutdown();
        }
    }

    // ------------------------------------------------------------------------------------------
    // The test script
    // ------------------------------------------------------------------------------------------

    private void buildSteps() {
        client("create test world", () -> {
            mc.getSaveLoader()
                .deleteWorldDirectory(WORLD);
            WorldSettings settings = new WorldSettings(
                1234L,
                WorldSettings.GameType.SURVIVAL,
                false,
                false,
                WorldType.FLAT).enableCommands();
            mc.launchIntegratedServer(WORLD, WORLD, settings);
        });
        waitUntil("world loaded", this::inWorld, 20 * 60 * 10);
        waitTicks(100);

        server("storage logic", ctx -> ServerChecks.storageLogic(results));
        server("save/load", ctx -> ServerChecks.nbtRoundTrip(results));
        server("break/place/explode", ctx -> {
            baseX = (int) Math.floor(ctx.player.posX) + 3;
            baseY = (int) Math.floor(ctx.player.posY);
            baseZ = (int) Math.floor(ctx.player.posZ);
            results.info(
                "GregTech loaded: " + cpw.mods.fml.common.Loader.isModLoaded("gregtech")
                    + ", NEI loaded: "
                    + cpw.mods.fml.common.Loader.isModLoaded("NotEnoughItems"));
            ServerChecks.breakAndPlace(results, ctx.world, ctx.player, baseX, baseY, baseZ);
        });
        server("hoppers", ctx -> ServerChecks.setUpHoppers(ctx.world, baseX + 3, baseY, baseZ));
        waitTicks(80);
        server("hopper result", ctx -> ServerChecks.checkHoppers(results, ctx.world, baseX + 3, baseY, baseZ));

        // --- GUI ---------------------------------------------------------------------------------
        server("open terminal GUI", ctx -> {
            TileTerminal te = ServerChecks.placeTerminal(ctx.world, baseX, baseY, baseZ + 2);
            uiTypes = ServerChecks.fillUiTerminal(te);
            ctx.player.openGui(SimpleME.instance, SimpleME.GUI_TERMINAL, ctx.world, baseX, baseY, baseZ + 2);
        });
        waitUntil("GUI shows all item types", () -> gui() != null && gui().visibleEntryCount() == uiTypes, 20 * 30);
        waitTicks(10);
        client("screenshot open", () -> screenshot("simpleme-01-terminal"));
        client("NEI integration registered", this::checkNei);
        client("search", () -> gui().setSearch("paper #12"));
        waitTicks(5);
        client("search result", () -> {
            results.check(
                // "paper #12", "#120".."#129" and "#1200".."#1299"
                gui().visibleEntryCount() == 111,
                "search filters the grid",
                "found " + gui().visibleEntryCount());
            screenshot("simpleme-02-search");
            gui().setSearch("");
        });

        // --- NEI transfer + crafting --------------------------------------------------------------
        server(
            "remember iron",
            ctx -> ironBefore = ctx.storage(baseX, baseY, baseZ + 2)
                .getAmount(ItemKey.of(new ItemStack(Items.iron_ingot))));
        client("NEI transfer: terminal recipe", () -> fillGrid(terminalRecipe(), false));
        waitTicks(10);
        client("result shows terminal", () -> {
            ItemStack result = container().getSlot(ContainerTerminal.RESULT_SLOT)
                .getStack();
            results.check(
                result != null && result.getItem() == SimpleME.terminalItem(),
                "NEI transfer fills the grid from storage",
                String.valueOf(result));
            screenshot("simpleme-03-recipe");
            mc.playerController.windowClick(container().windowId, ContainerTerminal.RESULT_SLOT, 0, 1, mc.thePlayer);
        });
        waitTicks(10);
        server("shift-click crafted", ctx -> {
            int crafted = ServerChecks.count(ctx.player, SimpleME.terminalItem());
            long iron = ctx.storage(baseX, baseY, baseZ + 2)
                .getAmount(ItemKey.of(new ItemStack(Items.iron_ingot)));
            // 4 chests limit it to 4 crafts; each takes 4 iron, and the grid holds one more set (4 iron)
            results.check(crafted == 4, "shift-click crafts until an ingredient runs out", "crafted " + crafted);
            // 4 iron went into the grid, and after every craft the grid was refilled with 4 more
            results.check(
                iron == ironBefore - 4L * (crafted + 1),
                "ingredients came from storage",
                "iron " + ironBefore + " -> " + iron);
        });

        client(
            "clear grid",
            () -> mc.playerController.sendEnchantPacket(container().windowId, ContainerTerminal.BUTTON_CLEAR_GRID));
        waitTicks(5);
        client(
            "NEI transfer: tool recipe",
            () -> fillGrid(
                Arrays.asList(stacks(new ItemStack(TestItems.hammer)), stacks(new ItemStack(Items.clay_ball))),
                false));
        waitTicks(10);
        client(
            "craft with tool",
            () -> mc.playerController
                .windowClick(container().windowId, ContainerTerminal.RESULT_SLOT, 0, 1, mc.thePlayer));
        waitTicks(10);
        server("tool stays in grid", ctx -> {
            ContainerTerminal c = (ContainerTerminal) ctx.player.openContainer;
            int bricks = ServerChecks.count(ctx.player, Items.brick);
            ItemStack hammer = null;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = c.craftMatrix.getStackInSlot(i);
                if (stack != null && stack.getItem() == TestItems.hammer) {
                    hammer = stack;
                }
            }
            results.check(bricks == 10, "tool recipe: crafted with all clay from storage", "bricks " + bricks);
            results.check(
                hammer != null && TestItems.uses(hammer) == 10,
                "tool recipe: damaged tool stays in the grid (like GregTech tools)",
                "uses " + TestItems.uses(hammer));
        });
        client("client view after tool crafting", () -> {
            ContainerTerminal c = container();
            ItemStack hammer = null;
            int clay = 0;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = c.craftMatrix.getStackInSlot(i);
                if (stack != null && stack.getItem() == TestItems.hammer) {
                    hammer = stack;
                } else if (stack != null && stack.getItem() == Items.clay_ball) {
                    clay += stack.stackSize;
                }
            }
            int bricks = 0;
            for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
                if (stack != null && stack.getItem() == Items.brick) {
                    bricks += stack.stackSize;
                }
            }
            results.check(
                hammer != null && TestItems.uses(hammer) == 10 && clay == 0,
                "client shows the real crafting grid after shift-click crafting",
                "hammer uses " + TestItems.uses(hammer) + ", clay " + clay);
            results.check(bricks == 10, "client shows the crafted items in the inventory", "bricks " + bricks);
            screenshot("simpleme-04-tool");
        });

        client(
            "clear grid 2",
            () -> mc.playerController.sendEnchantPacket(container().windowId, ContainerTerminal.BUTTON_CLEAR_GRID));
        waitTicks(5);
        client(
            "NEI transfer: bucket recipe",
            () -> fillGrid(
                Arrays.asList(stacks(new ItemStack(Items.milk_bucket)), stacks(new ItemStack(TestItems.token))),
                false));
        waitTicks(10);
        client(
            "take cake",
            () -> mc.playerController
                .windowClick(container().windowId, ContainerTerminal.RESULT_SLOT, 0, 0, mc.thePlayer));
        waitTicks(10);
        server("bucket goes to storage", ctx -> {
            TerminalStorage storage = ctx.storage(baseX, baseY, baseZ + 2);
            ContainerTerminal c = (ContainerTerminal) ctx.player.openContainer;
            ItemStack cursor = ctx.player.inventory.getItemStack();
            results.check(
                cursor != null && cursor.getItem() == TestItems.result,
                "bucket recipe: crafted",
                String.valueOf(cursor));
            results.check(
                storage.getAmount(ItemKey.of(new ItemStack(Items.bucket))) == 1,
                "bucket recipe: empty bucket went into the storage");
            boolean refilled = false;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = c.craftMatrix.getStackInSlot(i);
                refilled |= stack != null && stack.getItem() == Items.milk_bucket;
            }
            results.check(refilled, "bucket recipe: grid refilled with a milk bucket from storage");
        });

        // --- grid clicks through the real packets -------------------------------------------------
        client("client view after bucket crafting", () -> {
            boolean milk = false;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = container().craftMatrix.getStackInSlot(i);
                milk |= stack != null && stack.getItem() == Items.milk_bucket;
            }
            results.check(milk, "client shows the refilled grid after taking the result");
        });
        client("store cake", () -> click(new ItemStack(TestItems.result), PacketStorageClick.INSERT_ALL));
        waitTicks(5);
        server(
            "remember diamonds",
            ctx -> diamondsBefore = ctx.storage(baseX, baseY, baseZ + 2)
                .getAmount(ItemKey.of(new ItemStack(Items.diamond))));
        client("take diamonds", () -> click(new ItemStack(Items.diamond), PacketStorageClick.TAKE_STACK));
        waitTicks(5);
        server("clicks", ctx -> {
            ItemStack cursor = ctx.player.inventory.getItemStack();
            TerminalStorage storage = ctx.storage(baseX, baseY, baseZ + 2);
            results.check(storage.getAmount(ItemKey.of(new ItemStack(TestItems.result))) == 1, "click: cursor stored");
            results.check(
                cursor != null && cursor.getItem() == Items.diamond && cursor.stackSize == Math.min(64, diamondsBefore),
                "click: takes a stack onto the cursor",
                cursor + " of " + diamondsBefore);
        });
        client("put diamonds back", () -> click(new ItemStack(Items.diamond), PacketStorageClick.INSERT_ALL));
        waitTicks(5);
        client("shift-click stone", () -> click(new ItemStack(Blocks.stone), PacketStorageClick.QUICK_MOVE));
        waitTicks(5);
        server(
            "quick move",
            ctx -> results.check(
                ServerChecks.count(ctx.player, net.minecraft.item.Item.getItemFromBlock(Blocks.stone)) == 64,
                "click: shift-click moves a stack into the inventory"));
        client("hacked click is ignored", () -> click(new ItemStack(Blocks.bedrock), PacketStorageClick.TAKE_STACK));
        waitTicks(5);
        server(
            "no free items",
            ctx -> results
                .check(ctx.player.inventory.getItemStack() == null, "click: cannot take items that are not stored"));
        client("close GUI", () -> mc.thePlayer.closeScreen());
        waitTicks(10);

        // --- save, quit, load again -----------------------------------------------------------------
        client("quit world", () -> {
            mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld((WorldClient) null);
            mc.displayGuiScreen(new GuiMainMenu());
        });
        waitTicks(200);
        client("load world again", () -> mc.launchIntegratedServer(WORLD, WORLD, null));
        waitUntil("world loaded again", this::inWorld, 20 * 60 * 10);
        waitTicks(60);
        server("contents after reload", ctx -> {
            TerminalStorage storage = ctx.storage(baseX, baseY, baseZ + 2);
            results.check(
                storage.getAmount(ItemKey.of(new ItemStack(TestItems.result))) == 1
                    && storage.getAmount(ItemKey.of(new ItemStack(Blocks.stone))) == 12_345 - 64,
                "reload: storage contents were saved",
                storage.typeCount() + " types");
            ctx.player.openGui(SimpleME.instance, SimpleME.GUI_TERMINAL, ctx.world, baseX, baseY, baseZ + 2);
        });
        waitUntil("GUI after reload", () -> gui() != null && gui().visibleEntryCount() > 3000, 20 * 30);
        waitTicks(10);
        client("screenshot reloaded", () -> screenshot("simpleme-05-reloaded"));
    }

    private void checkNei() {
        try {
            Class<?> api = Class.forName("codechicken.nei.api.API");
            boolean registered = (Boolean) api.getMethod("hasGuiOverlayHandler", Class.class, String.class)
                .invoke(null, GuiTerminal.class, "crafting");
            results.check(registered && GuiTerminal.neiActive, "NEI: recipe transfer handler registered");
        } catch (ClassNotFoundException e) {
            results.info("NEI not installed, NEI checks skipped");
        } catch (ReflectiveOperationException e) {
            results.error("NEI check", e);
        }
    }

    private List<List<ItemStack>> terminalRecipe() {
        ItemStack iron = new ItemStack(Items.iron_ingot);
        ItemStack pane = new ItemStack(Blocks.glass_pane);
        ItemStack redstone = new ItemStack(Items.redstone);
        ItemStack chest = ServerChecks.chestForRecipe();
        ItemStack diamond = new ItemStack(Items.diamond);
        return Arrays.asList(
            stacks(iron),
            stacks(pane),
            stacks(iron),
            stacks(redstone),
            stacks(chest),
            stacks(redstone),
            stacks(iron),
            stacks(diamond),
            stacks(iron));
    }

    private static List<ItemStack> stacks(ItemStack stack) {
        List<ItemStack> list = new ArrayList<>();
        list.add(stack);
        return list;
    }

    private void fillGrid(List<List<ItemStack>> slots, boolean max) {
        List<List<ItemStack>> nine = new ArrayList<>(slots);
        while (nine.size() < 9) {
            nine.add(new ArrayList<>());
        }
        Network.CHANNEL.sendToServer(new PacketFillGrid(container().windowId, nine, max));
    }

    private void click(ItemStack stack, int action) {
        Network.CHANNEL.sendToServer(new PacketStorageClick(container().windowId, stack, action));
    }

    private GuiTerminal gui() {
        return mc.currentScreen instanceof GuiTerminal ? (GuiTerminal) mc.currentScreen : null;
    }

    private ContainerTerminal container() {
        return (ContainerTerminal) mc.thePlayer.openContainer;
    }

    private boolean inWorld() {
        MinecraftServer server = MinecraftServer.getServer();
        return mc.theWorld != null && mc.thePlayer != null
            && server != null
            && !server.getConfigurationManager().playerEntityList.isEmpty();
    }

    private void screenshot(String name) {
        ScreenShotHelper
            .saveScreenshot(mc.mcDataDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }

    // ------------------------------------------------------------------------------------------
    // Step helpers
    // ------------------------------------------------------------------------------------------

    private static final class Step {

        final String name;
        final Runnable start;
        final BooleanSupplier done;
        final int timeout;

        Step(String name, Runnable start, BooleanSupplier done, int timeout) {
            this.name = name;
            this.start = start;
            this.done = done;
            this.timeout = timeout;
        }
    }

    static final class ServerContext {

        final WorldServer world;
        final EntityPlayerMP player;

        ServerContext() {
            MinecraftServer server = MinecraftServer.getServer();
            this.world = server.worldServerForDimension(0);
            this.player = server.getConfigurationManager().playerEntityList.get(0);
        }

        TerminalStorage storage(int x, int y, int z) {
            return ((TileTerminal) world.getTileEntity(x, y, z)).getStorage();
        }
    }

    private void client(String name, Runnable action) {
        steps.add(new Step(name, () -> {
            try {
                action.run();
            } catch (Throwable t) {
                results.error(name, t);
            }
        }, () -> true, 1));
    }

    private void server(String name, Consumer<ServerContext> action) {
        AtomicBoolean done = new AtomicBoolean();
        steps.add(new Step(name, () -> MainThread.server(() -> {
            try {
                action.accept(new ServerContext());
            } catch (Throwable t) {
                results.error(name, t);
            } finally {
                done.set(true);
            }
        }), done::get, 20 * 60));
    }

    private void waitTicks(int ticks) {
        int[] waited = new int[1];
        steps.add(new Step("wait", () -> waited[0] = 0, () -> ++waited[0] >= ticks, ticks + 5));
    }

    private void waitUntil(String name, BooleanSupplier condition, int timeout) {
        steps.add(new Step(name, () -> {}, () -> {
            boolean ok = condition.getAsBoolean();
            if (ok) {
                results.check(true, name);
            }
            return ok;
        }, timeout));
    }
}
