package com.openggf.editor.desktop;

import com.openggf.control.InputHandler;
import com.openggf.editor.EditorCommandPalette;
import com.openggf.editor.EditorInputHandler;
import com.openggf.editor.EditorWindowChrome;
import com.openggf.editor.LevelEditorController;
import com.openggf.stageplay.StageplayDocument;
import com.openggf.stageplay.StageplayStore;
import com.openggf.editor.stageplay.StageplayWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/** Desktop input and commands use the same authoring objects as the built-in HUD. */
class TestStageplayChrome {
    @Test
    void workspaceSwitchAndSeekingAreQueuedAndOwnTheirTransitionTicks() throws Exception {
        var workspace = workspace();
        var chrome = chrome(workspace);
        assertFalse(workspace.active());
        assertFalse(chrome.key(GLFW_KEY_F4, GLFW_PRESS, 0));
        assertFalse(workspace.active(), "Window callbacks must not mutate the authoring workspace");
        assertTrue(chrome.update(new InputHandler()));
        assertTrue(workspace.active());
        assertTrue(chrome.key(GLFW_KEY_F4, GLFW_RELEASE, 0));

        assertFalse(chrome.key(GLFW_KEY_RIGHT, GLFW_PRESS, 0));
        assertEquals(0, workspace.controller().frame());
        assertTrue(chrome.update(new InputHandler()));
        assertEquals(1, workspace.controller().frame());
        assertFalse(chrome.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS));
        assertTrue(chrome.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE));

        chrome.key(GLFW_KEY_F4, GLFW_PRESS, 0);
        assertTrue(workspace.active());
        assertTrue(chrome.update(new InputHandler()), "Exit input cannot continue into terrain editing");
        assertFalse(workspace.active());
        assertFalse(chrome.update(new InputHandler()));
    }

    @Test
    void desktopCommandEditsAndSavesTheSharedDocument(@TempDir Path root) throws Exception {
        var workspace = workspace();
        workspace.activate();
        var queue = new ArrayDeque<Runnable>();
        var frame = new StageplayChromeShell.Frame(workspace, queue::addLast, 0, 0, 320, 224, 1);
        frame.invoke(StageplayWorkspace.Action.ADD_ACTOR);
        assertFalse(workspace.menu().isOpen());
        queue.removeFirst().run();
        workspace.menu().setValue("name", "Lead");
        workspace.menu().submit();
        String actor = workspace.controller().document().actors().getFirst().id();
        frame.select(actor);
        frame.seek(30);
        frame.invoke(StageplayWorkspace.Action.SET_KEY);
        while (!queue.isEmpty()) queue.removeFirst().run();
        workspace.menu().setValue("x", "280");
        workspace.menu().setValue("pose", "4");
        workspace.menu().submit();
        Path saved = root.resolve("scene.stageplay");
        frame.invoke(StageplayWorkspace.Action.SAVE_AS);
        queue.removeFirst().run();
        workspace.menu().setValue("path", saved.toString());
        workspace.menu().submit();
        var reopened = StageplayStore.read(saved);
        assertEquals(workspace.controller().document(), reopened);
        assertEquals(280, reopened.actors().getFirst().keys().getLast().x());
        assertFalse(workspace.controller().dirty());
        frame.invoke(StageplayWorkspace.Action.UNDO);
        queue.removeFirst().run();
        assertTrue(workspace.controller().dirty());
        assertEquals(1, workspace.controller().document().actors().getFirst().keys().size());
    }

    @Test
    void directedRecordingPassesMovementKeysButQueuesStop() throws Exception {
        var workspace = workspace();
        workspace.activate();
        workspace.invoke(StageplayWorkspace.Action.ADD_ACTOR);
        workspace.menu().submit();
        workspace.invoke(StageplayWorkspace.Action.RECORD);
        assertTrue(workspace.recording());
        var chrome = chrome(workspace);
        assertTrue(chrome.key(GLFW_KEY_W, GLFW_PRESS, 0));
        assertTrue(chrome.key(GLFW_KEY_D, GLFW_PRESS, 0));
        assertFalse(chrome.key(GLFW_KEY_SPACE, GLFW_PRESS, 0));
        assertTrue(workspace.recording());
        assertTrue(chrome.update(new InputHandler()));
        assertFalse(workspace.recording());
        assertTrue(chrome.key(GLFW_KEY_W, GLFW_RELEASE, 0));
    }

    @Test
    void stageplayPaletteConsumesInputAndClosesWhenTheChromeLeaves() throws Exception {
        var workspace = workspace();
        workspace.activate();
        var chrome = chrome(workspace);
        assertFalse(chrome.key(GLFW_KEY_F1, GLFW_PRESS, 0));
        assertFalse(chrome.key(GLFW_KEY_SPACE, GLFW_PRESS, 0));
        assertTrue(chrome.update(new InputHandler()));
        assertFalse(workspace.controller().playing());
        chrome.setActive(false);
        var field = ImGuiEditorChrome.class.getDeclaredField("stageplayShell");
        field.setAccessible(true);
        assertFalse(((StageplayChromeShell) field.get(chrome)).isCommandPaletteOpen());
    }

    @Test
    void cueListRetainsAllTrackKindsAndChronologicalOrder() {
        var document = StageplayDocument.empty("s2", 0, 0, 0, 0, 320, 224)
                .withCaptions(List.of(new StageplayDocument.Caption("line", 20, 50, "Hello", 5, 10, -1)))
                .withAudio(List.of(new StageplayDocument.AudioCue("sound", 10, StageplayDocument.AudioKind.ROM_SFX, "s2", 1, "", 1)))
                .withActors(List.of(new StageplayDocument.Actor("hero", "Hero", "s2", "sonic", List.of(
                        new StageplayDocument.ActorKey(0, 100, 100, 0, -1, false, true, 1, StageplayDocument.Interpolation.HOLD)))));
        var shell = new StageplayChromeShell(new EditorChromeHost());
        var rows = shell.cueRows(document);
        assertEquals(List.of(0, 0, 10, 20), rows.stream().map(StageplayChromeShell.CueRow::frame).toList());
        assertEquals(List.of("Hero", "Camera", "Audio", "Caption"), rows.stream().map(StageplayChromeShell.CueRow::track).toList());
        assertSame(rows, shell.cueRows(document), "An unchanged immutable timeline does not rebuild every display frame");
    }

    @Test
    void previewKeepsIntegerScalingWhenPossibleAndFitsLargeScenes() {
        assertArrayEquals(new int[] {10, 20, 640, 448, 2},
                StageplayChromeShell.fitPreview(10, 20, 640, 448, 320, 224));
        int[] fit = StageplayChromeShell.fitPreview(10, 20, 640, 400, 1920, 1080);
        assertArrayEquals(new int[] {10, 40, 640, 360, 0}, fit);
    }

    @Test
    void cameraModePassesMotionKeysCapturesThroughQueueAndSuspendsForPalette() throws Exception {
        var workspace = workspace(); workspace.activate(); var chrome = chrome(workspace);
        chrome.key(GLFW_KEY_C,GLFW_PRESS,0); assertFalse(workspace.cameraMode());
        var input = new InputHandler(); chrome.update(input); assertTrue(workspace.cameraMode());
        assertTrue(chrome.key(GLFW_KEY_RIGHT,GLFW_PRESS,0));
        assertTrue(chrome.key(GLFW_KEY_Q,GLFW_PRESS,0));
        assertTrue(chrome.key(GLFW_KEY_LEFT_CONTROL,GLFW_PRESS,GLFW_MOD_CONTROL));
        assertTrue(chrome.key(GLFW_KEY_LEFT_CONTROL,GLFW_RELEASE,0));
        input.handleKeyEvent(GLFW_KEY_RIGHT,GLFW_PRESS); input.refreshLogicalSnapshot();
        chrome.update(input); workspace.tick(input);
        assertEquals(0,workspace.controller().frame()); assertEquals(2,workspace.directorCamera().x());
        chrome.key(GLFW_KEY_F1,GLFW_PRESS,0); chrome.update(input); workspace.tick(input);
        assertEquals(2,workspace.directorCamera().x(),"a held arrow cannot pan behind the palette");
        chrome.key(GLFW_KEY_ESCAPE,GLFW_PRESS,0); chrome.update(input);
        input.handleKeyEvent(GLFW_KEY_RIGHT,GLFW_RELEASE);
        chrome.key(GLFW_KEY_K,GLFW_PRESS,0); assertEquals(0,workspace.controller().document().camera().getFirst().x());
        chrome.update(input); assertEquals(2,workspace.controller().document().camera().getFirst().x());
        chrome.key(GLFW_KEY_R,GLFW_PRESS,0); chrome.update(input); assertTrue(workspace.recordingCamera());
        assertTrue(chrome.key(GLFW_KEY_E,GLFW_PRESS,0)); assertTrue(chrome.key(GLFW_KEY_DOWN,GLFW_PRESS,0));
        chrome.key(GLFW_KEY_SPACE,GLFW_PRESS,0); chrome.update(input); assertFalse(workspace.recording());
    }

    @Test
    void smallWindowsShareSidePanelTabsAndNewTracksRemainReachable() {
        assertFalse(StageplayChromeShell.compactLayout(1280,570));
        assertTrue(StageplayChromeShell.compactLayout(1024,450));
        assertTrue(StageplayChromeShell.compactLayout(1280,400));
        var doc = StageplayDocument.empty("s2",0,0,0,0,320,224);
        doc = doc.withCamera(List.of(doc.camera().getFirst(),new StageplayDocument.CameraKey(100,20,30,1,StageplayDocument.Interpolation.HOLD)))
                .withCameraEffects(List.of(new StageplayDocument.CameraEffect("quake",20,60,2,4,8,1)))
                .withSceneChanges(List.of(new StageplayDocument.SceneChange("shot",100,doc.set(),StageplayDocument.Transition.WIPE_RIGHT,30)));
        var rows = new StageplayChromeShell(new EditorChromeHost()).cueRows(doc);
        assertEquals(List.of("Camera","Camera shake","Camera","Scene change"),rows.stream().map(StageplayChromeShell.CueRow::track).toList());
    }

    private static StageplayWorkspace workspace() {
        return new StageplayWorkspace(() -> StageplayDocument.empty("s2", 0, 0, 0, 0, 320, 224));
    }

    private static ImGuiEditorChrome chrome(StageplayWorkspace workspace) throws Exception {
        var chrome = new ImGuiEditorChrome();
        set(chrome, "shell", new EditorChromeShell(new EditorChromeHost()));
        set(chrome, "stageplayShell", new StageplayChromeShell(new EditorChromeHost()));
        set(chrome, "engine", new EditorWindowChrome.Host() {
            public LevelEditorController controller() { return new LevelEditorController(); }
            public void dispatch(EditorInputHandler.Action action) { fail("Stageplay input reached terrain editing"); }
            public List<EditorCommandPalette.HostCommand> commands() { return List.of(); }
            public StageplayWorkspace stageplay() { return workspace; }
        });
        set(chrome, "active", true);
        return chrome;
    }

    private static void set(ImGuiEditorChrome chrome, String name, Object value) throws Exception {
        var field = ImGuiEditorChrome.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(chrome, value);
    }
}
