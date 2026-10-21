package com.openggf.editor.desktop;

import com.openggf.stageplay.StageplayDocument;
import com.openggf.editor.stageplay.StageplayWorkspace;
import com.openggf.editor.stageplay.StageplayWorkspace.Action;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Desktop presentation of the engine-owned Stageplay authoring workspace. */
final class StageplayChromeShell {
    record Frame(StageplayWorkspace workspace, Consumer<Runnable> queue,
                 int gameX, int gameY, int gameWidth, int gameHeight, int gameScale) {
        void invoke(Action action) { queue.accept(() -> workspace.invoke(action)); }
        void seek(int frame) { queue.accept(() -> workspace.seek(frame)); }
        void select(String id) { queue.accept(() -> workspace.selectActor(id)); }
    }
    record CueRow(int frame, String track, String detail, String actorId) { }

    private static final String CAST = "Cast##stageplay";
    private static final String INSPECTOR = "Direction##stageplay";
    private static final String TIMELINE = "Timeline##stageplay";
    private static final int FIXED_WINDOW = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoSavedSettings
            | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;
    private static final float TOOLBAR_HEIGHT = 48;
    private static final float STATUS_HEIGHT = 28;
    private static final int ROW_PAGE_SIZE = 64;

    private final EditorChromeHost host;
    private final ImString filter = new ImString(128);
    private boolean layoutBuilt;
    private boolean commandPalette;
    private boolean focusPalette;
    private int cuePage;
    private StageplayDocument rowDocument;
    private List<CueRow> cachedRows = List.of();
    private EditorChromeShell.Area canvas = new EditorChromeShell.Area(0, 0, 1, 1);

    StageplayChromeShell(EditorChromeHost host) { this.host = host; }

    /** Prefer crisp integer upscaling, but fit scenes larger than the authoring canvas. */
    static int[] fitPreview(int x, int y, int width, int height, int logicalWidth, int logicalHeight) {
        if (width >= logicalWidth && height >= logicalHeight)
            return EditorChromeHost.fitIntegerViewport(x, y, width, height, logicalWidth, logicalHeight);
        double fit = Math.min(width / (double) Math.max(1, logicalWidth), height / (double) Math.max(1, logicalHeight));
        int w = Math.max(1, (int) (logicalWidth * fit)), h = Math.max(1, (int) (logicalHeight * fit));
        return new int[] {x + (width - w) / 2, y + (height - h) / 2, w, h, 0};
    }

    EditorChromeShell.Area draw(Frame frame) {
        ImGui.pushFont(host.bodyFont(), EditorChromeHost.BODY_SIZE);
        try {
            ImGui.beginDisabled(frame.workspace.recording());
            float menuHeight;
            try { menuHeight = menu(frame); }
            finally { ImGui.endDisabled(); }
            var viewport = ImGui.getMainViewport();
            float x = viewport.getPosX(), y = viewport.getPosY() + menuHeight;
            float width = viewport.getSizeX(), height = viewport.getSizeY() - menuHeight;
            toolbar(frame, x, y, width);
            dockspace(x, y + TOOLBAR_HEIGHT, width, height - TOOLBAR_HEIGHT - STATUS_HEIGHT);
            ImGui.beginDisabled(frame.workspace.recording());
            try {
                cast(frame);
                inspector(frame);
                timeline(frame);
            } finally { ImGui.endDisabled(); }
            canvasFrame(frame);
            status(frame, x, y + height - STATUS_HEIGHT, width);
            commands(frame);
        } finally {
            ImGui.popFont();
        }
        return canvas;
    }

