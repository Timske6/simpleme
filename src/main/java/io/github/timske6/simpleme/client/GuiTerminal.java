package io.github.timske6.simpleme.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import io.github.timske6.simpleme.menu.ContainerTerminal;
import io.github.timske6.simpleme.network.Network;
import io.github.timske6.simpleme.network.PacketStorageClick;
import io.github.timske6.simpleme.storage.ItemKey;

public class GuiTerminal extends GuiContainer {

    private static final ResourceLocation CREATIVE_TABS = new ResourceLocation(
        "textures/gui/container/creative_inventory/tabs.png");

    private static final int IMAGE_WIDTH = 195;
    private static final int SCROLLBAR_X = 175;
    private static final int SCROLLER_HEIGHT = 15;

    private static final int COLOR_PANEL = 0xFFC6C6C6;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_DARK = 0xFF373737;
    private static final int COLOR_SHADOW = 0xFF555555;
    private static final int COLOR_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_BORDER = 0xFF000000;

    /** Set by the NEI plugin: NEI then draws the tooltips and handles R/U on grid items. */
    public static boolean neiActive;
    /** The most recently opened terminal screen (used by the NEI overlay positioner). */
    public static GuiTerminal current;

    /** Remembered while the game runs, so the terminal reopens the way you left it. */
    private static SortMode sortMode = SortMode.NAME;
    private static String lastSearch = "";

    private final ContainerTerminal container;
    private final Map<ItemKey, Entry> entryCache = new HashMap<>();
    private final List<Entry> visibleEntries = new ArrayList<>();

    private GuiTextField searchField;
    private SmallButton sortButton;
    private SmallButton clearButton;

    private int seenRevision = -1;
    private int scrollRow;
    private double scrollRemainder;
    private boolean draggingScrollbar;
    private int consumedButton = -1;
    private Entry hoveredEntry;

    public GuiTerminal(ContainerTerminal container) {
        super(container);
        this.container = container;
        this.xSize = IMAGE_WIDTH;
        this.ySize = container.guiHeight;
    }

    public ContainerTerminal getContainer() {
        return container;
    }

    // ------------------------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------------------------

    @Override
    public void initGui() {
        super.initGui();
        current = this;
        Keyboard.enableRepeatEvents(true);
        searchField = new GuiTextField(fontRendererObj, guiLeft + 84, guiTop + 6, 86, 10);
        searchField.setMaxStringLength(64);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setTextColor(0xFFFFFF);
        searchField.setText(lastSearch);

        buttonList.clear();
        sortButton = new SmallButton(0, guiLeft + SCROLLBAR_X - 2, guiTop + 3, 18, 14, sortMode.symbol);
        clearButton = new SmallButton(1, guiLeft + 9, guiTop + container.craftingY + 19, 14, 14, "x");
        buttonList.add(sortButton);
        buttonList.add(clearButton);
        refreshEntries();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == sortButton) {
            sortMode = sortMode.next();
            sortButton.displayString = sortMode.symbol;
            refreshEntries();
        } else if (button == clearButton) {
            mc.playerController.sendEnchantPacket(container.windowId, ContainerTerminal.BUTTON_CLEAR_GRID);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        searchField.updateCursorCounter();
        if (container.getClientRevision() != seenRevision) {
            refreshEntries();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Item list
    // ------------------------------------------------------------------------------------------

    private void refreshEntries() {
        seenRevision = container.getClientRevision();
        Map<ItemKey, Long> items = container.getClientItems();
        entryCache.keySet()
            .retainAll(items.keySet());

        List<String> terms = new ArrayList<>();
        for (String term : searchField.getText()
            .toLowerCase(Locale.ROOT)
            .split("\\s+")) {
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }

        visibleEntries.clear();
        for (Map.Entry<ItemKey, Long> item : items.entrySet()) {
            Entry entry = entryCache.get(item.getKey());
            if (entry == null) {
                entry = new Entry(item.getKey());
                entryCache.put(item.getKey(), entry);
            }
            entry.count = item.getValue();
            if (entry.matches(terms)) {
                visibleEntries.add(entry);
            }
        }
        visibleEntries.sort(sortMode.comparator);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll()));
    }

