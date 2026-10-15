package com.openggf.editor.desktop;

import com.openggf.control.InputHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestDesktopPaletteInput {
    @Test
    void f1OpensDesktopPaletteAndBlocksCanvasInputUntilClosed() throws Exception {
        var chrome = activeChromeWithoutGl();
        var input = new InputHandler();
        assertFalse(chrome.update(input));
        assertFalse(chrome.key(GLFW_KEY_F1, GLFW_PRESS, 0));
        assertTrue(chrome.update(input), "The palette owns the editor tick");
        assertFalse(chrome.key(GLFW_KEY_SPACE, GLFW_PRESS, 0));
        assertFalse(chrome.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS));
        assertTrue(chrome.key(GLFW_KEY_SPACE, GLFW_RELEASE, 0), "Releases clear existing engine holds");
        assertTrue(chrome.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE));
        assertFalse(chrome.key(GLFW_KEY_F1, GLFW_PRESS, 0));
        assertFalse(chrome.update(input));
        assertTrue(chrome.key(GLFW_KEY_SPACE, GLFW_PRESS, 0));
    }

    @Test
    void leavingTheEditorClosesItsDesktopPalette() throws Exception {
        var chrome = activeChromeWithoutGl();
        chrome.key(GLFW_KEY_F1, GLFW_PRESS, 0);
        assertTrue(chrome.update(new InputHandler()));
        chrome.setActive(false);
        assertFalse(chrome.update(new InputHandler()));
    }

    private static ImGuiEditorChrome activeChromeWithoutGl() throws Exception {
        var chrome = new ImGuiEditorChrome();
        // Exercise input ownership without initializing ImGui or opening a native window.
        var shell = ImGuiEditorChrome.class.getDeclaredField("shell");
        shell.setAccessible(true);
        shell.set(chrome, new EditorChromeShell(new EditorChromeHost()));
        var active = ImGuiEditorChrome.class.getDeclaredField("active");
        active.setAccessible(true);
        active.setBoolean(chrome, true);
        return chrome;
    }
}
