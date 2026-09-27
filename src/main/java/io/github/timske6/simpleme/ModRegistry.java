package io.github.timske6.simpleme;

import io.github.timske6.simpleme.block.TerminalBlock;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.item.TerminalBlockItem;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.storage.StoredItems;
import io.github.timske6.simpleme.storage.TerminalContents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModRegistry {
	private static final ResourceKey<Block> TERMINAL_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, SimpleME.id("terminal"));
	private static final ResourceKey<Item> TERMINAL_ITEM_KEY = ResourceKey.create(Registries.ITEM, SimpleME.id("terminal"));

	/** Which storage a terminal item belongs to (plus a summary for the tooltip). */
	public static final DataComponentType<TerminalContents> STORAGE = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		SimpleME.id("storage"),
		DataComponentType.<TerminalContents>builder()
			.persistent(TerminalContents.CODEC)
			.networkSynchronized(TerminalContents.STREAM_CODEC)
			.build()
	);

	/** Pre-release format that stored all items inside the item; only read to migrate old terminals. */
	public static final DataComponentType<StoredItems> LEGACY_STORED_ITEMS = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		SimpleME.id("stored_items"),
		DataComponentType.<StoredItems>builder()
			.persistent(StoredItems.CODEC)
			.networkSynchronized(StoredItems.STREAM_CODEC)
			.cacheEncoding()
			.build()
	);

	public static final Block TERMINAL_BLOCK = Registry.register(
		BuiltInRegistries.BLOCK,
		TERMINAL_BLOCK_KEY,
		new TerminalBlock(BlockBehaviour.Properties.of()
			.setId(TERMINAL_BLOCK_KEY)
			.mapColor(MapColor.METAL)
			.strength(1.5F, 6.0F)
			.sound(SoundType.METAL))
	);

	public static final Item TERMINAL_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		TERMINAL_ITEM_KEY,
		new TerminalBlockItem(TERMINAL_BLOCK, new Item.Properties().setId(TERMINAL_ITEM_KEY).useBlockDescriptionPrefix().fireResistant())
	);

	public static final BlockEntityType<TerminalBlockEntity> TERMINAL_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		SimpleME.id("terminal"),
		FabricBlockEntityTypeBuilder.create(TerminalBlockEntity::new, TERMINAL_BLOCK).build()
	);

	public static final ExtendedMenuType<TerminalMenu, BlockPos> TERMINAL_MENU = Registry.register(
		BuiltInRegistries.MENU,
		SimpleME.id("terminal"),
		new ExtendedMenuType<>(TerminalMenu::new, BlockPos.STREAM_CODEC)
	);

	private ModRegistry() {
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> output.accept(TERMINAL_ITEM));

		// Hoppers, droppers and item pipes of other mods can push items into the terminal.
		ItemStorage.SIDED.registerForBlockEntity(
			(terminal, direction) -> terminal.getLevel() instanceof ServerLevel ? terminal.getStorage().insertionStorage() : null,
			TERMINAL_BLOCK_ENTITY
		);
	}
}
