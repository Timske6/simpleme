package io.github.timske6.simpleme.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.GroupActionPayload;
import io.github.timske6.simpleme.network.GroupListPayload;
import io.github.timske6.simpleme.storage.ItemGroups;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Fold-out panel on the right side of the terminal: item groups of the connected network. Each
 * group is a list of item types that stays visible (with 0 when the network has none). Groups are
 * created, renamed, shared and filled here; while a group is edited, items are dragged into it from
 * the storage grid. Every player chooses which groups are shown by clicking their title.
 */
public final class GroupPanel {
	public static final int WIDTH = 110;
	private static final int COLUMNS = 5;
	private static final int PADDING = 7;
	private static final int LIST_TOP = 20;
	private static final int HEADER_HEIGHT = 13;
	private static final int GROUP_GAP = 3;
	private static final int TAB_WIDTH = 22;
	private static final int TAB_HEIGHT = 22;
	private static final int TAB_Y = 18;
	private static final int FOOTER_HEIGHT = 72;
	private static final int COLOR_TEXT = 0xFF404040;
	private static final int COLOR_SHARED = 0xFF1F4F9F;

	/** Remembered while the game runs, so the terminal reopens the way you left it. */
	private static boolean expanded;

	private final Minecraft minecraft;
	private final Font font;
	private final TerminalMenu menu;
	private final TerminalScreen screen;
	private final EditBox nameBox;
	private final Button newButton;
	private final Button closeButton;
	private final Button shareButton;
	private final Button deleteButton;
	private final Button doneButton;
	private final List<AbstractWidget> widgets;
	private final Map<ItemVariant, ItemStack> stacks = new HashMap<>();

	private int x;
	private int y;
	private int height;
	private int scroll;
	private double scrollRemainder;
	private int seenRevision = -1;
	/** The group being edited, or {@code null}. */
	private @Nullable UUID editing;
	/** Ticks left to wait for a group created here to arrive from the server. */
	private int pendingTicks;
	private String editedName = "";
	private boolean deleteArmed;

