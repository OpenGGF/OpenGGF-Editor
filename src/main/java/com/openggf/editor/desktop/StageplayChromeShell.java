package com.openggf.editor.desktop;

import com.openggf.stageplay.StageplayDocument;
import com.openggf.stageplay.StageplayDialogue;
import com.openggf.stageplay.StageplayTimeline;
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
final class StageplayChromeShell implements com.openggf.stageplay.editor.StageplayRouteOverlay.Painter {
    @Override public void line(float x1, float y1, float x2, float y2, int rgb, float width) {
        ImGui.getBackgroundDrawList().addLine(x1, y1, x2, y2, color(rgb), width);
    }
    private static int color(int rgb) {
        return 0xFF000000 | (rgb & 255) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 255);
    }

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
    private static final float TOOLBAR_HEIGHT = 70;
    private static final float STATUS_HEIGHT = 28;
    private static final int ROW_PAGE_SIZE = 64;

    private final EditorChromeHost host;
    private final ImString filter = new ImString(128);
    private boolean layoutBuilt;
    private boolean compactLayout;
    private boolean commandPalette;
    private boolean focusPalette;
    private int cuePage;
    private StageplayDocument rowDocument;
    private List<CueRow> cachedRows = List.of();
    private EditorChromeShell.Area canvas = new EditorChromeShell.Area(0, 0, 1, 1);

    StageplayChromeShell(EditorChromeHost host) { this.host = host; }

    static boolean compactLayout(float width, float height) { return width < 1100 || height < 480; }

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
                    menuItem(frame, Action.IMPORT_SCREENPLAY, "");
                    menuItem(frame, Action.EXPORT_SCREENPLAY, "");
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
                    menuItem(frame, Action.FILM_FORMAT, "");
                } finally { ImGui.endMenu(); }
            }
            if (ImGui.beginMenu("Direct")) {
                try {
                    for (Action action : List.of(Action.ADD_ACTOR, Action.EDIT_ACTOR, Action.DELETE_ACTOR,
                            Action.SET_KEY, Action.PUPPET_ACTIONS, Action.ROUTE_HELPER, Action.OBJECTS, Action.DELETE_KEY, Action.CAMERA_MODE, Action.SET_CAMERA_KEY, Action.CAMERA_KEY,
                            Action.RECORD_CAMERA, Action.CAMERA_EFFECTS, Action.SCENE_CHANGES, Action.CAPTION, Action.DIALOGUE, Action.DIALOGUE_TYPERS, Action.DIALOGUE_SKINS, Action.AUDIO, Action.RECORD))
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
            if (ImGui.button(frame.workspace.recording() ? "Finish take" : frame.workspace.cameraMode() ? "Record camera R" : "Record actor R")) {
                if (frame.workspace.recording()) frame.queue.accept(frame.workspace::stop);
                else frame.invoke(frame.workspace.cameraMode() ? Action.RECORD_CAMERA : Action.RECORD);
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip("One take is one undo. Puppet: A/D move, W jump, release/S brake. Poser: WASD choreography. Camera: WASD/arrows pan. Q/E zoom.");
            ImGui.sameLine();
            ImGui.beginDisabled(frame.workspace.recording());
            if (ImGui.button(frame.workspace.cameraMode() ? "Camera mode ON  C" : "Direct camera  C")) frame.invoke(Action.CAMERA_MODE);
            ImGui.endDisabled();
            ImGui.textDisabled(frame.workspace.cameraMode()
                    ? "CAMERA: WASD / arrows pan | Q / E zoom | K capture key | R record | C exit"
                    : frame.workspace.controller().document().name() + "   |   C camera direction   |   F1 all commands");
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
            boolean compact = compactLayout(width,height);
            if (!layoutBuilt || compact != compactLayout) {
                imgui.internal.ImGui.dockBuilderRemoveNode(dockId);
                imgui.internal.ImGui.dockBuilderAddNode(dockId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
                imgui.internal.ImGui.dockBuilderSetNodeSize(dockId, Math.max(1, width), Math.max(1, height));
                var left = new ImInt(); var right = new ImInt(); var rest = new ImInt(); var center = new ImInt(); var bottom = new ImInt();
                imgui.internal.ImGui.dockBuilderSplitNode(dockId, ImGuiDir.Down, .28f, bottom, rest);
                imgui.internal.ImGui.dockBuilderSplitNode(rest.get(), ImGuiDir.Left, Math.min(.25f, 245f / Math.max(1, width)), left, center);
                imgui.internal.ImGui.dockBuilderDockWindow(CAST, left.get());
                if (compact) imgui.internal.ImGui.dockBuilderDockWindow(INSPECTOR, left.get());
                else {
                    imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Right, Math.min(.35f, 310f / Math.max(1, width - 245)), right, rest);
                    imgui.internal.ImGui.dockBuilderDockWindow(INSPECTOR, right.get());
                }
                imgui.internal.ImGui.dockBuilderDockWindow(TIMELINE, bottom.get());
                imgui.internal.ImGui.dockBuilderFinish(dockId);
                layoutBuilt = true;
                compactLayout = compact;
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
            ImGui.textDisabled(worldLabel(frame.workspace));
            ImGui.separator();
            if (ImGui.button("Add actor", -1, 0)) frame.invoke(Action.ADD_ACTOR);
            if (ImGui.button("ROM object cast / clear", -1, 0)) frame.invoke(Action.OBJECTS);
            var actors = frame.workspace.controller().document().actors();
            if (actors.isEmpty()) ImGui.textWrapped("Cast a character, place its centre near the floor, then direct a destination or jump with K.");
            for (var actor : actors) {
                ImGui.pushID(actor.id());
                try {
                    if (ImGui.selectable(actor.name(), actor.id().equals(frame.workspace.controller().selectedActorId()))) frame.select(actor.id());
                    boolean physical = frame.workspace.controller().document().puppets().stream().anyMatch(p -> p.actorId().equals(actor.id()));
                    ImGui.textDisabled(actor.gameId().toUpperCase(Locale.ROOT) + " / " + actor.character() + (physical ? " / Puppet" : " / Poser"));
                    ImGui.spacing();
                } finally { ImGui.popID(); }
            }
            for (var actor : frame.workspace.controller().document().objectActors()) {
                ImGui.pushID(actor.id());
                try {
                    if (ImGui.selectable(actor.name(), actor.id().equals(frame.workspace.controller().selectedActorId()))) frame.select(actor.id());
                    ImGui.textDisabled(actor.gameId().toUpperCase(Locale.ROOT) + " / Object");
                } finally { ImGui.popID(); }
            }
            ImGui.separator();
            if (ImGui.button("Scene and set", -1, 0)) frame.invoke(Action.SCENE);
            if (ImGui.button("Camera key", -1, 0)) frame.invoke(Action.CAMERA_KEY);
            if (ImGui.button("Camera shake / quake", -1, 0)) frame.invoke(Action.CAMERA_EFFECTS);
            if (ImGui.button("Scene changes / transitions", -1, 0)) frame.invoke(Action.SCENE_CHANGES);
            if (ImGui.button("Film format presets", -1, 0)) frame.invoke(Action.FILM_FORMAT);
            if (ImGui.button("Dialogue / speakers", -1, 0)) frame.invoke(Action.DIALOGUE);
            if (ImGui.button("Caption", -1, 0)) frame.invoke(Action.CAPTION);
            if (ImGui.button("Sound, music or voice", -1, 0)) frame.invoke(Action.AUDIO);
        }
        ImGui.end();
    }

    private void inspector(Frame frame) {
        if (ImGui.begin(INSPECTOR)) {
            var route = frame.workspace.routeOverlay();
            if (route != null) {
                ImGui.textColored(route.result().success() ? EditorChromeTheme.SELECTION : EditorChromeTheme.WARNING, route.title());
                ImGui.textColored(color(com.openggf.stageplay.editor.StageplayRouteOverlay.GROUND), "Cyan: walk");
                ImGui.sameLine();
                ImGui.textColored(color(com.openggf.stageplay.editor.StageplayRouteOverlay.AIR), "Gold: air / takeoff");
                ImGui.textWrapped("Square: start   Diamond: goal");
                ImGui.textWrapped(route.applied() ? "White cross: actual position" : "Red cross: stopped attempt");
                ImGui.spacing();
                ImGui.textWrapped(route.motionSummary());
                ImGui.textWrapped(route.reason());
                ImGui.separator();
            }
            var controller = frame.workspace.controller();
            var actor = controller.document().actors().stream()
                    .filter(value -> value.id().equals(controller.selectedActorId())).findFirst().orElse(null);
            if (frame.workspace.cameraMode()) {
                var camera = frame.workspace.directorCamera();
                ImGui.textColored(EditorChromeTheme.SELECTION, "CAMERA DIRECTION");
                ImGui.text("Position: " + coordinate(camera.x()) + ", " + coordinate(camera.y()));
                ImGui.text("Zoom: " + coordinate(camera.zoom()));
                ImGui.textWrapped("WASD / arrows pan. Q / E zoom around the film centre. Shift moves faster. K captures framing; camera keys animate pans and zoom with their easing. Edit a key to follow one actor or frame a group.");
                if (ImGui.button("Capture camera key  K", -1, 0)) frame.invoke(Action.SET_CAMERA_KEY);
                if (ImGui.button("Edit / remove camera key", -1, 0)) frame.invoke(Action.CAMERA_KEY);
                if (ImGui.button("Record camera take  R", -1, 0)) frame.invoke(Action.RECORD_CAMERA);
                if (ImGui.button("Shake / quake effects", -1, 0)) frame.invoke(Action.CAMERA_EFFECTS);
                ImGui.textWrapped("Framing is a preview until captured. Seeking discards unkeyed framing. Shake is layered over the base camera.");
            } else if (actor == null) {
                ImGui.textWrapped("Select an actor: Puppet directs native movement and jumps; Poser controls position, animation and frames.");
            } else {
                ImGui.textColored(EditorChromeTheme.SELECTION, actor.name());
                ImGui.textDisabled(actor.gameId() + " / " + actor.character());
                ImGui.separator();
                boolean physical = frame.workspace.selectedActorIsPuppet();
                ImGui.textWrapped(frame.workspace.selectedActorStatus());
                var world = frame.workspace.previewFrame().world();
                if (world != null) world.actors().stream().filter(a -> a.actorId().equals(actor.id())).findFirst()
                        .ifPresent(state -> ImGui.textWrapped(liveActorStatus(state)));
                if (physical) {
                    ImGui.textColored(EditorChromeTheme.SELECTION, "PUPPET / NATIVE PHYSICS");
                    ImGui.textWrapped("Direct movement, jumps and native abilities. Target a placed object, or wait for contact, arrival or recovery. Goals can be blocked or time out.");
                    ImGui.textWrapped("Placement applies at shot boundaries. Actor properties can disable floor snapping or choose Poser choreography.");
                } else {
                    ImGui.textColored(EditorChromeTheme.SELECTION, "POSER / POSITION AND POSE");
                    StageplayDocument.ActorKey key = actor.keys().getFirst();
                    for (var candidate : actor.keys()) if (candidate.frame() <= controller.frame()) key = candidate; else break;
                    ImGui.text("Latest key: frame " + key.frame());
                    ImGui.text(key.pose() >= 0 ? "Fixed pose: " + key.pose() : "Animation: " + key.animation());
                    ImGui.text("Facing: " + (key.flipX() ? "left" : "right"));
                    ImGui.text("Visible: " + key.visible() + "  Scale: " + coordinate(key.scale()));
                    ImGui.text("Transition: " + key.interpolation());
                }
                ImGui.spacing();
                if (ImGui.button(physical ? "Direct native actions  K" : "Set / edit key at playhead", -1, 0)) frame.invoke(Action.SET_KEY);
                if (ImGui.button(physical ? "Delete action at playhead" : "Delete key at playhead", -1, 0)) frame.invoke(Action.DELETE_KEY);
                if (ImGui.button("Actor properties", -1, 0)) frame.invoke(Action.EDIT_ACTOR);
                if (ImGui.button("Remove actor", -1, 0)) frame.invoke(Action.DELETE_ACTOR);
            }
            ImGui.separator();
            ImGui.textWrapped("Select a key or cue in the timeline to seek. Direction forms are shared with the in-engine editor.");
            if (frame.workspace.recording()) ImGui.textColored(EditorChromeTheme.WARNING,
                    frame.workspace.recordingCamera() ? "Camera take: pan / zoom / Space stop"
                            : frame.workspace.recordingPuppet() ? "Puppet take: A/D move, W jump, release/S brake, Space end"
                            : "Poser take: WASD move / Space stop");
        }
        ImGui.end();
    }

    private static String coordinate(double value) { return String.format(Locale.ROOT, "%.2f", value); }

    static String worldLabel(StageplayWorkspace workspace) {
        return "Native world / objects and rings";
    }

    static String liveActorStatus(StageplayTimeline.LiveActor actor) {
        return "Shared rings: " + actor.rings() + (actor.ringDelta() == 0 ? "" : " (" + (actor.ringDelta() > 0 ? "+" : "") + actor.ringDelta() + ")")
                + " | " + (actor.dead() ? "Dead" : actor.hurt() ? "Hurt" : "Ready")
                + "\nAbility: " + actor.ability() + " / " + actor.abilityState() + " / " + actor.abilityTimer() + " ticks"
                + (actor.onObject() ? "\nRiding layout #" + actor.ridingLayoutIndex() : "")
                + "\nTarget: " + actor.targetStatus();
    }

    private static String commandDetail(StageplayDocument.PuppetCommand command) {
        String destination = command.target().layoutIndex() >= 0
                ? "Layout #" + command.target().layoutIndex() + " + " + coordinate(command.target().offsetX()) + ", " + coordinate(command.target().offsetY())
                : "Goal " + coordinate(command.x()) + ", " + coordinate(command.y());
        String detail = switch (command.kind()) {
            case MOVE_TO, FLY_TO, GLIDE_TO -> destination + " / " + Math.round(command.urgency() * 100) + "%";
            case HOVER -> "Hold native hover for " + command.holdFrames() + " frames";
            case JUMP -> "Hold " + command.holdFrames() + " frames";
            case STOP -> "Native braking";
            case WAIT -> "Wait for " + command.condition() + (command.target().layoutIndex() >= 0 ? " / " + destination : "");
            case RELEASE -> "Release native controls";
            case CROUCH -> "Crouch for " + command.holdFrames() + " frames";
            case LOOK_UP -> "Look up for " + command.holdFrames() + " frames";
            case SPINDASH -> "Charge spindash";
                case FACE_LEFT -> "Face left";
                case FACE_RIGHT -> "Face right";
                case ROLL -> "Native roll";
        };
        return detail + (command.timeoutFrames() > 0 ? " / timeout " + command.timeoutFrames() + " frames" : "");
    }

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
        for (var actor : document.actors()) for (var key : actor.keys()) {
            boolean physical = document.puppets().stream().anyMatch(p -> p.actorId().equals(actor.id()));
            rows.add(new CueRow(key.frame(), actor.name(), physical ? "Placement / visibility"
                    : key.pose() >= 0 ? "Pose " + key.pose() : "Animation " + key.animation(), actor.id()));
        }
        for (var track : document.puppets()) for (var command : track.commands()) {
            var actor = document.actors().stream().filter(a -> a.id().equals(track.actorId())).findFirst().orElseThrow();
            rows.add(new CueRow(command.frame(), actor.name() + " / " + command.kind(), commandDetail(command), actor.id()));
        }
        for (var key : document.camera()) rows.add(new CueRow(key.frame(), "Camera", key.follow().actors().isEmpty()
                ? key.interpolation().name() : "Follow " + String.join(", ", key.follow().actors()), ""));
        for (var caption : document.captions()) rows.add(new CueRow(caption.startFrame(), "Caption", caption.text().replace('\n', ' '), ""));
        for (var cue : document.dialogue()) rows.add(new CueRow(cue.startFrame(), "Dialogue / "+StageplayDialogue.speakerName(document,cue),
                cue.text().replace('\n',' '),cue.speakerActor()));
        for (var cue : document.audio()) rows.add(new CueRow(cue.frame(), "Audio", cue.kind().name(), ""));
        for (var effect : document.cameraEffects()) rows.add(new CueRow(effect.startFrame(), "Camera shake",
                effect.amplitudeX() + " / " + effect.amplitudeY() + " px, " + effect.frequency() + " Hz to " + effect.endFrame(), ""));
        for (var change : document.sceneChanges()) rows.add(new CueRow(change.frame(), "Scene change",
                change.transition() + " / " + change.set().gameId() + " zone " + change.set().zone() + " act " + change.set().act(), ""));
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
        if (frame.workspace.routeOverlay() != null) {
            draw.pushClipRect(gx, gy, gr, gb, true);
            try { frame.workspace.paintRouteOverlay(this, gx, gy, frame.gameWidth, frame.gameHeight); }
            finally { draw.popClipRect(); }
        }
        draw.addRect(gx - 1, gy - 1, gr + 1, gb + 1, EditorChromeTheme.GRID_LINE);
        draw.addText(gx, gb + 5, EditorChromeTheme.TEXT_DIM, "Stage preview / "
                + (frame.gameScale == 0 ? "fit" : frame.gameScale + "x") + " / frame " + frame.workspace.controller().frame());
    }

    private static void status(Frame frame, float x, float y, float width) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, STATUS_HEIGHT);
        if (ImGui.begin("##stageplayStatus", FIXED_WINDOW)) {
            String path = frame.workspace.path() == null ? "Unsaved scene" : frame.workspace.path().getFileName().toString();
            var route = frame.workspace.routeOverlay();
            ImGui.textDisabled(path + "   /   " + (route == null ? frame.workspace.status() : route.statusText()));
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
        float width = Math.min(500, Math.max(160,viewport.getSizeX()-32));
        ImGui.setNextWindowPos(viewport.getPosX() + (viewport.getSizeX()-width)/2, viewport.getPosY() + 60);
        ImGui.setNextWindowSize(width, Math.max(120,Math.min(500, viewport.getSizeY() - 90)));
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
