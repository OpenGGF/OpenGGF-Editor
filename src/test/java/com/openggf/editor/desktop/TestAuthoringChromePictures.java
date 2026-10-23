package com.openggf.editor.desktop;

import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.NullAudioBackend;
import com.openggf.control.InputHandler;
import com.openggf.editor.EditorCommandPalette;
import com.openggf.editor.EditorInputHandler;
import com.openggf.editor.EditorSpawnEditMode;
import com.openggf.editor.EditorWindowChrome;
import com.openggf.editor.LevelEditorController;
import com.openggf.editor.EditorCommand;
import com.openggf.editor.project.EditorProjectDocument;
import com.openggf.editor.project.EditorProjectMetadata;
import com.openggf.editor.project.EditorProjectWorkspace;
import com.openggf.editor.persistence.FullLevelExporter;
import com.openggf.editor.render.EditorOverlayRenderer;
import com.openggf.game.GameId;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.LevelLoadMode;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.SessionManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Level;
import com.openggf.level.MutableLevel;
import com.openggf.tools.HeadlessGameBoot;
import imgui.ImGui;
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
import static org.lwjgl.opengl.GL11.*;

/**
 * Actual desktop authoring-shell evidence. Origin: 2026-10-22 editor authoring parity task.
 * Opt in with -Dopenggf.editor.authoringPictures=true, -Dopenggf.headless.gl=surfaceless-egl
 * and -Dsonic2.rom.path=/absolute/existing/Sonic2.gen. Unset DISPLAY and WAYLAND_DISPLAY.
 * Writes only target/authoring-desktop/*.png; never launches Engine or sends desktop input.
 */
class TestAuthoringChromePictures {
    private static final int WIDTH = 1600, HEIGHT = 1000;
    private static final int GAME_WIDTH = 320, GAME_HEIGHT = 224;
    private static final String PALETTE_HEADER = "Level palette / Genesis 9-bit";