    private float menu(Frame frame) {
        if (!ImGui.beginMainMenuBar()) return 0;
        try {
            float height = ImGui.getWindowHeight();
            if (ImGui.beginMenu("File")) {
                try {
                    menuItem(frame, Action.NEW, "");
                    menuItem(frame, Action.OPEN, "");
                    ImGui.separator();
                    menuItem(frame, Action.SAVE, "Ctrl+S");
                    menuItem(frame, Action.SAVE_AS, "Ctrl+Shift+S");
                    menuItem(frame, Action.EXPORT, "");
                    ImGui.separator();
                    if (ImGui.menuItem("Level editor", "F4")) frame.queue.accept(frame.workspace::deactivate);
                } finally { ImGui.endMenu(); }
            }
            if (ImGui.beginMenu("Edit")) {
                try {
                    if (ImGui.menuItem("Undo", "Ctrl+Z", false, frame.workspace.controller().canUndo())) frame.invoke(Action.UNDO);
                    if (ImGui.menuItem("Redo", "Ctrl+Y", false, frame.workspace.controller().canRedo())) frame.invoke(Action.REDO);
                    ImGui.separator();
                    menuItem(frame, Action.SCENE, "");
                } finally { ImGui.endMenu(); }
            }
            if (ImGui.beginMenu("Direct")) {
                try {
                    for (Action action : List.of(Action.ADD_ACTOR, Action.EDIT_ACTOR, Action.DELETE_ACTOR,
                            Action.SET_KEY, Action.DELETE_KEY, Action.CAMERA_KEY, Action.CAPTION, Action.AUDIO, Action.RECORD))
                        menuItem(frame, action, "");
                } finally { ImGui.endMenu(); }
            }
            if (ImGui.beginMenu("View")) {
                try {
                    if (ImGui.menuItem("All Stageplay commands", "F1")) toggleCommandPalette();
                    if (ImGui.menuItem("Reset panel layout")) layoutBuilt = false;
                } finally { ImGui.endMenu(); }
            }
            String state = frame.workspace.controller().dirty() ? "Unsaved changes"
                    : frame.workspace.path() == null ? "New scene" : "Saved";
            ImGui.setCursorPosX(Math.max(ImGui.getCursorPosX(), ImGui.getWindowWidth() - ImGui.calcTextSizeX(state) - 18));
            ImGui.textColored(frame.workspace.controller().dirty() ? EditorChromeTheme.UNSAVED : EditorChromeTheme.SAVED, state);
            return height;
        } finally { ImGui.endMainMenuBar(); }
    }

    private static void menuItem(Frame frame, Action action, String shortcut) {
        if (ImGui.menuItem(action.label(), shortcut)) frame.invoke(action);
    }

