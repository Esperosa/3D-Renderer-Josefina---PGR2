package engine.ui;

import engine.core.RenderMode;

public final class UiStrings {

    public static final class Tabs {
        public static final String SCENE = "Scéna";
        public static final String WORLD = "Prostředí";
        public static final String VIEW = "Zobrazení";
        public static final String OBJECT = "Objekt";
        public static final String RENDER = "Render";
        public static final String OUTPUT = "Výstup";

        private Tabs() {
        }
    }

    public static final class Toolbar {
        public static final String NAVIGATION = "Navigace";
        public static final String DISPLAY = "Zobrazení";
        public static final String SYSTEM = "Systém";
        public static final String HELP = "Nápověda";
        public static final String CURSOR = "Kurzor";
        public static final String FPS = "FPS";
        public static final String BLENDER = "Blender";
        public static final String PROJECTION_ORTHO = "Orto";
        public static final String PROJECTION_PERSP = "Persp";
        public static final String DEBUG = "Debug";
        public static final String OVERLAY = "Overlay";
        public static final String PHYSICS = "Fyzika";
        public static final String THREADS = "Vlákna";
        public static final String ANTI_ALIAS = "AA";
        public static final String HELP_BUTTON = "Nápověda";
        public static final String SELECTION_MODE = "Kliknutí";
        public static final String SELECTION_FRAME_AND_FOCUS = "Kliknutí: zaměřit a zaostřit";
        public static final String SELECTION_ONLY = "Kliknutí: pouze vybrat";

        private Toolbar() {
        }
    }

    public static final class Scene {
        public static final String HEADER_TITLE = "Správa scény";
        public static final String HEADER_SUBTITLE = "Outliner, rychlé akce a kontext vybrané položky na jednom místě.";
        public static final String OUTLINER = "Outliner scény";
        public static final String SELECTED_ITEM = "Vybraná položka";
        public static final String ADD_TO_SCENE = "Přidat do scény";
        public static final String SELECTION = "Výběr";
        public static final String BASIC_OBJECTS = "Základní objekty";
        public static final String FEATURED_OBJECTS = "Výrazné tvary";
        public static final String LIGHTS = "Světla";
        public static final String FORCE_FIELDS = "Síly";
        public static final String SIMULATION = "Částicové efekty";

        private Scene() {
        }
    }

    public static final class World {
        public static final String HEADER_TITLE = "Prostředí";
        public static final String HEADER_SUBTITLE = "Světlo prostředí, pozadí a celková nálada scény.";
        public static final String LIGHT = "Světlo prostředí";
        public static final String PRESET_STUDIO = "Neutrální studio";
        public static final String PRESET_SUNSET = "Teplý západ";
        public static final String PRESET_NIGHT = "Chladná noc";
        public static final String PRESET_CONTRAST = "Vysoký kontrast";

        private World() {
        }
    }

    public static final class Spray {
        public static final String TITLE = "Spray / splash emitor";
        public static final String HONEST_HINT = "Experimentální částicový spray a splash overlay. Nejde o objemovou fluid simulaci.";
        public static final String EMISSION = "Emise";
        public static final String MOTION = "Pohyb částic";
        public static final String COLLISIONS = "Kolize a útlum";
        public static final String RENDERING = "Vzhled overlaye";
        public static final String APPLY_CLEAR_MATERIAL = "Použít čirý materiál";

        private Spray() {
        }
    }

    public static final class View {
        public static final String HEADER_TITLE = "Zobrazení a ovládání";
        public static final String HEADER_SUBTITLE = "Navigace kamery, projekce a citlivost práce ve viewportu.";
        public static final String NAVIGATION = "Navigace";
        public static final String MOTION = "Pohyb";

        private View() {
        }
    }

    public static final class Object {
        public static final String HEADER_TITLE = "Objekt";
        public static final String HEADER_SUBTITLE = "Transformace, fokus a operace nad vybraným objektem.";
        public static final String TRANSFORM = "Transformace";
        public static final String OPERATIONS = "Operace objektu";
        public static final String NONE_SELECTED = "Vybraný objekt: žádný";

        private Object() {
        }
    }