	GroupPanel(Minecraft minecraft, TerminalMenu menu, TerminalScreen screen) {
		this.minecraft = minecraft;
		this.font = minecraft.font;
		this.menu = menu;
		this.screen = screen;
		this.nameBox = new EditBox(this.font, 0, 0, WIDTH - 2 * PADDING, 14, Component.translatable("gui.simpleme.group.name"));
		this.nameBox.setMaxLength(ItemGroups.MAX_NAME_LENGTH);
		this.nameBox.setHint(Component.translatable("gui.simpleme.network.name_hint").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		this.newButton = Button.builder(Component.literal("+"), button -> this.create())
			.size(14, 14).tooltip(Tooltip.create(Component.translatable("gui.simpleme.group.new"))).build();
		this.closeButton = Button.builder(Component.literal("×"), button -> this.setExpanded(false))
			.size(14, 14).tooltip(Tooltip.create(Component.translatable("gui.simpleme.group.close"))).build();
		int half = (WIDTH - 2 * PADDING - 2) / 2;
		this.shareButton = Button.builder(Component.empty(), button -> this.send(GroupActionPayload.TOGGLE_SHARED, this.editing))
			.size(half, 14).build();
		this.deleteButton = Button.builder(Component.translatable("gui.simpleme.group.delete"), button -> this.delete())
			.size(half, 14).build();
		this.doneButton = Button.builder(Component.translatable("gui.simpleme.group.done"), button -> this.stopEditing())
			.size(WIDTH - 2 * PADDING, 14).build();
		this.widgets = List.of(this.newButton, this.closeButton, this.nameBox, this.shareButton, this.deleteButton, this.doneButton);
	}

	/** Called whenever the terminal moves (opened, resized, recipe book toggled). */
	void layout(int terminalRight, int top, int height) {
		this.x = terminalRight + 2;
		this.y = top;
		this.height = height;
		this.newButton.setPosition(this.x + WIDTH - 36, this.y + 4);
		this.closeButton.setPosition(this.x + WIDTH - 20, this.y + 4);
		int footer = this.footerTop();
		this.nameBox.setPosition(this.x + PADDING, footer + 20);
		this.shareButton.setPosition(this.x + PADDING, footer + 38);
		this.deleteButton.setPosition(this.x + WIDTH - PADDING - this.deleteButton.getWidth(), footer + 38);
		this.doneButton.setPosition(this.x + PADDING, footer + 55);
		this.clampScroll();
	}

	public static boolean isExpanded() {
		return expanded;
	}

	void setExpanded(boolean value) {
		if (!value) {
			this.stopEditing();
		}
		expanded = value;
		this.screen.relayout(value);
	}

	/** Folds the panel in without moving the terminal (the caller does that). */
	void collapse() {
		this.stopEditing();
		expanded = false;
	}

	public boolean isEditing() {
		return expanded && this.editing != null;
	}

	/** Where the panel (or its tab) is, so recipe viewers keep their item lists out of the way. */
	public Rect2i area() {
		return expanded ? new Rect2i(this.x, this.y, WIDTH, this.height) : new Rect2i(this.x - 2, this.y + TAB_Y, TAB_WIDTH + 2, TAB_HEIGHT);
	}

	private boolean isOverPanel(double mouseX, double mouseY) {
		Rect2i area = this.area();
		return area.contains((int) Math.floor(mouseX), (int) Math.floor(mouseY));
	}

	// ------------------------------------------------------------------------------------------
	// Groups
	// ------------------------------------------------------------------------------------------

	private List<GroupListPayload.Entry> groups() {
		return this.menu.getClientGroups();
	}

	private GroupListPayload.@Nullable Entry group(@Nullable UUID id) {
		for (GroupListPayload.Entry entry : this.groups()) {
			if (entry.id().equals(id)) {
				return entry;
			}
		}
		return null;
	}

	private void send(int action, @Nullable UUID group) {
		if (group != null) {
			ClientPlayNetworking.send(new GroupActionPayload(this.menu.containerId, action, group));
		}
	}

	private void send(int action, UUID group, ItemVariant item) {
		ClientPlayNetworking.send(new GroupActionPayload(this.menu.containerId, action, group, "", Optional.of(item)));
	}

	private void create() {
		this.stopEditing();
		int own = 0;
		for (GroupListPayload.Entry entry : this.groups()) {
			if (entry.canManage()) {
				own++;
			}
		}
		String name = Component.translatable("gui.simpleme.group.default_name", own + 1).getString();
		UUID id = UUID.randomUUID();
		ClientPlayNetworking.send(new GroupActionPayload(this.menu.containerId, GroupActionPayload.CREATE, id, name, Optional.empty()));
		this.startEditing(id, name);
		this.pendingTicks = 40;
		this.nameBox.setFocused(true);
		this.nameBox.moveCursorToEnd(false);
		this.nameBox.setHighlightPos(0);
	}

	private void startEditing(UUID id, String name) {
		this.stopEditing();
		this.editing = id;
		this.editedName = name;
		this.nameBox.setValue(name);
		this.nameBox.setFocused(false);
		this.deleteArmed = false;
		this.updateButtons();
	}

	/** Saves the name and leaves edit mode. */
	private void stopEditing() {
		this.commitName();
		this.editing = null;
		this.nameBox.setFocused(false);
		this.deleteArmed = false;
	}

	/** Sends the new name, if it changed. */
	void commitName() {
		if (this.editing == null) {
			return;
		}
		String name = this.nameBox.getValue().strip();
		if (!name.isEmpty() && !name.equals(this.editedName)) {
			this.editedName = name;
			ClientPlayNetworking.send(new GroupActionPayload(this.menu.containerId, GroupActionPayload.RENAME, this.editing, name, Optional.empty()));
		}
	}

	private void delete() {
		if (!this.deleteArmed) {
			this.deleteArmed = true;
			this.updateButtons();
			return;
		}
		this.send(GroupActionPayload.DELETE, this.editing);
		this.editing = null;
		this.nameBox.setFocused(false);
		this.deleteArmed = false;
	}

	/** Puts an item type into the group being edited (dragged there from the storage grid). */
	void addToEditedGroup(ItemVariant item) {
		if (this.editing != null && !item.isBlank()) {
			this.send(GroupActionPayload.ADD_ITEM, this.editing, item);
		}
	}

	void tick() {
		GroupListPayload.Entry edited = this.group(this.editing);
		if (edited != null) {
			this.pendingTicks = 0;
		} else if (this.pendingTicks > 0) {
			this.pendingTicks--;
		}
		if (this.editing != null && (edited == null ? this.pendingTicks == 0 : !edited.canManage())) {
			// deleted (maybe by another terminal), or the server refused to create it
			this.editing = null;
			this.nameBox.setFocused(false);
			this.deleteArmed = false;
		}
		if (this.menu.getClientGroupsRevision() != this.seenRevision) {
			this.seenRevision = this.menu.getClientGroupsRevision();
			if (edited != null && !this.nameBox.isFocused() && !edited.name().equals(this.editedName)) {
				this.editedName = edited.name();
				this.nameBox.setValue(edited.name());
			}
			this.clampScroll();
		}
		this.updateButtons();
	}

	private void updateButtons() {
		GroupListPayload.Entry edited = this.group(this.editing);
		boolean shared = edited != null && edited.shared();
		this.shareButton.active = edited != null;
		this.shareButton.setMessage(Component.translatable(shared ? "gui.simpleme.group.make_private" : "gui.simpleme.group.share"));
		this.shareButton.setTooltip(Tooltip.create(Component.translatable(shared ? "gui.simpleme.group.make_private.tooltip" : "gui.simpleme.group.share.tooltip")));
		this.deleteButton.active = edited != null;
		this.deleteButton.setMessage(Component.translatable(this.deleteArmed ? "gui.simpleme.group.delete_confirm" : "gui.simpleme.group.delete")
			.withStyle(this.deleteArmed ? ChatFormatting.RED : ChatFormatting.RESET));
		this.deleteButton.setTooltip(Tooltip.create(Component.translatable("gui.simpleme.group.delete.tooltip")));
		this.newButton.active = this.menu.networkState().current() != null;
	}

	// ------------------------------------------------------------------------------------------
	// Layout of the list
	// ------------------------------------------------------------------------------------------

	private int footerTop() {
		return this.y + this.height - PADDING - FOOTER_HEIGHT;
	}

	private int listLeft() {
		return this.x + PADDING;
	}

	private int listTop() {
		return this.y + LIST_TOP;
	}

	private int listBottom() {
		return this.editing != null ? this.footerTop() - 2 : this.y + this.height - PADDING;
	}

	/** Item cells of a group; while it is edited there is one more, empty cell to drop items on. */
	private int cellCount(GroupListPayload.Entry group) {
		if (group.hidden()) {
			return 0;
		}
		return group.items().size() + (group.id().equals(this.editing) ? 1 : 0);
	}

	private int groupHeight(GroupListPayload.Entry group) {
		int rows = (this.cellCount(group) + COLUMNS - 1) / COLUMNS;
		return HEADER_HEIGHT + rows * 18 + GROUP_GAP;
	}

	private int contentHeight() {
		int total = 0;
		for (GroupListPayload.Entry group : this.groups()) {
			total += this.groupHeight(group);
		}
		return total;
	}

	private int maxScroll() {
		return Math.max(0, this.contentHeight() - (this.listBottom() - this.listTop()));
	}

	private void clampScroll() {
		this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
	}

	private boolean isOverList(double mouseX, double mouseY) {
		return expanded && mouseX >= this.listLeft() && mouseX < this.listLeft() + COLUMNS * 18
			&& mouseY >= this.listTop() && mouseY < this.listBottom();
	}

	/** What is at the mouse in the list: a group header, or an item cell ({@code index} -1 is the empty drop cell). */
	private record Hit(GroupListPayload.Entry group, boolean header, int index, int cellX, int cellY) {
		boolean isEditButton(double mouseX, GroupPanel panel) {
			return this.header && this.group.canManage() && mouseX >= panel.listLeft() + COLUMNS * 18 - 11;
		}
	}

	private @Nullable Hit hitAt(double mouseX, double mouseY) {
		if (!this.isOverList(mouseX, mouseY)) {
			return null;
		}
		int top = this.listTop() - this.scroll;
		for (GroupListPayload.Entry group : this.groups()) {
			if (mouseY >= top && mouseY < top + HEADER_HEIGHT) {
				return new Hit(group, true, 0, 0, 0);
			}
			int cellsTop = top + HEADER_HEIGHT;
			int cells = this.cellCount(group);
			int rows = (cells + COLUMNS - 1) / COLUMNS;
			if (mouseY >= cellsTop && mouseY < cellsTop + rows * 18) {
				int column = (int) ((mouseX - this.listLeft()) / 18);
				int row = (int) ((mouseY - cellsTop) / 18);
				int index = row * COLUMNS + column;
				if (index >= cells) {
					return null;
				}
				return new Hit(group, false, index < group.items().size() ? index : -1, this.listLeft() + column * 18 + 1, cellsTop + row * 18 + 1);
			}
			top += this.groupHeight(group);
		}
		return null;
	}

	/** Whether a dragged item would land in the edited group. */
	boolean isDropTarget(double mouseX, double mouseY) {
		return this.isEditing() && this.isOverPanel(mouseX, mouseY) && mouseY < this.footerTop();
	}

	private ItemStack stack(ItemVariant variant) {
		return this.stacks.computeIfAbsent(variant, ItemVariant::toStack);
	}

	/** The item shown at the mouse (for recipe viewers), with its cell in screen coordinates. */
	TerminalScreen.@Nullable StorageCell cellAt(double mouseX, double mouseY) {
		Hit hit = this.hitAt(mouseX, mouseY);
		if (hit == null || hit.header() || hit.index() < 0) {
			return null;
		}
		return new TerminalScreen.StorageCell(this.stack(hit.group().items().get(hit.index())), hit.cellX(), hit.cellY());
	}

	// ------------------------------------------------------------------------------------------
	// Rendering
	// ------------------------------------------------------------------------------------------

	void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, boolean dragging) {
		if (!expanded) {
			this.extractTab(graphics, mouseX, mouseY);
			return;
		}
		TerminalScreen.drawPanel(graphics, this.x, this.y, WIDTH, this.height);
		graphics.text(this.font, Component.translatable("gui.simpleme.group.title"), this.x + PADDING, this.y + 7, COLOR_TEXT, false);

		int left = this.listLeft();
		int top = this.listTop();
		int bottom = this.listBottom();
		List<GroupListPayload.Entry> groups = this.groups();
		if (groups.isEmpty()) {
			Component empty = Component.translatable(this.menu.networkState().current() != null ? "gui.simpleme.group.empty_list" : "gui.simpleme.network.none_short");
			graphics.textWithWordWrap(this.font, empty, left, top + 4, COLUMNS * 18, 0xFF606060, false);
		}

		Hit hovered = this.hitAt(mouseX, mouseY);
		graphics.enableScissor(left - 1, top, left + COLUMNS * 18 + 1, bottom);
		int rowTop = top - this.scroll;
		for (GroupListPayload.Entry group : groups) {
			int groupHeight = this.groupHeight(group);
			if (rowTop + groupHeight >= top && rowTop < bottom) {
				this.extractGroup(graphics, group, left, rowTop, hovered, mouseX, dragging);
			}
			rowTop += groupHeight;
		}
		graphics.disableScissor();

		int maxScroll = this.maxScroll();
		if (maxScroll > 0) {
			int trackHeight = bottom - top;
			int barHeight = Math.max(8, trackHeight * trackHeight / this.contentHeight());
			int barTop = top + (trackHeight - barHeight) * this.scroll / maxScroll;
			int barX = left + COLUMNS * 18 + 3;
			graphics.fill(barX, top, barX + 3, bottom, 0xFF8B8B8B);
			graphics.fill(barX, barTop, barX + 3, barTop + barHeight, 0xFF373737);
		}

		if (this.editing != null) {
			int footer = this.footerTop();
			graphics.fill(this.x + PADDING - 1, footer - 1, this.x + WIDTH - PADDING + 1, footer, 0xFF8B8B8B);
			graphics.textWithWordWrap(this.font, Component.translatable("gui.simpleme.group.drag_hint"), this.x + PADDING, footer + 2, WIDTH - 2 * PADDING, 0xFF606060, false);
			this.nameBox.extractRenderState(graphics, mouseX, mouseY, a);
			this.shareButton.extractRenderState(graphics, mouseX, mouseY, a);
			this.deleteButton.extractRenderState(graphics, mouseX, mouseY, a);
			this.doneButton.extractRenderState(graphics, mouseX, mouseY, a);
		}
		this.newButton.extractRenderState(graphics, mouseX, mouseY, a);
		this.closeButton.extractRenderState(graphics, mouseX, mouseY, a);

		if (hovered != null && this.menu.getCarried().isEmpty() && !dragging) {
			this.extractHitTooltip(graphics, hovered, mouseX, mouseY);
		}
	}

