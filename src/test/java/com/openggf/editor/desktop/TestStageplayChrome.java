package com.openggf.editor.desktop;

import com.openggf.control.InputHandler;
import com.openggf.editor.EditorCommandPalette;
import com.openggf.editor.EditorInputHandler;
import com.openggf.editor.EditorWindowChrome;
import com.openggf.editor.LevelEditorController;
import com.openggf.stageplay.StageplayDocument;
import com.openggf.stageplay.StageplayStore;
import com.openggf.stageplay.StageplayTimeline;
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
    void routeHelperQueuesTheSharedRequestAndCancellationWithoutEditingTheScene() {
        var request=new java.util.concurrent.atomic.AtomicReference<com.openggf.stageplay.route.StageplayRouteRequest>();
        var cancelled=new java.util.concurrent.atomic.AtomicInteger();
        var scene=StageplayDocument.empty("s2",0,0,0,500,320,224).withActors(List.of(
                new StageplayDocument.Actor("hero","Sonic","s2","sonic",List.of(new StageplayDocument.ActorKey(0,128,648,0,-1,false,true,1,StageplayDocument.Interpolation.HOLD)))))
                .withPuppets(List.of(new StageplayDocument.PuppetTrack("hero",true,0,List.of())));
        var workspace=new StageplayWorkspace(new StageplayWorkspace.Host(){
            public StageplayDocument initialDocument(){return scene;}
            public void startRoute(StageplayDocument document,Path root,com.openggf.stageplay.route.StageplayRouteRequest value){request.set(value);}
            public com.openggf.stageplay.editor.StageplayRouteJob.Update pollRoute(){return request.get()==null?null:
                    new com.openggf.stageplay.editor.StageplayRouteJob.Update(1,com.openggf.stageplay.editor.StageplayRouteJob.Phase.PLANNING,"working",null);}
            public void cancelRoute(){cancelled.incrementAndGet();}
        });
        workspace.activate();var queue=new ArrayDeque<Runnable>();
        var frame=new StageplayChromeShell.Frame(workspace,queue::addLast,0,0,320,224,1);
        frame.invoke(StageplayWorkspace.Action.ROUTE_HELPER);assertFalse(workspace.menu().isOpen());queue.removeFirst().run();
        assertEquals("Route attempts (object AI off)",workspace.menu().title());
        workspace.menu().setValue("x","240");workspace.menu().setValue("y","648");workspace.menu().setValue("frames","90");workspace.menu().submit();
        assertNull(workspace.menu().error());assertEquals("hero",request.get().actorId());assertNull(request.get().footprint());
        assertEquals(scene,workspace.controller().document());assertFalse(workspace.controller().canUndo());
        int before=cancelled.get();frame.invoke(StageplayWorkspace.Action.ROUTE_HELPER);queue.removeFirst().run();choose(workspace,1);
        assertTrue(cancelled.get()>before);assertEquals(scene,workspace.controller().document());
    }

    @Test
    void screenplayImportAndExportUseQueuedSharedFormsAndPreserveUndo(@TempDir Path root) throws Exception {
        Path source = root.resolve("source.json");
        java.nio.file.Files.write(source,com.openggf.tools.StageplayScriptDecompiler.decompile(
                StageplayDocument.empty("s2",0,0,0,500,320,224).withName("Imported")));
        var workspace = workspace(); workspace.activate();
        var queue = new ArrayDeque<Runnable>();
        var frame = new StageplayChromeShell.Frame(workspace,queue::addLast,0,0,320,224,1);
        frame.invoke(StageplayWorkspace.Action.IMPORT_SCREENPLAY);
        assertFalse(workspace.menu().isOpen()); queue.removeFirst().run();
        assertEquals("Import screenplay",workspace.menu().title());
        workspace.menu().setValue("path",source.toString()); workspace.menu().submit();
        assertNull(workspace.menu().error()); assertNull(workspace.path());
        assertTrue(workspace.controller().dirty()); assertEquals(root,workspace.assetRoot());
        var imported = workspace.controller().document();
        frame.invoke(StageplayWorkspace.Action.SCENE); queue.removeFirst().run();
        workspace.menu().setValue("name","Desktop refinement"); workspace.menu().submit();
        var refined = workspace.controller().document();
        Path output = root.resolve("refined.json");
        frame.invoke(StageplayWorkspace.Action.EXPORT_SCREENPLAY); queue.removeFirst().run();
        assertEquals("Export screenplay",workspace.menu().title());
        workspace.menu().setValue("path",output.toString()); workspace.menu().submit();
        assertNull(workspace.menu().error());
        assertEquals(refined,com.openggf.tools.StageplayScriptCompiler.compile(java.nio.file.Files.readAllBytes(output)));
        assertTrue(workspace.controller().dirty()); assertNull(workspace.path());
        frame.invoke(StageplayWorkspace.Action.UNDO); queue.removeFirst().run();
        assertEquals(imported,workspace.controller().document()); assertTrue(workspace.controller().dirty());
        assertEquals(imported,com.openggf.tools.StageplayScriptCompiler.compile(java.nio.file.Files.readAllBytes(source)));
    }

    @Test
    void desktopObjectCastAndClearSavePortableTracks(@TempDir Path root) throws Exception {
        var workspace = workspace(); workspace.activate();
        var queue = new ArrayDeque<Runnable>();
        var frame = new StageplayChromeShell.Frame(workspace, queue::addLast, 0, 0, 320, 224, 1);
        frame.invoke(StageplayWorkspace.Action.OBJECTS);
        assertFalse(workspace.menu().isOpen()); queue.removeFirst().run(); choose(workspace, 0);
        workspace.menu().setValue("name", "Directed prop"); workspace.menu().setValue("object", "86");
        workspace.menu().setValue("mode", "POSE"); workspace.menu().submit();
        assertNull(workspace.menu().error());
        frame.seek(30); frame.invoke(StageplayWorkspace.Action.OBJECTS);
        while (!queue.isEmpty()) queue.removeFirst().run(); choose(workspace, 4);
        assertNull(workspace.menu().error());
        var path = root.resolve("objects.stageplay"); workspace.saveTo(path);
        var reopened = StageplayStore.read(path);
        assertEquals(workspace.controller().document(), reopened);
        assertEquals(StageplayDocument.ObjectMode.POSE, reopened.objectActors().getFirst().keys().getFirst().mode());
        assertEquals(-1, reopened.objectActors().getFirst().layoutIndex());
        assertEquals(StageplayDocument.WorldCommandKind.CLEAR_OBJECTS, reopened.worldCommands().getFirst().kind());
        assertEquals(30, reopened.worldCommands().getFirst().frame());
    }

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
        workspace.menu().setValue("mode", "POSER");
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
        workspace.menu().setValue("mode", "POSER");
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

    @Test
    void dialogueCommandsAreQueuedAndTimelineUsesSpeakerNames() {
        var workspace=workspace(); workspace.activate();
        var queue=new ArrayDeque<Runnable>();
        var frame=new StageplayChromeShell.Frame(workspace,queue::addLast,0,0,320,224,1);
        frame.invoke(StageplayWorkspace.Action.DIALOGUE);
        assertFalse(workspace.menu().isOpen()); queue.removeFirst().run();
        assertEquals("Dialogue lines",workspace.menu().title()); workspace.menu().close();
        frame.invoke(StageplayWorkspace.Action.DIALOGUE_TYPERS); queue.removeFirst().run();
        assertEquals("Reusable dialogue typers",workspace.menu().title()); workspace.menu().close();
        frame.invoke(StageplayWorkspace.Action.DIALOGUE_SKINS); queue.removeFirst().run();
        assertEquals("Reusable dialogue decorations",workspace.menu().title()); workspace.menu().close();
        workspace.invoke(StageplayWorkspace.Action.ADD_ACTOR); workspace.menu().setValue("name","Sonic"); workspace.menu().submit();
        String actor=workspace.controller().selectedActorId();
        var doc=workspace.controller().document().withDialogue(List.of(new StageplayDocument.DialogueCue(
                "speech",20,100,actor,"","Hello!","","",8,140,304,72,"",List.of())));
        var rows=new StageplayChromeShell(new EditorChromeHost()).cueRows(doc);
        var row=rows.stream().filter(r -> r.track().equals("Dialogue / Sonic")).findFirst().orElseThrow();
        assertEquals(20,row.frame()); assertEquals("Hello!",row.detail()); assertEquals(actor,row.actorId());
    }

    @Test
    void physicalDirectionUsesSharedFormsAndListsGoalsWithNativeActions() throws Exception {
        var workspace=workspace(); workspace.activate();
        workspace.invoke(StageplayWorkspace.Action.ADD_ACTOR); workspace.menu().submit();
        assertTrue(workspace.selectedActorIsPuppet());
        var chrome=chrome(workspace);
        assertFalse(chrome.key(GLFW_KEY_K,GLFW_PRESS,0));
        assertFalse(workspace.menu().isOpen()); chrome.update(new InputHandler());
        assertEquals("Direct Sonic",workspace.menu().title());
        var input=new InputHandler(); input.handleKeyEvent(GLFW_KEY_ENTER,GLFW_PRESS); input.refreshLogicalSnapshot(); workspace.menu().update(input);
        assertEquals("Move to destination",workspace.menu().title());
        workspace.menu().setValue("x","300"); workspace.menu().setValue("y","112"); workspace.menu().setValue("urgency","0.5"); workspace.menu().submit();
        var document=workspace.controller().document();
        var track=document.puppets().getFirst();
        document=document.withPuppets(List.of(new StageplayDocument.PuppetTrack(track.actorId(),true,0,List.of(
                track.commands().getFirst(),new StageplayDocument.PuppetCommand("jump",10,StageplayDocument.PuppetCommandKind.JUMP,0,0,0,12),
                new StageplayDocument.PuppetCommand("stop",40,StageplayDocument.PuppetCommandKind.STOP,0,0,0,0)))));
        var rows=new StageplayChromeShell(new EditorChromeHost()).cueRows(document);
        assertEquals(List.of(0,0,0,10,40),rows.stream().map(StageplayChromeShell.CueRow::frame).toList());
        var move=rows.stream().filter(r -> r.track().endsWith("MOVE_TO")).findFirst().orElseThrow();
        assertTrue(move.detail().contains("300.00, 112.00 / 50%")); assertEquals(track.actorId(),move.actorId());
        assertTrue(rows.stream().anyMatch(r -> r.track().endsWith("JUMP")));
        assertTrue(rows.stream().anyMatch(r -> r.track().endsWith("STOP")));
    }

    @Test
    void nativeContentsPoliciesExposeTheSameFramingAndSaveContract(@TempDir Path root) throws Exception {
        for (var policy : List.of(StageplayDocument.WorldPolicy.LIVE, StageplayDocument.WorldPolicy.TERRAIN_ONLY)) {
            var document = StageplayDocument.empty("s2", 0, 0, 0, 500, 384, 216)
                    .withSet(new StageplayDocument.SetSource("s2", 0, 0, "", "", policy));
            var workspace = new StageplayWorkspace(() -> document); workspace.activate();
            workspace.invoke(StageplayWorkspace.Action.CAMERA_KEY); choose(workspace, 0);
            workspace.menu().setValue("zoom", "1.5");
            workspace.menu().setValue("interpolation", "SMOOTH"); workspace.menu().submit();
            assertNull(workspace.menu().error(), policy.name());
            assertEquals(1.5, workspace.controller().document().camera().getFirst().zoom());
            assertEquals(216, workspace.controller().document().height());
            assertTrue(StageplayChromeShell.worldLabel(workspace).startsWith("Native world"));
            workspace.seek(60); workspace.invoke(StageplayWorkspace.Action.CAMERA_KEY); choose(workspace, 0);
            workspace.menu().setValue("zoom", ".75"); workspace.menu().submit();
            assertNull(workspace.menu().error(), policy.name());
            assertEquals(1.125, com.openggf.stageplay.StageplayTimeline.sample(
                    workspace.controller().document(), 30).camera().zoom());
            var path = root.resolve(policy.name() + ".stageplay"); workspace.saveTo(path);
            assertEquals(workspace.controller().document(), com.openggf.stageplay.StageplayStore.read(path));
        }
    }

    @Test
    void liveDirectionRoundTripsTargetsWaitsAbilitiesAndFollowThroughSharedForms(@TempDir Path root) throws Exception {
        var document = StageplayDocument.empty("s3k",0,0,0,0,320,224)
                .withSet(new StageplayDocument.SetSource("s3k",0,0,"","",StageplayDocument.WorldPolicy.LIVE));
        var workspace = new StageplayWorkspace(() -> document); workspace.activate();
        workspace.invoke(StageplayWorkspace.Action.ADD_ACTOR);
        workspace.menu().setValue("name","Tails"); workspace.menu().setValue("character","tails"); workspace.menu().submit();
        assertNull(workspace.menu().error());
        String actor = workspace.controller().selectedActorId();
        workspace.invoke(StageplayWorkspace.Action.SET_KEY); choose(workspace,0);
        workspace.menu().setValue("layout","179"); workspace.menu().setValue("object","74");
        workspace.menu().setValue("subtype","1"); workspace.menu().setValue("offsetX","16");
        workspace.menu().setValue("timeout","120"); workspace.menu().submit();
        assertNull(workspace.menu().error());
        var track=workspace.controller().document().puppets().getFirst();
        var commands=new java.util.ArrayList<>(track.commands());
        commands.add(new StageplayDocument.PuppetCommand("wait",1,StageplayDocument.PuppetCommandKind.WAIT,0,0,0,0,
                track.commands().getFirst().target(),StageplayDocument.WaitCondition.ON_OBJECT,120));
        commands.add(new StageplayDocument.PuppetCommand("fly",30,StageplayDocument.PuppetCommandKind.FLY_TO,200,80,.5,0));
        commands.add(new StageplayDocument.PuppetCommand("hover",60,StageplayDocument.PuppetCommandKind.HOVER,0,0,0,30));
        commands.add(new StageplayDocument.PuppetCommand("release",90,StageplayDocument.PuppetCommandKind.RELEASE,0,0,0,0));
        var scene=workspace.controller().document().withPuppets(List.of(new StageplayDocument.PuppetTrack(actor,true,0,commands)))
                .withCamera(List.of(new StageplayDocument.CameraKey(0,0,0,1,StageplayDocument.Interpolation.HOLD,
                        new StageplayDocument.CameraFollow(List.of(actor),12,8,4,.2,0,0))));
        Path saved=root.resolve("live.stageplay"); StageplayStore.write(saved,scene);
        assertEquals(scene,StageplayStore.read(saved));
        var rows=new StageplayChromeShell(new EditorChromeHost()).cueRows(StageplayStore.read(saved));
        assertTrue(rows.stream().anyMatch(r -> r.track().endsWith("MOVE_TO") && r.detail().contains("Layout #179 + 16.00, 0.00") && r.detail().contains("timeout 120")));
        assertTrue(rows.stream().anyMatch(r -> r.track().endsWith("WAIT") && r.detail().contains("ON_OBJECT") && r.detail().contains("Layout #179")));
        assertTrue(rows.stream().anyMatch(r -> r.track().endsWith("FLY_TO") && r.detail().contains("200.00, 80.00 / 50%")));
        assertTrue(rows.stream().anyMatch(r -> r.track().endsWith("HOVER") && r.detail().contains("30 frames")));
        assertTrue(rows.stream().anyMatch(r -> r.track().endsWith("RELEASE") && r.detail().equals("Release native controls")));
        assertTrue(rows.stream().anyMatch(r -> r.track().equals("Camera") && r.detail().equals("Follow " + actor)));
    }

    @Test
    void liveInspectorUsesObservedSharedRingsDamageAndSupport() {
        String status=StageplayChromeShell.liveActorStatus(new StageplayTimeline.LiveActor(
                "tails",0,-3,true,false,true,179,"FLIGHT",1,24,2,5,"AVAILABLE"));
        assertTrue(status.contains("Shared rings: 0 (-3)"));
        assertTrue(status.contains("Hurt"));
        assertTrue(status.contains("FLIGHT / 1 / 24 ticks"));
        assertTrue(status.contains("Riding layout #179"));
        assertTrue(status.contains("Target: AVAILABLE"));
    }

    private static void choose(StageplayWorkspace workspace,int row) {
        var input=new InputHandler();
        for(int i=0;i<row;i++) {
            input.handleKeyEvent(GLFW_KEY_DOWN,GLFW_PRESS); input.refreshLogicalSnapshot(); workspace.menu().update(input);
            input.handleKeyEvent(GLFW_KEY_DOWN,GLFW_RELEASE); input.refreshLogicalSnapshot(); workspace.menu().update(input);
        }
        input.handleKeyEvent(GLFW_KEY_ENTER,GLFW_PRESS); input.refreshLogicalSnapshot(); workspace.menu().update(input);
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
