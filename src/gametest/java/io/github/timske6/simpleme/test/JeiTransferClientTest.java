package io.github.timske6.simpleme.test;

import static io.github.timske6.simpleme.test.TestItems.check;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.client.jei.TerminalRecipeTransferHandler;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.storage.TerminalStorage;
import java.util.Objects;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

/** JEI's "+" button in a terminal: fills the crafting grid from the storage, marks missing items. */
@SuppressWarnings({"UnstableApiUsage", "removal"})
public class JeiTransferClientTest implements FabricClientGameTest {
	private static final BlockPos TERMINAL = new BlockPos(0, -60, -3);

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a 0.5 -60 0.5 180 20");
			server.runCommand("setblock 0 -60 -3 simpleme:terminal[facing=south]");
			TerminalStorage[] storage = new TerminalStorage[1];
			server.runOnServer(s -> {
				storage[0] = TestItems.connectNewNetwork(s, TERMINAL, "JEI");
				storage[0].insert(ItemVariant.of(Items.BIRCH_PLANKS), 100);
			});
			context.waitFor(mc -> TestJeiPlugin.runtime != null);

			context.getInput().lookAt(TERMINAL);
			context.waitTicks(2);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			context.waitFor(mc -> menu(mc).getClientItems().getLong(ItemVariant.of(Items.BIRCH_PLANKS)) == 100);

			String handler = context.computeOnClient(mc -> handler(mc).getClass().getName());
			check(handler.equals(TerminalRecipeTransferHandler.class.getName()), "JEI uses the terminal transfer handler, got " + handler);

			// items for the stick are in the storage: no error, the "+" is active
			check(context.computeOnClient(mc -> transfer(mc, "stick", false, false)) == null, "stick can be transferred");
			// no diamonds anywhere: user facing error with red slots
			IRecipeTransferError.Type missing = context.computeOnClient(mc -> {
				IRecipeTransferError error = transfer(mc, "diamond_pickaxe", false, false);
				return error != null ? error.getType() : null;
			});
			check(missing == IRecipeTransferError.Type.USER_FACING, "missing diamonds are reported, got " + missing);

			// the recipe screen with the "+" button
			context.runOnClient(mc -> {
				IJeiRuntime runtime = Objects.requireNonNull(TestJeiPlugin.runtime);
				runtime.getRecipesGui().show(runtime.getJeiHelpers().getFocusFactory()
					.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, new ItemStack(Items.STICK)));
			});
			context.waitTicks(5);
			context.takeScreenshot("simpleme-jei-01-recipe-with-plus");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(TerminalScreen.class);

			// "+" (one set), then shift + "+" (as many as possible)
			context.runOnClient(mc -> transfer(mc, "stick", false, true));
			context.waitFor(mc -> menu(mc).getSlot(TerminalMenu.RESULT_SLOT).getItem().is(Items.STICK));
			context.waitFor(mc -> menu(mc).getClientItems().getLong(ItemVariant.of(Items.BIRCH_PLANKS)) == 98);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> TestItems.screen(mc).visibleCount(0)) == 98, "the terminal shows the remaining planks");
			context.takeScreenshot("simpleme-jei-02-stick-in-grid");
			context.runOnClient(mc -> transfer(mc, "stick", true, true));
			context.waitFor(mc -> menu(mc).getClientItems().getLong(ItemVariant.of(Items.BIRCH_PLANKS)) == 0);
			int perSlot = context.computeOnClient(mc -> menu(mc).getInputGridSlots().stream().mapToInt(slot -> slot.getItem().getCount()).max().orElse(0));
			check(perSlot == 50, "max transfer splits the 100 planks over both slots, got " + perSlot);

			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			server.runOnServer(s -> check(storage[0].getAmount(ItemVariant.of(Items.BIRCH_PLANKS)) == 100, "closing returns the grid"));
		}
	}

	private static TerminalMenu menu(Minecraft mc) {
		return (TerminalMenu) Objects.requireNonNull(mc.player).containerMenu;
	}

	private static IRecipeTransferHandler<TerminalMenu, RecipeHolder<CraftingRecipe>> handler(Minecraft mc) {
		IJeiRuntime runtime = Objects.requireNonNull(TestJeiPlugin.runtime);
		IRecipeCategory<RecipeHolder<CraftingRecipe>> category = runtime.getRecipeManager().getRecipeCategory(RecipeTypes.CRAFTING);
		return runtime.getRecipeTransferManager().getRecipeTransferHandler(menu(mc), category).orElseThrow();
	}

	private static @Nullable IRecipeTransferError transfer(Minecraft mc, String recipeName, boolean max, boolean doTransfer) {
		IJeiRuntime runtime = Objects.requireNonNull(TestJeiPlugin.runtime);
		IRecipeCategory<RecipeHolder<CraftingRecipe>> category = runtime.getRecipeManager().getRecipeCategory(RecipeTypes.CRAFTING);
		RecipeHolder<CraftingRecipe> recipe = runtime.getRecipeManager().createRecipeLookup(RecipeTypes.CRAFTING).get()
			.filter(holder -> holder.id().identifier().getNamespace().equals("minecraft") && holder.id().identifier().getPath().equals(recipeName))
			.findFirst()
			.orElseThrow(() -> new AssertionError("JEI has no recipe " + recipeName));
		IRecipeLayoutDrawable<RecipeHolder<CraftingRecipe>> layout = runtime.getRecipeManager()
			.createRecipeLayoutDrawable(category, recipe, runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup())
			.orElseThrow();
		return handler(mc).transferRecipe(menu(mc), recipe, layout.getRecipeSlotsView(), Objects.requireNonNull(mc.player), max, doTransfer);
	}
}
