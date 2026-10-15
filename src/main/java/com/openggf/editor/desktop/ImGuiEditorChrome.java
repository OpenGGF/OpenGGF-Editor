package com.openggf.editor.desktop;

import com.openggf.editor.EditorWindowChrome;
import org.lwjgl.glfw.GLFW;

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
    private Host engine;
    private boolean active;
    private boolean viewportOverridden;
    private EditorChromeShell.Area canvas;
    private final int[] game = new int[5];

    @Override
    public void init(Host engine, float framebufferScale) {
        this.engine = engine;
        host.init(framebufferScale);
        shell = new EditorChromeShell(host);
    }

    @Override
    public boolean setActive(boolean editorFrame) {
        active = editorFrame && host.isInitialized();
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
        int[] fit = EditorChromeHost.fitIntegerViewport(ax, ay, aw, ah, logicalWidth, logicalHeight);
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
        canvas = shell.draw(new EditorChromeShell.Frame(engine.controller(), engine::dispatch, engine.commands(),
                game[0], game[1], game[2], game[3], game[4]));
        host.endFrameAndRender();
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
        return action == GLFW.GLFW_RELEASE || !host.wantsMouse();
    }

    @Override
    public boolean scroll(double xOffset, double yOffset) {
        if (!active) return true;
        host.onScroll(xOffset, yOffset);
        return !host.wantsMouse();
    }

    @Override
    public boolean key(int key, int action, int mods) {
        if (!active) return true;
        host.onKey(key, action, mods);
        return action == GLFW.GLFW_RELEASE || !host.wantsKeyboard();
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
        if (shell != null) shell.close();
        host.close();
    }
}
