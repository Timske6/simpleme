package io.github.timske6.simpleme;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapedOreRecipe;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import io.github.timske6.simpleme.block.BlockTerminal;
import io.github.timske6.simpleme.block.ItemBlockTerminal;
import io.github.timske6.simpleme.block.TileTerminal;
import io.github.timske6.simpleme.network.MainThread;
import io.github.timske6.simpleme.network.Network;

@Mod(
    modid = SimpleME.MODID,
    version = Tags.VERSION,
    name = "Simple ME Terminal",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "after:NotEnoughItems")
public class SimpleME {

    public static final String MODID = "simpleme";
    public static final Logger LOG = LogManager.getLogger(MODID);
    public static final int GUI_TERMINAL = 0;

    @Mod.Instance(MODID)
    public static SimpleME instance;

    @SidedProxy(
        clientSide = "io.github.timske6.simpleme.ClientProxy",
        serverSide = "io.github.timske6.simpleme.CommonProxy")
    public static CommonProxy proxy;

    public static BlockTerminal terminal;

    public static Item terminalItem() {
        return Item.getItemFromBlock(terminal);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        terminal = new BlockTerminal();
        GameRegistry.registerBlock(terminal, ItemBlockTerminal.class, "terminal");
        GameRegistry.registerTileEntity(TileTerminal.class, MODID + ":terminal");
        proxy.preInit(event);
        selfTestHook("preInit");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        Network.init();
        NetworkRegistry.INSTANCE.registerGuiHandler(this, new GuiHandler());
        FMLCommonHandler.instance()
            .bus()
            .register(new MainThread.ServerTicker());

        Object chest = OreDictionary.getOres("chestWood")
            .isEmpty() ? new ItemStack(Blocks.chest) : "chestWood";
        GameRegistry.addRecipe(
            new ShapedOreRecipe(
                new ItemStack(terminal),
                "IGI",
                "RCR",
                "IDI",
                'I',
                "ingotIron",
                'G',
                new ItemStack(Blocks.glass_pane),
                'R',
                "dustRedstone",
                'C',
                chest,
                'D',
                "gemDiamond"));

        proxy.init(event);
        selfTestHook("init");
    }

    /** The automated in-game test only exists in test builds (./gradlew build -Pselftest). */
    private static void selfTestHook(String method) {
        if (!Boolean.getBoolean("simpleme.selftest")) {
            return;
        }
        try {
            Class.forName("io.github.timske6.simpleme.selftest.SelfTest")
                .getMethod(method)
                .invoke(null);
        } catch (ClassNotFoundException e) {
            LOG.warn("simpleme.selftest is set, but this build does not contain the self-test");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Simple ME self-test failed to start", e);
        }
    }
}
