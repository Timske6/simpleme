package io.github.timske6.simpleme.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.menu.NetworkClientState;
import io.github.timske6.simpleme.network.NetworkActionPayload;
import io.github.timske6.simpleme.network.NetworkListPayload;
import io.github.timske6.simpleme.storage.Networks;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Overlay inside the terminal and transfer chest GUIs: choose, create, rename, share and delete
 * ME networks. (A separate screen would close the container on the client.)
 */
public final class NetworkPanel {
	public static final int WIDTH = 236;
	public static final int HEIGHT = 178;
	private static final int LIST_X = 8;
	private static final int LIST_Y = 30;
	private static final int LIST_WIDTH = 220;
	private static final int ROWS = 7;
	private static final int ROW_HEIGHT = 14;

	private final Minecraft minecraft;
	private final Font font;
	private final int containerId;
	private final NetworkClientState state;
	private final EditBox nameBox;
	private final Button createButton;
	private final Button connectButton;
	private final Button renameButton;
	private final Button publicButton;
	private final Button deleteButton;
	private final Button closeButton;
	private final List<AbstractWidget> widgets;

	private boolean open;
	private int x;
	private int y;
	private int screenWidth;
	private int screenHeight;
	private int scroll;
	private int seenRevision = -1;
	private @Nullable UUID highlighted;
	private @Nullable UUID lastClicked;
	private long lastClickTime;

