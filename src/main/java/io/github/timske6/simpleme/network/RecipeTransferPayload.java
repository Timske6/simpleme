package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.menu.RecipeFill;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server: fill the crafting grid of the open terminal with a recipe (sent by the JEI "+" button).
 *
 * @param slots for each of the 9 grid slots the items that fit there (empty list: slot stays empty)
 * @param maxTransfer fill as many sets as possible instead of one more
 */
public record RecipeTransferPayload(int containerId, List<List<ItemVariant>> slots, boolean maxTransfer) implements CustomPacketPayload {
	public static final Type<RecipeTransferPayload> TYPE = new Type<>(SimpleME.id("recipe_transfer"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RecipeTransferPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, RecipeTransferPayload::containerId,
		ItemVariant.PACKET_CODEC.apply(ByteBufCodecs.list(RecipeFill.MAX_ALTERNATIVES)).apply(ByteBufCodecs.list(RecipeFill.GRID_SIZE)), RecipeTransferPayload::slots,
		ByteBufCodecs.BOOL, RecipeTransferPayload::maxTransfer,
		RecipeTransferPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
