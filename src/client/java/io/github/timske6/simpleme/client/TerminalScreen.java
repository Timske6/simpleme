package io.github.timske6.simpleme.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.StorageClickPayload;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.recipebook.CraftingRecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeUpdateListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import org.jspecify.annotations.Nullable;

public class TerminalScreen extends AbstractContainerScreen<TerminalMenu> implements RecipeUpdateListener {
	private static final Identifier SCROLLER_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
	private static final Identifier SCROLLER_DISABLED_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");
	private static final Identifier SLOT_HIGHLIGHT_BACK_SPRITE = Identifier.withDefaultNamespace("container/slot_highlight_back");
	private static final Identifier SLOT_HIGHLIGHT_FRONT_SPRITE = Identifier.withDefaultNamespace("container/slot_highlight_front");

	private static final int IMAGE_WIDTH = 195;
	private static final int SCROLLBAR_X = 175;
	private static final int SCROLLER_HEIGHT = 15;

	private static final int COLOR_PANEL = 0xFFC6C6C6;
	private static final int COLOR_SLOT = 0xFF8B8B8B;
	private static final int COLOR_DARK = 0xFF373737;
	private static final int COLOR_SHADOW = 0xFF555555;
	private static final int COLOR_LIGHT = 0xFFFFFFFF;
	private static final int COLOR_BORDER = 0xFF000000;

	/** Remembered while the game runs, so the terminal reopens the way you left it. */
	private static SortMode sortMode = SortMode.NAME;
	private static String lastSearch = "";

	private final CraftingRecipeBookComponent recipeBook;
	private final Map<ItemVariant, Entry> entryCache = new HashMap<>();
	/**
	 * All entries in display order. It is only sorted when the terminal is opened (or the network
	 * switched) and when the sort mode is changed, so taking items out does not shuffle the grid.
	 * Item types that run out stay in the list with 0 until then; new ones are added at the end.
	 */
	private final List<Entry> order = new ArrayList<>();
	private final List<Entry> visibleEntries = new ArrayList<>();
	private int seenFullSyncs = -1;

	private boolean widthTooNarrow;
	private EditBox searchBox;
	private Button sortButton;
	private Button clearButton;
	private ImageButton recipeButton;
	private Button networkButton;
	private @Nullable NetworkPanel networkPanel;
	private int seenNetworkRevision = -1;
	/** The network list opens by itself once, if the terminal is not connected to a network. */
	private boolean networkPanelShown;

	private int seenRevision = -1;
	private int scrollRow;
	private double scrollRemainder;
	private boolean draggingScrollbar;
	private boolean gridClickConsumed;
	private @Nullable Entry hoveredEntry;

