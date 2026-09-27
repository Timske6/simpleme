package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the player clicked on an item (or empty cell) of the terminal grid. */
public record StorageClickPayload(int containerId, ItemVariant variant, int action) implements CustomPacketPayload {
	/** Left click, empty cursor: take up to a full stack onto the cursor. */
	public static final int TAKE_STACK = 0;
	/** Right click, empty cursor: take half a stack onto the cursor. */
	public static final int TAKE_HALF = 1;
	/** Shift + left click: move a full stack into the player inventory. */
	public static final int QUICK_MOVE = 2;
	/** Left click with an item on the cursor: store the whole cursor stack. */
	public static final int INSERT_ALL = 3;
	/** Right click with an item on the cursor: store a single item. */
	public static final int INSERT_ONE = 4;
	/** Middle click in creative: copy a full stack without taking it out. */
	public static final int CLONE = 5;
	/** Shift + right click, empty cursor: take a single item. */
	public static final int TAKE_ONE = 6;

	public static final Type<StorageClickPayload> TYPE = new Type<>(SimpleME.id("storage_click"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StorageClickPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, StorageClickPayload::containerId,
		ItemVariant.PACKET_CODEC, StorageClickPayload::variant,
		ByteBufCodecs.VAR_INT, StorageClickPayload::action,
		StorageClickPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