	public NetworkPanel(Minecraft minecraft, int containerId, NetworkClientState state) {
		this.minecraft = minecraft;
		this.font = minecraft.font;
		this.containerId = containerId;
		this.state = state;
		this.nameBox = new EditBox(this.font, 0, 0, 156, 16, Component.translatable("gui.simpleme.network.name"));
		this.nameBox.setMaxLength(Networks.MAX_NAME_LENGTH);
		this.nameBox.setHint(Component.translatable("gui.simpleme.network.name_hint").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		this.nameBox.setResponder(value -> this.updateButtons());
		this.createButton = Button.builder(Component.translatable("gui.simpleme.network.create"), button -> this.create())
			.size(60, 16).tooltip(Tooltip.create(Component.translatable("gui.simpleme.network.create.tooltip"))).build();
		this.connectButton = Button.builder(Component.translatable("gui.simpleme.network.connect"), button -> this.connect())
			.size(54, 16).build();
		this.renameButton = Button.builder(Component.translatable("gui.simpleme.network.rename"), button -> this.send(NetworkActionPayload.RENAME, this.highlighted, this.nameBox.getValue()))
			.size(54, 16).tooltip(Tooltip.create(Component.translatable("gui.simpleme.network.rename.tooltip"))).build();
		this.publicButton = Button.builder(Component.translatable("gui.simpleme.network.make_public"), button -> this.send(NetworkActionPayload.TOGGLE_PUBLIC, this.highlighted, ""))
			.size(54, 16).build();
		this.deleteButton = Button.builder(Component.translatable("gui.simpleme.network.delete"), button -> this.send(NetworkActionPayload.DELETE, this.highlighted, ""))
			.size(54, 16).tooltip(Tooltip.create(Component.translatable("gui.simpleme.network.delete.tooltip"))).build();
		this.closeButton = Button.builder(Component.literal("×"), button -> this.close()).size(14, 14).build();
		this.widgets = List.of(this.nameBox, this.createButton, this.connectButton, this.renameButton, this.publicButton, this.deleteButton, this.closeButton);
	}

	public void layout(int screenWidth, int screenHeight) {
		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;
		this.x = (screenWidth - WIDTH) / 2;
		this.y = (screenHeight - HEIGHT) / 2;
		this.nameBox.setPosition(this.x + 8, this.y + 134);
		this.createButton.setPosition(this.x + 168, this.y + 134);
		Button[] row = {this.connectButton, this.renameButton, this.publicButton, this.deleteButton};
		for (int i = 0; i < row.length; i++) {
			row[i].setPosition(this.x + 8 + i * 56, this.y + 156);
		}
		this.closeButton.setPosition(this.x + WIDTH - 18, this.y + 4);
	}

	public boolean isOpen() {
		return this.open;
	}

	public void open() {
		this.open = true;
		this.highlighted = this.state.current();
		this.nameBox.setValue("");
		this.nameBox.setFocused(false);
		this.scroll = 0;
		this.seenRevision = -1;
		this.send(NetworkActionPayload.REFRESH, null, "");
	}

	public void close() {
		this.open = false;
		this.nameBox.setFocused(false);
	}

	// ------------------------------------------------------------------------------------------
	// Actions
	// ------------------------------------------------------------------------------------------

	private void send(int action, @Nullable UUID network, String text) {
		ClientPlayNetworking.send(new NetworkActionPayload(this.containerId, action, Optional.ofNullable(network), text));
	}

	private void create() {
		String name = this.nameBox.getValue().strip();
		if (name.isEmpty()) {
			name = Component.translatable("gui.simpleme.network.default_name", this.minecraft.player.getPlainTextName()).getString();
		}
		this.send(NetworkActionPayload.CREATE, null, name);
		this.close();
	}

	private void connect() {
		if (this.highlighted != null) {
			this.send(NetworkActionPayload.CONNECT, this.highlighted, "");
			this.close();
		}
	}

	private NetworkListPayload.@Nullable Entry entry(@Nullable UUID id) {
		for (NetworkListPayload.Entry entry : this.state.entries()) {
			if (entry.id().equals(id)) {
				return entry;
			}
		}
		return null;
	}

	public void tick() {
		if (this.state.revision() != this.seenRevision) {
			this.seenRevision = this.state.revision();
			if (this.entry(this.highlighted) == null) {
				this.highlighted = this.state.current();
			}
			this.scroll = Mth.clamp(this.scroll, 0, this.maxScroll());
			this.updateButtons();
		}
	}

	private void updateButtons() {
		NetworkListPayload.Entry entry = this.entry(this.highlighted);
		boolean manage = entry != null && entry.canManage();
		this.connectButton.active = entry != null && !entry.id().equals(this.state.current());
		String name = this.nameBox.getValue().strip();
		this.renameButton.active = manage && !name.isEmpty() && !name.equals(entry.name());
		this.publicButton.active = manage && entry.hasOwner();
		boolean isPublic = entry != null && entry.isPublic();
		this.publicButton.setMessage(Component.translatable(isPublic ? "gui.simpleme.network.make_private" : "gui.simpleme.network.make_public"));
		this.publicButton.setTooltip(Tooltip.create(Component.translatable(entry != null && !entry.hasOwner()
			? "gui.simpleme.network.legacy.tooltip"
			: isPublic ? "gui.simpleme.network.make_private.tooltip" : "gui.simpleme.network.make_public.tooltip")));
		this.deleteButton.active = manage && entry.types() == 0;
	}

	private int maxScroll() {
		return Math.max(0, this.state.entries().size() - ROWS);
	}

	// ------------------------------------------------------------------------------------------
	// Rendering
	// ------------------------------------------------------------------------------------------

	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		graphics.fill(0, 0, this.screenWidth, this.screenHeight, 0xA0101010);
		TerminalScreen.drawPanel(graphics, this.x, this.y, WIDTH, HEIGHT);
		graphics.text(this.font, Component.translatable("gui.simpleme.network.title"), this.x + 8, this.y + 7, 0xFF404040, false);
		boolean connected = this.state.current() != null;
		Component current = connected
			? Component.translatable("gui.simpleme.network.connected", this.state.currentName())
			: Component.translatable("gui.simpleme.network.none");
		graphics.text(this.font, this.clip(current.getString(), WIDTH - 16), this.x + 8, this.y + 18, connected ? 0xFF1E7B34 : 0xFFA02020, false);

		int listLeft = this.x + LIST_X;
		int listTop = this.y + LIST_Y;
		graphics.fill(listLeft - 1, listTop - 1, listLeft + LIST_WIDTH + 1, listTop + ROWS * ROW_HEIGHT + 1, 0xFF373737);
		graphics.fill(listLeft, listTop, listLeft + LIST_WIDTH, listTop + ROWS * ROW_HEIGHT, 0xFF202020);
		List<NetworkListPayload.Entry> entries = this.state.entries();
		if (entries.isEmpty()) {
			Component empty = Component.translatable("gui.simpleme.network.empty_list");
			graphics.text(this.font, empty, listLeft + (LIST_WIDTH - this.font.width(empty)) / 2, listTop + ROWS * ROW_HEIGHT / 2 - 4, 0xFFA0A0A0, false);
		}
		NetworkListPayload.Entry hovered = null;
		for (int row = 0; row < ROWS && this.scroll + row < entries.size(); row++) {
			NetworkListPayload.Entry entry = entries.get(this.scroll + row);
			int rowTop = listTop + row * ROW_HEIGHT;
			boolean isHovered = mouseX >= listLeft && mouseX < listLeft + LIST_WIDTH && mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT;
			if (entry.id().equals(this.highlighted)) {
				graphics.fill(listLeft, rowTop, listLeft + LIST_WIDTH, rowTop + ROW_HEIGHT, 0xFF4A5A80);
			} else if (isHovered) {
				graphics.fill(listLeft, rowTop, listLeft + LIST_WIDTH, rowTop + ROW_HEIGHT, 0xFF353535);
			}
			if (isHovered) {
				hovered = entry;
			}
			boolean isCurrent = entry.id().equals(this.state.current());
			String info = entry.hasOwner()
				? Component.translatable(entry.isPublic() ? "gui.simpleme.network.public" : "gui.simpleme.network.private").getString() + " · " + entry.ownerName()
				: Component.translatable("gui.simpleme.network.everyone").getString();
			int infoWidth = Math.min(this.font.width(info), 90);
			info = this.clip(info, 90);
			String name = this.clip((isCurrent ? "» " : "") + entry.name(), LIST_WIDTH - infoWidth - 12);
			graphics.text(this.font, name, listLeft + 3, rowTop + 3, isCurrent ? 0xFF7CFC7C : 0xFFFFFFFF, false);
			graphics.text(this.font, info, listLeft + LIST_WIDTH - 3 - this.font.width(info), rowTop + 3, entry.isPublic() ? 0xFF8FD0FF : 0xFFA0A0A0, false);
		}
		if (this.maxScroll() > 0) {
			int trackHeight = ROWS * ROW_HEIGHT;
			int barHeight = Math.max(8, trackHeight * ROWS / entries.size());
			int barTop = listTop + (trackHeight - barHeight) * this.scroll / this.maxScroll();
			graphics.fill(listLeft + LIST_WIDTH - 2, barTop, listLeft + LIST_WIDTH, barTop + barHeight, 0xFFA0A0A0);
		}

		for (AbstractWidget widget : this.widgets) {
			widget.extractRenderState(graphics, mouseX, mouseY, a);
		}
		if (hovered != null) {
			graphics.setTooltipForNextFrame(this.font, Component.translatable("gui.simpleme.network.contents",
				String.format("%,d", hovered.total()), hovered.types()), mouseX, mouseY);
		}
	}

