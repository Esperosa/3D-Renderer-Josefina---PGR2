package engine.util;

/**
 * Represents jednoduchý generický objektový pool, abych omezil alokace v nejteplejších smyčkách.
 *
 * @param <T> tím označí typ objektu v poolu
 */
public class ObjectPool<T> {

    private final Object[] pool;
    private int size;
    private final Supplier<T> factory;

    public ObjectPool(int capacity, Supplier<T> factory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Pool capacity must be > 0");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Factory must not be null");
        }
        this.pool = new Object[capacity];
        this.factory = factory;
        this.size = capacity;
        for (int i = 0; i < capacity; i++) {
            pool[i] = factory.get();
        }
    }

 /**
 * Acquires objekt z poolu.
 *
 * @return vrátí recyklovaný objekt nebo novou instanci, když je pool prázdný
 */
    @SuppressWarnings("unchecked")
    public T acquire() {
        if (size > 0) {
            int idx = --size;
            T obj = (T) pool[idx];
            pool[idx] = null;
            return obj;
        }
        return factory.get();
    }

 /**
 * Returns objekt zpátky do poolu pro další použití.
 *
 * @param obj objekt k recyklaci
 */
    public void release(T obj) {
        if (obj == null) {
            return;
        }
        if (size < pool.length) {
            pool[size++] = obj;
        }
    }

    public int capacity() {
        return pool.length;
    }

    public int available() {
        return size;
    }

 /**
 * Represents jednoduché rozhraní pro vytváření objektů.
 */
    @FunctionalInterface
    public interface Supplier<T> {
        T get();
    }
}