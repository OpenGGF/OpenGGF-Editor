package com.openggf.editor.desktop;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiKey;
import imgui.gl3.ImGuiImplGl3;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Owns the Dear ImGui context that draws editor chrome at window resolution.
 *
 * <p>The game keeps rendering into its integer-scaled viewport; this host draws panels,
 * text and controls over the whole framebuffer afterwards. Input reaches ImGui first through
 * {@link #onKey}/{@link #onMouseButton}/etc.; callers forward an event to the engine only when
 * the matching {@code wants*} query is false. Origin: the 2026-10-14 OpenGGF editor ImGui spike
 * (OpenGGF branch feature/ai-editor-imgui-spike, d2b331a4c).
 */
public final class EditorChromeHost implements AutoCloseable {
    /** Logical font sizes, rasterised once into the legacy (static) font atlas. */
    static final float BODY_SIZE = 15f;
    static final float HEADING_SIZE = 13f;
    static final float TITLE_SIZE = 18f;

    private final ImGuiImplGl3 renderer = new ImGuiImplGl3();
    private boolean initialized;
    private long lastFrameNanos;
    private ImFont body;
    private ImFont heading;
    private ImFont title;

    /** Creates the context, fonts and GL objects. Requires a current GL 3.2+ core context. */
    public void init(float framebufferScale) {
        if (initialized) return;
        ImGui.createContext();
        try {
            ImGuiIO io = ImGui.getIO();
            // Layout lives in code for now; persisted docking state is a later decision.
            io.setIniFilename(null);
            io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
            loadFonts(io, Math.max(1f, framebufferScale));
            EditorChromeTheme.apply(ImGui.getStyle());
            renderer.init("#version 410 core");
        } catch (RuntimeException | Error failure) {
            // close() only tears down a finished init; release the context here.
            ImGui.destroyContext();
            throw failure;
        }
        initialized = true;
        lastFrameNanos = System.nanoTime();
    }

    private void loadFonts(ImGuiIO io, float density) {
        byte[] medium = resource("/fonts/editor/NotoSans-Medium.ttf");
        byte[] semiBold = resource("/fonts/editor/NotoSans-Bold.ttf");
        byte[] icons = resource("/fonts/editor/lucide.ttf");
        var atlas = io.getFonts();
        atlas.clear();
        body = addFont(atlas, medium, BODY_SIZE, density);
        mergeIcons(atlas, icons, BODY_SIZE + 1f, density);
        heading = addFont(atlas, semiBold, HEADING_SIZE, density);
        mergeIcons(atlas, icons, HEADING_SIZE + 1f, density);
        title = addFont(atlas, semiBold, TITLE_SIZE, density);
        mergeIcons(atlas, icons, TITLE_SIZE, density);
    }

    /**
     * Noto Sans has no private-use glyphs, so the merged Lucide icons (U+E000..) are never
     * shadowed: ImGui 1.92 resolves a codepoint from the first source that has it and treats
     * glyph ranges only as a preload hint. Inter was rejected for carrying 745 PUA glyphs.
     */
    private static ImFont addFont(imgui.ImFontAtlas atlas, byte[] ttf, float size, float density) {
        ImFontConfig config = new ImFontConfig();
        try {
            config.setRasterizerDensity(density);
            config.setOversampleH(2);
            return atlas.addFontFromMemoryTTF(ttf, size, config);
        } finally {
            config.destroy();
        }
    }

    private static void mergeIcons(imgui.ImFontAtlas atlas, byte[] ttf, float size, float density) {
        ImFontConfig config = new ImFontConfig();
        try {
            config.setMergeMode(true);
            config.setRasterizerDensity(density);
            config.setGlyphMinAdvanceX(size);
            config.setGlyphOffset(0, 2f);
            // The legacy atlas bakes only requested ranges; Lucide lives in the private-use area.
            atlas.addFontFromMemoryTTF(ttf, size, config, new short[] {(short) 0xE000, (short) 0xE5FF, 0});
        } finally {
            config.destroy();
        }
    }

    private static byte[] resource(String path) {
        try (InputStream in = EditorChromeHost.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing editor font resource " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isInitialized() { return initialized; }

    ImFont bodyFont() { return body; }
    ImFont headingFont() { return heading; }
    ImFont titleFont() { return title; }

    /**
     * Starts an ImGui frame for a window of {@code windowWidth}x{@code windowHeight} screen units
     * whose framebuffer is {@code framebufferWidth}x{@code framebufferHeight} pixels.
     */
    public void beginFrame(int windowWidth, int windowHeight, int framebufferWidth, int framebufferHeight) {
        requireInitialized();
        ImGuiIO io = ImGui.getIO();
        io.setDisplaySize(Math.max(1, windowWidth), Math.max(1, windowHeight));
        io.setDisplayFramebufferScale(windowWidth > 0 ? framebufferWidth / (float) windowWidth : 1f,
                windowHeight > 0 ? framebufferHeight / (float) windowHeight : 1f);
        long now = System.nanoTime();
        io.setDeltaTime(Math.max(1e-4f, (now - lastFrameNanos) / 1e9f));
        lastFrameNanos = now;
        renderer.newFrame();
        ImGui.newFrame();
    }

    /** Ends the frame and draws it over the current framebuffer. */
    public void endFrameAndRender() {
        requireInitialized();
        ImGui.render();
        renderer.renderDrawData(ImGui.getDrawData());
    }

    public boolean wantsMouse() { return initialized && ImGui.getIO().getWantCaptureMouse(); }
    public boolean wantsKeyboard() { return initialized && ImGui.getIO().getWantCaptureKeyboard(); }
    public boolean wantsTextInput() { return initialized && ImGui.getIO().getWantTextInput(); }

    public void onMouseMove(double x, double y) {
        if (initialized) ImGui.getIO().addMousePosEvent((float) x, (float) y);
    }

    public void onMouseButton(int glfwButton, int action) {
        if (!initialized || glfwButton < 0 || glfwButton > 4) return;
        ImGui.getIO().addMouseButtonEvent(glfwButton, action != GLFW_RELEASE);
    }

    public void onScroll(double xOffset, double yOffset) {
        if (initialized) ImGui.getIO().addMouseWheelEvent((float) xOffset, (float) yOffset);
    }

    public void onChar(int codepoint) {
        if (initialized) ImGui.getIO().addInputCharacter(codepoint);
    }

    public void onFocus(boolean focused) {
        if (initialized) ImGui.getIO().addFocusEvent(focused);
    }

    public void onKey(int glfwKey, int action, int mods) {
        if (!initialized || action == GLFW_REPEAT && glfwKey == GLFW_KEY_UNKNOWN) return;
        ImGuiIO io = ImGui.getIO();
        boolean down = action != GLFW_RELEASE;
        io.addKeyEvent(ImGuiKey.ImGuiMod_Ctrl, (mods & GLFW_MOD_CONTROL) != 0);
        io.addKeyEvent(ImGuiKey.ImGuiMod_Shift, (mods & GLFW_MOD_SHIFT) != 0);
        io.addKeyEvent(ImGuiKey.ImGuiMod_Alt, (mods & GLFW_MOD_ALT) != 0);
        io.addKeyEvent(ImGuiKey.ImGuiMod_Super, (mods & GLFW_MOD_SUPER) != 0);
        int key = imguiKey(glfwKey);
        if (key != ImGuiKey.None) io.addKeyEvent(key, down);
    }

    /** Text editing and navigation keys only; the editor keeps its own shortcut table. */
    static int imguiKey(int glfwKey) {
        if (glfwKey >= GLFW_KEY_A && glfwKey <= GLFW_KEY_Z) return ImGuiKey.A + (glfwKey - GLFW_KEY_A);
        if (glfwKey >= GLFW_KEY_0 && glfwKey <= GLFW_KEY_9) return ImGuiKey._0 + (glfwKey - GLFW_KEY_0);
        return switch (glfwKey) {
            case GLFW_KEY_TAB -> ImGuiKey.Tab;
            case GLFW_KEY_LEFT -> ImGuiKey.LeftArrow;
            case GLFW_KEY_RIGHT -> ImGuiKey.RightArrow;
            case GLFW_KEY_UP -> ImGuiKey.UpArrow;
            case GLFW_KEY_DOWN -> ImGuiKey.DownArrow;
            case GLFW_KEY_PAGE_UP -> ImGuiKey.PageUp;
            case GLFW_KEY_PAGE_DOWN -> ImGuiKey.PageDown;
            case GLFW_KEY_HOME -> ImGuiKey.Home;
            case GLFW_KEY_END -> ImGuiKey.End;
            case GLFW_KEY_INSERT -> ImGuiKey.Insert;
            case GLFW_KEY_DELETE -> ImGuiKey.Delete;
            case GLFW_KEY_BACKSPACE -> ImGuiKey.Backspace;
            case GLFW_KEY_SPACE -> ImGuiKey.Space;
            case GLFW_KEY_ENTER -> ImGuiKey.Enter;
            case GLFW_KEY_KP_ENTER -> ImGuiKey.KeypadEnter;
            case GLFW_KEY_ESCAPE -> ImGuiKey.Escape;
            case GLFW_KEY_LEFT_CONTROL -> ImGuiKey.LeftCtrl;
            case GLFW_KEY_RIGHT_CONTROL -> ImGuiKey.RightCtrl;
            case GLFW_KEY_LEFT_SHIFT -> ImGuiKey.LeftShift;
            case GLFW_KEY_RIGHT_SHIFT -> ImGuiKey.RightShift;
            case GLFW_KEY_LEFT_ALT -> ImGuiKey.LeftAlt;
            case GLFW_KEY_RIGHT_ALT -> ImGuiKey.RightAlt;
            default -> ImGuiKey.None;
        };
    }

    private void requireInitialized() {
        if (!initialized) throw new IllegalStateException("Editor chrome is not initialized");
    }

    @Override
    public void close() {
        if (!initialized) return;
        initialized = false;
        renderer.shutdown();
        ImGui.destroyContext();
    }

    /**
     * Largest integer scale of {@code logicalWidth}x{@code logicalHeight} that fits {@code area},
     * centred in it. {@code area} and the result use framebuffer pixels with a top-left origin.
     */
    public static int[] fitIntegerViewport(int areaX, int areaY, int areaWidth, int areaHeight,
                                           int logicalWidth, int logicalHeight) {
        int scale = Math.max(1, Math.min(areaWidth / Math.max(1, logicalWidth),
                areaHeight / Math.max(1, logicalHeight)));
        int width = logicalWidth * scale, height = logicalHeight * scale;
        return new int[] {areaX + (areaWidth - width) / 2, areaY + (areaHeight - height) / 2, width, height, scale};
    }
}
