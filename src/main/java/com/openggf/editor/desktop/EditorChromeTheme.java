package com.openggf.editor.desktop;

import imgui.ImGuiStyle;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

/**
 * One dark editor theme: neutral slate panels, a single blue accent for interaction and a
 * gold highlight reserved for the current selection, so selection never competes with hover.
 */
final class EditorChromeTheme {
    static final int ACCENT = rgba(0x4C9AFF, 1f);
    static final int SELECTION = rgba(0xFFC845, 1f);
    static final int SELECTION_FILL = rgba(0xFFC845, 0.16f);
    static final int CANVAS_BG = rgba(0x0E1014, 1f);
    static final int PANEL_BG = rgba(0x171A20, 1f);
    static final int CELL_BG = rgba(0x0F1115, 1f);
    static final int GRID_LINE = rgba(0x2A2F3A, 1f);
    static final int TEXT_DIM = rgba(0x8B93A3, 1f);
    static final int WARNING = rgba(0xF2A93B, 1f);
    static final int UNSAVED = rgba(0xF2A93B, 1f);
    static final int SAVED = rgba(0x5CC98A, 1f);

    private EditorChromeTheme() {
    }

    static void apply(ImGuiStyle style) {
        style.setWindowPadding(12, 10);
        style.setFramePadding(8, 5);
        style.setItemSpacing(8, 7);
        style.setItemInnerSpacing(6, 4);
        style.setCellPadding(6, 4);
        style.setIndentSpacing(14);
        style.setScrollbarSize(12);
        style.setGrabMinSize(10);
        style.setWindowRounding(0);
        style.setChildRounding(6);
        style.setFrameRounding(5);
        style.setPopupRounding(6);
        style.setScrollbarRounding(6);
        style.setGrabRounding(4);
        style.setTabRounding(5);
        style.setWindowBorderSize(0);
        style.setFrameBorderSize(0);
        style.setPopupBorderSize(1);
        style.setTabBorderSize(0);
        style.setDockingSeparatorSize(1);
        style.setSeparatorTextBorderSize(1);
        style.setWindowMenuButtonPosition(-1);

        ImVec4[] colors = style.getColors();
        set(colors, ImGuiCol.Text, 0xE6E9EF, 1f);
        set(colors, ImGuiCol.TextDisabled, 0x6C7486, 1f);
        set(colors, ImGuiCol.WindowBg, 0x171A20, 1f);
        set(colors, ImGuiCol.ChildBg, 0x13161B, 1f);
        set(colors, ImGuiCol.PopupBg, 0x1D2128, 0.98f);
        set(colors, ImGuiCol.Border, 0x2A2F3A, 1f);
        set(colors, ImGuiCol.BorderShadow, 0x000000, 0f);
        set(colors, ImGuiCol.FrameBg, 0x232731, 1f);
        set(colors, ImGuiCol.FrameBgHovered, 0x2B303C, 1f);
        set(colors, ImGuiCol.FrameBgActive, 0x323848, 1f);
        set(colors, ImGuiCol.TitleBg, 0x12141A, 1f);
        set(colors, ImGuiCol.TitleBgActive, 0x12141A, 1f);
        set(colors, ImGuiCol.TitleBgCollapsed, 0x12141A, 1f);
        set(colors, ImGuiCol.MenuBarBg, 0x12141A, 1f);
        set(colors, ImGuiCol.ScrollbarBg, 0x171A20, 0f);
        set(colors, ImGuiCol.ScrollbarGrab, 0x343A47, 1f);
        set(colors, ImGuiCol.ScrollbarGrabHovered, 0x414857, 1f);
        set(colors, ImGuiCol.ScrollbarGrabActive, 0x4C9AFF, 1f);
        set(colors, ImGuiCol.CheckMark, 0x4C9AFF, 1f);
        set(colors, ImGuiCol.SliderGrab, 0x4C9AFF, 1f);
        set(colors, ImGuiCol.SliderGrabActive, 0x76B2FF, 1f);
        set(colors, ImGuiCol.Button, 0x262B35, 1f);
        set(colors, ImGuiCol.ButtonHovered, 0x313746, 1f);
        set(colors, ImGuiCol.ButtonActive, 0x3A4254, 1f);
        set(colors, ImGuiCol.Header, 0x4C9AFF, 0.20f);
        set(colors, ImGuiCol.HeaderHovered, 0x4C9AFF, 0.28f);
        set(colors, ImGuiCol.HeaderActive, 0x4C9AFF, 0.36f);
        set(colors, ImGuiCol.Separator, 0x2A2F3A, 1f);
        set(colors, ImGuiCol.SeparatorHovered, 0x4C9AFF, 0.6f);
        set(colors, ImGuiCol.SeparatorActive, 0x4C9AFF, 1f);
        set(colors, ImGuiCol.ResizeGrip, 0x000000, 0f);
        set(colors, ImGuiCol.ResizeGripHovered, 0x4C9AFF, 0.5f);
        set(colors, ImGuiCol.ResizeGripActive, 0x4C9AFF, 1f);
        set(colors, ImGuiCol.Tab, 0x12141A, 1f);
        set(colors, ImGuiCol.TabHovered, 0x232731, 1f);
        set(colors, ImGuiCol.TabSelected, 0x171A20, 1f);
        set(colors, ImGuiCol.TabSelectedOverline, 0x4C9AFF, 1f);
        set(colors, ImGuiCol.TabDimmed, 0x12141A, 1f);
        set(colors, ImGuiCol.TabDimmedSelected, 0x171A20, 1f);
        set(colors, ImGuiCol.TabDimmedSelectedOverline, 0x4C9AFF, 0.4f);
        set(colors, ImGuiCol.DockingPreview, 0x4C9AFF, 0.35f);
        set(colors, ImGuiCol.DockingEmptyBg, 0x0E1014, 1f);
        set(colors, ImGuiCol.TableHeaderBg, 0x1D2128, 1f);
        set(colors, ImGuiCol.TableBorderStrong, 0x2A2F3A, 1f);
        set(colors, ImGuiCol.TableBorderLight, 0x232731, 1f);
        set(colors, ImGuiCol.TableRowBg, 0x000000, 0f);
        set(colors, ImGuiCol.TableRowBgAlt, 0xFFFFFF, 0.02f);
        set(colors, ImGuiCol.TextSelectedBg, 0x4C9AFF, 0.35f);
        set(colors, ImGuiCol.NavCursor, 0x4C9AFF, 1f);
        set(colors, ImGuiCol.ModalWindowDimBg, 0x000000, 0.55f);
        style.setColors(colors);
    }

    private static void set(ImVec4[] colors, int index, int rgb, float alpha) {
        colors[index].set(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, alpha);
    }

    /** Packs an ImGui ABGR colour. */
    static int rgba(int rgb, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | ((rgb & 255) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 255);
    }
}
