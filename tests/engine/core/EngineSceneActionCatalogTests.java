package engine.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EngineSceneActionCatalogTests {

    private EngineSceneActionCatalogTests() {
    }

    public static void main(String[] args) {
        testSceneAddGroupsStayStructuredAndUnique();
        System.out.println("EngineSceneActionCatalogTests: ALL TESTS PASSED");
    }

    private static void testSceneAddGroupsStayStructuredAndUnique() {
        List<EngineSceneActions.SceneAddGroup> groups = EngineSceneActions.sceneAddGroups();
        if (groups.size() != 6) {
            throw new AssertionError("Expected 6 add-scene groups, got " + groups.size());
        }
        assertGroup(groups.get(0), "Základní objekty", true, 9);
        assertGroup(groups.get(1), "Výrazné tvary", true, 2);
        assertGroup(groups.get(2), "Import", false, 1);
        assertGroup(groups.get(3), "Světla", false, 3);
        assertGroup(groups.get(4), "Síly", false, 4);
        assertGroup(groups.get(5), "Částicové efekty", false, 1);

        Set<String> labels = new HashSet<>();
        for (EngineSceneActions.SceneAddGroup group : groups) {
            for (EngineSceneActions.SceneAddAction action : group.actions()) {
                if (!labels.add(action.label())) {
                    throw new AssertionError("Duplicate scene add action label: " + action.label());
                }
            }
        }
    }

    private static void assertGroup(EngineSceneActions.SceneAddGroup group,
                                    String expectedTitle,
                                    boolean expectedGrid,
                                    int expectedActions) {
        if (!expectedTitle.equals(group.title())) {
            throw new AssertionError("Expected group title '" + expectedTitle + "', got '" + group.title() + "'");
        }
        if (group.buttonGrid() != expectedGrid) {
            throw new AssertionError("Unexpected buttonGrid flag for " + expectedTitle);
        }
        if (group.actions().size() != expectedActions) {
            throw new AssertionError("Expected " + expectedActions + " actions for " + expectedTitle
                    + ", got " + group.actions().size());
        }
    }
}