    private int maxScroll() {
        int totalRows = (visibleEntries.size() + ContainerTerminal.COLUMNS - 1) / ContainerTerminal.COLUMNS;
        return Math.max(0, totalRows - container.rows);
    }

    public boolean isOverGrid(int mouseX, int mouseY) {
        int x = mouseX - guiLeft - (ContainerTerminal.GRID_X - 1);
        int y = mouseY - guiTop - (ContainerTerminal.GRID_Y - 1);
        return x >= 0 && y >= 0 && x < ContainerTerminal.COLUMNS * 18 && y < container.rows * 18;
    }

    private boolean isOverScrollbar(int mouseX, int mouseY) {
        int x = mouseX - guiLeft - SCROLLBAR_X;
        int y = mouseY - guiTop - (ContainerTerminal.GRID_Y - 1);
        return x >= 0 && y >= 0 && x < 14 && y < container.rows * 18;
    }

    private boolean isOverSearch(int mouseX, int mouseY) {
        return mouseX >= guiLeft + 82 && mouseX < guiLeft + 172 && mouseY >= guiTop + 4 && mouseY < guiTop + 16;
    }

    private Entry entryAt(int mouseX, int mouseY) {
        if (!isOverGrid(mouseX, mouseY)) {
            return null;
        }
        int column = (mouseX - guiLeft - (ContainerTerminal.GRID_X - 1)) / 18;
        int row = (mouseY - guiTop - (ContainerTerminal.GRID_Y - 1)) / 18;
        int index = (scrollRow + row) * ContainerTerminal.COLUMNS + column;
        return index >= 0 && index < visibleEntries.size() ? visibleEntries.get(index) : null;
    }

    /** For NEI: the stack under the mouse (R/U show its recipes), or null. */
    public ItemStack stackAt(int mouseX, int mouseY) {
        Entry entry = entryAt(mouseX, mouseY);
        return entry == null ? null : entry.stack;
    }

    /** For NEI tooltips: the stored amount of the item under the mouse, or -1. */
    public long storedAt(int mouseX, int mouseY) {
        Entry entry = entryAt(mouseX, mouseY);
        return entry == null ? -1 : entry.count;
    }

    /** For NEI: how many of an item are available (storage mirror). */
    public long storedAmount(ItemKey key) {
        Long count = container.getClientItems()
            .get(key);
        return count == null ? 0 : count;
    }

    private void scrollTo(int mouseY) {
        int trackTop = guiTop + ContainerTerminal.GRID_Y;
        int trackHeight = container.rows * 18 - 2;
        float progress = (float) (mouseY - trackTop - SCROLLER_HEIGHT / 2.0) / (trackHeight - SCROLLER_HEIGHT);
        scrollRow = Math.max(0, Math.min(maxScroll(), Math.round(progress * maxScroll())));
    }

    // ------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        int x = guiLeft;
        int y = guiTop;
        drawPanel(x, y, xSize, ySize);

        // search box
        drawRect(x + 82, y + 4, x + 172, y + 16, 0xFFA0A0A0);
        drawRect(x + 83, y + 5, x + 171, y + 15, 0xFF000000);

        // storage grid
        for (int row = 0; row < container.rows; row++) {
            for (int column = 0; column < ContainerTerminal.COLUMNS; column++) {
                drawSlot(
                    x + ContainerTerminal.GRID_X - 1 + column * 18,
                    y + ContainerTerminal.GRID_Y - 1 + row * 18,
                    18,
                    18);
            }
        }

        // scrollbar
        int trackX = x + SCROLLBAR_X;
        int trackY = y + ContainerTerminal.GRID_Y - 1;
        int trackHeight = container.rows * 18;
        drawSlot(trackX, trackY, 14, trackHeight);
        int maxScroll = maxScroll();
        int scrollerY = trackY + 1 + (maxScroll == 0 ? 0 : (trackHeight - 2 - SCROLLER_HEIGHT) * scrollRow / maxScroll);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager()
            .bindTexture(CREATIVE_TABS);
        drawTexturedModalRect(trackX + 1, scrollerY, maxScroll == 0 ? 244 : 232, 0, 12, SCROLLER_HEIGHT);

