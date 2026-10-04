package io.github.timske6.simpleme.client;

import io.github.timske6.simpleme.menu.NetworkClientState;
import io.github.timske6.simpleme.menu.TransferChestMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import org.jspecify.annotations.Nullable;

/** Chest GUI with an on/off button and the network it sends to. */
public class TransferChestScreen extends AbstractContainerScreen<TransferChestMenu> {
	private static final Identifier CONTAINER_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
	private static final int BUTTON_WIDTH = 30;
	private static final int NETWORK_BUTTON_WIDTH = 96;

	private Button toggleButton;
	private Button networkButton;
	private @Nullable NetworkPanel networkPanel;
	private boolean shownEnabled;
	private int shownStatus = -1;
	private int seenNetworkRevision = -1;

	public TransferChestScreen(TransferChestMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 114 + TransferChestMenu.ROWS * 18);
		this.inventoryLabelY = this.imageHeight - 94;
	}

	@Override
	protected void init() {
		super.init();
		this.shownEnabled = this.menu.isEnabled();
		this.toggleButton = Button.builder(toggleLabel(this.shownEnabled),
				button -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, TransferChestMenu.BUTTON_TOGGLE))
			.bounds(this.leftPos + this.imageWidth - 7 - BUTTON_WIDTH, this.topPos + 3, BUTTON_WIDTH, 13)
			.tooltip(Tooltip.create(Component.translatable("gui.simpleme.toggle_transfer")))
			.build();
		this.addRenderableWidget(this.toggleButton);

		this.networkButton = Button.builder(Component.empty(), button -> this.networkPanel.open())
			.bounds(this.leftPos + this.imageWidth - 7 - NETWORK_BUTTON_WIDTH, this.topPos + this.inventoryLabelY - 3, NETWORK_BUTTON_WIDTH, 13)
			.tooltip(Tooltip.create(Component.translatable("gui.simpleme.network.choose")))
			.build();
		this.addRenderableWidget(this.networkButton);
		if (this.networkPanel == null) {
			this.networkPanel = new NetworkPanel(this.minecraft, this.menu.containerId, this.menu.networkState());
		}
		this.networkPanel.layout(this.width, this.height);
		this.seenNetworkRevision = -1;
	}

	private boolean panelOpen() {
		return this.networkPanel != null && this.networkPanel.isOpen();
	}

	public boolean isNetworkPanelOpen() {
		return this.panelOpen();
	}

	private void updateNetworkButton() {
		NetworkClientState state = this.menu.networkState();
		if (state.revision() == this.seenNetworkRevision && this.menu.status() == this.shownStatus) {
			return;
		}
		this.seenNetworkRevision = state.revision();
		this.shownStatus = this.menu.status();
		Component label = switch (this.shownStatus) {
			case TransferChestMenu.STATUS_ONLINE -> state.current() != null
				? Component.literal(state.currentName()).withStyle(ChatFormatting.GREEN)
				: Component.translatable("gui.simpleme.status.no_access").withStyle(ChatFormatting.GOLD);
			case TransferChestMenu.STATUS_OFFLINE -> Component.translatable("gui.simpleme.status.offline").withStyle(ChatFormatting.GOLD);
			default -> Component.translatable("gui.simpleme.network.none_short").withStyle(ChatFormatting.RED);
		};
		String text = label.getString();
		int max = NETWORK_BUTTON_WIDTH - 6;
		if (this.font.width(text) > max) {
			label = Component.literal(this.font.plainSubstrByWidth(text, max - this.font.width("...")) + "...").withStyle(label.getStyle());
		}
		this.networkButton.setMessage(label);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		this.updateNetworkButton();
		this.networkPanel.tick();
		if (this.menu.isEnabled() != this.shownEnabled) {
			this.shownEnabled = this.menu.isEnabled();
			this.toggleButton.setMessage(toggleLabel(this.shownEnabled));
		}
	}

	private static Component toggleLabel(boolean enabled) {
		return enabled
			? Component.translatable("gui.simpleme.on").withStyle(ChatFormatting.GREEN)
			: Component.translatable("gui.simpleme.off").withStyle(ChatFormatting.RED);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int rowsHeight = TransferChestMenu.ROWS * 18 + 17;
		graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_BACKGROUND, this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, rowsHeight, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, CONTAINER_BACKGROUND, this.leftPos, this.topPos + rowsHeight, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (!this.panelOpen()) {
			super.extractRenderState(graphics, mouseX, mouseY, a);
			return;
		}
		this.extractContents(graphics, -1000, -1000, a);
		graphics.nextStratum();
		this.networkPanel.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.nextStratum();
		this.extractCarriedItem(graphics, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return this.panelOpen() ? this.networkPanel.mouseClicked(event, doubleClick) : super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return this.panelOpen() ? this.networkPanel.mouseReleased(event) : super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return this.panelOpen() ? this.networkPanel.mouseDragged(event, dx, dy) : super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return this.panelOpen() ? this.networkPanel.mouseScrolled(scrollY) : super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return this.panelOpen() ? this.networkPanel.keyPressed(event) : super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return this.panelOpen() ? this.networkPanel.charTyped(event) : super.charTyped(event);
	}

	@Override
	protected boolean isHovering(int left, int top, int w, int h, double xm, double ym) {
		return !this.panelOpen() && super.isHovering(left, top, w, h, xm, ym);
	}
}
