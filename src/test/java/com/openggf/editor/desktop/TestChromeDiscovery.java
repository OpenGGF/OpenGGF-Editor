package com.openggf.editor.desktop;

import com.openggf.editor.EditorWindowChromes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestChromeDiscovery {
    @Test
    void engineFindsThisEditorThroughTheServiceRegistration() {
        assertInstanceOf(ImGuiEditorChrome.class, EditorWindowChromes.discover(),
                "META-INF/services must name the ImGui chrome so OpenGGF uses it");
    }
}
