package com.openggf.editor.desktop;

import imgui.ImGui;
import imgui.type.ImInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Native atlas coverage without a window or a GL context. */
class TestEditorChromeFonts {
    @Test
    void bundledFontsSurviveCollectionBeforeAtlasUploadAndContextRecreation() {
        for (float density : new float[] {1, 2}) {
            ImGui.createContext();
            try {
                var host = new EditorChromeHost();
                var io = ImGui.getIO();
                host.loadFonts(io, density);
                // The previous JNI byte[] path left font/range addresses in the Java heap.
                System.gc();
                var width = new ImInt();
                var height = new ImInt();
                var pixels = io.getFonts().getTexDataAsRGBA32(width, height);
                assertTrue(io.getFonts().isBuilt());
                assertTrue(width.get() > 0 && height.get() > 0);
                assertEquals(width.get() * height.get() * 4, pixels.remaining());
                assertTrue(host.bodyFont().calcTextSizeAX(EditorChromeHost.BODY_SIZE,
                        Float.MAX_VALUE, 0, "Stageplay " + EditorIcons.PLAY) > 0);
                assertTrue(host.headingFont().calcTextSizeAX(EditorChromeHost.HEADING_SIZE,
                        Float.MAX_VALUE, 0, "CAST " + EditorIcons.PLUS) > 0);
                assertTrue(host.titleFont().calcTextSizeAX(EditorChromeHost.TITLE_SIZE,
                        Float.MAX_VALUE, 0, "Direction " + EditorIcons.SETTINGS) > 0);
            } finally {
                ImGui.destroyContext();
            }
        }
    }
}
