// src/JsonSimd.java
package bitmap;
/**
 * JNI bridge for our AVX2‑accelerated JSON structural parsing routines.
 */
public class JsonSimd {
    // Load the native library named "jsonsimd" (libjsonsimd.so / jsonsimd.dll)
    static {
        System.loadLibrary("jsonsimd");
    }

    /**
     * For a 32‑byte input slice, returns an array of eight bit‑masks:
     *   [ colonMask, quoteMask, escapeMask, lbraceMask,
     *     rbraceMask, commaMask, lbracketMask, rbracketMask ]
     *
     * @param chunk exactly 32 bytes of input data
     * @return eight-element long[] of movemask results
     */
    public static native long[] processChunk(byte[] chunk);

    /**
     * Performs the carry‑less prefix XOR (via CLMUL+extract)
     * on the given quoteBits and returns the raw str_mask.
     * You should then XOR the returned value with your
     * prevIterInsideQuote in Java to get the final mask.
     *
     * @param quoteBits          the 64‑bit mask of quote positions
     * @param prevIterInsideQuote the previous iteration’s inside‑quote bit
     * @return the new str_mask before Java‑side XOR
     */
    public static native long computeStrMask(long quoteBits, long prevIterInsideQuote);
}