    public static final class Dock {
        public static final String TITLE = "Pracovní prostor";
        public static final String TIMELINE = "Časová osa";
        public static final String MATERIAL = "Materiál";
        public static final String TIMELINE_SUBTITLE = "Klíče, scrub a přehrávání animace.";
        public static final String MATERIAL_SUBTITLE = "Node-based editor materiálu inspirovaný Blenderem.";

        private Dock() {
        }
    }

    public static final class MaterialDock {
        public static final String WORKSPACE_TITLE = "Materiálový workspace";
        public static final String EMPTY_STATE_TITLE = "Josefína Atelier";
        public static final String NO_OBJECT_SELECTED = "Není vybraný objekt";
        public static final String NO_MESH_SELECTED = "Vybraná položka nemá mesh";
        public static final String EMPTY_SELECT_OBJECT = "Vyberte mesh objekt, se kterým chcete pracovat v materiálovém workspace.";
        public static final String EMPTY_NO_MESH = "Vybraná položka nemá mesh, takže pro ni nelze otevřít editovatelný materiálový graf.";
        public static final String LOOKDEV_SECTION = "Lookdev a preview materiálu";
        public static final String LOOKDEV_TITLE = "Lookdev materiálu";
        public static final String LOOKDEV_HINT = "Shader Editor a inspektor pracují nad stejným materiálovým grafem.";
        public static final String FOOTER_HINT_PREFIX = "LMB táhne uzly, tahem ze socketu vytváříte spojení, kolečko zoomuje, Shift posouvá plochu, ";
        public static final String NODE_INSPECTOR = "Inspektor uzlu";
        public static final String NODE_INSPECTOR_EMPTY = "Vyberte uzel a upravte jeho vlastnosti.";
        public static final String NODE_INSPECTOR_ADD_HINT = "Pravým tlačítkem v prázdném prostoru přidáte další uzly.";
        public static final String RESET_DEFAULT_GRAPH = "Obnovit výchozí graf";
        public static final String DUPLICATE_MATERIAL = "Duplikovat materiál";
        public static final String RESET_GRAPH = "Reset grafu";
        public static final String IMPORT_PBR = "Importovat PBR sadu...";
        public static final String APPLY_TO_SELECTED = "Použít na vybraný objekt";
        public static final String PREVIEW_PRIMITIVE = "Primitiv";
        public static final String PREVIEW_LIGHTING = "Světla";
        public static final String PREVIEW_BACKGROUND = "Pozadí";
        public static final String PREVIEW_RENDERER = "Renderer";
        public static final String PREVIEW_TITLE = "Náhled materiálu";
        public static final String PREVIEW_REFRESHING = "Aktualizuji náhled...";
        public static final String NODE_ACTIONS = "Akce uzlu";
        public static final String RESET_NODE_DEFAULTS = "Obnovit výchozí hodnoty";
        public static final String DISCONNECT_INPUTS = "Odpojit vstupy";
        public static final String DISCONNECT_OUTPUTS = "Odpojit výstupy";
        public static final String DELETE_NODE = "Smazat uzel";
        public static final String DUPLICATE_NODE = "Duplikovat uzel";
        public static final String IMAGE_SOURCE = "Zdroj obrázku";
        public static final String IMAGE_PICK = "Vybrat obrázek";
        public static final String IMAGE_RELOAD = "Znovu načíst obrázek";
        public static final String IMAGE_CLEAR = "Vyčistit soubor";
        public static final String SAMPLING = "Vzorkování";
        public static final String MATERIAL_COPY_SUFFIX = " Kopie";

        private MaterialDock() {
        }

        public static String materialTitle(String entityName) {
            return "Materiál: " + entityName;
        }
    }

    public static final class Common {
        public static final String SELECT_IN_OUTLINER = "Vyberte položku v outlineru.";
        public static final String OPEN_WORLD_TAB = "Otevřít panel prostředí";
        public static final String OPEN_SCENE_TAB = "Otevřít panel scény";
        public static final String BACK_TO_TIMELINE = "Zpět na časovou osu";
        public static final String NO_MESH = "Vybraná položka nemá mesh.";
        public static final String NO_MATERIAL = "Žádný materiál";
        public static final String YES = "ANO";
        public static final String NO = "NE";

        private Common() {
        }
    }

