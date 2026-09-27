package io.github.timske6.simpleme.storage;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Item/block data of a terminal: which storage it belongs to, plus a small summary for the
 * tooltip. The items themselves live in the world's saved data (see {@link StorageData}), so a
 * terminal item stays tiny no matter how much it holds.
 */
public record TerminalContents(UUID id, int types, long total) {
	public static final Codec<TerminalContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		// accepts both the int-array form and a plain UUID string, so admins can type /give commands by hand
		UUIDUtil.LENIENT_CODEC.fieldOf("id").forGetter(TerminalContents::id),
		Codec.INT.optionalFieldOf("types", 0).forGetter(TerminalContents::types),
		Codec.LONG.optionalFieldOf("total", 0L).forGetter(TerminalContents::total)
	).apply(instance, TerminalContents::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, TerminalContents> STREAM_CODEC = StreamCodec.composite(
		UUIDUtil.STREAM_CODEC, TerminalContents::id,
		ByteBufCodecs.VAR_INT, TerminalContents::types,
		ByteBufCodecs.VAR_LONG, TerminalContents::total,
		TerminalContents::new
	);
}
