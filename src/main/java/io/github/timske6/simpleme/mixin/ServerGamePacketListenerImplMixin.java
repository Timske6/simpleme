package io.github.timske6.simpleme.mixin;

import io.github.timske6.simpleme.item.StoragePickBlock;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pick block / pick entity: fall back to the ME system of a carried wireless terminal. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "tryPickItem", at = @At("HEAD"), cancellable = true)
	private void simpleme$pickFromStorage(ItemStack itemStack, CallbackInfo ci) {
		if (StoragePickBlock.tryPick(this.player, itemStack)) {
			ci.cancel();
		}
	}
}
