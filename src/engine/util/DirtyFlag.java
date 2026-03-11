package engine.util;

/**
 * Tady sleduju dirty flag, abych odkládal přepočet odvozených hodnot.
 */
public class DirtyFlag {

    private boolean dirty;
    private long version;

    public DirtyFlag() {
        this.dirty = true;
        this.version = 1L;
    }

    /** Tady označím stav jako dirty a zvýším verzi. */
    public void markDirty() {
        dirty = true;
        version++;
    }

    /** Tady dirty flag smažu po přepočtu. */
    public void clear() {
        dirty = false;
    }

    /** @return vrátím true, když potřebuju hodnotu přepočítat */
    public boolean isDirty() {
        return dirty;
    }

    /** @return vrátím aktuální číslo verze pro detekci změn */
    public long getVersion() {
        return version;
    }
}