    private void toolbar(Frame frame, float x, float y, float width) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, TOOLBAR_HEIGHT);
        if (ImGui.begin("##stageplayToolbar", FIXED_WINDOW)) {
            ImGui.textColored(EditorChromeTheme.SELECTION, "STAGEPLAY");
            ImGui.sameLine();
            ImGui.beginDisabled(frame.workspace.recording());
            if (ImGui.button("Level editor  F4")) frame.queue.accept(frame.workspace::deactivate);
            ImGui.sameLine(0, 20);
            if (ImGui.button(frame.workspace.controller().playing() ? "Pause" : "Play")) frame.invoke(Action.PLAY);
            ImGui.endDisabled();
            ImGui.sameLine();
            if (ImGui.button("Stop")) frame.invoke(Action.STOP);
            ImGui.sameLine();
            if (ImGui.button(frame.workspace.recording() ? "Finish take" : "Record directed take"))
                frame.invoke(frame.workspace.recording() ? Action.STOP : Action.RECORD);
            if (ImGui.isItemHovered()) ImGui.setTooltip("Record a baked visual performance with WASD. This is directed motion, not physics input replay.");
            ImGui.sameLine(0, 20);
            ImGui.textDisabled(frame.workspace.controller().document().name());
        }
        ImGui.end();
    }

    private void dockspace(float x, float y, float width, float height) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, Math.max(1, height));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.begin("##stageplayDockHost", FIXED_WINDOW | ImGuiWindowFlags.NoBackground);
        ImGui.popStyleVar();
        try {
            int dockId = ImGui.getID("StageplayDock");
            if (!layoutBuilt) {
                imgui.internal.ImGui.dockBuilderRemoveNode(dockId);
                imgui.internal.ImGui.dockBuilderAddNode(dockId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
                imgui.internal.ImGui.dockBuilderSetNodeSize(dockId, Math.max(1, width), Math.max(1, height));
                var left = new ImInt(); var right = new ImInt(); var rest = new ImInt(); var center = new ImInt(); var bottom = new ImInt();
                imgui.internal.ImGui.dockBuilderSplitNode(dockId, ImGuiDir.Down, .28f, bottom, rest);
                imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Left, Math.min(.25f, 245f / Math.max(1, width)), left, center);
                imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Right, Math.min(.35f, 310f / Math.max(1, width - 245)), right, rest);
                imgui.internal.ImGui.dockBuilderDockWindow(CAST, left.get());
                imgui.internal.ImGui.dockBuilderDockWindow(INSPECTOR, right.get());
                imgui.internal.ImGui.dockBuilderDockWindow(TIMELINE, bottom.get());
                imgui.internal.ImGui.dockBuilderFinish(dockId);
                layoutBuilt = true;
            }
            ImGui.dockSpace(dockId, 0, 0, ImGuiDockNodeFlags.PassthruCentralNode);
            var central = imgui.internal.ImGui.dockBuilderGetCentralNode(dockId);
            if (central != null && central.getSizeX() > 0)
                canvas = new EditorChromeShell.Area(central.getPosX(), central.getPosY(), central.getSizeX(), central.getSizeY());
            else canvas = new EditorChromeShell.Area(x, y, width, height);
        } finally { ImGui.end(); }
    }

    private void cast(Frame frame) {
        if (ImGui.begin(CAST)) {
            ImGui.textDisabled("ACTORS");
            ImGui.separator();
            if (ImGui.button("Add actor", -1, 0)) frame.invoke(Action.ADD_ACTOR);
            var actors = frame.workspace.controller().document().actors();
            if (actors.isEmpty()) ImGui.textWrapped("Cast a character, move the playhead, then set a movement or pose key.");
            for (var actor : actors) {
                ImGui.pushID(actor.id());
                try {
                    if (ImGui.selectable(actor.name(), actor.id().equals(frame.workspace.controller().selectedActorId()))) frame.select(actor.id());
                    ImGui.textDisabled(actor.gameId().toUpperCase(Locale.ROOT) + " / " + actor.character() + " / " + actor.keys().size() + " keys");
                    ImGui.spacing();
                } finally { ImGui.popID(); }
            }
            ImGui.separator();
            if (ImGui.button("Scene and set", -1, 0)) frame.invoke(Action.SCENE);
            if (ImGui.button("Camera key", -1, 0)) frame.invoke(Action.CAMERA_KEY);
            if (ImGui.button("Caption", -1, 0)) frame.invoke(Action.CAPTION);
            if (ImGui.button("Sound, music or voice", -1, 0)) frame.invoke(Action.AUDIO);
        }
        ImGui.end();
    }

    private void inspector(Frame frame) {
        if (ImGui.begin(INSPECTOR)) {
            var controller = frame.workspace.controller();
            var actor = controller.document().actors().stream()
                    .filter(value -> value.id().equals(controller.selectedActorId())).findFirst().orElse(null);
            if (actor == null) {
                ImGui.textWrapped("Select an actor to direct its movement, animation and poses.");
            } else {
                ImGui.textColored(EditorChromeTheme.SELECTION, actor.name());
                ImGui.textDisabled(actor.gameId() + " / " + actor.character());
                ImGui.separator();
                StageplayDocument.ActorKey key = actor.keys().getFirst();
                for (var candidate : actor.keys()) if (candidate.frame() <= controller.frame()) key = candidate; else break;
                ImGui.text("Latest key: frame " + key.frame());
                ImGui.text("Position: " + coordinate(key.x()) + ", " + coordinate(key.y()));
                ImGui.text(key.pose() >= 0 ? "Fixed pose: " + key.pose() : "Animation: " + key.animation());
                ImGui.text("Facing: " + (key.flipX() ? "left" : "right"));
                ImGui.text("Visible: " + key.visible() + "  Scale: " + coordinate(key.scale()));
                ImGui.text("Transition: " + key.interpolation());
                ImGui.spacing();
                if (ImGui.button("Set / edit key at playhead", -1, 0)) frame.invoke(Action.SET_KEY);
                if (ImGui.button("Delete key at playhead", -1, 0)) frame.invoke(Action.DELETE_KEY);
                if (ImGui.button("Actor properties", -1, 0)) frame.invoke(Action.EDIT_ACTOR);
                if (ImGui.button("Remove actor", -1, 0)) frame.invoke(Action.DELETE_ACTOR);
            }
            ImGui.separator();
            ImGui.textWrapped("Select a key or cue in the timeline to seek. Direction forms are shared with the in-engine editor.");
            if (frame.workspace.recording()) ImGui.textColored(EditorChromeTheme.WARNING, "Recording: WASD move / Space stop");
        }
        ImGui.end();
    }

    private static String coordinate(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    private void timeline(Frame frame) {
        if (ImGui.begin(TIMELINE)) {
            var controller = frame.workspace.controller();
            int[] playhead = {controller.frame()};
            if (ImGui.button("|<")) frame.seek(0);
            ImGui.sameLine();
            if (ImGui.button("<")) frame.seek(controller.frame() - 1);
            ImGui.sameLine();
            if (ImGui.button(">")) frame.seek(controller.frame() + 1);
            ImGui.sameLine();
            ImGui.setNextItemWidth(Math.max(120, ImGui.getContentRegionAvailX() - 170));
            if (ImGui.sliderInt("##stageplayFrame", playhead, 0, controller.document().durationFrames() - 1, "Frame %d")) frame.seek(playhead[0]);
            ImGui.sameLine();
            ImGui.textDisabled(controller.document().durationFrames() + " frames / 60 fps");
            List<CueRow> rows = cueRows(controller.document());
            int lastPage = Math.max(0, (rows.size() - 1) / ROW_PAGE_SIZE);
            cuePage = Math.clamp(cuePage, 0, lastPage);
            if (lastPage > 0) {
                if (ImGui.button("Previous keys")) cuePage = Math.max(0, cuePage - 1);
                ImGui.sameLine();
                if (ImGui.button("Next keys")) cuePage = Math.min(lastPage, cuePage + 1);
                ImGui.sameLine();
                ImGui.textDisabled("Page " + (cuePage + 1) + " / " + (lastPage + 1));
            }
            if (ImGui.beginChild("##stageplayKeys", 0, 0, false)) {
                for (int index = cuePage * ROW_PAGE_SIZE; index < Math.min(rows.size(), (cuePage + 1) * ROW_PAGE_SIZE); index++) {
                    CueRow row = rows.get(index);
                    String label = row.frame() + "   " + row.track() + "   " + row.detail() + "##stageplayCue" + index;
                    if (ImGui.selectable(label, controller.frame() == row.frame()
                            && (row.actorId().isEmpty() || controller.selectedActorId().equals(row.actorId())))) {
                        if (!row.actorId().isEmpty()) frame.select(row.actorId());
                        frame.seek(row.frame());
                    }
                }
            }
            ImGui.endChild();
        }
        ImGui.end();
    }

    List<CueRow> cueRows(StageplayDocument document) {
        if (rowDocument == document) return cachedRows;
        var rows = new ArrayList<CueRow>();
        for (var actor : document.actors()) for (var key : actor.keys())
            rows.add(new CueRow(key.frame(), actor.name(), key.pose() >= 0 ? "Pose " + key.pose() : "Animation " + key.animation(), actor.id()));
        for (var key : document.camera()) rows.add(new CueRow(key.frame(), "Camera", key.interpolation().name(), ""));
        for (var caption : document.captions()) rows.add(new CueRow(caption.startFrame(), "Caption", caption.text().replace('\n', ' '), ""));
        for (var cue : document.audio()) rows.add(new CueRow(cue.frame(), "Audio", cue.kind().name(), ""));
        rows.sort(Comparator.comparingInt(CueRow::frame));
        rowDocument = document;
        cachedRows = List.copyOf(rows);
        return cachedRows;
    }

    private void canvasFrame(Frame frame) {
        var draw = ImGui.getBackgroundDrawList();
        float x = canvas.x(), y = canvas.y(), right = x + canvas.width(), bottom = y + canvas.height();
        float gx = frame.gameX, gy = frame.gameY, gr = gx + frame.gameWidth, gb = gy + frame.gameHeight;
        draw.addRectFilled(x, y, right, gy, EditorChromeTheme.CANVAS_BG);
        draw.addRectFilled(x, gb, right, bottom, EditorChromeTheme.CANVAS_BG);
        draw.addRectFilled(x, gy, gx, gb, EditorChromeTheme.CANVAS_BG);
        draw.addRectFilled(gr, gy, right, gb, EditorChromeTheme.CANVAS_BG);
        draw.addRect(gx - 1, gy - 1, gr + 1, gb + 1, EditorChromeTheme.GRID_LINE);
        draw.addText(gx, gb + 5, EditorChromeTheme.TEXT_DIM, "Stage preview / "
                + (frame.gameScale == 0 ? "fit" : frame.gameScale + "x") + " / frame " + frame.workspace.controller().frame());
    }

    private static void status(Frame frame, float x, float y, float width) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, STATUS_HEIGHT);
        if (ImGui.begin("##stageplayStatus", FIXED_WINDOW)) {
            String path = frame.workspace.path() == null ? "Unsaved scene" : frame.workspace.path().getFileName().toString();
            ImGui.textDisabled(path + "   /   " + frame.workspace.status());
        }
        ImGui.end();
    }

    boolean isCommandPaletteOpen() { return commandPalette; }
    void closeCommandPalette() { commandPalette = false; filter.set(""); }
    void toggleCommandPalette() {
        commandPalette = !commandPalette;
        focusPalette = commandPalette;
        if (!commandPalette) filter.set("");
    }

    private void commands(Frame frame) {
        if (!commandPalette) return;
        var viewport = ImGui.getMainViewport();
        ImGui.setNextWindowPos(viewport.getPosX() + viewport.getSizeX() / 2 - 250, viewport.getPosY() + 90);
        ImGui.setNextWindowSize(500, Math.min(500, viewport.getSizeY() - 120));
        if (ImGui.begin("Stageplay commands", ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoCollapse)) {
            if (focusPalette) { ImGui.setKeyboardFocusHere(); focusPalette = false; }
            boolean submitted = ImGui.inputTextWithHint("##stageplayCommand", "Find a command", filter,
                    imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue);
            String query = filter.get().strip().toLowerCase(Locale.ROOT);
            Action first = null;
            for (Action action : Action.values()) {
                if (!action.label().toLowerCase(Locale.ROOT).contains(query)) continue;
                if (first == null) first = action;
                if (ImGui.selectable(action.label())) { frame.invoke(action); closeCommandPalette(); break; }
            }
            if (submitted && first != null && commandPalette) { frame.invoke(first); closeCommandPalette(); }
            if (ImGui.button("Close  F1 / Esc")) closeCommandPalette();
        }
        ImGui.end();
    }
}
