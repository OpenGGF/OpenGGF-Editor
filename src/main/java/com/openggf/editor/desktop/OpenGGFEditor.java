package com.openggf.editor.desktop;

/**
 * Starts OpenGGF with this editor's window chrome on the class path. Open
 * Tools > Editor projects at the title to create or open a level.
 */
public final class OpenGGFEditor {
    private OpenGGFEditor() { }

    public static void main(String[] args) {
        com.openggf.Engine.main(args);
    }
}
