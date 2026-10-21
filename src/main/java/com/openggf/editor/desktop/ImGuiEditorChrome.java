package com.openggf.editor.desktop;

import com.openggf.control.InputHandler;
import com.openggf.editor.EditorCommandPalette;
import com.openggf.editor.EditorWindowChrome;
import com.openggf.editor.stageplay.StageplayWorkspace;
import com.openggf.editor.stageplay.StageplayWorkspace.Action;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.List;

/**
 * OpenGGF's {@link EditorWindowChrome} implemented with Dear ImGui: menu bar, toolbar, docked
 * library and inspector, status bar and command palette at window resolution, with the game
 * fitted at an integer scale inside the dock's central node. The engine discovers this class
 * through {@code META-INF/services}; every edit goes through the engine's editor controller.
 */
public final class ImGuiEditorChrome implements EditorWindowChrome {
    private static final int CANVAS_MARGIN = 10;
    private static final int CAPTION_HEIGHT = 20;

    private final EditorChromeHost host = new EditorChromeHost();
    private EditorChromeShell shell;
    private StageplayChromeShell stageplayShell;
    private Host engine;
    private boolean active;
    private boolean viewportOverridden;
    private EditorChromeShell.Area canvas;
    private final int[] game = new int[5];
    /** Actions and commands chosen while drawing; they run at the next editor tick. */
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    @Override
    public void init(Host engine, float framebufferScale) {
        this.engine = engine;
        host.init(framebufferScale);
        shell = new EditorChromeShell(host);
        stageplayShell = new StageplayChromeShell(host);
    }