	public TerminalScreen(TerminalMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, IMAGE_WIDTH, menu.imageHeight);
		this.recipeBook = new CraftingRecipeBookComponent(menu);
		this.inventoryLabelY = menu.inventoryLabelY;
	}

	// ------------------------------------------------------------------------------------------
	// Setup
	// ------------------------------------------------------------------------------------------

	@Override
	protected void init() {
		super.init();
		this.widthTooNarrow = this.width < 379;
		this.recipeBook.init(this.width, this.height, this.minecraft, this.widthTooNarrow);
		this.leftPos = this.recipeBook.updateScreenPosition(this.width, this.imageWidth);

		this.searchBox = new EditBox(this.font, 0, 0, 89, 12, Component.translatable("gui.simpleme.search"));
		this.searchBox.setMaxLength(64);
		this.searchBox.setHint(Component.translatable("gui.simpleme.search.hint").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		this.searchBox.setValue(lastSearch);
		this.searchBox.setResponder(value -> {
			lastSearch = value;
			this.scrollRow = 0;
			this.refreshEntries(false, false);
		});
		this.addRenderableWidget(this.searchBox);

		this.sortButton = Button.builder(sortMode.label(), button -> {
			sortMode = sortMode.next();
			button.setMessage(sortMode.label());
			button.setTooltip(Tooltip.create(sortMode.tooltip()));
			this.refreshEntries(true, false);
		}).size(18, 14).tooltip(Tooltip.create(sortMode.tooltip())).build();
		this.addRenderableWidget(this.sortButton);

		this.clearButton = Button.builder(Component.literal("×"),
				button -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, TerminalMenu.BUTTON_CLEAR_GRID))
			.size(14, 14)
			.tooltip(Tooltip.create(Component.translatable("gui.simpleme.clear_grid")))
			.build();
		this.addRenderableWidget(this.clearButton);

		this.recipeButton = new ImageButton(0, 0, 20, 18, RecipeBookComponent.RECIPE_BUTTON_SPRITES, button -> {
			this.recipeBook.toggleVisibility();
			this.leftPos = this.recipeBook.updateScreenPosition(this.width, this.imageWidth);
			this.layoutWidgets();
		});
		this.addRenderableWidget(this.recipeButton);
		this.addWidget(this.recipeBook);

		this.networkButton = Button.builder(Component.empty(), button -> this.networkPanel.open())
			.size(73, 13)
			.tooltip(Tooltip.create(Component.translatable("gui.simpleme.network.choose")))
			.build();
		this.addRenderableWidget(this.networkButton);
		if (this.networkPanel == null) {
			this.networkPanel = new NetworkPanel(this.minecraft, this.menu.containerId, this.menu.networkState());
		}
		this.networkPanel.layout(this.width, this.height);
		this.seenNetworkRevision = -1;

		this.layoutWidgets();
		this.refreshEntries(false, false);
	}

	private void layoutWidgets() {
		this.searchBox.setX(this.leftPos + 82);
		this.searchBox.setY(this.topPos + 4);
		this.sortButton.setX(this.leftPos + SCROLLBAR_X - 2);
		this.sortButton.setY(this.topPos + 3);
		this.clearButton.setX(this.leftPos + 9);
		this.clearButton.setY(this.topPos + this.menu.craftingY + 19);
		this.recipeButton.setX(this.leftPos + 152);
		this.recipeButton.setY(this.topPos + this.menu.craftingY + 17);
		this.networkButton.setX(this.leftPos + 6);
		this.networkButton.setY(this.topPos + 3);
	}

	private boolean panelOpen() {
		return this.networkPanel != null && this.networkPanel.isOpen();
	}

	private void updateNetworkButton() {
		var state = this.menu.networkState();
		if (state.revision() == this.seenNetworkRevision) {
			return;
		}
		this.seenNetworkRevision = state.revision();
		Component label = state.current() != null
			? Component.literal(state.currentName())
			: Component.translatable("gui.simpleme.network.none_short").withStyle(ChatFormatting.RED);
		String text = label.getString();
		int max = this.networkButton.getWidth() - 6;
		if (this.font.width(text) > max) {
			label = Component.literal(this.font.plainSubstrByWidth(text, max - this.font.width("...")) + "...").withStyle(label.getStyle());
		}
		this.networkButton.setMessage(label);
		if (state.received() && state.current() == null && !this.networkPanelShown) {
			this.networkPanelShown = true;
			this.networkPanel.open();
		}
	}

	// ------------------------------------------------------------------------------------------
	// Item list
	// ------------------------------------------------------------------------------------------

	@Override
	protected void containerTick() {
		super.containerTick();
		this.recipeBook.tick();
		this.updateNetworkButton();
		this.networkPanel.tick();
		if (this.menu.getClientRevision() != this.seenRevision) {
			boolean full = this.menu.getClientFullSyncs() != this.seenFullSyncs;
			this.seenFullSyncs = this.menu.getClientFullSyncs();
			this.refreshEntries(full, full);
			// Makes the recipe book re-check which recipes can be crafted with the new storage contents.
			this.minecraft.player.getInventory().setChanged();
		}
	}

	/**
	 * @param resort sort the whole list again (otherwise the order stays, new item types go to the end)
	 * @param dropEmpty forget item types that ran out (otherwise they are shown with 0)
	 */
	private void refreshEntries(boolean resort, boolean dropEmpty) {
		this.seenRevision = this.menu.getClientRevision();
		Object2LongMap<ItemVariant> items = this.menu.getClientItems();
		if (dropEmpty) {
			this.entryCache.keySet().retainAll(items.keySet());
			this.order.removeIf(entry -> !items.containsKey(entry.variant));
		}
		for (Entry entry : this.entryCache.values()) {
			entry.count = items.getLong(entry.variant);
		}
		for (Object2LongMap.Entry<ItemVariant> item : items.object2LongEntrySet()) {
			if (!this.entryCache.containsKey(item.getKey())) {
				Entry entry = new Entry(item.getKey());
				entry.count = item.getLongValue();
				this.entryCache.put(item.getKey(), entry);
				this.order.add(entry);
			}
		}
		if (resort) {
			this.order.sort(sortMode.comparator);
		}

		List<String> terms = new ArrayList<>();
		for (String term : this.searchBox.getValue().toLowerCase(Locale.ROOT).split("\\s+")) {
			if (!term.isEmpty()) {
				terms.add(term);
			}
		}

		this.visibleEntries.clear();
		for (Entry entry : this.order) {
			if (entry.matches(terms)) {
				this.visibleEntries.add(entry);
			}
		}
		this.scrollRow = Mth.clamp(this.scrollRow, 0, this.maxScroll());
	}

	private int maxScroll() {
		int totalRows = (this.visibleEntries.size() + TerminalMenu.COLUMNS - 1) / TerminalMenu.COLUMNS;
		return Math.max(0, totalRows - this.menu.rows);
	}

	private boolean interactionBlocked() {
		return this.widthTooNarrow && this.recipeBook.isVisible();
	}

	private boolean isOverGrid(double mouseX, double mouseY) {
		double x = mouseX - this.leftPos - (TerminalMenu.GRID_X - 1);
		double y = mouseY - this.topPos - (TerminalMenu.GRID_Y - 1);
		return !this.interactionBlocked() && !this.panelOpen() && x >= 0 && y >= 0 && x < TerminalMenu.COLUMNS * 18 && y < this.menu.rows * 18;
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		double x = mouseX - this.leftPos - SCROLLBAR_X;
		double y = mouseY - this.topPos - (TerminalMenu.GRID_Y - 1);
		return !this.interactionBlocked() && !this.panelOpen() && x >= 0 && y >= 0 && x < 14 && y < this.menu.rows * 18;
	}

	private @Nullable Entry entryAt(double mouseX, double mouseY) {
		if (!this.isOverGrid(mouseX, mouseY)) {
			return null;
		}
		int column = (int) ((mouseX - this.leftPos - (TerminalMenu.GRID_X - 1)) / 18);
		int row = (int) ((mouseY - this.topPos - (TerminalMenu.GRID_Y - 1)) / 18);
		int index = (this.scrollRow + row) * TerminalMenu.COLUMNS + column;
		return index >= 0 && index < this.visibleEntries.size() ? this.visibleEntries.get(index) : null;
	}

	private void scrollTo(double mouseY) {
		int trackTop = this.topPos + TerminalMenu.GRID_Y;
		int trackHeight = this.menu.rows * 18 - 2;
		float progress = (float) (mouseY - trackTop - SCROLLER_HEIGHT / 2.0) / (trackHeight - SCROLLER_HEIGHT);
		this.scrollRow = Mth.clamp(Math.round(progress * this.maxScroll()), 0, this.maxScroll());
	}

	// ------------------------------------------------------------------------------------------
	// Rendering
	// ------------------------------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		boolean panel = this.panelOpen();
		// with the network panel open, nothing below it reacts to the mouse
		int x = panel ? -1000 : mouseX;
		int y = panel ? -1000 : mouseY;
		if (this.interactionBlocked()) {
			this.extractBackground(graphics, x, y, a);
		} else {
			super.extractContents(graphics, x, y, a);
		}
		graphics.nextStratum();
		this.recipeBook.extractRenderState(graphics, x, y, a);
		graphics.nextStratum();
		if (panel) {
			this.networkPanel.extractRenderState(graphics, mouseX, mouseY, a);
			graphics.nextStratum();
			this.extractCarriedItem(graphics, mouseX, mouseY);
			return;
		}
		this.extractCarriedItem(graphics, mouseX, mouseY);
		this.extractTooltip(graphics, mouseX, mouseY);
		this.recipeBook.extractTooltip(graphics, mouseX, mouseY, this.hoveredSlot);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int x = this.leftPos;
		int y = this.topPos;
		drawPanel(graphics, x, y, this.imageWidth, this.imageHeight);

		// storage grid
		for (int row = 0; row < this.menu.rows; row++) {
			for (int column = 0; column < TerminalMenu.COLUMNS; column++) {
				drawSlot(graphics, x + TerminalMenu.GRID_X - 1 + column * 18, y + TerminalMenu.GRID_Y - 1 + row * 18, 18);
			}
		}

		// scrollbar
		int trackX = x + SCROLLBAR_X;
		int trackY = y + TerminalMenu.GRID_Y - 1;
		int trackHeight = this.menu.rows * 18;
		drawSlot(graphics, trackX, trackY, 14, trackHeight);
		int maxScroll = this.maxScroll();
		int scrollerY = trackY + 1 + (maxScroll == 0 ? 0 : (trackHeight - 2 - SCROLLER_HEIGHT) * this.scrollRow / maxScroll);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, maxScroll == 0 ? SCROLLER_DISABLED_SPRITE : SCROLLER_SPRITE, trackX + 1, scrollerY, 12, SCROLLER_HEIGHT);

		// crafting grid, result and player inventory slots
		for (Slot slot : this.menu.slots) {
			if (slot.index == TerminalMenu.RESULT_SLOT) {
				drawSlot(graphics, x + slot.x - 5, y + slot.y - 5, 26);
			} else {
				drawSlot(graphics, x + slot.x - 1, y + slot.y - 1, 18);
			}
		}

		// crafting arrow
		int arrowX = x + 92;
		int arrowCenterY = y + this.menu.craftingY + 18 + 8;
		graphics.fill(arrowX, arrowCenterY - 2, arrowX + 14, arrowCenterY + 2, COLOR_SLOT);
		for (int i = 0; i < 7; i++) {
			graphics.fill(arrowX + 14 + i, arrowCenterY - 7 + i, arrowX + 15 + i, arrowCenterY + 7 - i, COLOR_SLOT);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// the title row shows the network button instead of the title
		graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0xFF404040, false);
	}

	@Override
	protected void extractSlots(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		this.extractStorageGrid(graphics, mouseX, mouseY);
		super.extractSlots(graphics, mouseX, mouseY);
		this.recipeBook.extractGhostRecipe(graphics, true);
	}

	private void extractStorageGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		this.hoveredEntry = null;
		boolean overGrid = this.isOverGrid(mouseX, mouseY);
		int hoverColumn = overGrid ? (mouseX - this.leftPos - (TerminalMenu.GRID_X - 1)) / 18 : -1;
		int hoverRow = overGrid ? (mouseY - this.topPos - (TerminalMenu.GRID_Y - 1)) / 18 : -1;

		for (int row = 0; row < this.menu.rows; row++) {
			for (int column = 0; column < TerminalMenu.COLUMNS; column++) {
				int x = TerminalMenu.GRID_X + column * 18;
				int y = TerminalMenu.GRID_Y + row * 18;
				boolean hovered = row == hoverRow && column == hoverColumn;
				int index = (this.scrollRow + row) * TerminalMenu.COLUMNS + column;
				Entry entry = index < this.visibleEntries.size() ? this.visibleEntries.get(index) : null;

				if (hovered) {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK_SPRITE, x - 4, y - 4, 24, 24);
				}
				if (entry != null) {
					graphics.item(entry.stack, x, y, x + y * this.imageWidth);
					graphics.itemDecorations(this.font, entry.stack, x, y, "");
					this.extractCount(graphics, entry.count, x, y);
				}
				if (hovered) {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT_SPRITE, x - 4, y - 4, 24, 24);
					this.hoveredEntry = entry;
				}
			}
		}
	}

	private void extractCount(GuiGraphicsExtractor graphics, long count, int x, int y) {
		if (count == 1) {
			return;
		}
		String text = formatCount(count);
		// Up to two digits look like vanilla; bigger numbers use half-size text so they stay inside the cell.
		if (count < 100) {
			graphics.text(this.font, text, x + 17 - this.font.width(text), y + 9, 0xFFFFFFFF, true);
		} else {
			graphics.pose().pushMatrix();
			graphics.pose().translate(x + 16 - this.font.width(text) / 2.0F, y + 12);
			graphics.pose().scale(0.5F, 0.5F);
			graphics.text(this.font, text, 0, 0, 0xFFFFFFFF, true);
			graphics.pose().popMatrix();
		}
	}

	static String formatCount(long count) {
		if (count < 1000) {
			return Long.toString(count);
		}
		String[] units = {"k", "M", "G", "T", "P", "E"};
		double value = count;
		int unit = -1;
		while (value >= 1000 && unit < units.length - 1) {
			value /= 1000;
			unit++;
		}
		String number = value < 10 ? String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10) : Long.toString((long) value);
		if (number.endsWith(".0")) {
			number = number.substring(0, number.length() - 2);
		}
		return number + units[unit];
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		super.extractTooltip(graphics, mouseX, mouseY);
		Entry entry = this.hoveredEntry;
		if (entry != null && this.menu.getCarried().isEmpty()) {
			List<Component> lines = new ArrayList<>(getTooltipFromItem(this.minecraft, entry.stack));
			lines.add(Component.translatable("gui.simpleme.stored", String.format("%,d", entry.count)).withStyle(ChatFormatting.GRAY));
			graphics.setTooltipForNextFrame(this.font, lines, entry.stack.getTooltipImage(), mouseX, mouseY, entry.stack.get(DataComponents.TOOLTIP_STYLE));
		}
	}

	static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		int right = x + width;
		int bottom = y + height;
		graphics.fill(x + 1, y + 1, right - 1, bottom - 1, COLOR_PANEL);
		// rounded black outline
		graphics.fill(x + 2, y, right - 2, y + 1, COLOR_BORDER);
		graphics.fill(x + 2, bottom - 1, right - 2, bottom, COLOR_BORDER);
		graphics.fill(x, y + 2, x + 1, bottom - 2, COLOR_BORDER);
		graphics.fill(right - 1, y + 2, right, bottom - 2, COLOR_BORDER);
		graphics.fill(x + 1, y + 1, x + 2, y + 2, COLOR_BORDER);
		graphics.fill(right - 2, y + 1, right - 1, y + 2, COLOR_BORDER);
		graphics.fill(x + 1, bottom - 2, x + 2, bottom - 1, COLOR_BORDER);
		graphics.fill(right - 2, bottom - 2, right - 1, bottom - 1, COLOR_BORDER);
		// bevel
		graphics.fill(x + 2, y + 1, right - 3, y + 3, COLOR_LIGHT);
		graphics.fill(x + 1, y + 2, x + 3, bottom - 3, COLOR_LIGHT);
		graphics.fill(x + 3, bottom - 3, right - 2, bottom - 1, COLOR_SHADOW);
		graphics.fill(right - 3, y + 3, right - 1, bottom - 2, COLOR_SHADOW);
	}

	private static void drawSlot(GuiGraphicsExtractor graphics, int x, int y, int size) {
		drawSlot(graphics, x, y, size, size);
	}

	private static void drawSlot(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
		graphics.fill(x, y, x + width, y + height, COLOR_SLOT);
		graphics.fill(x, y, x + width - 1, y + 1, COLOR_DARK);
		graphics.fill(x, y, x + 1, y + height - 1, COLOR_DARK);
		graphics.fill(x + 1, y + height - 1, x + width, y + height, COLOR_LIGHT);
		graphics.fill(x + width - 1, y + 1, x + width, y + height, COLOR_LIGHT);
	}

	// ------------------------------------------------------------------------------------------
	// Input
	// ------------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.panelOpen()) {
			return this.networkPanel.mouseClicked(event, doubleClick);
		}
		if (this.recipeBook.mouseClicked(event, doubleClick)) {
			this.setFocused(this.recipeBook);
			return true;
		}
		if (this.interactionBlocked()) {
			return true;
		}

		double mouseX = event.x();
		double mouseY = event.y();
		if (this.searchBox.isMouseOver(mouseX, mouseY)) {
			if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
				this.searchBox.setValue("");
				this.setFocused(this.searchBox);
				return true;
			}
		} else if (this.searchBox.isFocused()) {
			this.setFocused(null);
		}

		if (this.isOverScrollbar(mouseX, mouseY)) {
			this.draggingScrollbar = true;
			this.scrollTo(mouseY);
			return true;
		}
		if (this.isOverGrid(mouseX, mouseY)) {
			this.handleGridClick(event, mouseX, mouseY);
			this.gridClickConsumed = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void handleGridClick(MouseButtonEvent event, double mouseX, double mouseY) {
		ItemStack carried = this.menu.getCarried();
		int button = event.button();
		ItemVariant variant;
		int action;
		if (!carried.isEmpty()) {
			variant = ItemVariant.of(carried);
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				action = StorageClickPayload.INSERT_ALL;
			} else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				action = StorageClickPayload.INSERT_ONE;
			} else {
				return;
			}
		} else {
			Entry entry = this.entryAt(mouseX, mouseY);
			if (entry == null) {
				return;
			}
			variant = entry.variant;
			if (this.minecraft.options.keyPickItem.matchesMouse(event)) {
				if (!this.minecraft.player.hasInfiniteMaterials()) {
					return;
				}
				action = StorageClickPayload.CLONE;
			} else if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				action = event.hasShiftDown() ? StorageClickPayload.QUICK_MOVE : StorageClickPayload.TAKE_STACK;
			} else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				action = event.hasShiftDown() ? StorageClickPayload.TAKE_ONE : StorageClickPayload.TAKE_HALF;
			} else {
				return;
			}
		}
		ClientPlayNetworking.send(new StorageClickPayload(this.menu.containerId, variant, action));
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.panelOpen()) {
			this.gridClickConsumed = false;
			return this.networkPanel.mouseReleased(event);
		}
		if (this.draggingScrollbar) {
			this.draggingScrollbar = false;
			return true;
		}
		if (this.gridClickConsumed) {
			this.gridClickConsumed = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.panelOpen()) {
			return this.networkPanel.mouseDragged(event, dx, dy);
		}
		if (this.draggingScrollbar) {
			this.scrollTo(event.y());
			return true;
		}
		if (this.gridClickConsumed) {
			return true;
		}
		return this.recipeBook.mouseDragged(event, dx, dy) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.panelOpen()) {
			return this.networkPanel.mouseScrolled(scrollY);
		}
		if (this.isOverGrid(mouseX, mouseY) || this.isOverScrollbar(mouseX, mouseY)) {
			// Keep fractions so touchpads scroll smoothly and the mouse wheel sensitivity option is respected.
			this.scrollRemainder += scrollY;
			int rows = (int) this.scrollRemainder;
			this.scrollRemainder -= rows;
			this.scrollRow = Mth.clamp(this.scrollRow - rows, 0, this.maxScroll());
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.panelOpen()) {
			return this.networkPanel.keyPressed(event);
		}
		if (this.searchBox.isFocused()) {
			if (event.isEscape()) {
				return super.keyPressed(event);
			}
			if (event.isConfirmation()) {
				this.setFocused(null);
				return true;
			}
			this.searchBox.keyPressed(event);
			return true;
		}
		return this.recipeBook.keyPressed(event) || super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (this.panelOpen()) {
			return this.networkPanel.charTyped(event);
		}
		if (this.searchBox.isFocused()) {
			return this.searchBox.charTyped(event);
		}
		return this.recipeBook.charTyped(event) || super.charTyped(event);
	}

	@Override
	protected boolean isHovering(int left, int top, int w, int h, double xm, double ym) {
		return !this.interactionBlocked() && !this.panelOpen() && super.isHovering(left, top, w, h, xm, ym);
	}

	@Override
	protected boolean hasClickedOutside(double mx, double my, int xo, int yo) {
		boolean outside = mx < xo || my < yo || mx >= xo + this.imageWidth || my >= yo + this.imageHeight;
		return this.recipeBook.hasClickedOutside(mx, my, this.leftPos, this.topPos, this.imageWidth, this.imageHeight) && outside;
	}

	@Override
	protected void slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput containerInput) {
		super.slotClicked(slot, slotId, buttonNum, containerInput);
		this.recipeBook.slotClicked(slot);
	}

	@Override
	public void recipesUpdated() {
		this.recipeBook.recipesUpdated();
	}

	@Override
	public void fillGhostRecipe(RecipeDisplay display) {
		this.recipeBook.fillGhostRecipe(display);
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	public int guiLeft() {
		return this.leftPos;
	}

	public int guiTop() {
		return this.topPos;
	}

	public int visibleEntryCount() {
		return this.visibleEntries.size();
	}

	public int scrollRow() {
		return this.scrollRow;
	}

	public @Nullable ItemStack visibleEntry(int index) {
		return index >= 0 && index < this.visibleEntries.size() ? this.visibleEntries.get(index).stack : null;
	}

	/** The count shown for an entry (0 for item types that ran out while the terminal is open). */
	public long visibleCount(int index) {
		return index >= 0 && index < this.visibleEntries.size() ? this.visibleEntries.get(index).count : -1;
	}

	public String searchText() {
		return this.searchBox.getValue();
	}

	public boolean isNetworkPanelOpen() {
		return this.panelOpen();
	}

	private static final class Entry {
		final ItemVariant variant;
		final ItemStack stack;
		final String name;
		final String namespace;
		final String path;
		long count;

		Entry(ItemVariant variant) {
			this.variant = variant;
			this.stack = variant.toStack();
			this.name = this.stack.getHoverName().getString().toLowerCase(Locale.ROOT);
			Identifier id = BuiltInRegistries.ITEM.getKey(variant.getItem());
			this.namespace = id.getNamespace();
			this.path = id.getPath();
		}

		boolean matches(List<String> terms) {
			for (String term : terms) {
				if (term.startsWith("@")) {
					if (!this.namespace.contains(term.substring(1))) {
						return false;
					}
				} else if (!this.name.contains(term) && !this.path.contains(term)) {
					return false;
				}
			}
			return true;
		}
	}

	private enum SortMode {
		NAME("Az", "gui.simpleme.sort.name",
			Comparator.<Entry, String>comparing(e -> e.name).thenComparing(e -> e.namespace).thenComparingLong(e -> -e.count)),
		COUNT("#", "gui.simpleme.sort.count",
			Comparator.<Entry>comparingLong(e -> -e.count).thenComparing(e -> e.name)),
		MOD("@", "gui.simpleme.sort.mod",
			Comparator.<Entry, String>comparing(e -> e.namespace).thenComparing(e -> e.name));

		private final String symbol;
		private final String translationKey;
		private final Comparator<Entry> comparator;

		SortMode(String symbol, String translationKey, Comparator<Entry> comparator) {
			this.symbol = symbol;
			this.translationKey = translationKey;
			this.comparator = comparator;
		}

		Component label() {
			return Component.literal(this.symbol);
		}

		Component tooltip() {
			return Component.translatable(this.translationKey);
		}

		SortMode next() {
			return values()[(this.ordinal() + 1) % values().length];
		}
	}
}
