package io.github.timske6.simpleme;

import io.github.timske6.simpleme.block.TerminalBlock;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.block.TransferChestBlock;
import io.github.timske6.simpleme.block.TransferChestBlockEntity;
import io.github.timske6.simpleme.item.TerminalBlockItem;
import io.github.timske6.simpleme.item.TransferChestItem;
import io.github.timske6.simpleme.item.WirelessTerminalItem;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.menu.TransferChestMenu;
import io.github.timske6.simpleme.storage.StorageLink;
import io.github.timske6.simpleme.storage.StoredItems;
import io.github.timske6.simpleme.storage.TerminalContents;
import io.github.timske6.simpleme.storage.TerminalStorage;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
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
	private static final ResourceKey<Block> TRANSFER_CHEST_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, SimpleME.id("transfer_chest"));
	private static final ResourceKey<Item> TRANSFER_CHEST_ITEM_KEY = ResourceKey.create(Registries.ITEM, SimpleME.id("transfer_chest"));
	private static final ResourceKey<Item> WIRELESS_TERMINAL_KEY = ResourceKey.create(Registries.ITEM, SimpleME.id("wireless_terminal"));

	/** Terminal items of version 1.0 carried their storage (plus a summary for the tooltip); only read to migrate them. */
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

	/** Which network a wireless terminal or transfer chest is connected to. */
	public static final DataComponentType<StorageLink> LINK = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		SimpleME.id("link"),
		DataComponentType.<StorageLink>builder()
			.persistent(StorageLink.CODEC)
			.networkSynchronized(StorageLink.STREAM_CODEC)
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

	public static final Block TRANSFER_CHEST_BLOCK = Registry.register(
		BuiltInRegistries.BLOCK,
		TRANSFER_CHEST_BLOCK_KEY,
		new TransferChestBlock(BlockBehaviour.Properties.of()
			.setId(TRANSFER_CHEST_BLOCK_KEY)
			.mapColor(MapColor.METAL)
			.strength(1.5F, 6.0F)
			.sound(SoundType.METAL))
	);

	public static final Item TRANSFER_CHEST_ITEM = Registry.register(
		BuiltInRegistries.ITEM,
		TRANSFER_CHEST_ITEM_KEY,
		new TransferChestItem(TRANSFER_CHEST_BLOCK, new Item.Properties().setId(TRANSFER_CHEST_ITEM_KEY).useBlockDescriptionPrefix())
	);

	public static final BlockEntityType<TransferChestBlockEntity> TRANSFER_CHEST_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		SimpleME.id("transfer_chest"),
		FabricBlockEntityTypeBuilder.create(TransferChestBlockEntity::new, TRANSFER_CHEST_BLOCK).build()
	);

	public static final MenuType<TransferChestMenu> TRANSFER_CHEST_MENU = Registry.register(
		BuiltInRegistries.MENU,
		SimpleME.id("transfer_chest"),
		new MenuType<>(TransferChestMenu::new, FeatureFlags.VANILLA_SET)
	);

	public static final Item WIRELESS_TERMINAL = Registry.register(
		BuiltInRegistries.ITEM,
		WIRELESS_TERMINAL_KEY,
		new WirelessTerminalItem(new Item.Properties().setId(WIRELESS_TERMINAL_KEY).stacksTo(1))
	);

	public static final ExtendedMenuType<TerminalMenu, InteractionHand> WIRELESS_TERMINAL_MENU = Registry.register(
		BuiltInRegistries.MENU,
		SimpleME.id("wireless_terminal"),
		new ExtendedMenuType<>((containerId, inventory, hand) -> new TerminalMenu(ModRegistry.WIRELESS_TERMINAL_MENU, containerId, inventory, null),
			InteractionHand.STREAM_CODEC)
	);

	private ModRegistry() {
	}

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> {
			output.accept(TERMINAL_ITEM);
			output.accept(TRANSFER_CHEST_ITEM);
		});
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(WIRELESS_TERMINAL));

		// Hoppers, droppers and item pipes of other mods can push items into the terminal's network.
		ItemStorage.SIDED.registerForBlockEntity((terminal, direction) -> {
			TerminalStorage storage = terminal.getLevel() instanceof ServerLevel ? terminal.getStorage() : null;
			return storage != null ? storage.insertionStorage() : null;
		}, TERMINAL_BLOCK_ENTITY);
	}
}