    @Test void captureTypedObjectInspectorPaletteAndMovableRegionGhost() throws Exception {
        assumeTrue(Boolean.getBoolean("openggf.editor.authoringPictures"), "Explicit authoring picture refresh only");
        assertEquals("surfaceless-egl", System.getProperty("openggf.headless.gl"), "No display fallback is allowed");
        assertTrue(System.getenv("DISPLAY") == null && System.getenv("WAYLAND_DISPLAY") == null,
                "Launch with DISPLAY and WAYLAND_DISPLAY unset");
        String configuredRom = System.getProperty("sonic2.rom.path", "");
        assertFalse(configuredRom.isBlank(), "Supply an explicit absolute S2 ROM path");
        Path rom = Path.of(configuredRom);
        assertTrue(rom.isAbsolute() && Files.isRegularFile(rom), "The supplied S2 ROM must exist");

        try (var boot = new HeadlessGameBoot(WIDTH, HEIGHT, GAME_WIDTH, GAME_HEIGHT, GAME_WIDTH, GAME_HEIGHT)) {
            boot.setBootToTitle(true);
            boot.boot(rom, 0, 0);
            assertEquals(GameId.S2, GameServices.module().getGameId());
            assertTrue(GameServices.audio().getBackend() instanceof NullAudioBackend
                            || GameServices.audio().getBackend() instanceof HeadlessSmpsAudioBackend,
                    "The capture must own a no-device audio backend");
            GameServices.level().loadZoneAndAct(0, 0, LevelLoadMode.PREVIEW_CAPTURE);
            var library = MutableLevel.snapshot(GameServices.level().getCurrentLevel());
            var module = GameServices.module();
            var metadata = new EditorProjectMetadata(GameId.S2, "Emerald Authoring Workshop", 0, 0,
                    "offscreen-s2-evidence", Math.max(0, library.getMinX()), Math.max(0, library.getMinY()),
                    library.getMinX(), library.getMaxX(), library.getMinY(), library.getMaxY(),
                    new FullLevelExporter.ExportMusic.Stock(0x81), null);
            var document = EditorProjectDocument.create(metadata, library);
            var level = document.level();
            var monitor = level.getObjects().stream().filter(object -> object.objectId() == 0x26)
                    .findFirst().orElseThrow(() -> new AssertionError("EHZ1 must supply a native monitor placement"));
            var world = SessionManager.getCurrentWorldSession();
            world.setCurrentLevel(level);
            var editor = SessionManager.enterEditorMode(new EditorCursorState(monitor.x(), monitor.y()));
            assertSame(level, editor.getLevelManager().getCurrentLevel());
            var graphics = GameServices.graphics();
            graphics.bindRuntimeManagedReferences(editor.getCamera(), graphics.getFadeManager());
            for (int line = 0; line < level.getPaletteCount(); line++)
                graphics.cachePaletteTexture(level.getPalette(line), line);
            graphics.beginPatternAtlasBatch();
            try {
                for (int pattern = 0; pattern < level.getPatternCount(); pattern++)
                    graphics.cachePatternTexture(level.getPattern(pattern), pattern);
            } finally { graphics.endPatternAtlasBatch(); }

            var controller = new LevelEditorController();
            controller.attachLevel(level);
            controller.configureSpawnEditing(module.createObjectRegistry(), module.getObjectPlacementEncoding());
            var workspace = new EditorProjectWorkspace(new EditorProjectWorkspace.Host() {
                @Override public GameModule selectGame(GameId game) { assertEquals(GameId.S2, game); return module; }
                @Override public GameModule currentModule() { return module; }
                @Override public Level loadLibrary(int zone, int act) { return library; }
                @Override public String sourceFingerprint() { return "offscreen-s2-evidence"; }
                @Override public EditorCursorState cursor() { return controller.worldCursor(); }
                @Override public void finishPendingEdits() { controller.finishPendingEdits(); }
                @Override public void showDocument(EditorProjectDocument value) { controller.attachLevel(value.level()); }
                @Override public void playtest(EditorProjectDocument value, boolean fromCursor) { fail("No gameplay launch in UI capture"); }
                @Override public void closeDocument() { fail("No project close in UI capture"); }
                @Override public void menuClosed(EditorProjectDocument value) { }
                @Override public void execute(EditorCommand command) { controller.executeCommand(command); }
                @Override public void status(String message) { controller.setStatus(message); }
            });
            controller.setProjects(workspace);
            workspace.openProject(document);
            assertSame(document, workspace.document());
            assertTrue(!workspace.backgroundOptions().isEmpty());
            workspace.applySettings(document, document.metadata().settings()
                    .withBackground(workspace.backgroundOptions().getFirst().source())
                    .withWaterHeight(576)
                    .withTeam(com.openggf.editor.project.EditorLevelTeams.choices().get(2).team()));
            controller.setPaletteRefresh(line -> graphics.cachePaletteTexture(level.getPalette(line), line));
            controller.setSpawnEditMode(EditorSpawnEditMode.OBJECTS);
            controller.setWorldCursor(new EditorCursorState(monitor.x(), monitor.y()));
            controller.eyedropSpawnAtCursor();
            assertEquals(monitor.layoutIndex(), controller.selectedObjectSpawn().layoutIndex());
            assertTrue(controller.selectedObjectProperties().editableFields().stream()
                    .anyMatch(field -> field.id().equals("contents")), "The pictured inspector must have named fields");
            int brushSubtype = controller.objectPalette().selectedSubtype();
            controller.setSelectedObjectProperty("contents", 4);
            assertEquals(4, controller.selectedObjectSpawn().subtype());
            assertEquals(brushSubtype, controller.objectPalette().selectedSubtype(), "Editing the placement preserves its brush");
            controller.editPaletteColor(1, 0, 1, 4, 7);
            var editedColor = level.getPalette(1).getColor(0);
            assertArrayEquals(new int[] {36, 146, 255}, new int[] {
                    Byte.toUnsignedInt(editedColor.r), Byte.toUnsignedInt(editedColor.g), Byte.toUnsignedInt(editedColor.b)});

            int blockSize = level.getBlockPixelSize();
            int sourceX = monitor.x() / blockSize, sourceY = monitor.y() / blockSize;
            assertTrue(sourceX + 1 < level.getMap().getWidth());
            editor.getCamera().setX((short) Math.max(0, sourceX * blockSize - 16));
            editor.getCamera().setY((short) Math.max(0, sourceY * blockSize - 16));

            try (var chrome = new ImGuiEditorChrome()) {
                chrome.init(new EditorWindowChrome.Host() {
                    @Override public LevelEditorController controller() { return controller; }
                    @Override public void dispatch(EditorInputHandler.Action action) {
                        fail("The capture did not request a keyboard action: " + action);
                    }
                    @Override public List<EditorCommandPalette.HostCommand> commands() { return List.of(); }
                }, 1);
                assertTrue(chrome.setActive(true));
                for (int frame = 0; frame < 4; frame++) render(chrome, controller);
                openPaletteSection(chrome);
                for (int frame = 0; frame < 4; frame++) render(chrome, controller);
                capture("object-inspector-palette");

                controller.setSpawnEditMode(EditorSpawnEditMode.TILES);
                controller.setWorldCursor(new EditorCursorState(sourceX * blockSize, sourceY * blockSize));
                controller.beginRegionAtCursor();
                controller.finishRegionSelection();
                controller.copyRegion();
                controller.setWorldCursor(new EditorCursorState((sourceX + 1) * blockSize, sourceY * blockSize));
                byte destinationBefore = level.getMap().getValue(0, sourceX + 1, sourceY);
                controller.beginPastePreview();
                assertTrue(controller.isPastePreviewActive());
                assertNotNull(controller.pastePreview());
                assertEquals(sourceX + 1, controller.pastePreview().rectangle().x());
                assertEquals(1, controller.pastePreview().rectangle().width());
                assertFalse(controller.pastePreview().blocks().isEmpty());
                for (int frame = 0; frame < 4; frame++) render(chrome, controller);
                assertEquals(destinationBefore, level.getMap().getValue(0, sourceX + 1, sourceY),
                        "Reviewing the translucent ghost must not paste it into the level");
                capture("selection-ghost");
                controller.cancelPastePreview();

                // A minimal inspector context keeps the complete level-wide settings surface
                // visible; these values belong to the real workspace/document contract.
                controller.setSpawnEditMode(EditorSpawnEditMode.RINGS);
                setInspectorHeader(chrome, PALETTE_HEADER, false);
                for (int frame = 0; frame < 4; frame++) render(chrome, controller);
                capture("settings-panel");

                controller.setSpawnEditMode(EditorSpawnEditMode.OBJECTS);
                controller.objectPalette().setObjectKey("openggf:camera-zone");
                controller.setWorldCursor(new EditorCursorState(monitor.x() + 96, monitor.y() - 32));
                controller.placeObjectSpawnAtCursor();
                controller.setSelectedGizmoProperty("width", 160);
                controller.setSelectedGizmoProperty("height", 96);
                controller.setSelectedGizmoProperty("releaseAt", monitor.x() + 256);
                controller.setSelectedGizmoProperty("releaseCondition", "CROSS_X");
                editor.getCamera().setX((short) Math.max(0, monitor.x() - 32));
                editor.getCamera().setY((short) Math.max(0, monitor.y() - 64));
                editor.getCamera().captureRenderCopy();
                setInspectorHeader(chrome, "Level settings", false);
                for (int frame = 0; frame < 4; frame++) render(chrome, controller);
                assertEquals("openggf:camera-zone", controller.selectedObjectSpawn().objectKey());
                capture("camera-gizmo");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        }
    }

    /** Open the real header's native window state, without changing shell code or faking its controls. */
    private static void openPaletteSection(ImGuiEditorChrome chrome) throws Exception {
        setInspectorHeader(chrome, PALETTE_HEADER, true);
    }

    private static void setInspectorHeader(ImGuiEditorChrome chrome, String label, boolean open) throws Exception {
        var field = ImGuiEditorChrome.class.getDeclaredField("host");
        field.setAccessible(true);
        var host = (EditorChromeHost) field.get(chrome);
        host.beginFrame(WIDTH, HEIGHT, WIDTH, HEIGHT);
        try {
            ImGui.begin("Inspector");
            try {
                int id = ImGui.getID(label);
                ImGui.getStateStorage().setInt(id, open ? 1 : 0);
                ImGui.setScrollY(0);
                assertEquals(open ? 1 : 0, ImGui.getStateStorage().getInt(id));
            } finally { ImGui.end(); }
        } finally { host.endFrameAndRender(); }
    }

    private static void render(ImGuiEditorChrome chrome, LevelEditorController controller) {
        chrome.update(new InputHandler());
        int[] viewport = chrome.gameViewport(WIDTH, HEIGHT, WIDTH, HEIGHT, GAME_WIDTH, GAME_HEIGHT);
        assertNotNull(viewport);
        glViewport(0, 0, WIDTH, HEIGHT);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.025f, 0.03f, 0.045f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        var graphics = GameServices.graphics();
        graphics.setViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        graphics.setProjectionWidth(GAME_WIDTH);
        SessionManager.getCurrentEditorMode().getLevelManager().drawWithRenderOptions(null,
                new LevelManager.LevelRenderOptions(false, false, false, false, false, false, false));
        new EditorOverlayRenderer(controller, graphics).renderWorldSpaceOverlay();
        var camera = SessionManager.getCurrentEditorMode().getCamera();
        graphics.flushWithCamera(camera.getXCopy(), camera.getYCopy(), (short) GAME_WIDTH, (short) GAME_HEIGHT);
        chrome.render(WIDTH, HEIGHT, WIDTH, HEIGHT);
    }

    private static void capture(String name) throws Exception {
        glFinish();
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                int at = ((HEIGHT - 1 - y) * WIDTH + x) * 4;
                image.setRGB(x, y, (pixels.get(at) & 255) << 16
                        | (pixels.get(at + 1) & 255) << 8 | pixels.get(at + 2) & 255);
            }
            Path output = Path.of("target", "authoring-desktop", name + ".png");
            Files.createDirectories(output.getParent());
            assertTrue(ImageIO.write(image, "png", output.toFile()));
        } finally { MemoryUtil.memFree(pixels); }
    }
}
