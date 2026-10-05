package io.github.timske6.simpleme.client.jei;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.client.TerminalScreen;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiKeyMappings;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * JEI ignores its keys while a text box has the focus. In the terminal, the "show recipes" and
 * "show uses" keys still work on the item under the mouse (in the terminal, in JEI's item list or in
 * its bookmarks); without an item there they are typed into the search box as usual.
 */
final class JeiRecipeKeys implements TerminalScreen.RecipeKeys {
	private final IJeiRuntime runtime;

	JeiRecipeKeys(IJeiRuntime runtime) {
		this.runtime = runtime;
	}

	@Override
	public boolean show(KeyEvent event, @Nullable ItemStack hovered) {
		InputConstants.Key key = InputConstants.getKey(event);
		IJeiKeyMappings keys = this.runtime.getKeyMappings();
		RecipeIngredientRole role;
		if (keys.getShowRecipe().isActiveAndMatches(key)) {
			role = RecipeIngredientRole.OUTPUT;
		} else if (keys.getShowUses().isActiveAndMatches(key)) {
			role = RecipeIngredientRole.INPUT;
		} else {
			return false;
		}
		IFocusFactory focuses = this.runtime.getJeiHelpers().getFocusFactory();
		if (hovered != null && !hovered.isEmpty()) {
			this.runtime.getRecipesGui().show(focuses.createFocus(role, VanillaTypes.ITEM_STACK, hovered));
			return true;
		}
		Optional<ITypedIngredient<?>> ingredient = this.runtime.getIngredientListOverlay().getIngredientUnderMouse()
			.or(() -> this.runtime.getBookmarkOverlay().getIngredientUnderMouse());
		if (ingredient.isEmpty()) {
			return false;
		}
		this.show(focuses, role, ingredient.get());
		return true;
	}

	private <V> void show(IFocusFactory focuses, RecipeIngredientRole role, ITypedIngredient<V> ingredient) {
		this.runtime.getRecipesGui().show(focuses.createFocus(role, ingredient));
	}
}
