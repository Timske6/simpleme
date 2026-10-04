package io.github.timske6.simpleme.test;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Gives the client tests access to the JEI runtime. */
@JeiPlugin
public class TestJeiPlugin implements IModPlugin {
	static volatile @Nullable IJeiRuntime runtime;

	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("simpleme-test", "jei");
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
	}

	@Override
	public void onRuntimeUnavailable() {
		runtime = null;
	}
}
