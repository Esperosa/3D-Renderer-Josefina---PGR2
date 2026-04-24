package engine.core;

final class EditorActionId {
    static final String UNDO = "editor.undo";
    static final String REDO = "editor.redo";
    static final String DELETE = "editor.delete";
    static final String DUPLICATE = "editor.duplicate";
    static final String FRAME_SELECTED = "editor.frameSelected";
    static final String FRAME_ALL = "editor.frameAll";
    static final String CANCEL = "editor.cancel";
    static final String TIMELINE_PLAY_PAUSE = "timeline.playPause";
    static final String TIMELINE_PREVIOUS_FRAME = "timeline.previousFrame";
    static final String TIMELINE_NEXT_FRAME = "timeline.nextFrame";
    static final String TIMELINE_ADD_KEY = "timeline.addKey";
    static final String TIMELINE_REMOVE_KEY = "timeline.removeKey";
    static final String TIMELINE_ADD_ALL_KEYS = "timeline.addAllKeys";
    static final String TIMELINE_ADD_RELEASE_KEY = "timeline.addReleaseKey";

    private EditorActionId() {
    }
}
