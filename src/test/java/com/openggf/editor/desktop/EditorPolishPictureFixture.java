package com.openggf.editor.desktop;

import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.NullAudioBackend;
import com.openggf.control.InputHandler;
import com.openggf.editor.*;
import com.openggf.editor.hud.EditorHudRuntime;
import com.openggf.editor.render.EditorObjectPreviewCache;
import com.openggf.editor.render.EditorOverlayRenderer;
import com.openggf.game.GameId;
import com.openggf.game.GameServices;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.SessionManager;
import com.openggf.level.LevelManager;
import com.openggf.game.LevelLoadMode;
import com.openggf.level.MutableLevel;
import com.openggf.tools.HeadlessGameBoot;
import imgui.ImGui;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL11.*;

/** Three-game viewer pass using real ROM-loaded levels, production EGL and both editor fronts.
 * Origin: editor-authoring visual polish, 2026-10-23. Opt in: openggf.editor.polishPictures=true.
 * Outputs target/editor-polish; no display connection, device audio, or live object construction. */
abstract class EditorPolishPictureFixture {
    abstract GameId game();
    private static GameId capturedGame;
    static final int WIDTH = 1600, HEIGHT = 1000, LW = 320, LH = 224;

    @Test void captureBothEditors() throws Exception {
        GameId game = game();
        assumeTrue(Boolean.getBoolean("openggf.editor.polishPictures"), "Explicit editor polish picture refresh only");
        assertNull(capturedGame, "Each ROM capture requires a fresh JVM: use -DreuseForks=false");
        capturedGame = game;
        assertEquals("surfaceless-egl", System.getProperty("openggf.headless.gl"));
        assertNull(System.getenv("DISPLAY")); assertNull(System.getenv("WAYLAND_DISPLAY"));
        String property = switch (game) { case S1 -> "sonic1.rom.path"; case S2 -> "sonic2.rom.path"; case S3K -> "s3k.rom.path"; default -> throw new IllegalArgumentException("Stock game required"); };
        Path rom = Path.of(System.getProperty(property, ""));
        assertTrue(rom.isAbsolute() && Files.isRegularFile(rom), property);
        var priorGraphics = GameServices.graphics();
        int priorWidth = priorGraphics.getProjectionWidth();
        float[] priorProjection = priorGraphics.getProjectionMatrixBuffer() == null ? null : priorGraphics.getProjectionMatrixBuffer().clone();
        int[] priorViewport = {priorGraphics.getViewportX(), priorGraphics.getViewportY(),
                priorGraphics.getViewportWidth(), priorGraphics.getViewportHeight()};
        boolean priorBatching = priorGraphics.isBatchingEnabled();
        try (var boot = new HeadlessGameBoot(WIDTH, HEIGHT, LW, LH, LW, LH)) {
            boot.setBootToTitle(true); boot.boot(rom, 0, 0);
            GameServices.level().loadZoneAndAct(0, 0, LevelLoadMode.PREVIEW_CAPTURE);
            assertTrue(GameServices.audio().getBackend() instanceof NullAudioBackend
                    || GameServices.audio().getBackend() instanceof HeadlessSmpsAudioBackend);
            var module = GameServices.module();
            int monitorId = game == GameId.S3K ? 1 : 0x26;
            var monitor = GameServices.level().getCurrentLevel().getObjects().stream()
                    .filter(s -> s.objectId() == monitorId).findFirst().orElseThrow();
            GameServices.configuration().setConfigValue(com.openggf.configuration.SonicConfiguration.EDITOR_ENABLED, true);
            var engine = new com.openggf.Engine(com.openggf.game.session.EngineServices.current());
            engine.enterEditorFromCurrentPlayer(null, monitor.x(), monitor.y());
            assertEquals(com.openggf.game.GameMode.EDITOR, engine.getCurrentGameMode(), "Native editor entry must succeed before pictures");
            var controller = engine.getLevelEditorController();
            var level = controller.currentLevel();
            assertNotNull(level);
            var editor = SessionManager.getCurrentEditorMode();
            var graphics = GameServices.graphics();
            graphics.bindRuntimeManagedReferences(editor.getCamera(), graphics.getFadeManager());
            controller.setSpawnEditMode(EditorSpawnEditMode.OBJECTS);
            controller.setWorldCursor(new EditorCursorState(monitor.x(), monitor.y())); controller.eyedropSpawnAtCursor();
            editor.getCamera().setX((short) Math.max(0, monitor.x() - 112));
            editor.getCamera().setY((short) Math.max(0, monitor.y() - 130));
            editor.getCamera().captureRenderCopy();
            try (var cache = new EditorObjectPreviewCache()) {
                cache.refresh(level, controller.objectPalette());
                assertNotNull(cache.sprite(monitor), game + " monitor must have real ROM art");
                var report = new StringBuilder(game + " ROM preview catalogue\nAvailable:\n");
                for (var entry : controller.objectPalette().entries()) if (cache.available(entry)) report.append(entry.label()).append('\n');
                report.append("\nPlaced IDs without static art (logic markers / runtime assemblies):\n");
                level.getObjects().stream().map(s -> s.objectId()).distinct().sorted().forEach(id -> {
                    var entry = controller.objectPalette().entries().get(id);
                    if (!cache.available(entry)) report.append(entry.label()).append('\n');
                });
                Files.createDirectories(Path.of("target/editor-polish"));
                Files.writeString(Path.of("target/editor-polish/" + game + "-catalogue.txt"), report);
            }
            var dispatcher = new EditorInputHandler(controller);
            EditorWindowChrome.Host host = new EditorWindowChrome.Host() {
                public LevelEditorController controller() { return controller; }
                public void dispatch(EditorInputHandler.Action action) { dispatcher.handleAction(action); }
                public List<EditorCommandPalette.HostCommand> commands() { return List.of(); }
            };
            int objectCount = level.getObjects().size();
            try (var chrome = new ImGuiEditorChrome()) {
                chrome.init(host, 1); chrome.setActive(true);
                for (int i = 0; i < 5; i++) render(chrome, null, controller);
                capture(game + "-desktop-palette");
                controller.objectPalette().setObjectId(game == GameId.S3K ? 7 : 0x41);
                controller.setWorldCursor(new EditorCursorState(monitor.x() + 56, monitor.y()));
                for (int i = 0; i < 3; i++) render(chrome, null, controller);
                capture(game + "-desktop-ghost");
                if (game == GameId.S1) {
                    int subtype = controller.objectPalette().selectedSubtype();
                    controller.objectPalette().setObjectId(0x42);
                    controller.objectPalette().setSubtype(1);
                    for (int i = 0; i < 3; i++) render(chrome, null, controller);
                    capture(game + "-desktop-subtype");
                    controller.objectPalette().setSubtype(subtype);
                }
                controller.objectPalette().setObjectKey("openggf:camera-zone");
                controller.setWorldCursor(new EditorCursorState(monitor.x() + 32, monitor.y() - 90));
                controller.placeObjectSpawnAtCursor();
                for (int i = 0; i < 3; i++) render(chrome, null, controller);
                capture(game + "-desktop-gizmo");
                controller.setSpawnEditMode(EditorSpawnEditMode.RINGS);
                controller.setWorldCursor(new EditorCursorState(monitor.x() + 80, monitor.y() - 40));
                for (int i = 0; i < 3; i++) render(chrome, null, controller);
                capture(game + "-desktop-rings");
            }
            try (var hud = new EditorHudRuntime()) {
                hud.init(host, 1); assertTrue(hud.isAvailable()); hud.setActive(true);
                controller.setSpawnEditMode(EditorSpawnEditMode.OBJECTS);
                controller.objectPalette().setObjectId(monitorId);
                controller.setWorldCursor(new EditorCursorState(monitor.x(), monitor.y())); controller.eyedropSpawnAtCursor();
                for (int i = 0; i < 4; i++) render(null, hud, controller);
                capture(game + "-hud-palette");
                // Library is opened by the production HUD shortcut and remains controller-navigable.
                var input = new InputHandler();
                input.handleKeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, org.lwjgl.glfw.GLFW.GLFW_PRESS);
                hud.update(input);
                for (int i = 0; i < 4; i++) render(null, hud, controller);
                capture(game + "-hud-library");
                input.handleKeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, org.lwjgl.glfw.GLFW.GLFW_PRESS);
                hud.update(input);
                controller.objectPalette().setObjectId(game == GameId.S3K ? 7 : 0x41);
                controller.setWorldCursor(new EditorCursorState(monitor.x() + 56, monitor.y()));
                for (int i = 0; i < 3; i++) render(null, hud, controller);
                capture(game + "-hud-ghost");
                if (game == GameId.S1) {
                    controller.objectPalette().setObjectId(0x42);
                    controller.objectPalette().setSubtype(1);
                    for (int i = 0; i < 3; i++) render(null, hud, controller);
                    capture(game + "-hud-subtype");
                }
                controller.setWorldCursor(new EditorCursorState(monitor.x() + 32, monitor.y() - 90));
                controller.eyedropSpawnAtCursor();
                for (int i = 0; i < 3; i++) render(null, hud, controller);
                capture(game + "-hud-gizmo");
                controller.setSpawnEditMode(EditorSpawnEditMode.RINGS);
                controller.setWorldCursor(new EditorCursorState(monitor.x() + 80, monitor.y() - 40));
                for (int i = 0; i < 3; i++) render(null, hud, controller);
                assertMonitorVisible(hud, controller, monitor);
                capture(game + "-hud-rings");
            }
            assertEquals(objectCount + 1, level.getObjects().size(), "Only the explicit camera placement changes authoring state");
        } finally {
            com.openggf.Engine.clearGlobalInstance();
            priorGraphics.setProjectionWidth(priorWidth);
            priorGraphics.setProjectionMatrixBuffer(priorProjection);
            priorGraphics.setViewport(priorViewport[0], priorViewport[1], priorViewport[2], priorViewport[3]);
            priorGraphics.setBatchingEnabled(priorBatching);
        }
    }
    private static void render(ImGuiEditorChrome desktop, EditorHudRuntime hud, LevelEditorController controller) {
        if (desktop != null) desktop.update(new InputHandler());
        else hud.update(new InputHandler());
        int[] viewport = desktop != null ? desktop.gameViewport(WIDTH, HEIGHT, WIDTH, HEIGHT, LW, LH)
                : hud.gameViewport(WIDTH, HEIGHT, WIDTH, HEIGHT, LW, LH);
        glViewport(0, 0, WIDTH, HEIGHT); glDisable(GL_SCISSOR_TEST);
        glClearColor(.025f, .03f, .045f, 1); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        var graphics = GameServices.graphics();
        graphics.setViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        graphics.setProjectionWidth(LW);
        SessionManager.getCurrentEditorMode().getLevelManager().drawWithRenderOptions(null,
                new LevelManager.LevelRenderOptions(false, false, false, false, false, false, false));
        new EditorOverlayRenderer(controller, graphics).renderWorldSpaceOverlay();
        var camera = SessionManager.getCurrentEditorMode().getCamera();
        graphics.flushWithCamera(camera.getXCopy(), camera.getYCopy(), (short) LW, (short) LH);
        if (desktop != null) desktop.render(WIDTH, HEIGHT, WIDTH, HEIGHT);
        else hud.render(WIDTH, HEIGHT, WIDTH, HEIGHT);
    }
    /** Tool switches must retain the actual sprite pixels, even when the object hotbar is hidden. */
    private static void assertMonitorVisible(EditorHudRuntime hud, LevelEditorController controller,
                                             com.openggf.level.objects.ObjectSpawn monitor) {
        try (var expected = new EditorObjectPreviewCache()) {
            expected.refresh(controller.currentLevel(), controller.objectPalette());
            var sprite = expected.sprite(monitor);
            assertNotNull(sprite);
            int priorTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
            var tex = MemoryUtil.memAlloc(sprite.width() * sprite.height() * 4);
            var frame = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
            try {
                glBindTexture(GL_TEXTURE_2D, sprite.texture());
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, tex);
                glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, frame);
                assertEquals(GL_NO_ERROR, glGetError(), "ROM sprite/frame readback");
                int[] viewport = hud.gameViewport(WIDTH, HEIGHT, WIDTH, HEIGHT, LW, LH);
                int unit = viewport[2] / LW;
                var camera = SessionManager.getCurrentEditorMode().getCamera();
                int compared = 0, matched = 0;
                for (int y = 0; y < sprite.height(); y++) for (int x = 0; x < sprite.width(); x++) {
                    int at = (y * sprite.width() + x) * 4;
                    if ((tex.get(at + 3) & 255) != 255) continue;
                    int sx = viewport[0] + (monitor.x() - camera.getXCopy() - sprite.originX() + x) * unit + unit / 2;
                    int sy = viewport[1] + viewport[3] - (monitor.y() - camera.getYCopy() - sprite.originY() + y) * unit - unit / 2 - 1;
                    int actual = (sy * WIDTH + sx) * 4;
                    compared++;
                    if (tex.get(at) == frame.get(actual) && tex.get(at + 1) == frame.get(actual + 1)
                            && tex.get(at + 2) == frame.get(actual + 2)) matched++;
                }
                assertTrue(compared > 100, "Check a complete ROM pose rather than an empty texture");
                assertEquals(compared, matched, "Placed monitor remains visible when switching to Rings");
            } finally {
                glBindTexture(GL_TEXTURE_2D, priorTexture);
                MemoryUtil.memFree(tex); MemoryUtil.memFree(frame);
            }
        }
    }
    private static void capture(String name) throws Exception {
        glFinish(); var pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1); glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            assertEquals(GL_NO_ERROR, glGetError(), "frame readback");
            var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                int at = ((HEIGHT - 1 - y) * WIDTH + x) * 4;
                image.setRGB(x, y, (pixels.get(at) & 255) << 16 | (pixels.get(at + 1) & 255) << 8 | pixels.get(at + 2) & 255);
            }
            Path output = Path.of("target/editor-polish/" + name + ".png");
            Files.createDirectories(output.getParent()); assertTrue(ImageIO.write(image, "png", output.toFile()));
        } finally { MemoryUtil.memFree(pixels); }
    }
}
