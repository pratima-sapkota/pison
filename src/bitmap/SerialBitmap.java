package bitmap;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import tokenizer.*;
import java.util.stream.Collectors;

public class SerialBitmap extends Bitmap {
    // Adjust MAX_LEVEL as needed.
    public static final int MAX_LEVEL = 22;

    // Members corresponding to the C++ class.
    private byte[] mRecord;
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
    public SerialBitmap(byte[] record, int levelNum) {
        this.mRecord = record;
        this.mDepth = levelNum - 1;
        // mQuoteBitmap will be allocated in setRecordLength.
        this.mQuoteBitmap = null;
        for (int i = 0; i <= this.mDepth; ++i) {
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

    public byte[] getRecord() {
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
        for (int m = 0; m <= mDepth; ++m) {
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

    // Main method to construct the index. This is a direct translation
    // of the C++ indexConstruction() method.
    public void indexConstruction() {
        // Define target bytes.
        byte vQuote = 0x22;      // '"'
        byte vColon = 0x3a;       // ':'
        byte vEscape = 0x5c;      // '\'
        byte vLbrace = 0x7b;      // '{'
        byte vRbrace = 0x7d;      // '}'
        byte vComma = 0x2c;       // ','
        byte vLbracket = 0x5b;    // '['
        byte vRbracket = 0x5d;    // ']'

        // Variables to hold lower 32-bit masks before combining.
        long colonbit0 = 0, quotebit0 = 0, escapebit0 = 0;
        long lbracebit0 = 0, rbracebit0 = 0, commabit0 = 0;
        long lbracketbit0 = 0, rbracketbit0 = 0;

        long first, second;
        long colonbit, quotebit, escapebit,
             lbracebit, rbracebit, commabit,
             lbracketbit, rbracketbit;

        long lbMask, rbMask, cbMask, lbBit, rbBit, cbBit;

        // Context variables.
        int curLevel = -1;
        int maxPosLevel = -1;
        int topWord = -1;
        long prevIterEndsOddBackslash = 0L;
        long prevIterInsideQuote = 0L;
        final long evenBits = 0x5555555555555555L;
        final long oddBits = ~evenBits;
        
        int numTmp = (int) mNumTmpWords; // assume it fits in int
        for (int j = 0; j < numTmp; ++j) {
            int off = j * 32;
            byte[] slice = Arrays.copyOfRange(mRecord, off, off + 32);
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
            long bsBits = escapebit;
            long startEdges = bsBits & ~(bsBits << 1);
            long evenStartMask = evenBits ^ prevIterEndsOddBackslash;
            long evenStarts = startEdges & evenStartMask;
            long oddStarts = startEdges & ~evenStartMask;
            long evenCarries = bsBits + evenStarts;
            long oddCarries = bsBits + oddStarts;
            boolean iterEndsOddBackslash = Long.compareUnsigned(oddCarries, bsBits) < 0;
            oddCarries |= prevIterEndsOddBackslash;
            prevIterEndsOddBackslash = iterEndsOddBackslash ? 1L : 0L;
            long evenCarryEnds = evenCarries & ~bsBits;
            long oddCarryEnds = oddCarries & ~bsBits;
            long evenStartOddEnd = evenCarryEnds & oddBits;
            long oddStartEvenEnd = oddCarryEnds & evenBits;
            long oddEnds = evenStartOddEnd | oddStartEvenEnd;
            long quoteBits = quotebit & ~oddEnds;
            
            mQuoteBitmap[++topWord] = quoteBits;

            // Step 3: Build string mask bitmaps (simulate carry-less multiplication via a prefix scan).
           // call into your native computeStrMask, then xor in Java
            long strMasks = JsonSimd.computeStrMask(quoteBits, prevIterInsideQuote)
                        ^ prevIterInsideQuote;
            // System.out.println("strMasks " + strMasks);
            // arithmetic right‑shift will sign‑extend, giving you 0xFFFF… or 0x0000…
            prevIterInsideQuote = strMasks >> 63;
        // System.out.println("prevIterInsideQuote " + prevIterInsideQuote);
            // Step 4: Update structural character bitmaps.
            long tmp = ~strMasks;
            colonbit  &= tmp;
            lbracebit &= tmp;
            rbracebit &= tmp;
            commabit  &= tmp;
            lbracketbit &= tmp;
            rbracketbit &= tmp;

            // Step 5: Generate leveled bitmaps.
            lbMask = lbracebit | lbracketbit;
            rbMask = rbracebit | rbracketbit;
            cbMask = lbMask | rbMask;
            lbBit = lbMask & -lbMask;
            rbBit = rbMask & -rbMask;

            if (cbMask == 0) {
                if (curLevel >= 0 && curLevel <= mDepth) {
                    // Allocate final level arrays if needed.
                    if (mLevColonBitmap[curLevel] == null) {
                        mLevColonBitmap[curLevel] = new long[(int) mNumWords];
                    }
                    if (mLevCommaBitmap[curLevel] == null) {
                        mLevCommaBitmap[curLevel] = new long[(int) mNumWords];
                    }
                    if (colonbit != 0) {
                        mLevColonBitmap[curLevel][topWord] = colonbit;
                    } else {
                        mLevCommaBitmap[curLevel][topWord] = commabit;
                    }
                }
            } else {
                first = 1;
                // The loop continues while there are still bits in cbMask or first is nonzero.
                while (cbMask != 0 || first != 0) {
    
                    if (cbMask == 0) {
                        second = 1L << 63;
                    } else {
                        cbBit = cbMask & -cbMask;
                        second = cbBit;

                        if (curLevel >= 0 && curLevel <= mDepth) {
                            if (mLevColonBitmap[curLevel] == null) {
                                mLevColonBitmap[curLevel] = new long[(int) mNumWords];
                            }
                            if (mLevCommaBitmap[curLevel] == null) {
                                mLevCommaBitmap[curLevel] = new long[(int) mNumWords];
                            }
                            long mask = second - first;
                            // If cbMask is zero then set the final bit.
                            if (cbMask == 0) {
                                mask |= second;
                            }
                            long colonMask = mask & colonbit;
                            if (colonMask != 0) {
                                mLevColonBitmap[curLevel][topWord] |= colonMask;
                            } else {
                                mLevCommaBitmap[curLevel][topWord] |= (commabit & mask);
                            }
                            if (cbMask != 0) {
                                if (cbBit == rbBit) {
                                    mLevColonBitmap[curLevel][topWord] |= cbBit;
                                    mLevCommaBitmap[curLevel][topWord] |= cbBit;
                                } else if (cbBit == lbBit && curLevel + 1 <= mDepth) {
                                    if (mLevCommaBitmap[curLevel + 1] == null) {
                                        mLevCommaBitmap[curLevel + 1] = new long[(int) mNumWords];
                                    }
                                    mLevCommaBitmap[curLevel + 1][topWord] |= cbBit;
                                }
                            }
                        }
                    }
                    if (cbMask != 0) {
                        cbBit = cbMask & -cbMask; // recalc for clarity
                        if (cbBit == lbBit) {
                            lbMask = lbMask & (lbMask - 1);
                            lbBit = lbMask & -lbMask;
                            ++curLevel;
                            if (curLevel == 0) {
                                if (mLevCommaBitmap[curLevel] == null) {
                                    mLevCommaBitmap[curLevel] = new long[(int) mNumWords];
                                }
                                mLevCommaBitmap[curLevel][topWord] |= cbBit;
                            }
                        } else if (cbBit == rbBit) {
                            rbMask = rbMask & (rbMask - 1);
                            rbBit = rbMask & -rbMask;
                            --curLevel;
                        }
                        first = second;
                        cbMask = cbMask & (cbMask - 1);
                        if (curLevel > maxPosLevel) {
                            maxPosLevel = curLevel;
                        }
                    } else {
                        first = 0;
                    }
                }
            }
        }
        if (mDepth == MAX_LEVEL - 1) {
            mDepth = maxPosLevel;
        }
        System.out.println("cur level " + curLevel);

// for (int lvl = 0; lvl < mLevColonBitmap.length; lvl++) {
//     long[] row = mLevColonBitmap[lvl];
//     if (row == null) continue;
//     String hexList = Arrays.stream(row)
//         .mapToObj(l -> String.format("%x", l))
//         .collect(Collectors.joining(", "));
//     System.out.println("colon level bitmap[" + lvl + "]: " + hexList);
// }

// for (int lvl = 0; lvl < mLevCommaBitmap.length; lvl++) {
//     long[] row = mLevCommaBitmap[lvl];
//     if (row == null) continue;
//     String hexList = Arrays.stream(row)
//         .mapToObj(l -> String.format("%x", l))
//         .collect(Collectors.joining(", "));
//     System.out.println("comma level bitmap[" + lvl + "]: " + hexList);
// }

    }
}
