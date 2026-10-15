package com.openggf.editor.desktop;

import com.openggf.editor.EditorCollisionPath;
import com.openggf.editor.EditorCommandPalette;
import com.openggf.editor.EditorFocusRegion;
import com.openggf.editor.EditorHierarchyDepth;
import com.openggf.editor.EditorInputHandler.Action;
import com.openggf.editor.EditorSpawnEditMode;
import com.openggf.editor.EditorStockObjectPalette;
import com.openggf.editor.LevelEditorController;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.MutableLevel;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectSpawn;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Builds the editor's window-resolution chrome each frame: menu bar, toolbar, docked library
 * and inspector panels, status bar and command palette. Every edit goes through the existing
 * {@link LevelEditorController} and {@link Action} dispatch, so undo, persistence and
 * validation are unchanged. Returns the free canvas area the game viewport should occupy.
 */
public final class EditorChromeShell implements AutoCloseable {
    /** Inputs the shell reads each frame; the engine owns all of them. */
    public record Frame(LevelEditorController controller, Consumer<Action> dispatch,
                        List<EditorCommandPalette.HostCommand> commands,
                        int gameX, int gameY, int gameWidth, int gameHeight, int gameScale) {
    }

    /** Canvas area in window coordinates with a top-left origin. */
    public record Area(float x, float y, float width, float height) {
    }

    private static final float TOOLBAR_HEIGHT = 46f;
    private static final float STATUS_HEIGHT = 28f;
    private static final String LIBRARY = "Library";
    private static final String INSPECTOR = "Inspector";
    private static final int FIXED_WINDOW = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;

    private final EditorChromeHost host;
    private final EditorTileAtlas atlas = new EditorTileAtlas();
    private final ImString filter = new ImString(32);
    private final ImString commandFilter = new ImString(64);
    private final float[] thumbnailSize = {64f};
    private boolean layoutBuilt;
    private boolean commandPaletteFocus;
    private boolean commandPaletteOpen;
    private int patternPaletteLine;
    private Area canvas = new Area(0, 0, 1, 1);

    public EditorChromeShell(EditorChromeHost host) {
        this.host = host;
    }

    public Area draw(Frame frame) {
        LevelEditorController controller = frame.controller();
        MutableLevel level = controller.currentLevel();
        Integer patternRaw = controller.selectedPatternRaw();
        if (patternRaw != null) patternPaletteLine = (patternRaw >> 13) & 3;
        atlas.refresh(level, patternPaletteLine);

        ImGui.pushFont(host.bodyFont(), EditorChromeHost.BODY_SIZE);
        try {
            float menuHeight = drawMenuBar(frame);
            ImGuiViewport viewport = ImGui.getMainViewport();
            float x = viewport.getPosX(), y = viewport.getPosY() + menuHeight;
            float width = viewport.getSizeX(), height = viewport.getSizeY() - menuHeight;
            drawToolbar(frame, x, y, width);
            drawStatusBar(frame, x, y + height - STATUS_HEIGHT, width);
            drawDockspace(x, y + TOOLBAR_HEIGHT, width, height - TOOLBAR_HEIGHT - STATUS_HEIGHT);
            drawLibrary(frame, level);
            drawInspector(frame, level);
            drawCanvasFrame(frame);
            drawCommandPalette(frame);
        } finally {
            ImGui.popFont();
        }
        return canvas;
    }

    // ---------------------------------------------------------------- menu bar

    private float drawMenuBar(Frame frame) {
        LevelEditorController controller = frame.controller();
        float height = 0;
        if (ImGui.beginMainMenuBar()) {
            height = ImGui.getWindowHeight();
            if (ImGui.beginMenu("File")) {
                if (ImGui.menuItem(EditorIcons.SAVE + "  Save", "Ctrl+S")) frame.dispatch().accept(Action.SAVE);
                if (ImGui.menuItem(EditorIcons.DOWNLOAD + "  Export level", "Ctrl+Shift+E")) frame.dispatch().accept(Action.EXPORT);
                ImGui.separator();
                for (var command : frame.commands()) {
                    if (isPlaytest(command.label())) continue;
                    if (ImGui.menuItem(command.label())) command.action().run();
                }
                ImGui.endMenu();
            }
            if (ImGui.beginMenu("Edit")) {
                if (ImGui.menuItem(EditorIcons.UNDO + "  Undo", "Ctrl+Z", false, controller.hasUndoHistory()))
                    frame.dispatch().accept(Action.UNDO);
                if (ImGui.menuItem(EditorIcons.REDO + "  Redo", "Ctrl+Y")) frame.dispatch().accept(Action.REDO);
                ImGui.separator();
                if (ImGui.menuItem(EditorIcons.PIPETTE + "  Pick at cursor", "E")) frame.dispatch().accept(Action.PERFORM_EYEDROP);
                if (ImGui.menuItem(EditorIcons.ERASER + "  Erase selection", "Del")) frame.dispatch().accept(Action.ERASE_SELECTION);
                ImGui.separator();
                if (ImGui.menuItem(EditorIcons.SEARCH + "  Command palette", "F1")) openCommandPalette();
                ImGui.endMenu();
            }
            if (ImGui.beginMenu("View")) {
                if (ImGui.menuItem("Collision overlay", "C", controller.isCollisionOverlayEnabled()))
                    frame.dispatch().accept(Action.TOGGLE_COLLISION_OVERLAY);
                if (ImGui.menuItem("Collision path B", "P", controller.collisionPath() == EditorCollisionPath.SECONDARY))
                    frame.dispatch().accept(Action.TOGGLE_COLLISION_PATH);
                ImGui.separator();
                if (ImGui.menuItem("Foreground layer", "L", controller.activeLayer() == 0) && controller.activeLayer() != 0)
                    frame.dispatch().accept(Action.TOGGLE_LAYER);
                if (ImGui.menuItem("Background layer", "L", controller.activeLayer() != 0) && controller.activeLayer() == 0)
                    frame.dispatch().accept(Action.TOGGLE_LAYER);
                ImGui.separator();
                if (ImGui.menuItem("Reset panel layout")) layoutBuilt = false;
                ImGui.endMenu();
            }
            if (ImGui.beginMenu("Play")) {
                for (var command : frame.commands()) {
                    if (isPlaytest(command.label()) && ImGui.menuItem(EditorIcons.PLAY + "  " + command.label()))
                        command.action().run();
                }
                ImGui.endMenu();
            }
            String state = controller.isModified() ? "Unsaved changes" : "Saved";
            float stateWidth = ImGui.calcTextSizeX(state) + 26;
            ImGui.setCursorPosX(ImGui.getWindowWidth() - stateWidth - 12);
            ImGui.textColored(controller.isModified() ? EditorChromeTheme.UNSAVED : EditorChromeTheme.SAVED,
                    controller.isModified() ? EditorIcons.CIRCLE_DOT : EditorIcons.CIRCLE_CHECK);
            ImGui.sameLine();
            ImGui.textDisabled(state);
            ImGui.endMainMenuBar();
        }
        return height;
    }

