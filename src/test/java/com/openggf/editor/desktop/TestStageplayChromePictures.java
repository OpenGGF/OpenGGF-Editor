package com.openggf.editor.desktop;

import com.openggf.control.InputHandler;
import com.openggf.data.RomManager;
import com.openggf.editor.EditorCommandPalette;
import com.openggf.editor.EditorInputHandler;
import com.openggf.editor.EditorWindowChrome;
import com.openggf.editor.LevelEditorController;
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
                        .withAudio(List.of(new StageplayDocument.AudioCue("music", 0, StageplayDocument.AudioKind.ROM_MUSIC, "s2", 129, "", 1)));
                var workspace = new StageplayWorkspace(() -> scene);
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
                workspace.invoke(StageplayWorkspace.Action.CAMERA_MODE); workspace.moveCamera(8,0,1.1);
                surface.record(scene.width(),scene.height(),canvas -> renderer.draw(workspace.previewDocument(),workspace.previewFrame(),canvas));
                for (int i=0;i<4;i++) render(chrome,surface,scene,1280,720);
                capture("camera-720p",1280,720);
                for (int i=0;i<4;i++) render(chrome,surface,scene,1024,576);
                capture("camera-small",1024,576);
                assertEquals(GL_NO_ERROR, glGetError());
            }
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
