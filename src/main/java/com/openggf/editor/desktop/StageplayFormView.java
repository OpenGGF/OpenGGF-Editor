package com.openggf.editor.desktop;

import com.openggf.editor.project.EditorProjectMenu;
import com.openggf.game.MenuTextEditor;
import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Native presentation of the engine-owned form; mutations still run on the editor tick. */
final class StageplayFormView {
    private final EditorChromeHost host;
    private final Map<String, ImString> inputs = new HashMap<>();
    private final Map<String,String> modelValues=new HashMap<>();
    private long generation = -1;
    private EditorProjectMenu owner;
    private boolean help;

    StageplayFormView(EditorChromeHost host) { this.host = host; }
    void toggleHelp() { help = !help; }
    boolean closeHelp() { boolean wasOpen = help; help = false; return wasOpen; }

    void draw(EditorProjectMenu menu, Consumer<Runnable> queue) {
        var view = menu.formView();
        if (view == null) { drawActions(menu,queue); return; }
        boolean fresh = owner != menu || generation != menu.generation();
        if (fresh) { owner = menu; generation = menu.generation(); inputs.clear();modelValues.clear(); help = false; }
        var viewport = ImGui.getMainViewport();
        float width = Math.min(660, viewport.getSizeX() - 48);
        ImGui.setNextWindowPos(viewport.getPosX() + viewport.getSizeX() / 2,
                viewport.getPosY() + 62, ImGuiCond.Always, .5f, 0);
        ImGui.setNextWindowSize(width, 0);
        ImGui.setNextWindowSizeConstraints(width, 120, width, Math.max(240, viewport.getSizeY() - 100));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 24, 20);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize;
        ImGui.begin("##stageplayForm", flags);
        ImGui.popStyleVar();
        ImGui.pushFont(host.bodyFont(), EditorChromeHost.BODY_SIZE);
        try {
            ImGui.pushFont(host.titleFont(), EditorChromeHost.TITLE_SIZE);
            ImGui.text(view.title()); ImGui.popFont();
            ImGui.textDisabled(view.hint());
            if (ImGui.isItemHovered()) ImGui.setTooltip(view.help());
            ImGui.spacing(); ImGui.separator(); ImGui.spacing();
            for (var group : view.groups()) {
                boolean show = true;
                if (group.advanced()) {
                    ImGui.dummy(0,12);
                    if (fresh) ImGui.setNextItemOpen(false, ImGuiCond.Always);
                    show = ImGui.collapsingHeader(group.title()+"##formAdvanced");
                    if (!show) {
                        ImGui.sameLine(Math.max(180, ImGui.getWindowContentRegionMaxX() - ImGui.calcTextSizeX(group.summary()) - 8));
                        ImGui.textDisabled(group.summary());
                    }
                } else if (!group.title().isEmpty()) {
                    ImGui.spacing();
                    ImGui.pushFont(host.headingFont(), EditorChromeHost.HEADING_SIZE);
                    ImGui.textColored(EditorChromeTheme.SELECTION, group.title());
                    ImGui.popFont();
                }
                if (show && ImGui.beginTable("##form" + group.title(), 2, ImGuiTableFlags.SizingStretchProp)) {
                    try {
                        ImGui.tableSetupColumn("Label", ImGuiTableColumnFlags.WidthFixed, (width - 48) * .45f);
                        ImGui.tableSetupColumn("Value", ImGuiTableColumnFlags.WidthStretch);
                        for (var field : group.fields()) field(menu, field, queue);
                    } finally { ImGui.endTable(); }
                }
                ImGui.spacing();
            }
            if (view.error() != null) {
                statusLine(EditorChromeTheme.WARNING, "Check the values; F1 explains why.");
                if (ImGui.isItemHovered()) ImGui.setTooltip(view.error());
            }
            ImGui.dummy(0,20);
            ImGui.pushStyleColor(ImGuiCol.Button, EditorChromeTheme.ACCENT);
            if (ImGui.button(view.apply())) enqueue(menu, queue, menu::submit);
            ImGui.popStyleColor();
            ImGui.sameLine();
            if (ImGui.button("Close")) enqueue(menu, queue, menu::close);
            ImGui.sameLine();
            if (ImGui.button("Help  F1")) help = !help;
            ImGui.spacing(); ImGui.separator();
            ImGui.textDisabled("Tab Move   Arrows Change   Enter Apply   Esc Close   F1 Help");
            if (help) {
                ImGui.spacing();
                ImGui.textWrapped(menu.detailsText());

            }
        } finally { ImGui.popFont(); ImGui.end(); }
    }

    /** A geometry mark avoids a missing-glyph box in fonts without the black-circle codepoint. */
    static void statusLine(int color,String text) {
        float x=ImGui.getCursorScreenPosX(),y=ImGui.getCursorScreenPosY()+ImGui.getTextLineHeight()/2-2;
        ImGui.getWindowDrawList().addRectFilled(x,y,x+5,y+5,color);
        ImGui.dummy(5,ImGui.getTextLineHeight());ImGui.sameLine(0,6);
        String shown=text;float available=ImGui.getContentRegionAvailX();
        while(!shown.isEmpty()&&ImGui.calcTextSizeX(shown+(shown.equals(text)?"":"..."))>available) {
            int space=shown.lastIndexOf(' ');shown=shown.substring(0,space>0?space:shown.length()-1);
        }
        ImGui.textColored(color,shown+(shown.equals(text)?"":"..."));
        if(!shown.equals(text)&&ImGui.isItemHovered())ImGui.setTooltip(text);
    }

    private void drawActions(EditorProjectMenu menu,Consumer<Runnable> queue){
        var view=menu.actionView();if(view==null)return;
        boolean fresh=owner!=menu||generation!=menu.generation();
        if(fresh){owner=menu;generation=menu.generation();inputs.clear();modelValues.clear();help=false;}
        var vp=ImGui.getMainViewport();float width=Math.min(660,vp.getSizeX()-48);
        ImGui.setNextWindowPos(vp.getPosX()+vp.getSizeX()/2,vp.getPosY()+62,ImGuiCond.Always,.5f,0);
        ImGui.setNextWindowSize(width,0);ImGui.setNextWindowSizeConstraints(width,120,width,Math.max(240,vp.getSizeY()-100));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding,24,20);
        ImGui.begin("##stageplayActions",ImGuiWindowFlags.NoTitleBar|ImGuiWindowFlags.NoResize|ImGuiWindowFlags.NoMove|ImGuiWindowFlags.NoDocking|ImGuiWindowFlags.NoSavedSettings|ImGuiWindowFlags.AlwaysAutoResize);
        ImGui.popStyleVar();
        ImGui.pushFont(host.bodyFont(),EditorChromeHost.BODY_SIZE);
        try{
            ImGui.pushFont(host.titleFont(),EditorChromeHost.TITLE_SIZE);ImGui.text(view.title());ImGui.popFont();
            ImGui.textDisabled(view.hint());if(ImGui.isItemHovered())ImGui.setTooltip(view.help());
            ImGui.separator();
            if(!view.status().isEmpty()){statusLine(view.tone()==EditorProjectMenu.Tone.SUCCESS?EditorChromeTheme.SAVED:EditorChromeTheme.WARNING,view.status());if(ImGui.isItemHovered())ImGui.setTooltip(view.help());}
            for(var row:view.rows()){
                ImGui.pushID(row.index());
                if(row.primary()){
                    ImGui.dummy(0,20);ImGui.pushStyleColor(ImGuiCol.Button,EditorChromeTheme.ACCENT);
                    if(ImGui.button(row.label()))enqueue(menu,queue,()->menu.runAction(row.index()));ImGui.popStyleColor();ImGui.dummy(0,8);
                }else if(ImGui.selectable(row.label()))enqueue(menu,queue,()->menu.runAction(row.index()));
                if(fresh&&row.index()==0)ImGui.setItemDefaultFocus();
                if(ImGui.isItemHovered())ImGui.setTooltip(menu.actionEntries().get(row.index()).label());
                ImGui.popID();
            }
            if(view.error()!=null)statusLine(EditorChromeTheme.WARNING,"Check the action; F1 explains why.");
            ImGui.dummy(0,12);ImGui.separator();if(ImGui.button("Close"))enqueue(menu,queue,menu::close);ImGui.sameLine();if(ImGui.button("Help F1"))help=!help;
            if(help)ImGui.textWrapped(menu.detailsText());
        }finally{ImGui.popFont();ImGui.end();}
    }

    private void field(EditorProjectMenu menu, EditorProjectMenu.FormField view, Consumer<Runnable> queue) {
        var field = view.field();
        ImGui.pushID(field.key());
        try {
            ImGui.tableNextRow(); ImGui.tableNextColumn();
            ImGui.alignTextToFramePadding(); ImGui.textDisabled(field.label());
            ImGui.tableNextColumn();
            if (field.label().equals("Who")) {
                if (field.choices().size() <= 1) { ImGui.alignTextToFramePadding(); ImGui.text(view.display()); }
                else {
                    ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
                    if (ImGui.beginCombo("##who", view.display())) {
                        try {
                            for (int i=0;i<field.choices().size();i++) {
                                String value = field.choices().get(i);
                                if (ImGui.selectable(view.optionLabels().get(i), value.equals(view.value())))
                                    enqueue(menu, queue, () -> menu.setValue(field.key(), value));
                            }
                        } finally { ImGui.endCombo(); }
                    }
                }
            } else if (view.partner() != null) {
                float inputWidth = Math.max(64, Math.min(110, (ImGui.getContentRegionAvailX()-(view.pickable()?88:22))/2));
                number(menu, field, view.value(), "X", inputWidth, queue);
                ImGui.sameLine(0, 6); ImGui.textDisabled(","); ImGui.sameLine(0, 6);
                number(menu, view.partner(), view.partnerValue(), "Y", inputWidth, queue);
                if(view.pickable()){ImGui.sameLine();if(ImGui.button("Pick"))enqueue(menu,queue,()->menu.pick(field.key()));}
            } else if (field.choices().size()>3 || view.optionLabels().stream().mapToDouble(label->ImGui.calcTextSizeX(label)+24).sum()>ImGui.getContentRegionAvailX()) {
                ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
                if(ImGui.beginCombo("##choices",view.display())){try{
                    for(int i=0;i<field.choices().size();i++){String value=field.choices().get(i);
                        if(ImGui.selectable(view.optionLabels().get(i),value.equals(view.value())))enqueue(menu,queue,()->menu.setValue(field.key(),value));}
                }finally{ImGui.endCombo();}}
            } else if (!field.choices().isEmpty()) {
                for (int i = 0; i < field.choices().size(); i++) {
                    if (i > 0) ImGui.sameLine(0, 4);
                    String value = field.choices().get(i);
                    boolean selected = value.equals(view.value());
                    if (selected) ImGui.pushStyleColor(ImGuiCol.Button, EditorChromeTheme.ACCENT);
                    if (ImGui.button(view.optionLabels().get(i))) enqueue(menu, queue, () -> menu.setValue(field.key(), value));
                    if (selected) ImGui.popStyleColor();
                }
            } else if (view.multiline()) {
                var input = input(field.key(), view.display(), Math.max(256, field.maxLength()+1));
                if (ImGui.inputTextMultiline("##value", input, ImGui.getContentRegionAvailX(), ImGui.getTextLineHeight()*2+12)) {
                    String value = EditorProjectMenu.encodePreviewText(input.get());
                    enqueue(menu, queue, () -> menu.setValue(field.key(), value));
                }
            } else {
                var input = input(field.key(), view.value(), Math.max(256, field.maxLength()+1));
                boolean path = field.mode() == MenuTextEditor.Mode.PATH;
                float unitWidth = view.unit().isEmpty() ? 0 : ImGui.calcTextSizeX(view.unit()) + 10;
                float available = ImGui.getContentRegionAvailX()-unitWidth-(path?64:0)-(view.pickable()?64:0);
                boolean numeric = view.value().matches("-?\\d+(\\.\\d+)?");
                ImGui.setNextItemWidth(Math.max(80, numeric ? Math.min(132, available) : available));
                if (ImGui.inputText("##value", input)) {
                    String value = input.get(); enqueue(menu, queue, () -> menu.setValue(field.key(), value));
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip(view.value());
                if (!view.unit().isEmpty()) { ImGui.sameLine(); ImGui.textDisabled(view.unit()); }
                if(view.pickable()){ImGui.sameLine();if(ImGui.button("Pick"))enqueue(menu,queue,()->menu.pick(field.key()));}
                if (path) {
                    ImGui.sameLine();
                    if (ImGui.button("Browse")) enqueue(menu, queue, () -> menu.editField(field.key()));
                }
            }
        } finally { ImGui.popID(); }
    }

    private ImString input(String key,String value,int capacity) {
        var input=inputs.computeIfAbsent(key,ignored->new ImString(value,capacity));
        if(!value.equals(modelValues.put(key,value)))input.set(value);
        return input;
    }

    private void number(EditorProjectMenu menu, EditorProjectMenu.Field field, String value, String axis,
                        float width, Consumer<Runnable> queue) {
        var input = input(field.key(), EditorProjectMenu.coordinate(value), field.maxLength()+1);
        ImGui.setNextItemWidth(width);
        if (ImGui.inputText("##" + axis, input)) {
            String next = input.get(); enqueue(menu, queue, () -> menu.setValue(field.key(), next));
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip(axis);
    }

    private static void enqueue(EditorProjectMenu menu, Consumer<Runnable> queue, Runnable action) {
        long generation = menu.generation();
        queue.accept(() -> { if (menu.isOpen() && menu.generation() == generation) action.run(); });
    }
}