	private String clip(String text, int width) {
		return this.font.width(text) <= width ? text : this.font.plainSubstrByWidth(text, width - this.font.width("...")) + "...";
	}

	// ------------------------------------------------------------------------------------------
	// Input (everything is swallowed while the panel is open)
	// ------------------------------------------------------------------------------------------

	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.nameBox.setFocused(this.nameBox.isMouseOver(event.x(), event.y()));
		for (AbstractWidget widget : this.widgets) {
			if (widget.mouseClicked(event, doubleClick)) {
				return true;
			}
		}
		int listLeft = this.x + LIST_X;
		int listTop = this.y + LIST_Y;
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() >= listLeft && event.x() < listLeft + LIST_WIDTH && event.y() >= listTop && event.y() < listTop + ROWS * ROW_HEIGHT) {
			int index = this.scroll + (int) ((event.y() - listTop) / ROW_HEIGHT);
			if (index < this.state.entries().size()) {
				NetworkListPayload.Entry entry = this.state.entries().get(index);
				long now = System.currentTimeMillis();
				boolean twice = entry.id().equals(this.lastClicked) && now - this.lastClickTime < 400;
				this.lastClicked = entry.id();
				this.lastClickTime = now;
				this.highlighted = entry.id();
				this.nameBox.setValue(entry.name());
				this.updateButtons();
				if (twice || doubleClick) {
					this.connect();
				}
			}
		}
		return true;
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		for (AbstractWidget widget : this.widgets) {
			widget.mouseReleased(event);
		}
		return true;
	}

	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		this.nameBox.mouseDragged(event, dx, dy);
		return true;
	}

	public boolean mouseScrolled(double scrollY) {
		this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY), 0, this.maxScroll());
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.close();
			return true;
		}
		if (this.nameBox.isFocused()) {
			if (event.isConfirmation()) {
				this.create();
				return true;
			}
			this.nameBox.keyPressed(event);
		}
		return true;
	}

	public boolean charTyped(CharacterEvent event) {
		if (this.nameBox.isFocused()) {
			this.nameBox.charTyped(event);
		}
		return true;
	}
}
