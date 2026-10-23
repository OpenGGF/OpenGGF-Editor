package com.openggf.editor.desktop;

import imgui.flag.ImGuiKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.glfw.GLFW.*;

class TestEditorChromeLayout {
    @Test
    void gameViewportUsesLargestIntegerScaleCentredInCanvas() {
        // 426x224 fits three times into 1300x700 (1278x672); remaining space is split evenly.
        assertArrayEquals(new int[] {111, 64, 1278, 672, 3},
                EditorChromeHost.fitIntegerViewport(100, 50, 1300, 700, 426, 224));
    }

    @Test
    void gameViewportNeverDropsBelowNativeScale() {
        int[] fit = EditorChromeHost.fitIntegerViewport(0, 0, 200, 100, 320, 224);
        assertEquals(1, fit[4]);
        assertEquals(320, fit[2]);
        assertEquals(224, fit[3]);
    }

    @Test void authoringViewportStaysInsideNarrowAndLargeDocks() {
        for (int[] area : new int[][] {{330, 70, 930, 880}, {200, 50, 190, 100}}) {
            int[] fit = EditorChromeHost.fitCanvasViewport(area[0], area[1], area[2], area[3], 320, 224);
            org.junit.jupiter.api.Assertions.assertTrue(fit[0] >= area[0] && fit[1] >= area[1]);
            org.junit.jupiter.api.Assertions.assertTrue(fit[0] + fit[2] <= area[0] + area[2]);
            org.junit.jupiter.api.Assertions.assertTrue(fit[1] + fit[3] <= area[1] + area[3]);
        }
    }

    @Test
    void textEditingKeysReachImGui() {
        assertEquals(ImGuiKey.A, EditorChromeHost.imguiKey(GLFW_KEY_A));
        assertEquals(ImGuiKey.Z, EditorChromeHost.imguiKey(GLFW_KEY_Z));
        assertEquals(ImGuiKey._0 + 7, EditorChromeHost.imguiKey(GLFW_KEY_7));
        assertEquals(ImGuiKey.Backspace, EditorChromeHost.imguiKey(GLFW_KEY_BACKSPACE));
        assertEquals(ImGuiKey.Enter, EditorChromeHost.imguiKey(GLFW_KEY_ENTER));
        assertEquals(ImGuiKey.None, EditorChromeHost.imguiKey(GLFW_KEY_F5));
    }

    @Test
    void themeColoursPackAsImGuiAbgr() {
        assertEquals(0xFF45C8FF, EditorChromeTheme.rgba(0xFFC845, 1f));
        assertEquals(0x00000000, EditorChromeTheme.rgba(0x000000, 0f));
    }
}