        // crafting grid, result and player inventory slots
        for (Object object : container.inventorySlots) {
            Slot slot = (Slot) object;
            if (slot.slotNumber == ContainerTerminal.RESULT_SLOT) {
                drawSlot(x + slot.xDisplayPosition - 5, y + slot.yDisplayPosition - 5, 26, 26);
            } else {
                drawSlot(x + slot.xDisplayPosition - 1, y + slot.yDisplayPosition - 1, 18, 18);
            }
        }

        // crafting arrow
        int arrowX = x + 92;
        int arrowCenterY = y + container.craftingY + 18 + 8;
        drawRect(arrowX, arrowCenterY - 2, arrowX + 14, arrowCenterY + 2, COLOR_SLOT);
        for (int i = 0; i < 7; i++) {
            drawRect(arrowX + 14 + i, arrowCenterY - 7 + i, arrowX + 15 + i, arrowCenterY + 7 - i, COLOR_SLOT);
        }

        searchField.drawTextBox();
        if (searchField.getText()
            .isEmpty() && !searchField.isFocused()) {
            fontRendererObj.drawString(
                EnumChatFormatting.ITALIC + StatCollector.translateToLocal("gui.simpleme.search.hint"),
                x + 84,
                y + 6,
                0x808080);
        }
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(StatCollector.translateToLocal("container.simpleme.terminal"), 8, 6, 0x404040);
        fontRendererObj
            .drawString(StatCollector.translateToLocal("container.inventory"), 8, container.inventoryLabelY, 0x404040);
        drawStorageGrid(mouseX, mouseY);
    }

    private void drawStorageGrid(int mouseX, int mouseY) {
        hoveredEntry = null;
        boolean overGrid = isOverGrid(mouseX, mouseY);
        int hoverColumn = overGrid ? (mouseX - guiLeft - (ContainerTerminal.GRID_X - 1)) / 18 : -1;
        int hoverRow = overGrid ? (mouseY - guiTop - (ContainerTerminal.GRID_Y - 1)) / 18 : -1;

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        zLevel = 100.0F;
        itemRender.zLevel = 100.0F;
        for (int row = 0; row < container.rows; row++) {
            for (int column = 0; column < ContainerTerminal.COLUMNS; column++) {
                int index = (scrollRow + row) * ContainerTerminal.COLUMNS + column;
                if (index >= visibleEntries.size()) {
                    break;
                }
                Entry entry = visibleEntries.get(index);
                int x = ContainerTerminal.GRID_X + column * 18;
                int y = ContainerTerminal.GRID_Y + row * 18;
                try {
                    itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), entry.stack, x, y);
                    itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), entry.stack, x, y, "");
                } catch (RuntimeException e) {
                    // a broken item renderer of some mod must not break the whole terminal
                }
            }
        }
        itemRender.zLevel = 0.0F;
        zLevel = 0.0F;
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        for (int row = 0; row < container.rows; row++) {
            for (int column = 0; column < ContainerTerminal.COLUMNS; column++) {
                int index = (scrollRow + row) * ContainerTerminal.COLUMNS + column;
                int x = ContainerTerminal.GRID_X + column * 18;
                int y = ContainerTerminal.GRID_Y + row * 18;
                Entry entry = index < visibleEntries.size() ? visibleEntries.get(index) : null;
                if (entry != null) {
                    drawCount(entry.count, x, y);
                }
                if (row == hoverRow && column == hoverColumn) {
                    GL11.glColorMask(true, true, true, false);
                    drawGradientRect(x, y, x + 16, y + 16, 0x80FFFFFF, 0x80FFFFFF);
                    GL11.glColorMask(true, true, true, true);
                    hoveredEntry = entry;
                }
            }
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawCount(long count, int x, int y) {
        if (count <= 1) {
            return;
        }
        String text = formatCount(count);
        // Up to two digits look like vanilla; bigger numbers use half-size text so they stay inside the cell.
        if (count < 100) {
            fontRendererObj.drawStringWithShadow(text, x + 17 - fontRendererObj.getStringWidth(text), y + 9, 0xFFFFFF);
        } else {
            GL11.glPushMatrix();
            GL11.glTranslatef(x + 16 - fontRendererObj.getStringWidth(text) / 2.0F, y + 12, 0);
            GL11.glScalef(0.5F, 0.5F, 1.0F);
            fontRendererObj.drawStringWithShadow(text, 0, 0, 0xFFFFFF);
            GL11.glPopMatrix();
        }
    }

    static String formatCount(long count) {
        if (count < 1000) {
            return Long.toString(count);
        }
        String[] units = { "k", "M", "G", "T", "P", "E" };
        double value = count;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        String number = value < 10 ? String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10)
            : Long.toString((long) value);
        if (number.endsWith(".0")) {
            number = number.substring(0, number.length() - 2);
        }
        return number + units[unit];
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        // With NEI installed, NEI draws the tooltip (and adds the stored amount through our tooltip handler).
        Entry entry = hoveredEntry;
        if (!neiActive && entry != null && mc.thePlayer.inventory.getItemStack() == null) {
            List<String> lines = new ArrayList<>();
            try {
                List<String> tooltip = entry.stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
                for (int i = 0; i < tooltip.size(); i++) {
                    lines
                        .add((i == 0 ? entry.stack.getRarity().rarityColor : EnumChatFormatting.GRAY) + tooltip.get(i));
                }
            } catch (RuntimeException e) {
                lines.add(entry.name);
            }
            lines.add(EnumChatFormatting.GRAY + storedLine(entry.count));
            FontRenderer font = entry.stack.getItem()
                .getFontRenderer(entry.stack);
            drawHoveringText(lines, mouseX, mouseY, font == null ? fontRendererObj : font);
        }
    }

    public static String storedLine(long count) {
        return StatCollector.translateToLocalFormatted("gui.simpleme.stored", String.format("%,d", count));
    }

    private void drawPanel(int x, int y, int width, int height) {
        int right = x + width;
        int bottom = y + height;
        drawRect(x + 1, y + 1, right - 1, bottom - 1, COLOR_PANEL);
        drawRect(x + 2, y, right - 2, y + 1, COLOR_BORDER);
        drawRect(x + 2, bottom - 1, right - 2, bottom, COLOR_BORDER);
        drawRect(x, y + 2, x + 1, bottom - 2, COLOR_BORDER);
        drawRect(right - 1, y + 2, right, bottom - 2, COLOR_BORDER);
        drawRect(x + 1, y + 1, x + 2, y + 2, COLOR_BORDER);
        drawRect(right - 2, y + 1, right - 1, y + 2, COLOR_BORDER);
        drawRect(x + 1, bottom - 2, x + 2, bottom - 1, COLOR_BORDER);
        drawRect(right - 2, bottom - 2, right - 1, bottom - 1, COLOR_BORDER);
        drawRect(x + 2, y + 1, right - 3, y + 3, COLOR_LIGHT);
        drawRect(x + 1, y + 2, x + 3, bottom - 3, COLOR_LIGHT);
        drawRect(x + 3, bottom - 3, right - 2, bottom - 1, COLOR_SHADOW);
        drawRect(right - 3, y + 3, right - 1, bottom - 2, COLOR_SHADOW);
    }

    private void drawSlot(int x, int y, int width, int height) {
        drawRect(x, y, x + width, y + height, COLOR_SLOT);
        drawRect(x, y, x + width - 1, y + 1, COLOR_DARK);
        drawRect(x, y, x + 1, y + height - 1, COLOR_DARK);
        drawRect(x + 1, y + height - 1, x + width, y + height, COLOR_LIGHT);
        drawRect(x + width - 1, y + 1, x + width, y + height, COLOR_LIGHT);
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        searchField.mouseClicked(mouseX, mouseY, button);
        if (isOverSearch(mouseX, mouseY)) {
            if (button == 1) {
                searchField.setText("");
                lastSearch = "";
                scrollRow = 0;
                refreshEntries();
            }
            consumedButton = button;
            return;
        }
        if (isOverScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            scrollTo(mouseY);
            consumedButton = button;
            return;
        }
        if (isOverGrid(mouseX, mouseY)) {
            handleGridClick(mouseX, mouseY, button);
            consumedButton = button;
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleGridClick(int mouseX, int mouseY, int button) {
        ItemStack carried = mc.thePlayer.inventory.getItemStack();
        ItemStack template;
        int action;
        if (carried != null) {
            template = carried;
            if (button == 0) {
                action = PacketStorageClick.INSERT_ALL;
            } else if (button == 1) {
                action = PacketStorageClick.INSERT_ONE;
            } else {
                return;
            }
        } else {
            Entry entry = entryAt(mouseX, mouseY);
            if (entry == null) {
                return;
            }
            template = entry.stack;
            if (button == 2) {
                if (!mc.thePlayer.capabilities.isCreativeMode) {
                    return;
                }
                action = PacketStorageClick.CLONE;
            } else if (button == 0) {
                action = isShiftKeyDown() ? PacketStorageClick.QUICK_MOVE : PacketStorageClick.TAKE_STACK;
            } else if (button == 1) {
                action = isShiftKeyDown() ? PacketStorageClick.TAKE_ONE : PacketStorageClick.TAKE_HALF;
            } else {
                return;
            }
        }
        Network.CHANNEL.sendToServer(new PacketStorageClick(container.windowId, template, action));
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        if (which >= 0) {
            if (draggingScrollbar) {
                draggingScrollbar = false;
                consumedButton = -1;
                return;
            }
            if (which == consumedButton) {
                consumedButton = -1;
                return;
            }
        }
        super.mouseMovedOrUp(mouseX, mouseY, which);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceClick) {
        if (draggingScrollbar) {
            scrollTo(mouseY);
            return;
        }
        if (button == consumedButton) {
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, timeSinceClick);
    }

    @Override
    public void handleMouseInput() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            if (isOverGrid(mouseX, mouseY) || isOverScrollbar(mouseX, mouseY)) {
                // one notch = 120; keep fractions for smooth scrolling devices
                scrollRemainder += wheel / 120.0;
                int rows = (int) scrollRemainder;
                scrollRemainder -= rows;
                scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - rows));
                return;
            }
        }
        super.handleMouseInput();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (searchField.isFocused()) {
            // Typing in the search box must not trigger inventory keys or NEI hotkeys.
            if (keyCode == Keyboard.KEY_ESCAPE) {
                super.keyTyped(typedChar, keyCode);
            } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                searchField.setFocused(false);
            } else if (searchField.textboxKeyTyped(typedChar, keyCode)) {
                lastSearch = searchField.getText();
                scrollRow = 0;
                refreshEntries();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    public int guiLeft() {
        return guiLeft;
    }

    public int guiTop() {
        return guiTop;
    }

    public int visibleEntryCount() {
        return visibleEntries.size();
    }

    public int scrollRow() {
        return scrollRow;
    }

    public ItemStack visibleEntry(int index) {
        return index >= 0 && index < visibleEntries.size() ? visibleEntries.get(index).stack : null;
    }

    public void setSearch(String text) {
        searchField.setText(text);
        lastSearch = text;
        scrollRow = 0;
        refreshEntries();
    }

    private static final class Entry {

        final ItemKey key;
        final ItemStack stack;
        final String name;
        final String modId;
        final String registryName;
        long count;

        Entry(ItemKey key) {
            this.key = key;
            this.stack = key.toStack(1);
            String displayName;
            try {
                displayName = stack.getDisplayName();
            } catch (RuntimeException e) {
                displayName = String.valueOf(Item.itemRegistry.getNameForObject(key.item));
            }
            this.name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
            String id = String.valueOf(Item.itemRegistry.getNameForObject(key.item));
            int colon = id.indexOf(':');
            this.modId = colon < 0 ? "minecraft"
                : id.substring(0, colon)
                    .toLowerCase(Locale.ROOT);
            this.registryName = id.toLowerCase(Locale.ROOT);
        }

        boolean matches(List<String> terms) {
            for (String term : terms) {
                if (term.startsWith("@")) {
                    if (!modId.contains(term.substring(1))) {
                        return false;
                    }
                } else if (!name.contains(term) && !registryName.contains(term)) {
                    return false;
                }
            }
            return true;
        }
    }

    private enum SortMode {

        NAME("Az", Comparator.<Entry, String>comparing(e -> e.name)
            .thenComparing(e -> e.modId)
            .thenComparingLong(e -> -e.count)),
        COUNT("#", Comparator.<Entry>comparingLong(e -> -e.count)
            .thenComparing(e -> e.name)),
        MOD("@", Comparator.<Entry, String>comparing(e -> e.modId)
            .thenComparing(e -> e.name));

        final String symbol;
        final Comparator<Entry> comparator;

        SortMode(String symbol, Comparator<Entry> comparator) {
            this.symbol = symbol;
            this.comparator = comparator;
        }

        SortMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }
}
