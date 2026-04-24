package engine.util;

/**
 * sleduju dirty flag, abych odkládal přepočet odvozených hodnot.
 */
public class DirtyFlag {

    private boolean dirty;
    private long version;

    public DirtyFlag() {
        this.dirty = true;
        this.version = 1L;
    }

 /** označí stav jako dirty a zvýším verzi. */
    public void markDirty() {
        dirty = true;
        version++;
    }

 /** dirty flag smaže po přepočtu. */
    public void clear() {
        dirty = false;
    }

 /** @return vrátí true, když potřebuju hodnotu přepočítat */
    public boolean isDirty() {
        return dirty;
    }

 /** @return vrátí aktuální číslo verze pro detekci změn */
    public long getVersion() {
        return version;
    }
}