package io.github.timske6.simpleme.client.jei;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.client.TerminalScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.Identifier;

/** JEI integration, loaded by JEI through the {@code jei_mod_plugin} entrypoint (only if JEI is installed). */
@JeiPlugin
public final class SimpleMEJeiPlugin implements IModPlugin {
	@Override
	public Identifier getPluginUid() {
		return SimpleME.id("jei_plugin");
	}

	@Override
	public void registerGuiHandlers(IGuiHandlerRegistration registration) {
		registration.addGuiContainerHandler(TerminalScreen.class, new TerminalGuiHandler());
	}

	@Override
	public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
		registration.addRecipeTransferHandler(new TerminalRecipeTransferHandler(registration.getTransferHelper()), RecipeTypes.CRAFTING);
	}
}
