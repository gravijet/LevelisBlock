package net.gravijet.levelblock.util;

/** Packs a pair of block/chunk coordinates into a single long. */
public final class Keys {

    private Keys() {
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }

    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackZ(long key) {
        return (int) key;
    }

    /** Chunk key for a block coordinate. Arithmetic shift keeps negatives correct. */
    public static long chunkOf(int blockX, int blockZ) {
        return pack(blockX >> 4, blockZ >> 4);
    }
}
