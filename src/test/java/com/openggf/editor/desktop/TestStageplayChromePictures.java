package com.openggf.editor.desktop;

import com.openggf.control.InputHandler;
import com.openggf.data.RomManager;
import com.openggf.editor.EditorCommandPalette;
import com.openggf.editor.EditorInputHandler;
import com.openggf.editor.EditorWindowChrome;
import com.openggf.editor.LevelEditorController;
import com.openggf.editor.project.EditorProjectMenuRenderer;
import com.openggf.game.GameServices;
import com.openggf.mods.scene.host.SceneRomLibrary;
import com.openggf.mods.scene.host.SceneSurface;
import com.openggf.stageplay.StageplayDocument;
import com.openggf.stageplay.StageplayTimeline;
import com.openggf.editor.stageplay.StageplayWorkspace;
import com.openggf.stageplay.runtime.StageplayRenderer;
import com.openggf.stageplay.runtime.StageplayResources;
import com.openggf.stageplay.editor.StageplayEditorSession;
import com.openggf.tools.HeadlessGameBoot;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Display-free desktop Stageplay review images. Origin: 2026-10-21 Stageplay task.
 * Requires -Dopenggf.stageplay.desktopPictures=true, surfaceless EGL and sonic2.rom.path.
 * Writes target/stageplay-desktop/*.png; never uses the user's desktop.
 */