    private static boolean isPlaytest(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return lower.startsWith("playtest") || lower.startsWith("fresh start");
    }

    // ---------------------------------------------------------------- toolbar

    private void drawToolbar(Frame frame, float x, float y, float width) {
        LevelEditorController controller = frame.controller();
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, TOOLBAR_HEIGHT);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 8);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorChromeTheme.rgba(0x12141A, 1f));
        if (ImGui.begin("##toolbar", FIXED_WINDOW)) {
            breadcrumb(frame);
            toolbarDivider();
            ImGui.textDisabled("Mode");
            ImGui.sameLine();
            segment("Terrain", controller.spawnEditMode() == EditorSpawnEditMode.TILES,
                    () -> controller.setSpawnEditMode(EditorSpawnEditMode.TILES));
            segment("Objects", controller.spawnEditMode() == EditorSpawnEditMode.OBJECTS,
                    () -> controller.setSpawnEditMode(EditorSpawnEditMode.OBJECTS));
            segment("Rings", controller.spawnEditMode() == EditorSpawnEditMode.RINGS,
                    () -> controller.setSpawnEditMode(EditorSpawnEditMode.RINGS));
            toolbarDivider();
            ImGui.textDisabled("Layer");
            ImGui.sameLine();
            segment("FG", controller.activeLayer() == 0, () -> { if (controller.activeLayer() != 0) frame.dispatch().accept(Action.TOGGLE_LAYER); });
            segment("BG", controller.activeLayer() != 0, () -> { if (controller.activeLayer() == 0) frame.dispatch().accept(Action.TOGGLE_LAYER); });
            toolbarDivider();
            segment((controller.isCollisionOverlayEnabled() ? EditorIcons.EYE : EditorIcons.EYE_OFF) + " Collision",
                    controller.isCollisionOverlayEnabled(), () -> frame.dispatch().accept(Action.TOGGLE_COLLISION_OVERLAY));
            segment("Path " + (controller.collisionPath() == EditorCollisionPath.PRIMARY ? "A" : "B"), false,
                    () -> frame.dispatch().accept(Action.TOGGLE_COLLISION_PATH));

            float right = ImGui.getWindowWidth() - 12;
            float buttons = 3 * (ImGui.getFrameHeight() + 6) + ImGui.calcTextSizeX(EditorIcons.PLAY + "  Playtest") + 24;
            ImGui.sameLine(right - buttons);
            ImGui.beginDisabled(!controller.hasUndoHistory());
            if (iconButton(EditorIcons.UNDO, "Undo (Ctrl+Z)")) frame.dispatch().accept(Action.UNDO);
            ImGui.endDisabled();
            ImGui.sameLine(0, 6);
            if (iconButton(EditorIcons.REDO, "Redo (Ctrl+Y)")) frame.dispatch().accept(Action.REDO);
            ImGui.sameLine(0, 6);
            if (iconButton(EditorIcons.SAVE, "Save (Ctrl+S)")) frame.dispatch().accept(Action.SAVE);
            ImGui.sameLine(0, 10);
            ImGui.pushStyleColor(ImGuiCol.Button, EditorChromeTheme.ACCENT);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, EditorChromeTheme.rgba(0x6AAEFF, 1f));
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, EditorChromeTheme.rgba(0x3A86EB, 1f));
            ImGui.pushStyleColor(ImGuiCol.Text, EditorChromeTheme.rgba(0x0B1220, 1f));
            if (ImGui.button(EditorIcons.PLAY + "  Playtest")) runCommand(frame, "Playtest from cursor");
            ImGui.popStyleColor(4);
        }
        ImGui.end();
        ImGui.popStyleColor();
        ImGui.popStyleVar();
    }

    private void breadcrumb(Frame frame) {
        LevelEditorController controller = frame.controller();
        EditorHierarchyDepth depth = controller.depth();
        Integer block = controller.selection().selectedBlock();
        Integer chunk = controller.selection().selectedChunk();
        ImGui.alignTextToFramePadding();
        crumb(EditorIcons.MAP + " World", depth == EditorHierarchyDepth.WORLD, () -> {
            // Dispatched actions run at the next editor tick, so queue one step per level.
            for (int level = controller.depth().ordinal(); level > 0; level--) frame.dispatch().accept(Action.ASCEND);
        });
        if (depth != EditorHierarchyDepth.WORLD) {
            separatorChevron();
            crumb(EditorIcons.BOX + " Block " + hex(block), depth == EditorHierarchyDepth.BLOCK, () -> {
                if (controller.depth() == EditorHierarchyDepth.CHUNK) frame.dispatch().accept(Action.ASCEND);
            });
        }
        if (depth == EditorHierarchyDepth.CHUNK) {
            separatorChevron();
            crumb(EditorIcons.GRID_2X2 + " Chunk " + hex(chunk), true, () -> { });
        }
    }

    private static void crumb(String label, boolean current, Runnable action) {
        ImGui.sameLine();
        ImGui.pushStyleColor(ImGuiCol.Button, 0);
        if (current) ImGui.pushStyleColor(ImGuiCol.Text, EditorChromeTheme.rgba(0xFFFFFF, 1f));
        else ImGui.pushStyleColor(ImGuiCol.Text, EditorChromeTheme.TEXT_DIM);
        if (ImGui.button(label) && !current) action.run();
        ImGui.popStyleColor(2);
    }

    private static void separatorChevron() {
        ImGui.sameLine(0, 2);
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled(EditorIcons.CHEVRON_RIGHT);
    }

    private static void toolbarDivider() {
        ImGui.sameLine(0, 14);
        float cx = ImGui.getCursorScreenPosX(), cy = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList().addLine(cx, cy + 4, cx, cy + ImGui.getFrameHeight() - 4, EditorChromeTheme.GRID_LINE);
        ImGui.dummy(1, ImGui.getFrameHeight());
        ImGui.sameLine(0, 14);
        ImGui.alignTextToFramePadding();
    }

    private static void segment(String label, boolean active, Runnable action) {
        ImGui.sameLine(0, 4);
        if (active) {
            ImGui.pushStyleColor(ImGuiCol.Button, EditorChromeTheme.rgba(0x4C9AFF, 0.22f));
            ImGui.pushStyleColor(ImGuiCol.Text, EditorChromeTheme.rgba(0xBFDBFF, 1f));
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, EditorChromeTheme.rgba(0x232731, 1f));
            ImGui.pushStyleColor(ImGuiCol.Text, EditorChromeTheme.rgba(0xC4CAD6, 1f));
        }
        if (ImGui.button(label)) action.run();
        ImGui.popStyleColor(2);
    }

    private static boolean iconButton(String icon, String tooltip) {
        boolean pressed = ImGui.button(icon, ImGui.getFrameHeight(), ImGui.getFrameHeight());
        if (ImGui.isItemHovered()) ImGui.setTooltip(tooltip);
        return pressed;
    }

    private static void runCommand(Frame frame, String label) {
        for (var command : frame.commands()) {
            if (command.label().equalsIgnoreCase(label)) {
                command.action().run();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- dockspace

    private void drawDockspace(float x, float y, float width, float height) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, height);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.begin("##dockhost", FIXED_WINDOW | ImGuiWindowFlags.NoBackground);
        ImGui.popStyleVar();
        int dockId = ImGui.getID("EditorDock");
        if (!layoutBuilt) {
            imgui.internal.ImGui.dockBuilderRemoveNode(dockId);
            imgui.internal.ImGui.dockBuilderAddNode(dockId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
            imgui.internal.ImGui.dockBuilderSetNodeSize(dockId, width, height);
            ImInt left = new ImInt(), rest = new ImInt(), right = new ImInt(), center = new ImInt();
            imgui.internal.ImGui.dockBuilderSplitNode(dockId, ImGuiDir.Left, Math.min(0.3f, 330f / width), left, rest);
            imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Right,
                    Math.min(0.35f, 340f / Math.max(1f, width - 330f)), right, center);
            imgui.internal.ImGui.dockBuilderDockWindow(LIBRARY, left.get());
            imgui.internal.ImGui.dockBuilderDockWindow(INSPECTOR, right.get());
            imgui.internal.ImGui.dockBuilderFinish(dockId);
            layoutBuilt = true;
        }
        ImGui.dockSpace(dockId, 0, 0, ImGuiDockNodeFlags.PassthruCentralNode);
        var central = imgui.internal.ImGui.dockBuilderGetCentralNode(dockId);
        if (central != null && central.getSizeX() > 0) {
            canvas = new Area(central.getPosX(), central.getPosY(), central.getSizeX(), central.getSizeY());
        } else {
            canvas = new Area(x, y, width, height);
        }
        ImGui.end();
    }

    // ---------------------------------------------------------------- canvas

    private void drawCanvasFrame(Frame frame) {
        ImDrawList draw = ImGui.getBackgroundDrawList();
        float cx = canvas.x(), cy = canvas.y(), cw = canvas.width(), ch = canvas.height();
        float gx = frame.gameX(), gy = frame.gameY(), gw = frame.gameWidth(), gh = frame.gameHeight();
        int bg = EditorChromeTheme.CANVAS_BG;
        // Fill only the letterbox around the game; ImGui draws after the game, never over it.
        draw.addRectFilled(cx, cy, cx + cw, gy, bg);
        draw.addRectFilled(cx, gy + gh, cx + cw, cy + ch, bg);
        draw.addRectFilled(cx, gy, gx, gy + gh, bg);
        draw.addRectFilled(gx + gw, gy, cx + cw, gy + gh, bg);
        draw.addRect(gx - 1, gy - 1, gx + gw + 1, gy + gh + 1, EditorChromeTheme.GRID_LINE);
        LevelEditorController controller = frame.controller();
        String caption = switch (controller.depth()) {
            case WORLD -> "World";
            case BLOCK -> "World  /  Block " + hex(controller.selection().selectedBlock());
            case CHUNK -> "World  /  Block " + hex(controller.selection().selectedBlock())
                    + "  /  Chunk " + hex(controller.selection().selectedChunk());
        } + "      " + frame.gameScale() + "x scale";
        draw.addText(gx, gy + gh + 6, EditorChromeTheme.TEXT_DIM, caption);
    }

    // ---------------------------------------------------------------- library

    private void drawLibrary(Frame frame, MutableLevel level) {
        LevelEditorController controller = frame.controller();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 12);
        if (ImGui.begin(LIBRARY)) {
            if (level == null) {
                ImGui.textDisabled("No level loaded");
            } else if (controller.spawnEditMode() == EditorSpawnEditMode.OBJECTS && controller.objectPaletteOrNull() != null) {
                drawObjectLibrary(controller);
            } else if (controller.spawnEditMode() == EditorSpawnEditMode.RINGS) {
                ImGui.textWrapped("Rings need no library. Click the level to place a ring; Delete removes the ring under the cursor.");
            } else {
                drawTileLibrary(frame, level);
            }
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    private void drawTileLibrary(Frame frame, MutableLevel level) {
        LevelEditorController controller = frame.controller();
        EditorTileAtlas.Kind kind = switch (controller.depth()) {
            case WORLD -> EditorTileAtlas.Kind.BLOCK;
            case BLOCK -> EditorTileAtlas.Kind.CHUNK;
            case CHUNK -> EditorTileAtlas.Kind.PATTERN;
        };
        int count = atlas.count(kind);
        Integer selected = switch (kind) {
            case BLOCK -> controller.selectedBlockIndex();
            case CHUNK -> controller.selection().selectedChunk();
            case PATTERN -> controller.selectedPatternRaw() == null ? null : controller.selectedPatternRaw() & 0x7FF;
        };
        String title = switch (kind) {
            case BLOCK -> "Blocks";
            case CHUNK -> "Chunks";
            case PATTERN -> "Patterns";
        };
        String unit = switch (kind) {
            case BLOCK -> level.getBlockPixelSize() + " px";
            case CHUNK -> "16 px";
            case PATTERN -> "8 px";
        };

        ImGui.pushFont(host.titleFont(), EditorChromeHost.TITLE_SIZE);
        ImGui.text(title);
        ImGui.popFont();
        ImGui.sameLine();
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled(count + " · " + unit);

        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##libraryFilter", EditorIcons.SEARCH + "  Filter by number (hex)", filter);
        float min = kind == EditorTileAtlas.Kind.BLOCK ? 40 : 24;
        float max = kind == EditorTileAtlas.Kind.BLOCK ? 160 : 96;
        thumbnailSize[0] = Math.max(min, Math.min(max, thumbnailSize[0]));
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled("Size");
        ImGui.sameLine();
        ImGui.setNextItemWidth(-1);
        ImGui.sliderFloat("##thumb", thumbnailSize, min, max, "%.0f px");
        if (kind == EditorTileAtlas.Kind.PATTERN) paletteLinePicker(level);
        ImGui.spacing();

        String filterText = filter.get().trim().toLowerCase(Locale.ROOT).replace("$", "").replace("0x", "");
        float cell = thumbnailSize[0];
        float gap = 6;
        ImGui.beginChild("##libraryGrid", 0, 0, false);
        float avail = ImGui.getContentRegionAvailX();
        int columns = Math.max(1, (int) ((avail + gap) / (cell + gap)));
        ImDrawList draw = ImGui.getWindowDrawList();
        int shown = 0;
        for (int index = 0; index < count; index++) {
            String label = Integer.toHexString(index).toUpperCase(Locale.ROOT);
            if (!filterText.isEmpty() && !label.toLowerCase(Locale.ROOT).startsWith(filterText)) continue;
            if (shown % columns != 0) ImGui.sameLine(0, gap);
            shown++;
            float px = ImGui.getCursorScreenPosX(), py = ImGui.getCursorScreenPosY();
            ImGui.invisibleButton("##lib" + index, cell, cell + 14);
            boolean hovered = ImGui.isItemHovered();
            if (!ImGui.isItemVisible()) continue;
            draw.addRectFilled(px, py, px + cell, py + cell, EditorChromeTheme.CELL_BG, 4);
            EditorTileAtlas.Cell texture = atlas.cell(kind, index);
            if (texture != null) {
                draw.addImage(texture.texture(), px + 2, py + 2, px + cell - 2, py + cell - 2,
                        texture.u0(), texture.v0(), texture.u1(), texture.v1());
            }
            boolean isSelected = selected != null && selected == index;
            if (isSelected) {
                draw.addRectFilled(px, py, px + cell, py + cell, EditorChromeTheme.SELECTION_FILL, 4);
                draw.addRect(px - 1, py - 1, px + cell + 1, py + cell + 1, EditorChromeTheme.SELECTION, 5, 0, 2);
            } else if (hovered) {
                draw.addRect(px, py, px + cell, py + cell, EditorChromeTheme.ACCENT, 4, 0, 1.5f);
            }
            draw.addText(px + 2, py + cell + 1, isSelected ? EditorChromeTheme.SELECTION : EditorChromeTheme.TEXT_DIM, "$" + label);
            if (hovered) libraryTooltip(kind, index, level);
            if (ImGui.isItemClicked(ImGuiMouseButton.Left)) selectLibrary(controller, kind, index);
        }
        if (shown == 0) ImGui.textDisabled("Nothing matches \"" + filter.get() + "\"");
        ImGui.endChild();
    }

    private void paletteLinePicker(MutableLevel level) {
        ImGui.textDisabled("Preview palette");
        for (int line = 0; line < Math.min(4, level.getPaletteCount()); line++) {
            ImGui.sameLine(0, line == 0 ? 8 : 4);
            boolean active = line == patternPaletteLine;
            float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
            float size = ImGui.getFrameHeight();
            if (ImGui.invisibleButton("##line" + line, size * 1.6f, size)) patternPaletteLine = line;
            ImDrawList draw = ImGui.getWindowDrawList();
            float w = size * 1.6f;
            for (int i = 0; i < 4; i++) {
                draw.addRectFilled(x + 2 + i * (w - 4) / 4, y + 4, x + 2 + (i + 1) * (w - 4) / 4, y + size - 4,
                        EditorTileAtlas.paletteColor(level, line, 1 + i * 4));
            }
            draw.addRect(x, y + 2, x + w, y + size - 2, active ? EditorChromeTheme.SELECTION : EditorChromeTheme.GRID_LINE,
                    3, 0, active ? 2 : 1);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Palette line " + line);
        }
    }

    private void libraryTooltip(EditorTileAtlas.Kind kind, int index, MutableLevel level) {
        ImGui.beginTooltip();
        EditorTileAtlas.Cell texture = atlas.cell(kind, index);
        float size = switch (kind) {
            case BLOCK -> Math.min(256, level.getBlockPixelSize() * 2f);
            case CHUNK -> 128;
            case PATTERN -> 96;
        };
        if (texture != null) {
            float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
            ImGui.getWindowDrawList().addRectFilled(x, y, x + size, y + size, EditorChromeTheme.CELL_BG, 4);
            ImGui.image(texture.texture(), size, size, texture.u0(), texture.v0(), texture.u1(), texture.v1());
        }
        String name = switch (kind) {
            case BLOCK -> "Block";
            case CHUNK -> "Chunk";
            case PATTERN -> "Pattern";
        };
        ImGui.text(name + " $" + Integer.toHexString(index).toUpperCase(Locale.ROOT) + "  (" + index + ")");
        ImGui.textDisabled("Click to use as the " + name.toLowerCase(Locale.ROOT) + " brush");
        ImGui.endTooltip();
    }

    private static void selectLibrary(LevelEditorController controller, EditorTileAtlas.Kind kind, int index) {
        switch (kind) {
            case BLOCK -> controller.selectBlock(index);
            case CHUNK -> controller.selectChunk(index);
            case PATTERN -> controller.selectPattern(index);
        }
    }

    private void drawObjectLibrary(LevelEditorController controller) {
        EditorStockObjectPalette palette = controller.objectPalette();
        ImGui.pushFont(host.titleFont(), EditorChromeHost.TITLE_SIZE);
        ImGui.text("Objects");
        ImGui.popFont();
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##objectFilter", EditorIcons.SEARCH + "  Filter objects", filter);
        String query = filter.get().trim().toLowerCase(Locale.ROOT);
        ImGui.beginChild("##objects", 0, 0, false);
        for (var entry : palette.entries()) {
            if (!query.isEmpty() && !entry.label().toLowerCase(Locale.ROOT).contains(query)) continue;
            boolean selected = entry.objectKey() != null
                    ? entry.objectKey().equals(palette.selectedObjectKey())
                    : !palette.selectedIsKeyed() && entry.stockObjectId() != null && entry.stockObjectId() == palette.selectedObjectId();
            String id = entry.stockObjectId() != null ? String.format("$%02X", entry.stockObjectId()) : "mod";
            if (ImGui.selectable(EditorIcons.SHAPES + "  " + entry.label() + "##" + id + entry.label(), selected)) {
                if (entry.objectKey() != null) palette.setObjectKey(entry.objectKey());
                else palette.setObjectId(entry.stockObjectId());
            }
            ImGui.sameLine(ImGui.getContentRegionMaxX() - ImGui.calcTextSizeX(id));
            ImGui.textDisabled(id);
        }
        ImGui.endChild();
    }

    // ---------------------------------------------------------------- inspector

    private void drawInspector(Frame frame, MutableLevel level) {
        LevelEditorController controller = frame.controller();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 14, 12);
        if (ImGui.begin(INSPECTOR) && level != null) {
            if (controller.spawnEditMode() == EditorSpawnEditMode.OBJECTS) {
                objectInspector(frame);
            } else if (controller.spawnEditMode() == EditorSpawnEditMode.RINGS) {
                heading("Rings");
                ImGui.textWrapped(controller.selectionSummary());
            } else {
                switch (controller.depth()) {
                    case WORLD -> worldInspector(frame, level);
                    case BLOCK -> blockInspector(frame, level);
                    case CHUNK -> chunkInspector(frame, level);
                }
            }
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    private void heading(String text) {
        ImGui.spacing();
        ImGui.pushFont(host.headingFont(), EditorChromeHost.HEADING_SIZE);
        ImGui.pushStyleColor(ImGuiCol.Text, EditorChromeTheme.TEXT_DIM);
        ImGui.text(text.toUpperCase(Locale.ROOT));
        ImGui.popStyleColor();
        ImGui.popFont();
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY() - 3;
        ImGui.getWindowDrawList().addLine(x, y, x + ImGui.getContentRegionAvailX(), y, EditorChromeTheme.GRID_LINE);
        ImGui.spacing();
    }

    private void preview(EditorTileAtlas.Kind kind, Integer index, float size) {
        float x = ImGui.getCursorScreenPosX(), y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList().addRectFilled(x, y, x + size, y + size, EditorChromeTheme.CELL_BG, 6);
        EditorTileAtlas.Cell cell = index == null ? null : atlas.cell(kind, index);
        if (cell != null) {
            ImGui.getWindowDrawList().addImage(cell.texture(), x + 4, y + 4, x + size - 4, y + size - 4,
                    cell.u0(), cell.v0(), cell.u1(), cell.v1());
        }
        ImGui.dummy(size, size);
    }

    private void worldInspector(Frame frame, MutableLevel level) {
        LevelEditorController controller = frame.controller();
        heading("Block brush");
        preview(EditorTileAtlas.Kind.BLOCK, controller.selectedBlockIndex(), 112);
        ImGui.sameLine(0, 12);
        ImGui.beginGroup();
        ImGui.pushFont(host.titleFont(), EditorChromeHost.TITLE_SIZE);
        ImGui.text("Block " + hex(controller.selectedBlockIndex()));
        ImGui.popFont();
        ImGui.textDisabled(level.getBlockPixelSize() + " x " + level.getBlockPixelSize() + " px");
        ImGui.textDisabled("Click or drag in the level to paint");
        ImGui.textDisabled("Right-click picks a block");
        ImGui.endGroup();

        heading("Cursor");
        var cursor = controller.worldCursor();
        int blockPx = level.getBlockPixelSize();
        int mapX = Math.max(0, Math.min(level.getMap().getWidth() - 1, cursor.x() / blockPx));
        int mapY = Math.max(0, Math.min(level.getMap().getHeight() - 1, cursor.y() / blockPx));
        int under = Byte.toUnsignedInt(level.getMap().getValue(controller.activeLayer(), mapX, mapY));
        if (ImGui.beginTable("##cursor", 2)) {
            row("Position", cursor.x() + ", " + cursor.y());
            row("Map cell", mapX + ", " + mapY + "  of " + level.getMap().getWidth() + " x " + level.getMap().getHeight());
            row("Layer", controller.activeLayer() == 0 ? "Foreground" : "Background");
            row("Block here", hex(under));
            ImGui.endTable();
        }
        preview(EditorTileAtlas.Kind.BLOCK, under, 72);
        ImGui.sameLine(0, 12);
        ImGui.beginGroup();
        if (ImGui.button(EditorIcons.BOX + "  Edit this block", -1, 0)) {
            controller.selectBlock(under);
            frame.dispatch().accept(Action.DESCEND);
        }
        if (ImGui.button(EditorIcons.PIPETTE + "  Use as brush", -1, 0)) controller.selectBlock(under);
        ImGui.endGroup();
        helpFooter("Enter edits the block under the cursor. L switches layer. Ctrl+Z undoes.");
    }

    private void blockInspector(Frame frame, MutableLevel level) {
        LevelEditorController controller = frame.controller();
        Block block = controller.selectedBlockPreview();
        if (block == null) {
            ImGui.textDisabled("No block selected");
            return;
        }
        heading("Block " + hex(controller.selection().selectedBlock()) + " composition");
        int side = block.getGridSide();
        float avail = ImGui.getContentRegionAvailX();
        float cell = (float) Math.floor(Math.min(avail, 320) / side);
        float originX = ImGui.getCursorScreenPosX(), originY = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(originX - 4, originY - 4, originX + cell * side + 4, originY + cell * side + 4,
                EditorChromeTheme.CELL_BG, 6);
        for (int cy = 0; cy < side; cy++) {
            for (int cx = 0; cx < side; cx++) {
                float x = originX + cx * cell, y = originY + cy * cell;
                ChunkDesc desc = block.getChunkDesc(cx, cy);
                EditorTileAtlas.Cell texture = atlas.cell(EditorTileAtlas.Kind.CHUNK, desc.getChunkIndex());
                if (texture != null) {
                    float u0 = desc.getHFlip() ? texture.u1() : texture.u0(), u1 = desc.getHFlip() ? texture.u0() : texture.u1();
                    float v0 = desc.getVFlip() ? texture.v1() : texture.v0(), v1 = desc.getVFlip() ? texture.v0() : texture.v1();
                    draw.addImage(texture.texture(), x, y, x + cell, y + cell, u0, v0, u1, v1);
                }
            }
        }
        for (int i = 0; i <= side; i++) {
            int line = EditorChromeTheme.rgba(0xFFFFFF, i % 4 == 0 ? 0.10f : 0.04f);
            draw.addLine(originX + i * cell, originY, originX + i * cell, originY + side * cell, line);
            draw.addLine(originX, originY + i * cell, originX + side * cell, originY + i * cell, line);
        }
        ImGui.invisibleButton("##blockGrid", cell * side, cell * side);
        compositionInput(frame, originX, originY, cell, side, EditorFocusRegion.BLOCK_PANE);
        int sx = controller.selectedBlockCellX(), sy = controller.selectedBlockCellY();
        draw.addRect(originX + sx * cell - 1, originY + sy * cell - 1, originX + (sx + 1) * cell + 1,
                originY + (sy + 1) * cell + 1, EditorChromeTheme.SELECTION, 2, 0, 2);
        ImGui.textDisabled("Click paints the chunk brush · right-click picks");

        ChunkDesc desc = controller.selectedBlockChunkDescriptor(sx, sy);
        heading("Cell " + sx + ", " + sy);
        preview(EditorTileAtlas.Kind.CHUNK, desc.getChunkIndex(), 72);
        ImGui.sameLine(0, 12);
        ImGui.beginGroup();
        ImGui.text("Chunk " + hex(desc.getChunkIndex()));
        if (ImGui.checkbox("Flip horizontal  (H)", desc.getHFlip())) frame.dispatch().accept(Action.TOGGLE_H_FLIP);
        if (ImGui.checkbox("Flip vertical  (J)", desc.getVFlip())) frame.dispatch().accept(Action.TOGGLE_V_FLIP);
        ImGui.endGroup();
        if (ImGui.beginTable("##cell", 2)) {
            row("Path A solidity", collisionLabel(desc.getPrimaryCollisionMode().name()));
            row("Path B solidity", collisionLabel(desc.getSecondaryCollisionMode().name()));
            Chunk chunk = controller.selectedBlockChunkPreview(sx, sy);
            if (chunk != null) row("Height map", hex(chunk.getSolidTileIndex()) + " / alt " + hex(chunk.getSolidTileAltIndex()));
            ImGui.endTable();
        }
        if (ImGui.button("Cycle solidity  (V)")) frame.dispatch().accept(Action.CYCLE_COLLISION_MODE);
        ImGui.sameLine();
        if (ImGui.button(EditorIcons.MINUS + "##solid")) frame.dispatch().accept(Action.DECREMENT_SOLID_TILE_INDEX);
        ImGui.sameLine(0, 4);
        if (ImGui.button(EditorIcons.PLUS + "##solid")) frame.dispatch().accept(Action.INCREMENT_SOLID_TILE_INDEX);
        ImGui.sameLine();
        ImGui.textDisabled("height map");
        ImGui.spacing();
        if (ImGui.button(EditorIcons.GRID_2X2 + "  Edit this chunk", -1, 0)) frame.dispatch().accept(Action.DESCEND);
        helpFooter("Changes apply to every placement of this block. Esc returns to the level.");
    }

    private void chunkInspector(Frame frame, MutableLevel level) {
        LevelEditorController controller = frame.controller();
        Chunk chunk = controller.selectedChunkPreview();
        if (chunk == null) {
            ImGui.textDisabled("No chunk selected");
            return;
        }
        heading("Chunk " + hex(controller.selection().selectedChunk()) + " composition");
        float avail = Math.min(ImGui.getContentRegionAvailX(), 256);
        float cell = (float) Math.floor(avail / 2);
        float originX = ImGui.getCursorScreenPosX(), originY = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(originX - 4, originY - 4, originX + cell * 2 + 4, originY + cell * 2 + 4,
                EditorChromeTheme.CELL_BG, 6);
        for (int py = 0; py < 2; py++) {
            for (int px = 0; px < 2; px++) {
                PatternDesc desc = chunk.getPatternDesc(px, py);
                // Composition cells use their own palette line, not the library preview line.
                drawPattern(draw, level, desc, originX + px * cell, originY + py * cell, cell);
            }
        }
        draw.addLine(originX + cell, originY, originX + cell, originY + 2 * cell, EditorChromeTheme.rgba(0xFFFFFF, 0.12f));
        draw.addLine(originX, originY + cell, originX + 2 * cell, originY + cell, EditorChromeTheme.rgba(0xFFFFFF, 0.12f));
        ImGui.invisibleButton("##chunkGrid", cell * 2, cell * 2);
        compositionInput(frame, originX, originY, cell, 2, EditorFocusRegion.CHUNK_PANE);
        int sx = controller.selectedChunkCellX(), sy = controller.selectedChunkCellY();
        draw.addRect(originX + sx * cell - 1, originY + sy * cell - 1, originX + (sx + 1) * cell + 1,
                originY + (sy + 1) * cell + 1, EditorChromeTheme.SELECTION, 2, 0, 2);
        ImGui.textDisabled("Click paints the pattern brush · right-click picks");

        PatternDesc desc = chunk.getPatternDesc(sx, sy);
        heading("Pattern " + hex(desc.getPatternIndex()));
        if (ImGui.checkbox("Flip horizontal  (H)", desc.getHFlip())) frame.dispatch().accept(Action.TOGGLE_H_FLIP);
        ImGui.sameLine();
        if (ImGui.checkbox("Flip vertical  (J)", desc.getVFlip())) frame.dispatch().accept(Action.TOGGLE_V_FLIP);
        if (ImGui.checkbox("High priority  (T)", desc.getPriority())) frame.dispatch().accept(Action.TOGGLE_PATTERN_PRIORITY);
        heading("Palette line  (G)");
        for (int line = 0; line < Math.min(4, level.getPaletteCount()); line++) {
            boolean active = desc.getPaletteIndex() == line;
            float swatch = (float) Math.floor((ImGui.getContentRegionAvailX() - 34) / 16);
            ImGui.alignTextToFramePadding();
            ImGui.textColored(active ? EditorChromeTheme.SELECTION : EditorChromeTheme.TEXT_DIM, String.valueOf(line));
            ImGui.sameLine(28);
            float sxStart = ImGui.getCursorScreenPosX(), syStart = ImGui.getCursorScreenPosY();
            if (ImGui.invisibleButton("##palette" + line, swatch * 16, swatch) && !active) {
                for (int step = 0; step < 4 && chunk.getPatternDesc(sx, sy).getPaletteIndex() != line; step++)
                    frame.dispatch().accept(Action.CYCLE_PATTERN_PALETTE);
            }
            for (int i = 0; i < 16; i++) {
                draw.addRectFilled(sxStart + i * swatch, syStart, sxStart + (i + 1) * swatch - 1, syStart + swatch,
                        EditorTileAtlas.paletteColor(level, line, i));
            }
            if (active) draw.addRect(sxStart - 2, syStart - 2, sxStart + swatch * 16 + 1, syStart + swatch + 2,
                    EditorChromeTheme.SELECTION, 3, 0, 2);
        }
        helpFooter("Changes apply to every block that uses this chunk. Esc returns to the block.");
    }

    private void drawPattern(ImDrawList draw, MutableLevel level, PatternDesc desc, float x, float y, float size) {
        int index = desc.getPatternIndex();
        if (index < 0 || index >= level.getPatternCount()) return;
        var pattern = level.getPattern(index);
        float pixel = size / 8f;
        for (int py = 0; py < 8; py++) {
            for (int px = 0; px < 8; px++) {
                int sx = desc.getHFlip() ? 7 - px : px, sy = desc.getVFlip() ? 7 - py : py;
                int value = pattern.getPixel(sx, sy) & 0x0F;
                if (value == 0) continue;
                draw.addRectFilled(x + px * pixel, y + py * pixel, x + (px + 1) * pixel, y + (py + 1) * pixel,
                        EditorTileAtlas.paletteColor(level, desc.getPaletteIndex(), value));
            }
        }
    }

    private static void compositionInput(Frame frame, float originX, float originY, float cell, int side,
                                         EditorFocusRegion region) {
        if (!ImGui.isItemHovered()) return;
        int cx = (int) ((ImGui.getMousePosX() - originX) / cell), cy = (int) ((ImGui.getMousePosY() - originY) / cell);
        if (cx < 0 || cy < 0 || cx >= side || cy >= side) return;
        ImGui.getWindowDrawList().addRect(originX + cx * cell, originY + cy * cell, originX + (cx + 1) * cell,
                originY + (cy + 1) * cell, EditorChromeTheme.ACCENT, 2, 0, 1.5f);
        boolean paint = ImGui.isMouseClicked(ImGuiMouseButton.Left);
        boolean pick = ImGui.isMouseClicked(ImGuiMouseButton.Right);
        if (!paint && !pick) return;
        LevelEditorController controller = frame.controller();
        controller.focusRegion(region);
        controller.selectActiveCell(cx, cy);
        frame.dispatch().accept(pick ? Action.PERFORM_EYEDROP : Action.APPLY_PRIMARY_ACTION);
    }

    private void objectInspector(Frame frame) {
        LevelEditorController controller = frame.controller();
        EditorStockObjectPalette palette = controller.objectPalette();
        if (palette != null) {
            heading("Object brush");
            ImGui.pushFont(host.titleFont(), EditorChromeHost.TITLE_SIZE);
            ImGui.text(palette.selectedLabel());
            ImGui.popFont();
            if (ImGui.beginTable("##brush", 2)) {
                row("Subtype", String.format("$%02X", palette.selectedSubtype()));
                row("Flags", "F" + palette.renderFlags());
                row("Respawn tracked", palette.respawnTracked() ? "Yes" : "No");
                ImGui.endTable();
            }
        }
        ObjectSpawn spawn = controller.selectedObjectSpawn();
        heading("Selected placement");
        if (spawn == null) {
            ImGui.textDisabled("Right-click an object in the level to select it");
        } else {
            if (ImGui.beginTable("##spawn", 2)) {
                row("Object", spawn.objectKey() != null ? spawn.objectKey() : String.format("$%02X", spawn.objectId()));
                row("Position", spawn.x() + ", " + spawn.y());
                ImGui.endTable();
            }
            ImGui.alignTextToFramePadding();
            ImGui.text(String.format("Subtype  $%02X", spawn.subtype()));
            ImGui.sameLine();
            if (ImGui.button(EditorIcons.MINUS + "##sub")) frame.dispatch().accept(Action.DECREMENT_SELECTED_SUBTYPE);
            ImGui.sameLine(0, 4);
            if (ImGui.button(EditorIcons.PLUS + "##sub")) frame.dispatch().accept(Action.INCREMENT_SELECTED_SUBTYPE);
            if (ImGui.checkbox("Flip horizontal", (spawn.renderFlags() & 1) != 0)) controller.toggleSelectedSpawnFlag(1);
            if (ImGui.checkbox("Flip vertical", (spawn.renderFlags() & 2) != 0)) controller.toggleSelectedSpawnFlag(2);
            if (ImGui.checkbox("Respawn tracked  (R)", spawn.respawnTracked())) frame.dispatch().accept(Action.TOGGLE_SELECTED_RESPAWN);
            if (ImGui.button(EditorIcons.MOVE + "  Move to cursor  (M)", -1, 0)) frame.dispatch().accept(Action.MOVE_SELECTED_SPAWN_TO_CURSOR);
            if (ImGui.button(EditorIcons.ERASER + "  Delete placement", -1, 0)) frame.dispatch().accept(Action.DELETE_SPAWN);
        }
        helpFooter("Click places the brush. Right-click selects an existing placement.");
    }

    private static void row(String label, String value) {
        ImGui.tableNextRow();
        ImGui.tableNextColumn();
        ImGui.textDisabled(label);
        ImGui.tableNextColumn();
        ImGui.text(value);
    }

    private static void helpFooter(String text) {
        ImGui.spacing();
        ImGui.spacing();
        ImGui.pushStyleColor(ImGuiCol.Text, EditorChromeTheme.TEXT_DIM);
        ImGui.textWrapped(EditorIcons.INFO + "  " + text);
        ImGui.popStyleColor();
    }

    private static String collisionLabel(String mode) {
        return switch (mode) {
            case "NO_COLLISION" -> "None";
            case "TOP_SOLID" -> "Top only";
            case "LEFT_RIGHT_BOTTOM_SOLID" -> "Sides and bottom";
            case "ALL_SOLID" -> "Solid";
            default -> mode;
        };
    }

    // ---------------------------------------------------------------- status bar

    private void drawStatusBar(Frame frame, float x, float y, float width) {
        LevelEditorController controller = frame.controller();
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, STATUS_HEIGHT);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 5);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorChromeTheme.rgba(0x12141A, 1f));
        if (ImGui.begin("##status", FIXED_WINDOW)) {
            String status = controller.statusText();
            boolean warning = status != null && (status.contains("first") || status.contains("cannot")
                    || status.contains("Cannot") || status.contains("unsupported"));
            ImGui.textColored(warning ? EditorChromeTheme.WARNING : EditorChromeTheme.TEXT_DIM,
                    warning ? EditorIcons.ALERT : EditorIcons.INFO);
            ImGui.sameLine();
            ImGui.text(status == null || status.isBlank() ? "Ready" : status);
            var cursor = controller.worldCursor();
            String mode = switch (controller.spawnEditMode()) {
                case TILES -> "Terrain";
                case OBJECTS -> "Objects";
                case RINGS -> "Rings";
            };
            String right = "X " + cursor.x() + "   Y " + cursor.y() + "     "
                    + (controller.activeLayer() == 0 ? "Foreground" : "Background") + "     " + mode;
            ImGui.sameLine(ImGui.getWindowWidth() - ImGui.calcTextSizeX(right) - 14);
            ImGui.textDisabled(right);
        }
        ImGui.end();
        ImGui.popStyleColor();
        ImGui.popStyleVar();
    }

    // ---------------------------------------------------------------- command palette

    void openCommandPalette() {
        commandPaletteOpen = true;
        commandPaletteFocus = true;
    }

    void closeCommandPalette() {
        commandPaletteOpen = false;
        commandFilter.set("");
    }

    boolean isCommandPaletteOpen() { return commandPaletteOpen; }

    private void drawCommandPalette(Frame frame) {
        EditorCommandPalette palette = EditorCommandPalette.forController(frame.controller());
        if (!commandPaletteOpen) return;
        ImGuiViewport viewport = ImGui.getMainViewport();
        float width = Math.min(560, viewport.getSizeX() - 80);
        ImGui.setNextWindowPos(viewport.getPosX() + (viewport.getSizeX() - width) / 2, viewport.getPosY() + 90);
        ImGui.setNextWindowSize(width, 0);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10, 10);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, EditorChromeTheme.rgba(0x1D2128, 1f));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 1);
        if (ImGui.begin("##commands", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoDocking
                | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            if (commandPaletteFocus) {
                ImGui.setKeyboardFocusHere();
                commandPaletteFocus = false;
            }
            ImGui.setNextItemWidth(-1);
            boolean submitted = ImGui.inputTextWithHint("##commandFilter", EditorIcons.SEARCH + "  Type a command",
                    commandFilter, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue);
            String query = commandFilter.get().trim().toLowerCase(Locale.ROOT);
            Runnable first = null;
            int shown = 0;
            ImGui.beginChild("##commandList", 0, 300, false);
            // The engine catalogue contains editing actions shared by both editor hosts.
            var commands = new java.util.ArrayList<>(palette.actionCommands(frame.dispatch()));
            commands.addAll(frame.commands());
            for (var command : commands) {
                if (!query.isEmpty() && !command.label().toLowerCase(Locale.ROOT).contains(query)) continue;
                if (first == null) first = command.action();
                if (ImGui.selectable(command.label(), shown == 0)) {
                    closeCommandPalette();
                    command.action().run();
                }
                shown++;
            }
            ImGui.endChild();
            if (submitted && first != null) {
                closeCommandPalette();
                first.run();
            }
            if (ImGui.isKeyPressed(ImGuiKey.Escape)) closeCommandPalette();
        }
        ImGui.end();
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    private static String hex(Integer value) {
        return value == null ? "-" : "$" + Integer.toHexString(value).toUpperCase(Locale.ROOT);
    }

    @Override
    public void close() {
        atlas.close();
    }
}