    @Override
    public boolean setActive(boolean editorFrame) {
        active = editorFrame && host.isInitialized();
        if (!active && shell != null) shell.closeCommandPalette();
        if (!active && stageplayShell != null) stageplayShell.closeCommandPalette();
        return active;
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public int[] gameViewport(int windowWidth, int windowHeight, int framebufferWidth, int framebufferHeight,
                              int logicalWidth, int logicalHeight) {
        if (!active) {
            if (!viewportOverridden) return null;
            viewportOverridden = false;
            return new int[0];
        }
        float sx = windowWidth > 0 ? framebufferWidth / (float) windowWidth : 1f;
        float sy = windowHeight > 0 ? framebufferHeight / (float) windowHeight : 1f;
        EditorChromeShell.Area area = canvas != null ? canvas
                : new EditorChromeShell.Area(0, 0, windowWidth, windowHeight);
        int ax = Math.round((area.x() + CANVAS_MARGIN) * sx);
        int ay = Math.round((area.y() + CANVAS_MARGIN) * sy);
        int aw = Math.max(1, Math.round((area.width() - 2 * CANVAS_MARGIN) * sx));
        int ah = Math.max(1, Math.round((area.height() - 2 * CANVAS_MARGIN - CAPTION_HEIGHT) * sy));
        int[] fit = stageplayActive() ? StageplayChromeShell.fitPreview(ax, ay, aw, ah, logicalWidth, logicalHeight)
                : EditorChromeHost.fitIntegerViewport(ax, ay, aw, ah, logicalWidth, logicalHeight);
        // Remember the window-space rectangle for the canvas frame drawn by the shell.
        game[0] = Math.round(fit[0] / sx);
        game[1] = Math.round(fit[1] / sy);
        game[2] = Math.round(fit[2] / sx);
        game[3] = Math.round(fit[3] / sy);
        game[4] = fit[4];
        viewportOverridden = true;
        return new int[] {fit[0], framebufferHeight - fit[1] - fit[3], fit[2], fit[3]};
    }

    @Override
    public void render(int windowWidth, int windowHeight, int framebufferWidth, int framebufferHeight) {
        if (!active) return;
        host.beginFrame(windowWidth, windowHeight, framebufferWidth, framebufferHeight);
        // Playtest, save and project commands switch modes and load levels; that belongs in the
        // editor tick, not after the frame's final display pass, so drawing only queues them.
        try {
            if (stageplayActive()) {
                canvas = stageplayShell.draw(new StageplayChromeShell.Frame(engine.stageplay(), pending::addLast,
                        game[0], game[1], game[2], game[3], game[4]));
            } else {
                var commands = new java.util.ArrayList<EditorCommandPalette.HostCommand>();
                for (var command : engine.commands()) commands.add(new EditorCommandPalette.HostCommand(command.label(),
                        () -> pending.addLast(command.action())));
                if (engine.stageplay() != null && commands.stream().noneMatch(command -> command.label().toLowerCase(java.util.Locale.ROOT).contains("stageplay")))
                    commands.add(new EditorCommandPalette.HostCommand("Stageplay workspace (F4)",
                            () -> pending.addLast(engine.stageplay()::activate)));
                canvas = shell.draw(new EditorChromeShell.Frame(engine.controller(),
                        action -> pending.addLast(() -> engine.dispatch(action)), List.copyOf(commands),
                        game[0], game[1], game[2], game[3], game[4]));
            }
        } finally { host.endFrameAndRender(); }
    }

    @Override
    public boolean update(InputHandler input) {
        boolean wasStageplay = stageplayActive();
        while (!pending.isEmpty()) pending.removeFirst().run();
        // Consume the transition tick too: a click or held key cannot paint terrain on exit.
        return wasStageplay || stageplayActive() || paletteOpen();
    }

    private boolean stageplayActive() { return engine != null && engine.stageplay() != null && engine.stageplay().active(); }

    private boolean paletteOpen() {
        return stageplayActive() ? stageplayShell != null && stageplayShell.isCommandPaletteOpen()
                : shell != null && shell.isCommandPaletteOpen();
    }

    @Override
    public boolean mouseMove(double x, double y) {
        host.onMouseMove(x, y);
        return true;
    }

    @Override
    public boolean mouseButton(int button, int action) {
        if (!active) return true;
        host.onMouseButton(button, action);
        // Releases always reach the engine so a stroke that started in the canvas can finish.
        return action == GLFW.GLFW_RELEASE || (!stageplayActive() && !paletteOpen() && !host.wantsMouse());
    }

    @Override
    public boolean scroll(double xOffset, double yOffset) {
        if (!active) return true;
        host.onScroll(xOffset, yOffset);
        return !stageplayActive() && !paletteOpen() && !host.wantsMouse();
    }

    @Override
    public boolean key(int key, int action, int mods) {
        if (!active) return true;
        host.onKey(key, action, mods);
        StageplayWorkspace workspace = engine == null ? null : engine.stageplay();
        if (workspace != null && key == GLFW.GLFW_KEY_F4 && action == GLFW.GLFW_PRESS) {
            pending.addLast(() -> {
                if (workspace.active()) workspace.deactivate(); else workspace.activate();
                if (shell != null) shell.closeCommandPalette();
                if (stageplayShell != null) stageplayShell.closeCommandPalette();
            });
            return false;
        }
        if (stageplayActive()) return stageplayKey(workspace, key, action, mods);
        if (key == GLFW.GLFW_KEY_F1 && action == GLFW.GLFW_PRESS) {
            if (shell.isCommandPaletteOpen()) shell.closeCommandPalette();
            else shell.openCommandPalette();
            return false;
        }
        return action == GLFW.GLFW_RELEASE || (!paletteOpen() && !host.wantsKeyboard());
    }

    private boolean stageplayKey(StageplayWorkspace workspace, int key, int action, int mods) {
        if (action == GLFW.GLFW_RELEASE) return true;
        if (workspace.recording()) {
            if (action == GLFW.GLFW_PRESS && (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ESCAPE)) {
                pending.addLast(() -> workspace.invoke(Action.STOP));
                return false;
            }
            return key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_D;
        }
        if (action != GLFW.GLFW_PRESS) return false;
        if (key == GLFW.GLFW_KEY_F1) { stageplayShell.toggleCommandPalette(); return false; }
        if (key == GLFW.GLFW_KEY_ESCAPE && paletteOpen()) { stageplayShell.closeCommandPalette(); return false; }
        if (paletteOpen() || host.wantsKeyboard()) return false;
        boolean control = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        Action command = null;
        if (control) {
            if (key == GLFW.GLFW_KEY_S) command = shift ? Action.SAVE_AS : Action.SAVE;
            else if (key == GLFW.GLFW_KEY_Z) command = shift ? Action.REDO : Action.UNDO;
            else if (key == GLFW.GLFW_KEY_Y) command = Action.REDO;
        } else {
            if (key == GLFW.GLFW_KEY_SPACE) command = Action.PLAY;
            else if (key == GLFW.GLFW_KEY_K) command = Action.SET_KEY;
            else if (key == GLFW.GLFW_KEY_DELETE) command = Action.DELETE_KEY;
            else if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                int direction = key == GLFW.GLFW_KEY_LEFT ? -1 : 1;
                pending.addLast(() -> workspace.seek(workspace.controller().frame() + direction * (shift ? 60 : 1)));
            } else if (key == GLFW.GLFW_KEY_HOME) pending.addLast(() -> workspace.seek(0));
            else if (key == GLFW.GLFW_KEY_END) pending.addLast(() -> workspace.seek(workspace.controller().document().durationFrames() - 1));
        }
        if (command != null) {
            Action chosen = command;
            pending.addLast(() -> workspace.invoke(chosen));
        }
        return false;
    }

    @Override
    public boolean character(int codepoint) {
        if (!active) return true;
        host.onChar(codepoint);
        return !host.wantsTextInput();
    }

    @Override
    public void focus(boolean focused) {
        host.onFocus(focused);
    }

    @Override
    public void close() {
        pending.clear();
        if (shell != null) shell.close();
        host.close();
    }
}
