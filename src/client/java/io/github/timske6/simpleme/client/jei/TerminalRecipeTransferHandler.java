package io.github.timske6.simpleme.client.jei;

import io.github.timske6.simpleme.menu.RecipeFill;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.RecipeTransferPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.api.recipe.types.IRecipeType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

/**
 * The "+" button in JEI's crafting recipes while a terminal is open: fills the terminal crafting grid
 * from the ME storage (and the player inventory). Missing ingredients are marked red.
 */
public final class TerminalRecipeTransferHandler implements IRecipeTransferHandler<TerminalMenu, RecipeHolder<CraftingRecipe>> {
	private final IRecipeTransferHandlerHelper helper;

	public TerminalRecipeTransferHandler(IRecipeTransferHandlerHelper helper) {
		this.helper = helper;
	}

	@Override
	public Class<? extends TerminalMenu> getContainerClass() {
		return TerminalMenu.class;
	}

	@Override
	public Optional<MenuType<TerminalMenu>> getMenuType() {
		// placed and wireless terminal
		return Optional.empty();
	}

	@Override
	public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
		return RecipeTypes.CRAFTING;
	}

	@Override
	public @Nullable IRecipeTransferError transferRecipe(IRecipeTransferContext<RecipeHolder<CraftingRecipe>, TerminalMenu> context, boolean doTransfer) {
		IRecipeTransferError error = this.transfer(context.getContainer(), context.getRecipeSlots(), context.getPlayer(), context.isMaxTransfer(), doTransfer);
		if (doTransfer) {
			context.completeRecipeTransfer(error == null ? RecipeTransferResult.SUCCESS : RecipeTransferResult.REJECTED);
		}
		return error;
	}

	@Override
	@SuppressWarnings("removal")
	public @Nullable IRecipeTransferError transferRecipe(
		TerminalMenu menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer
	) {
		return this.transfer(menu, recipeSlots, player, maxTransfer, doTransfer);
	}

	private @Nullable IRecipeTransferError transfer(TerminalMenu menu, IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {
		// JEI lays crafting recipes out on a 3x3 grid, so input slot i is grid slot i
		List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
		if (inputs.size() > RecipeFill.GRID_SIZE) {
			return this.helper.createInternalError();
		}
		List<List<ItemVariant>> slots = new ArrayList<>(inputs.size());
		for (IRecipeSlotView input : inputs) {
			slots.add(input.getItemStacks()
				.filter(stack -> !stack.isEmpty())
				.map(ItemVariant::of)
				.distinct()
				.limit(RecipeFill.MAX_ALTERNATIVES)
				.toList());
		}

		List<ItemStack> grid = menu.getInputGridSlots().stream().map(Slot::getItem).toList();
		RecipeFill.Plan plan = RecipeFill.plan(slots, RecipeFill.available(menu.getClientItems(), player.getInventory(), grid, ItemStack.EMPTY));
		if (!plan.missing().isEmpty()) {
			List<IRecipeSlotView> missing = new ArrayList<>();
			plan.missing().forEach(index -> missing.add(inputs.get(index)));
			return this.helper.createUserErrorForMissingSlots(Component.translatable("jei.tooltip.error.recipe.transfer.missing"), missing);
		}
		if (doTransfer) {
			ClientPlayNetworking.send(new RecipeTransferPayload(menu.containerId, slots, maxTransfer));
		}
		return null;
	}
}
