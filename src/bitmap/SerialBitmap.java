package bitmap;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import tokenizer.*;

public class SerialBitmap extends Bitmap {
    // Adjust MAX_LEVEL as needed.
    public static final int MAX_LEVEL = 64;

    // Members corresponding to the C++ class.
    private String mRecord;
    private long mRecordLength;
    private long mNumTmpWords;
    private long mNumWords;
    private long[] mQuoteBitmap;
    // Two-dimensional arrays: index by level then by word.
    private long[][] mLevColonBitmap = new long[MAX_LEVEL][];
    private long[][] mLevCommaBitmap = new long[MAX_LEVEL][];
    private int mDepth;  // deepest level (starting from 0)

    // Default constructor.
    public SerialBitmap() {
        // empty constructor
    }

    // Constructor taking the record (as a byte array) and level number.
    public SerialBitmap(String record, int levelNum) {
        this.mRecord = record;
        this.mDepth = levelNum - 1;
        // mQuoteBitmap will be allocated in setRecordLength.
        this.mQuoteBitmap = null;
        for (int i = 0; i <= this.mDepth; i++) {
            mLevColonBitmap[i] = null;
            mLevCommaBitmap[i] = null;
        }
    }

    public int getDepth() {
        return mDepth;
    }

    public long getLength() {
        return mRecordLength;
    }

    public long getRecordLength() {
        return mRecordLength;
    }

    public long getSize() {
        return mNumWords * 8; // 8 bytes per long
    }

    public String getRecord() {
        return mRecord;
    }

    public long getQuoteBitmap(int index) {
        return mQuoteBitmap[index];
    }

    public long getLevCommaBitmap(int level, int index) {
        return mLevCommaBitmap[level][index];
    }

    public long getLevColonBitmap(int level, int index) {
        return mLevColonBitmap[level][index];
    }

    // Free memory by nulling references (GC will reclaim them).
    private void freeMemory() {
        for (int m = 0; m <= mDepth; m++) {
            mLevColonBitmap[m] = null;
            mLevCommaBitmap[m] = null;
        }
        mQuoteBitmap = null;
    }


    // In Java we typically do not need destructors.
    // You may call freeMemory() when you no longer need this instance.

    public void setRecordLength(long length) {
        this.mRecordLength = length;
        this.mNumTmpWords = length / 32;
        this.mNumWords = length / 64;
        if (mQuoteBitmap == null) {
            mQuoteBitmap = new long[(int) mNumWords];
        }
    }

    // Helper method to compute a string mask via a simple prefix scan.
    // (This is only an approximation of the _mm_clmulepi64_si128 work in C++.)
    private long computeStringMask(long quoteBits, long prevInside) {
        long mask = 0;
        long inside = prevInside; // 0 or 1 to indicate whether we are inside quotes
        // Process 64 bits.
        for (int bit = 0; bit < 64; bit++) {
            if (((quoteBits >> bit) & 1L) != 0) {
                inside ^= 1; // flip the inside flag for each quote
            }
            if (inside == 1) {
                mask |= (1L << bit);
            }
        }
        return mask;
    }

