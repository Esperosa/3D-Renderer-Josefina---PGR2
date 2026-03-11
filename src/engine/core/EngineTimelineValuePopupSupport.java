package engine.core;

import engine.ui.UiStrings;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Tady přidávám kontextové timeline akce na editorová vstupní pole.
 */
final class EngineTimelineValuePopupSupport {

    private EngineTimelineValuePopupSupport() {
    }

    static void attach(Engine engine, Component target, Runnable preCommit) {
        if (target == null) {
            return;
        }
        MouseAdapter popupListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (e == null || !e.isPopupTrigger()) {
                    return;
                }
                show(engine, e.getComponent(), e.getX(), e.getY(), preCommit);
                e.consume();
            }
        };
        target.addMouseListener(popupListener);
    }

    private static void show(Engine engine, Component target, int x, int y, Runnable preCommit) {
        JPopupMenu popup = new JPopupMenu();
        boolean hasExplicitTarget = hasExplicitTimelineTargetSelection(engine);
        boolean hasEntityTarget = hasEntityTimelineTargetSelection(engine);

        JMenuItem insert = new JMenuItem(UiStrings.Timeline.INSERT_KEYFRAME);
        insert.setEnabled(hasExplicitTarget);
        insert.addActionListener(e -> addTimelineKeyFromValueControlIfSelection(engine, preCommit));
        popup.add(insert);

        JMenuItem remove = new JMenuItem(UiStrings.Timeline.REMOVE_KEYFRAME);
        remove.setEnabled(hasExplicitTarget);
        remove.addActionListener(e -> removeTimelineKeyForSelectionIfSelection(engine));
        popup.add(remove);

        JMenuItem insertRelease = new JMenuItem(UiStrings.Timeline.INSERT_RELEASE_KEY);
        insertRelease.setEnabled(hasEntityTarget);
        insertRelease.addActionListener(e -> addTimelineReleaseKeyFromValueControlIfEntitySelection(engine, preCommit));
        popup.add(insertRelease);

        JMenuItem removeRelease = new JMenuItem(UiStrings.Timeline.REMOVE_RELEASE_KEY);
        removeRelease.setEnabled(hasEntityTarget);
        removeRelease.addActionListener(e -> removeTimelineReleaseKeyForSelectionIfEntitySelection(engine));
        popup.add(removeRelease);

        if (!hasExplicitTarget) {
            JMenuItem hint = new JMenuItem(UiStrings.Timeline.SELECT_TARGET_HINT);
            hint.setEnabled(false);
            popup.add(hint);
        }
        popup.show(target, x, y);
    }

    private static void addTimelineKeyFromValueControlIfSelection(Engine engine, Runnable preCommit) {
        if (!hasExplicitTimelineTargetSelection(engine)) {
            return;
        }
        if (preCommit != null) {
            preCommit.run();
        }
        engine.addTimelineKeyForSelection();
    }

    private static void removeTimelineKeyForSelectionIfSelection(Engine engine) {
        if (!hasExplicitTimelineTargetSelection(engine)) {
            return;
        }
        engine.removeTimelineKeyForSelection();
    }

    private static void addTimelineReleaseKeyFromValueControlIfEntitySelection(Engine engine, Runnable preCommit) {
        if (!hasEntityTimelineTargetSelection(engine)) {
            return;
        }
        if (preCommit != null) {
            preCommit.run();
        }
        engine.addTimelineReleaseKeyForSelection();
    }

    private static void removeTimelineReleaseKeyForSelectionIfEntitySelection(Engine engine) {
        if (!hasEntityTimelineTargetSelection(engine)) {
            return;
        }
        engine.removeTimelineReleaseKeyForSelection();
    }

    private static boolean hasExplicitTimelineTargetSelection(Engine engine) {
        return engine.selectedEntity != null || engine.selectedLight != null || engine.selectedForceField != null;
    }

    private static boolean hasEntityTimelineTargetSelection(Engine engine) {
        return engine.selectedEntity != null && engine.selectedEntity != engine.outputCameraEntity;
    }
}
