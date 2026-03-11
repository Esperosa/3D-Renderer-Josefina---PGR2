package engine.core;

/**
 * Tady držím centralizované krátké maturity poznámky pro editorové UI a dokumentaci.
 */
public final class FeatureMaturityNotes {

    public static final String MATERIAL_GRAPH_SOURCE_OF_TRUTH =
            "Shader Editor a inspektor pracují nad stejným materiálovým grafem. Raster preview je rychlé a záměrně aproximované vůči Ray/Path režimům.";

    public static final String NORMAL_COMPATIBILITY_BRIDGE =
            "Normálová větev sdílí kompatibilní graph path Image Texture -> Normal Map -> shader normal, takže Raster, Ray i Path používají stejný výsledek tam, kde to engine umí poctivě vyhodnotit.";

    public static final String RASTER_APPROXIMATION =
            "Raster renderer používám hlavně jako rychlý viewport preview. Transparentní, volumetrické a složitější materiálové kombinace v něm záměrně aproximuju.";

    public static final String OUTPUT_SESSION_WORKFLOW =
            "Každý export ukládám do vlastní session složky s preview obrázkem, manifestem a logem. Still, sequence, GIF i AVI držím čistě v JDK bez externích nástrojů.";

    public static final String SPRAY_PARTICLE_SYSTEM =
            "Experimentální spray/splash částicový overlay. Nejde o objemovou fluid simulaci ani o plnohodnotný solver vody.";

    public static final String GALAXY_EXPERIMENTAL =
            "Galaxy systém zůstává experimentální scaffold vrstvou bez plného orbitálního nebo N-body řešiče.";

    private FeatureMaturityNotes() {
    }
}
