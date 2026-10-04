package io.github.timske6.simpleme.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Item/block data of a wireless terminal or transfer chest: which network it is connected to.
 * The name is only a copy for the tooltip (refreshed when the item is used).
 */
public record StorageLink(UUID id, String name) {
	public static final Codec<StorageLink> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		UUIDUtil.LENIENT_CODEC.fieldOf("id").forGetter(StorageLink::id),
		Codec.STRING.optionalFieldOf("name", "").forGetter(StorageLink::name)
	).apply(instance, StorageLink::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, StorageLink> STREAM_CODEC = StreamCodec.composite(
		UUIDUtil.STREAM_CODEC, StorageLink::id,
		ByteBufCodecs.stringUtf8(Networks.MAX_NAME_LENGTH * 4), StorageLink::name,
		StorageLink::new
	);

	public static StorageLink of(Networks.Network network) {
		return new StorageLink(network.id(), network.name());
	}
}