    public static final class Timeline {
        public static final String INSERT_KEYFRAME = "Vložit klíč";
        public static final String REMOVE_KEYFRAME = "Smazat klíč";
        public static final String INSERT_RELEASE_KEY = "Vložit release klíč (fyzika)";
        public static final String REMOVE_RELEASE_KEY = "Smazat release klíč (fyzika)";
        public static final String SELECT_TARGET_HINT = "Nejdřív vyberte objekt, světlo nebo sílu";

        private Timeline() {
        }
    }

    public static final class ContextMenu {
        public static final String ADD_PREFIX = "Přidat ";
        public static final String ADD_WATER_EMITTER = "Přidat spray emitor";
        public static final String IMPORT_MODEL_SCENE = "Importovat model / scénu...";
        public static final String ADD_POINT_LIGHT = "Přidat bodové světlo";
        public static final String ADD_AREA_LIGHT = "Přidat plošné světlo";
        public static final String ADD_CONE_LIGHT = "Přidat kuželové světlo";
        public static final String ADD_VECTOR_FORCE = "Přidat vektorovou sílu";
        public static final String ADD_POINT_ATTRACTOR = "Přidat bodový přitahovač";
        public static final String ADD_POINT_REPULSOR = "Přidat bodový odpuzovač";
        public static final String ADD_TURBULENCE = "Přidat turbulenci";

        private ContextMenu() {
        }
    }

    public static final class Output {
        public static final String STILL_IMAGE = "Statický snímek";
        public static final String IMAGE_SEQUENCE = "Sekvence obrázků";
        public static final String ANIMATED_GIF = "Animovaný GIF";
        public static final String ANIMATED_AVI = "AVI (MJPEG)";
        public static final String RENDER_STILL = "Vyrenderovat snímek";
        public static final String RENDER_SEQUENCE = "Vyrenderovat sekvenci";
        public static final String RENDER_GIF = "Vyrenderovat GIF";
        public static final String RENDER_AVI = "Vyrenderovat AVI";

        private Output() {
        }
    }

    private UiStrings() {
    }

    public static String renderModeLabel(RenderMode mode) {
        if (mode == null) {
            return "Path Tracing";
        }
        return switch (mode) {
            case MODEL -> "Model";
            case BASIC -> "Basic";
            case PHONG -> "Phong";
            case WIREFRAME -> "Wireframe";
            case DITHERING -> "Dither";
            case TEMPORAL_NOISE -> "Temporal";
            case RAY_TRACING -> "Ray Tracing";
            case PATH_TRACING -> "Path Tracing";
            case HEX_MOSAIC -> "Hex";
        };
    }

    public static String outputTypeLabel(String exportType) {
        if (exportType == null) {
            return Output.STILL_IMAGE;
        }
        return switch (exportType.trim().toLowerCase()) {
            case "sequence" -> Output.IMAGE_SEQUENCE;
            case "gif" -> Output.ANIMATED_GIF;
            case "avi" -> Output.ANIMATED_AVI;
            default -> Output.STILL_IMAGE;
        };
    }

    public static String outputTypeKey(String label) {
        if (label == null) {
            return "still";
        }
        return switch (label.trim().toLowerCase()) {
            case "sekvence obrázků" -> "sequence";
            case "animovaný gif" -> "gif";
            case "avi (mjpeg)" -> "avi";
            case "statický snímek" -> "still";
            default -> "still";
        };
    }

    public static String worldPresetLabel(String presetKey) {
        if (presetKey == null) {
            return World.PRESET_STUDIO;
        }
        return switch (presetKey.trim().toLowerCase()) {
            case "warm sunset", "teplý západ" -> World.PRESET_SUNSET;
            case "cool night", "chladná noc" -> World.PRESET_NIGHT;
            case "high contrast", "vysoký kontrast" -> World.PRESET_CONTRAST;
            default -> World.PRESET_STUDIO;
        };
    }

    public static String worldPresetKey(String label) {
        if (label == null) {
            return "Studio Neutral";
        }
        return switch (label.trim().toLowerCase()) {
            case "teplý západ", "warm sunset" -> "Warm Sunset";
            case "chladná noc", "cool night" -> "Cool Night";
            case "vysoký kontrast", "high contrast" -> "High Contrast";
            default -> "Studio Neutral";
        };
    }
}
