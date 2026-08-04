package net.gravijet.levelblock.util;

import java.util.function.LongConsumer;

/**
 * Allocation-free open-addressing hash set for primitive longs.
 * <p>
 * The unlocked-column lookup runs on every {@code PlayerMoveEvent}, so boxing every
 * coordinate into a {@link Long} would be wasteful. Linear probing with backward-shift
 * deletion keeps the table free of tombstones. {@code 0L} is the free marker and is
 * therefore tracked separately.
 */
public final class LongHashSet {

    private static final long FREE = 0L;
    private static final float LOAD_FACTOR = 0.7f;

    private long[] table;
    private int mask;
    private int threshold;
    /** Number of entries stored in {@link #table} (excludes the zero key). */
    private int stored;
    private boolean hasZero;

    public LongHashSet() {
        this(16);
    }

    public LongHashSet(int expectedSize) {
        int capacity = tableSizeFor(Math.max(16, (int) (expectedSize / LOAD_FACTOR) + 1));
        this.table = new long[capacity];
        this.mask = capacity - 1;
        this.threshold = (int) (capacity * LOAD_FACTOR);
    }

    public int size() {
        return stored + (hasZero ? 1 : 0);
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(long key) {
        if (key == FREE) {
            return hasZero;
        }
        int pos = mix(key) & mask;
        long current;
        while ((current = table[pos]) != FREE) {
            if (current == key) {
                return true;
            }
            pos = (pos + 1) & mask;
        }
        return false;
    }

    public boolean add(long key) {
        if (key == FREE) {
            if (hasZero) {
                return false;
            }
            hasZero = true;
            return true;
        }
        int pos = mix(key) & mask;
        long current;
        while ((current = table[pos]) != FREE) {
            if (current == key) {
                return false;
            }
            pos = (pos + 1) & mask;
        }
        table[pos] = key;
        if (++stored >= threshold) {
            rehash(table.length << 1);
        }
        return true;
    }

    public boolean remove(long key) {
        if (key == FREE) {
            if (!hasZero) {
                return false;
            }
            hasZero = false;
            return true;
        }
        int pos = mix(key) & mask;
        long current;
        while ((current = table[pos]) != FREE) {
            if (current == key) {
                stored--;
                shiftKeys(pos);
                return true;
            }
            pos = (pos + 1) & mask;
        }
        return false;
    }

    public void clear() {
        java.util.Arrays.fill(table, FREE);
        stored = 0;
        hasZero = false;
    }

    public void forEach(LongConsumer action) {
        if (hasZero) {
            action.accept(FREE);
        }
        for (long key : table) {
            if (key != FREE) {
                action.accept(key);
            }
        }
    }

    public long[] toArray() {
        long[] out = new long[size()];
        int i = 0;
        if (hasZero) {
            out[i++] = FREE;
        }
        for (long key : table) {
            if (key != FREE) {
                out[i++] = key;
            }
        }
        return out;
    }

    /** Backward-shift deletion: keeps every probe chain contiguous without tombstones. */
    private void shiftKeys(int pos) {
        int last;
        int slot;
        long current;
        while (true) {
            last = pos;
            pos = (pos + 1) & mask;
            while (true) {
                if ((current = table[pos]) == FREE) {
                    table[last] = FREE;
                    return;
                }
                slot = mix(current) & mask;
                if (last <= pos ? (last >= slot || slot > pos) : (last >= slot && slot > pos)) {
                    break;
                }
                pos = (pos + 1) & mask;
            }
            table[last] = current;
        }
    }

    private void rehash(int newCapacity) {
        long[] old = table;
        long[] fresh = new long[newCapacity];
        int newMask = newCapacity - 1;
        for (long key : old) {
            if (key == FREE) {
                continue;
            }
            int pos = mix(key) & newMask;
            while (fresh[pos] != FREE) {
                pos = (pos + 1) & newMask;
            }
            fresh[pos] = key;
        }
        this.table = fresh;
        this.mask = newMask;
        this.threshold = (int) (newCapacity * LOAD_FACTOR);
    }

    /** Fibonacci-style mixing; block coordinates cluster badly with the raw value. */
    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return (int) h;
    }

    private static int tableSizeFor(int value) {
        int n = 1;
        while (n < value) {
            n <<= 1;
        }
        return n;
    }
}