    // Main method to construct the index. This is a direct translation
    // of the C++ indexConstruction() method.
    public void indexConstruction() {
        // Define target bytes.
        byte v_quote = 0x22;      // '"'
        byte v_colon = 0x3a;       // ':'
        byte v_escape = 0x5c;      // '\'
        byte v_lbrace = 0x7b;      // '{'
        byte v_rbrace = 0x7d;      // '}'
        byte v_comma = 0x2c;       // ','
        byte v_lbracket = 0x5b;    // '['
        byte v_rbracket = 0x5d;    // ']'

        // Variables to hold lower 32-bit masks before combining.
        long colonbit0 = 0, quotebit0 = 0, escapebit0 = 0;
        long lbracebit0 = 0, rbracebit0 = 0, commabit0 = 0;
        long lbracketbit0 = 0, rbracketbit0 = 0;

        long colonbit, quotebit, escapebit,
             lbracebit, rbracebit, commabit,
             lbracketbit, rbracketbit;

        // Context variables.
        int cur_level = -1;
        int max_positive_level = -1;
        int top_word = -1;
        long prev_iter_ends_odd_backslash = 0L;
        long prev_iter_inside_quote = 0L;
        final long even_bits = 0x5555555555555555L;
        final long odd_bits = ~even_bits;

        byte[] recordBytes = mRecord.getBytes(StandardCharsets.UTF_8);
        int numTmp = (int) mNumTmpWords; // assume it fits in int
        for (int j = 0; j < numTmp; j++) {
            int off = j * 32;
            byte[] slice = Arrays.copyOfRange(recordBytes, off, off + 32);
            long[] bits = JsonSimd.processChunk(slice);
            colonbit    = bits[0];
            quotebit    = bits[1];
            escapebit   = bits[2];
            lbracebit   = bits[3];
            rbracebit   = bits[4];
            commabit    = bits[5];
            lbracketbit = bits[6];
            rbracketbit = bits[7];

            // Combine two 32-byte blocks into one 64-bit word.
            if (j % 2 == 0) {
                colonbit0 = colonbit;
                quotebit0 = quotebit;
                escapebit0 = escapebit;
                lbracebit0 = lbracebit;
                rbracebit0 = rbracebit;
                commabit0 = commabit;
                lbracketbit0 = lbracketbit;
                rbracketbit0 = rbracketbit;
                continue;
            } else {
                colonbit  = (colonbit  << 32) | colonbit0;
                quotebit  = (quotebit  << 32) | quotebit0;
                escapebit = (escapebit << 32) | escapebit0;
                lbracebit = (lbracebit << 32) | lbracebit0;
                rbracebit = (rbracebit << 32) | rbracebit0;
                commabit  = (commabit  << 32) | commabit0;
                lbracketbit = (lbracketbit << 32) | lbracketbit0;
                rbracketbit = (rbracketbit << 32) | rbracketbit0;
            }

            // Step 2: Update structural quote bitmaps.
            long bs_bits = escapebit;
            long start_edges = bs_bits & ~(bs_bits << 1);
            long even_start_mask = even_bits ^ prev_iter_ends_odd_backslash;
            long even_starts = start_edges & even_start_mask;
            long odd_starts = start_edges & ~even_start_mask;
            long even_carries = bs_bits + even_starts;
            long odd_carries = bs_bits + odd_starts;
            boolean iterEndsOddBackslash = Long.compareUnsigned(odd_carries, bs_bits) < 0;
            odd_carries |= prev_iter_ends_odd_backslash;
            prev_iter_ends_odd_backslash = iterEndsOddBackslash ? 1L : 0L;
            long even_carry_ends = even_carries & ~bs_bits;
            long odd_carry_ends = odd_carries & ~bs_bits;
            long even_start_odd_end = even_carry_ends & odd_bits;
            long odd_start_even_end = odd_carry_ends & even_bits;
            long odd_ends = even_start_odd_end | odd_start_even_end;
            long quote_bits = quotebit & ~odd_ends;
            top_word++;
            mQuoteBitmap[top_word] = quote_bits;

            // Step 3: Build string mask bitmaps (simulate carry-less multiplication via a prefix scan).
           // call into your native computeStrMask, then xor in Java
            long str_mask = JsonSimd.computeStrMask(quote_bits, prev_iter_inside_quote)
                        ^ prev_iter_inside_quote;

            // arithmetic right‑shift will sign‑extend, giving you 0xFFFF… or 0x0000…
            prev_iter_inside_quote = str_mask >> 63;

            // Step 4: Update structural character bitmaps.
            long tmp = ~str_mask;
            colonbit  &= tmp;
            lbracebit &= tmp;
            rbracebit &= tmp;
            commabit  &= tmp;
            lbracketbit &= tmp;
            rbracketbit &= tmp;

            // Step 5: Generate leveled bitmaps.
            long lb_mask = lbracebit | lbracketbit;
            long rb_mask = rbracebit | rbracketbit;
            long cb_mask = lb_mask | rb_mask;
            long lb_bit = lb_mask & -lb_mask;
            long rb_bit = rb_mask & -rb_mask;

            if (cb_mask == 0) {
                if (cur_level >= 0 && cur_level <= mDepth) {
                    // Allocate final level arrays if needed.
                    if (mLevColonBitmap[cur_level] == null) {
                        mLevColonBitmap[cur_level] = new long[(int) mNumWords];
                    }
                    if (mLevCommaBitmap[cur_level] == null) {
                        mLevCommaBitmap[cur_level] = new long[(int) mNumWords];
                    }
                    if (colonbit != 0) {
                        mLevColonBitmap[cur_level][top_word] = colonbit;
                    } else {
                        mLevCommaBitmap[cur_level][top_word] = commabit;
                    }
                }
            } else {
                long first = 1;
                // The loop continues while there are still bits in cb_mask or first is nonzero.
                while (cb_mask != 0 || first != 0) {
                    long second;
                    if (cb_mask == 0) {
                        second = 1L << 63;
                    } else {
                        long cb_bit = cb_mask & -cb_mask;
                        second = cb_bit;

                        if (cur_level >= 0 && cur_level <= mDepth) {
                            if (mLevColonBitmap[cur_level] == null) {
                                mLevColonBitmap[cur_level] = new long[(int) mNumWords];
                            }
                            if (mLevCommaBitmap[cur_level] == null) {
                                mLevCommaBitmap[cur_level] = new long[(int) mNumWords];
                            }
                            long mask = second - first;
                            // If cb_mask is zero then set the final bit.
                            if (cb_mask == 0) {
                                mask |= second;
                            }
                            long colon_mask = mask & colonbit;
                            if (colon_mask != 0) {
                                mLevColonBitmap[cur_level][top_word] |= colon_mask;
                            } else {
                                mLevCommaBitmap[cur_level][top_word] |= (commabit & mask);
                            }
                            if (cb_mask != 0) {
                                if (cb_bit == rb_bit) {
                                    mLevColonBitmap[cur_level][top_word] |= cb_bit;
                                    mLevCommaBitmap[cur_level][top_word] |= cb_bit;
                                } else if (cb_bit == lb_bit && cur_level + 1 <= mDepth) {
                                    if (mLevCommaBitmap[cur_level + 1] == null) {
                                        mLevCommaBitmap[cur_level + 1] = new long[(int) mNumWords];
                                    }
                                    mLevCommaBitmap[cur_level + 1][top_word] |= cb_bit;
                                }
                            }
                        }
                    }
                    if (cb_mask != 0) {
                        long cb_bit = cb_mask & -cb_mask; // recalc for clarity
                        if (cb_bit == lb_bit) {
                            lb_mask = lb_mask & (lb_mask - 1);
                            lb_bit = lb_mask & -lb_mask;
                            cur_level++;
                            if (cur_level == 0) {
                                if (mLevCommaBitmap[cur_level] == null) {
                                    mLevCommaBitmap[cur_level] = new long[(int) mNumWords];
                                }
                                mLevCommaBitmap[cur_level][top_word] |= cb_bit;
                            }
                        } else if (cb_bit == rb_bit) {
                            rb_mask = rb_mask & (rb_mask - 1);
                            rb_bit = rb_mask & -rb_mask;
                            cur_level--;
                        }
                        first = second;
                        cb_mask = cb_mask & (cb_mask - 1);
                        if (cur_level > max_positive_level) {
                            max_positive_level = cur_level;
                        }
                    } else {
                        first = 0;
                    }
                }
            }
        }
        if (mDepth == MAX_LEVEL - 1) {
            mDepth = max_positive_level;
        }
    }
}