class TestStageplayChromePictures {
    @Test
    void captureActualDesktopStageplayWithRomSetAndActors() throws Exception {
        assumeTrue(Boolean.getBoolean("openggf.stageplay.desktopPictures"), "Explicit desktop picture refresh only");
        assertEquals("surfaceless-egl", System.getProperty("openggf.headless.gl"), "No display fallback is allowed");
        String rom = System.getProperty("sonic2.rom.path", "");
        assertFalse(rom.isBlank(), "Supply an absolute S2 ROM path");
        assertTrue(Path.of(rom).isAbsolute() && Files.isRegularFile(Path.of(rom)));
        try (var boot = new HeadlessGameBoot(1600, 900, 320, 224, 320, 224)) {
            boot.setBootToTitle(true);
            boot.boot(Path.of(rom), 0, 0);
            try (var library = new SceneRomLibrary(GameServices.module(), GameServices.rom().getRom(), RomManager.getInstance());
                 var resources = new StageplayResources(library, false);
                 var surface = new SceneSurface();
                 var menuRenderer = new EditorProjectMenuRenderer();
                 var chrome = new ImGuiEditorChrome()) {
                int[] start = GameServices.module().getZoneRegistry().getStartPosition(0, 0);
                double x = Math.max(0, start[0] - 80), y = Math.max(0, start[1] - 140);
                var scene = StageplayDocument.empty("s2", 0, 0, x, y, 384, 216).withName("The next adventure")
                        .withActors(List.of(
                                new StageplayDocument.Actor("hero", "Sonic", "s2", "sonic", List.of(key(0, x + 80, start[1], -1), key(120, x + 160, start[1], 1),key(240,x+80,start[1],-1))),
                                new StageplayDocument.Actor("friend", "Tails", "s2", "tails", List.of(key(0, x + 220, start[1], -1),key(240,x+220,start[1],-1)))))
                        .withCamera(List.of(new StageplayDocument.CameraKey(0,x,y,1,StageplayDocument.Interpolation.LINEAR),
                                new StageplayDocument.CameraKey(240,x,y,1,StageplayDocument.Interpolation.HOLD)))
                        .withCameraEffects(List.of(new StageplayDocument.CameraEffect("quake",30,90,2,4,8,7)))
                        .withSceneChanges(List.of(new StageplayDocument.SceneChange("next-shot",240,
                                new StageplayDocument.SetSource("s2",0,1,"",""),StageplayDocument.Transition.FADE_BLACK,30)))
                        .withCaptions(List.of(new StageplayDocument.Caption("greeting", 0, 180, "Ready for another adventure?", 12, 190, -1)))
                        .withAudio(List.of(new StageplayDocument.AudioCue("music", 0, StageplayDocument.AudioKind.ROM_MUSIC, "s2", 129, "", 1)))
                        .withDialogueTypers(List.of(new StageplayDocument.DialogueTyper("conversation",3,8,0)))
                        .withDialogueSkins(List.of(new StageplayDocument.DialogueSkin("warm",0xfff5f0df,0xffffcf66,
                                0xff172338,0xffa8b8cf,6,"",0,true)))
                        .withDialogue(List.of(new StageplayDocument.DialogueCue("spoken-greeting",0,180,"hero","",
                                "Tails, ready for another adventure?","warm","conversation",12,8,360,62,"",List.of())));
                var line = StageplayTimeline.sample(scene,60).dialogue().getFirst();
                assertEquals("Sonic",line.speakerName());
                assertFalse(line.visibleText().isEmpty());
                var workspace = new StageplayWorkspace(new StageplayWorkspace.Host() {
                    public StageplayDocument initialDocument() { return scene; }
                    public StageplayTimeline.Frame resolvePreview(StageplayDocument document,Path root,int frame) throws java.io.IOException {
                        return resources.resolveFrame(document,StageplayTimeline.sample(document,frame));
                    }
                });
                workspace.activate();
                workspace.selectActor("hero");
                workspace.seek(60);
                var controller = new LevelEditorController();
                chrome.init(new EditorWindowChrome.Host() {
                    public LevelEditorController controller() { return controller; }
                    public void dispatch(EditorInputHandler.Action action) { fail("Stageplay cannot dispatch terrain edits"); }
                    public List<EditorCommandPalette.HostCommand> commands() { return List.of(); }
                    public StageplayWorkspace stageplay() { return workspace; }
                }, 1);
                var renderer = new StageplayRenderer(resources);
                for (int frame : new int[] {0, 60, 120}) for (var actor : StageplayTimeline.sample(scene, frame).actors()) {
                    var sprite = resources.character(actor.gameId(), actor.character()).frame(renderer.mappingFrame(actor));
                    assertTrue(java.util.Arrays.stream(sprite.image().pixels()).anyMatch(pixel -> (pixel >>> 24) != 0),
                            "The pictured cast must resolve visible ROM artwork: " + actor.actorId() + " at frame " + frame);
                }
                surface.record(scene.width(),scene.height(), canvas -> renderer.draw(scene, StageplayTimeline.sample(scene, 60), canvas));
                assertTrue(chrome.setActive(true));
                for (int i = 0; i < 4; i++) render(chrome, surface,scene,1600,900);
                capture("workspace",1600,900);
                chrome.key(GLFW_KEY_F1, GLFW_PRESS, 0);
                render(chrome, surface,scene,1600,900);
                render(chrome, surface,scene,1600,900);
                capture("commands",1600,900);
                chrome.key(GLFW_KEY_ESCAPE,GLFW_PRESS,0); chrome.update(new InputHandler());
                workspace.invoke(StageplayWorkspace.Action.CAMERA_MODE); workspace.moveCamera(8,0,2);
                surface.record(scene.width(),scene.height(),canvas -> renderer.draw(workspace.previewDocument(),workspace.previewFrame(),canvas));
                for (int i=0;i<4;i++) render(chrome,surface,scene,1280,720);
                capture("camera-720p",1280,720);
                for (int i=0;i<4;i++) render(chrome,surface,scene,1024,576);
                capture("camera-small",1024,576);

                // The production host disables desktop chrome while the shared native modal owns input.
                chrome.setActive(false);
                workspace.invoke(StageplayWorkspace.Action.DIALOGUE);
                choose(workspace,3); // Existing Sonic line, after Add / typers / decorations.
                assertEquals("Sonic / dialogue",workspace.menu().title());
                renderMenu(menuRenderer,workspace,1600,900);
                capture("dialogue-line-menu",1600,900);
                choose(workspace,0);
                assertEquals("Dialogue line",workspace.menu().title());
                assertEquals(scene.dialogue().getFirst().text(),workspace.menu().values().get("text"));
                renderMenu(menuRenderer,workspace,1600,900);
                capture("dialogue-line-form",1600,900);

                workspace.menu().close();
                workspace.invoke(StageplayWorkspace.Action.DIALOGUE_SKINS);
                choose(workspace,1); // Existing warm decoration profile.
                assertEquals("Decoration: warm",workspace.menu().title());
                renderMenu(menuRenderer,workspace,1600,900);
                capture("dialogue-decoration-menu",1600,900);
                choose(workspace,0);
                assertEquals("Decoration: warm",workspace.menu().title());
                assertEquals("FFFFCF66",workspace.menu().values().get("name"));
                renderMenu(menuRenderer,workspace,1600,900);
                capture("dialogue-decoration-form",1600,900);
                workspace.menu().close();
                assertEquals(scene,workspace.controller().document(),"Reviewing forms must not change the scene");

                // Exercise the real conversion, goal form and native resolver for the physical actor inspector.
                workspace.invoke(StageplayWorkspace.Action.CAMERA_MODE);
                workspace.seek(0);
                workspace.invoke(StageplayWorkspace.Action.EDIT_ACTOR);
                workspace.menu().setValue("mode","PUPPET"); workspace.menu().submit(); choose(workspace,0);
                assertTrue(workspace.selectedActorIsPuppet());
                workspace.invoke(StageplayWorkspace.Action.SET_KEY); choose(workspace,0);
                workspace.menu().setValue("x",Double.toString(start[0]+112));
                workspace.menu().setValue("y",Integer.toString(start[1])); workspace.menu().setValue("urgency","0.65"); workspace.menu().submit();
                assertNull(workspace.menu().error());
                workspace.seek(20); workspace.invoke(StageplayWorkspace.Action.SET_KEY); choose(workspace,1); workspace.menu().submit();
                assertNull(workspace.menu().error());
                workspace.seek(60);
                var physical=workspace.controller().document(); var resolved=workspace.previewFrame();
                assertNotEquals(StageplayTimeline.PuppetStatus.UNRESOLVED,resolved.puppets().getFirst().status());
                assertNotEquals((double)start[0],resolved.actors().stream().filter(a -> a.actorId().equals("hero")).findFirst().orElseThrow().x(),
                        "The pictured physical role must actually move");
                surface.record(physical.width(),physical.height(),canvas -> renderer.draw(physical,resolved,canvas));
                chrome.setActive(true);
                for (int i=0;i<4;i++) render(chrome,surface,physical,1280,720);
                capture("physics-720p",1280,720);
                chrome.setActive(false);
                workspace.invoke(StageplayWorkspace.Action.SET_KEY);
                renderMenu(menuRenderer,workspace,1600,900); capture("physics-actions",1600,900);
                choose(workspace,0);
                renderMenu(menuRenderer,workspace,1600,900); capture("physics-goal-form",1600,900);
                workspace.menu().close();
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    @Test
    void liveAirTrafficUsesNativePreviewAndDesktopAbilityInspector() throws Exception {
        assumeTrue(Boolean.getBoolean("openggf.stageplay.desktopPictures"), "Explicit desktop picture refresh only");
        assertEquals("surfaceless-egl",System.getProperty("openggf.headless.gl"));
        Path rom=Path.of(System.getProperty("sonic2.rom.path"));
        Path nativeRom=Path.of(System.getProperty("s3k.rom.path"));
        Path source=Path.of(System.getProperty("openggf.stageplay.liveScene"));
        assertTrue(rom.isAbsolute() && Files.isRegularFile(rom));
        assertTrue(nativeRom.isAbsolute() && Files.isRegularFile(nativeRom));
        var scene=com.openggf.stageplay.StageplayStore.read(source);
        try(var boot=new HeadlessGameBoot(1600,900,320,224,320,224)) {
            boot.setBootToTitle(true); boot.boot(rom,0,0);
            GameServices.configuration().setSessionOverride(com.openggf.configuration.SonicConfiguration.SONIC_3K_ROM,nativeRom.toString());
            var parent=com.openggf.game.session.SessionManager.getCurrentGameplayMode();
            var level=parent.getLevelManager().getCurrentLevel();
            try(var library=new SceneRomLibrary(GameServices.module(),GameServices.rom().getRom(),RomManager.getInstance());
                var resources=new StageplayResources(library,false);var surface=new SceneSurface();
                var menuRenderer=new EditorProjectMenuRenderer();var chrome=new ImGuiEditorChrome()) {
                var workspace=new StageplayWorkspace(new StageplayWorkspace.Host() {
                    public StageplayDocument initialDocument() { return scene; }
                    public StageplayTimeline.Frame resolvePreview(StageplayDocument document,Path root,int frame) throws java.io.IOException {
                        return resources.resolveFrame(document,StageplayTimeline.sample(document,frame));
                    }
                });
                workspace.activate(); workspace.selectActor("tails"); workspace.seek(150);
                var resolved=workspace.previewFrame();
                assertNotNull(resolved.world(),workspace.status());
                assertEquals(2,resolved.world().actors().size());
                assertTrue(resolved.world().actors().stream().anyMatch(a -> a.actorId().equals("tails") && a.abilityState()>0));
                assertSame(parent,com.openggf.game.session.SessionManager.getCurrentGameplayMode());
                assertSame(level,parent.getLevelManager().getCurrentLevel());
                var controller=new LevelEditorController();
                chrome.init(new EditorWindowChrome.Host() {
                    public LevelEditorController controller() { return controller; }
                    public void dispatch(EditorInputHandler.Action action) { fail("Stageplay cannot dispatch terrain edits"); }
                    public List<EditorCommandPalette.HostCommand> commands() { return List.of(); }
                    public StageplayWorkspace stageplay() { return workspace; }
                },1);
                var renderer=new StageplayRenderer(resources);
                surface.record(scene.width(),scene.height(),canvas -> renderer.draw(scene,resolved,canvas));
                chrome.setActive(true);
                for(int i=0;i<4;i++) render(chrome,surface,scene,1600,900);
                capture("live-air-traffic",1600,900);
                chrome.setActive(false); workspace.invoke(StageplayWorkspace.Action.SET_KEY);
                renderMenu(menuRenderer,workspace,1600,900); capture("live-actions",1600,900);
                workspace.menu().close();
                workspace.invoke(StageplayWorkspace.Action.CAMERA_KEY);
                renderMenu(menuRenderer,workspace,1600,900); capture("live-camera-follow",1600,900);
                workspace.menu().close();
                assertEquals(GL_NO_ERROR,glGetError());
            }
            assertSame(parent,com.openggf.game.session.SessionManager.getCurrentGameplayMode());
            assertSame(level,parent.getLevelManager().getCurrentLevel());
        }
    }

    private static StageplayDocument.ActorKey key(int frame, double x, double y, int pose) {
        // S2 mapping frame zero is empty. WAIT (5) resolves the shipped standing frames.
        return new StageplayDocument.ActorKey(frame, x, y, 5, pose, false, true, 1, StageplayDocument.Interpolation.LINEAR);
    }

    private static void render(ImGuiEditorChrome chrome, SceneSurface surface,StageplayDocument scene,int width,int height) {
        assertTrue(chrome.update(new InputHandler()));
        int[] viewport = chrome.gameViewport(width,height,width,height,320,224);
        glViewport(0, 0, width,height);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        int[] fitted = StageplayEditorSession.fitViewport(viewport,scene.width(),scene.height());
        glViewport(fitted[0], fitted[1], fitted[2], fitted[3]);
        surface.render(SceneSurface.projection(scene.width(),scene.height()), fitted);
        chrome.render(width,height,width,height);
    }

    private static void renderMenu(EditorProjectMenuRenderer renderer,StageplayWorkspace workspace,int width,int height) {
        assertTrue(workspace.menu().isOpen());
        glViewport(0,0,width,height);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0,0,0,1);
        glClear(GL_COLOR_BUFFER_BIT);
        int[] fit=StageplayChromeShell.fitPreview(0,0,width,height,320,224);
        glViewport(fit[0],height-fit[1]-fit[3],fit[2],fit[3]);
        renderer.render(workspace.menu(),SceneSurface.projection(320,224),320);
    }

    private static void choose(StageplayWorkspace workspace,int row) {
        assertTrue(workspace.menu().isOpen());
        var input=new InputHandler();
        for (int i=0;i<row;i++) press(workspace,input,GLFW_KEY_DOWN);
        press(workspace,input,GLFW_KEY_ENTER);
    }

    private static void press(StageplayWorkspace workspace,InputHandler input,int key) {
        input.handleKeyEvent(key,GLFW_PRESS); input.refreshLogicalSnapshot();
        assertTrue(workspace.tick(input)); input.update();
        input.handleKeyEvent(key,GLFW_RELEASE); input.update();
    }

    private static void capture(String name,int width,int height) throws Exception {
        glFinish();
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, width,height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            var image = new BufferedImage(width,height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int at = ((height-1-y) * width + x) * 4;
                image.setRGB(x, y, (pixels.get(at) & 255) << 16 | (pixels.get(at + 1) & 255) << 8 | pixels.get(at + 2) & 255);
            }
            Path output = Path.of("target", "stageplay-desktop", name + ".png");
            Files.createDirectories(output.getParent());
            assertTrue(ImageIO.write(image, "png", output.toFile()));
        } finally { MemoryUtil.memFree(pixels); }
    }
}