	private void extractTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int tabX = this.x - 2;
		int tabY = this.y + TAB_Y;
		TerminalScreen.drawPanel(graphics, tabX, tabY, TAB_WIDTH + 2, TAB_HEIGHT);
		boolean hovered = this.isOverPanel(mouseX, mouseY);
		if (hovered) {
			graphics.fill(tabX + 3, tabY + 3, tabX + TAB_WIDTH - 1, tabY + TAB_HEIGHT - 3, 0x40FFFFFF);
			graphics.setTooltipForNextFrame(this.font, Component.translatable("gui.simpleme.group.open"), mouseX, mouseY);
		}
		graphics.item(new ItemStack(Items.CHEST), tabX + 4, tabY + 3);
	}

	private void extractGroup(GuiGraphicsExtractor graphics, GroupListPayload.Entry group, int left, int top, @Nullable Hit hovered, int mouseX, boolean dragging) {
		int width = COLUMNS * 18;
		boolean isEdited = group.id().equals(this.editing);
		boolean headerHovered = hovered != null && hovered.header() && hovered.group() == group;
		int headerColor = isEdited ? 0xFFB4C4E0 : headerHovered ? 0xFFD8D8D8 : 0xFFB8B8B8;
		graphics.fill(left, top, left + width, top + HEADER_HEIGHT - 1, headerColor);

		// checkbox: shown or hidden
		int boxX = left + 2;
		int boxY = top + 2;
		graphics.fill(boxX, boxY, boxX + 8, boxY + 8, 0xFF373737);
		graphics.fill(boxX + 1, boxY + 1, boxX + 7, boxY + 7, 0xFFFFFFFF);
		if (!group.hidden()) {
			graphics.fill(boxX + 2, boxY + 2, boxX + 6, boxY + 6, 0xFF2E7D32);
		}

		boolean editable = group.canManage();
		int nameWidth = width - 14 - (editable ? 11 : 0);
		String name = this.clip(group.name(), nameWidth);
		graphics.text(this.font, name, left + 13, top + 2, group.shared() ? COLOR_SHARED : COLOR_TEXT, false);
		if (editable) {
			boolean editHovered = headerHovered && hovered.isEditButton(mouseX, this);
			graphics.text(this.font, "✎", left + width - 9, top + 2, isEdited || editHovered ? 0xFF1F4F9F : 0xFF606060, false);
		}
		if (group.hidden()) {
			return;
		}

		int cellsTop = top + HEADER_HEIGHT;
		int cells = this.cellCount(group);
		Object2LongMap<ItemVariant> counts = this.menu.getClientItems();
		for (int i = 0; i < cells; i++) {
			int cellX = left + (i % COLUMNS) * 18;
			int cellY = cellsTop + (i / COLUMNS) * 18;
			TerminalScreen.drawSlot(graphics, cellX, cellY, 18, 18);
			boolean cellHovered = hovered != null && !hovered.header() && hovered.group() == group && hovered.cellX() == cellX + 1 && hovered.cellY() == cellY + 1;
			if (i >= group.items().size()) {
				// the drop cell of the edited group
				boolean target = dragging || cellHovered;
				graphics.fill(cellX + 1, cellY + 1, cellX + 17, cellY + 17, target ? 0x8080C080 : 0x40FFFFFF);
				graphics.centeredText(this.font, "+", cellX + 9, cellY + 5, 0xFF404040);
				continue;
			}
			ItemVariant variant = group.items().get(i);
			ItemStack stack = this.stack(variant);
			long count = counts.getLong(variant);
			if (cellHovered) {
				graphics.fill(cellX + 1, cellY + 1, cellX + 17, cellY + 17, 0x80FFFFFF);
			}
			graphics.item(stack, cellX + 1, cellY + 1);
			graphics.itemDecorations(this.font, stack, cellX + 1, cellY + 1, "");
			if (count <= 0) {
				graphics.fill(cellX + 1, cellY + 1, cellX + 17, cellY + 17, 0x908B8B8B);
			}
			TerminalScreen.drawCount(graphics, this.font, count, cellX + 1, cellY + 1);
			if (isEdited && cellHovered) {
				graphics.fill(cellX + 1, cellY + 1, cellX + 17, cellY + 17, 0x60FF4040);
			}
		}
		if (isEdited && dragging) {
			int rows = (cells + COLUMNS - 1) / COLUMNS;
			graphics.outline(left, top, width, HEADER_HEIGHT + rows * 18, 0xFF2E7D32);
		}
	}

	private void extractHitTooltip(GuiGraphicsExtractor graphics, Hit hit, int mouseX, int mouseY) {
		GroupListPayload.Entry group = hit.group();
		if (hit.header()) {
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal(group.name()));
			lines.add(Component.translatable(group.shared() ? "gui.simpleme.group.shared" : "gui.simpleme.group.private").withStyle(ChatFormatting.GRAY));
			if (!group.canManage()) {
				lines.add(Component.translatable("gui.simpleme.group.owner", group.ownerName()).withStyle(ChatFormatting.GRAY));
			}
			if (hit.isEditButton(mouseX, this)) {
				lines.add(Component.translatable(group.id().equals(this.editing) ? "gui.simpleme.group.edit_done" : "gui.simpleme.group.edit").withStyle(ChatFormatting.YELLOW));
			} else {
				lines.add(Component.translatable(group.hidden() ? "gui.simpleme.group.show" : "gui.simpleme.group.hide").withStyle(ChatFormatting.YELLOW));
			}
			graphics.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
			return;
		}
		if (hit.index() < 0) {
			graphics.setTooltipForNextFrame(this.font, Component.translatable("gui.simpleme.group.drop"), mouseX, mouseY);
			return;
		}
		ItemVariant variant = group.items().get(hit.index());
		ItemStack stack = this.stack(variant);
		List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, stack));
		lines.add(Component.translatable("gui.simpleme.stored", String.format("%,d", this.menu.getClientItems().getLong(variant))).withStyle(ChatFormatting.GRAY));
		if (group.id().equals(this.editing)) {
			lines.add(Component.translatable("gui.simpleme.group.remove").withStyle(ChatFormatting.YELLOW));
		}
		graphics.setTooltipForNextFrame(this.font, lines, stack.getTooltipImage(), mouseX, mouseY, stack.get(DataComponents.TOOLTIP_STYLE));
	}

	private String clip(String text, int width) {
		return this.font.width(text) <= width ? text : this.font.plainSubstrByWidth(text, width - this.font.width("...")) + "...";
	}

	// ------------------------------------------------------------------------------------------
	// Input
	// ------------------------------------------------------------------------------------------

	/** @return whether the click was on the panel (it is used up then) */
	boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		boolean onNameBox = this.isEditing() && this.nameBox.isMouseOver(mouseX, mouseY);
		if (!onNameBox && this.nameBox.isFocused()) {
			this.nameBox.setFocused(false);
			this.commitName();
		}
		if (!this.isOverPanel(mouseX, mouseY)) {
			return false;
		}
		if (!expanded) {
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
				this.setExpanded(true);
			}
			return true;
		}
		if (this.deleteArmed && !this.deleteButton.isMouseOver(mouseX, mouseY)) {
			this.deleteArmed = false;
			this.updateButtons();
		}
		for (AbstractWidget widget : this.widgets) {
			boolean shown = widget == this.newButton || widget == this.closeButton || this.editing != null;
			if (shown && widget.mouseClicked(event, doubleClick)) {
				if (widget == this.nameBox) {
					this.nameBox.setFocused(true);
				}
				return true;
			}
		}

		Hit hit = this.hitAt(mouseX, mouseY);
		if (hit == null) {
			return true;
		}
		GroupListPayload.Entry group = hit.group();
		if (hit.header()) {
			if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
				return true;
			}
			if (hit.isEditButton(mouseX, this)) {
				if (group.id().equals(this.editing)) {
					this.stopEditing();
				} else {
					this.startEditing(group.id(), group.name());
					if (group.hidden()) {
						this.send(GroupActionPayload.SHOW, group.id());
					}
				}
			} else {
				this.send(group.hidden() ? GroupActionPayload.SHOW : GroupActionPayload.HIDE, group.id());
			}
			this.clampScroll();
			return true;
		}

		ItemStack carried = this.menu.getCarried();
		if (group.id().equals(this.editing)) {
			if (!carried.isEmpty()) {
				// an item on the cursor goes into the group (it stays on the cursor)
				this.addToEditedGroup(ItemVariant.of(carried));
			} else if (hit.index() >= 0) {
				this.send(GroupActionPayload.REMOVE_ITEM, group.id(), group.items().get(hit.index()));
			}
			return true;
		}
		if (hit.index() >= 0 || !carried.isEmpty()) {
			// like the storage grid: take items out, or store the item on the cursor
			this.screen.clickStorageItem(event, hit.index() >= 0 ? group.items().get(hit.index()) : null);
		}
		return true;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
		if (!expanded || !this.isOverPanel(mouseX, mouseY)) {
			return false;
		}
		this.scrollRemainder += scrollY * 18;
		int pixels = (int) this.scrollRemainder;
		this.scrollRemainder -= pixels;
		this.scroll = Mth.clamp(this.scroll - pixels, 0, this.maxScroll());
		return true;
	}

	boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.nameBox.isFocused() && this.nameBox.mouseDragged(event, dx, dy);
	}

	boolean isTyping() {
		return this.isEditing() && this.nameBox.isFocused();
	}

	/** Only called while {@link #isTyping()}: the name box gets every key. */
	boolean keyPressed(KeyEvent event) {
		if (event.isEscape() || event.isConfirmation()) {
			this.nameBox.setFocused(false);
			this.commitName();
			return true;
		}
		this.nameBox.keyPressed(event);
		return true;
	}

	boolean charTyped(CharacterEvent event) {
		return this.nameBox.charTyped(event);
	}

	/** Called when the mouse was pressed somewhere the panel did not see (e.g. a recipe viewer's list). */
	void unfocusUnlessOver(double mouseX, double mouseY) {
		if (this.nameBox.isFocused() && !this.nameBox.isMouseOver(mouseX, mouseY)) {
			this.nameBox.setFocused(false);
			this.commitName();
		}
	}
}
