package bitmap;

import java.nio.charset.StandardCharsets;
import tokenizer.*;
// import jdk.incubator.vector.ByteVector;
// import jdk.incubator.vector.VectorMask;
// import jdk.incubator.vector.VectorOperators;
// import jdk.incubator.vector.VectorSpecies;

public class LocalBitmap extends Bitmap {
    static { System.loadLibrary("jsonsimd"); }

    // this must match your C++ signature (you’ll generate the header with javac -h)
    private native long[] processChunk(byte[] in32);
    private native long computeStrMask(long quoteBits, long prevInside);

    // Constants
    private static final int MAX_LEVEL = 32;
    public static final int UNKNOWN = -2;
    // Using 256-bit wide species (32 bytes) for byte vectors.
    //private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_256;
    
    // Member variables
    private int mNumTknErr;
    private int mNumTrial;
    private int mThreadId;
    private byte[] mRecord;
    private long mRecordLength;
    private int mNumTmpWords;  // record length/32
    private int mNumWords;     // record length/64
    private int mDepth;
    
    // Bitmaps for structural characters
    private long[] mEscapeBitmap, mStrBitmap, mColonBitmap, mCommaBitmap;
    private long[] mLbracketBitmap, mRbracketBitmap, mLbraceBitmap, mRbraceBitmap;
    
    // Leveled bitmap arrays (allocated on demand)
    private long[][] mLevColonBitmap = new long[MAX_LEVEL][];
    private long[][] mLevCommaBitmap = new long[MAX_LEVEL][];
    private long[][] mNegLevColonBitmap = new long[MAX_LEVEL][];
    private long[][] mNegLevCommaBitmap = new long[MAX_LEVEL][];
    private long[][] mFinalLevColonBitmap = new long[MAX_LEVEL][];
    private long[][] mFinalLevCommaBitmap = new long[MAX_LEVEL][];
    
    // Variables used for context inference and iteration
    private long[] mQuoteBitmap;
    private long mStartWordId;
    private long mEndWordId;
    private long mStartInStrBitmap;
    private long mEndInStrBitmap;
    private int mMaxPositiveLevel;
    private int mMinNegativeLevel;
    private int mEndLevel;
    
    // Default constructor.
    public LocalBitmap() { }
    
    // Constructor with record and level number.
    public LocalBitmap(byte[] record, int levelNum) {
        mThreadId = 0;
        mRecord = record;
        mDepth = levelNum - 1;
        mStartWordId = 0;
        mEndWordId = 0;
        mQuoteBitmap = null;
        mEscapeBitmap = null;
        mColonBitmap = null;
        mCommaBitmap = null;
        mStrBitmap = null;
        mLbraceBitmap = null;
        mRbraceBitmap = null;
        mLbracketBitmap = null;
        mRbracketBitmap = null;
        for (int i = 0; i < MAX_LEVEL; i++) {
            mLevColonBitmap[i] = null;
            mLevCommaBitmap[i] = null;
            mNegLevColonBitmap[i] = null;
            mNegLevCommaBitmap[i] = null;
            mFinalLevColonBitmap[i] = null;
            mFinalLevCommaBitmap[i] = null;
        }
        mStartInStrBitmap = 0L;
        mEndInStrBitmap = 0L;
        mMaxPositiveLevel = 0;
        mMinNegativeLevel = -1;
        mNumTknErr = 0;
        mNumTrial = 0;
    }
    
    // Free allocated resources (here simply nulling references for GC).
    public void freeMemory() {
        for (int i = 0; i < MAX_LEVEL; i++) {
            mLevColonBitmap[i] = null;
            mLevCommaBitmap[i] = null;
            mNegLevColonBitmap[i] = null;
            mNegLevCommaBitmap[i] = null;
        }
        mQuoteBitmap = null;
        mEscapeBitmap = null;
        mStrBitmap = null;
        mColonBitmap = null;
        mCommaBitmap = null;
        mLbraceBitmap = null;
        mRbraceBitmap = null;
        mLbracketBitmap = null;
        mRbracketBitmap = null;
    }
    
    @Override
    protected void finalize() throws Throwable {
        freeMemory();
        super.finalize();
    }
    
    // --- Getters and Setters ---
    public int getThreadId() { return mThreadId; }
    public int getNumWords() { return mNumWords; }
    public int getMinNegativeLevel() { return mMinNegativeLevel; }
    public int getMaxPositiveLevel() { return mMaxPositiveLevel; }
    public int getEndLevel() { return mEndLevel; }
    public long getStartInStrBitmap() { return mStartInStrBitmap; }
    public long getEndInStrBitmap() { return mEndInStrBitmap; }
    public int getFinalLevSize() { return mFinalLevColonBitmap.length; }
    public long getStartWordId() { return mStartWordId; }
    public long getEndWordId() { return mEndWordId; }
    public long[] getQuoteBitmap() { return mQuoteBitmap; }
    public long[][] getFinalLevColonBitmap() { return mFinalLevColonBitmap; }
    public long[][] getFinalLevCommaBitmap() { return mFinalLevCommaBitmap; }
    
    public void setStartWordId(long startWordId) { mStartWordId = startWordId; }
    public void setEndWordId(long endWordId) { mEndWordId = endWordId; }
    public void setThreadId(int threadId) { mThreadId = threadId; }
    public void setStartInStrBitmap(long startInStrBitmap) { mStartInStrBitmap = startInStrBitmap; }
    public void setEndInStrBitmap(long endInStrBitmap) { mEndInStrBitmap = endInStrBitmap; }
    
    public void copyLevBitmapsToFinal(int level, int idx) {
        if (level < 0 || level >= mFinalLevColonBitmap.length) {
            System.err.println("copyLevBitmapsToFinal: level " + level + " is out-of-bounds. Skipping.");
            return;
        }
        mFinalLevColonBitmap[level] = mLevColonBitmap[idx];
        mFinalLevCommaBitmap[level] = mLevCommaBitmap[idx];
    }
    
    public void copyNegLevBitmapsToFinal(int level, int idx) {
        if (level < 0 || level >= mFinalLevColonBitmap.length) {
            System.err.println("copyNegLevBitmapsToFinal: level " + level + " is out-of-bounds. Skipping.");
            return;
        }
        mFinalLevColonBitmap[level] = mNegLevColonBitmap[idx];
        mFinalLevCommaBitmap[level] = mNegLevCommaBitmap[idx];
    }
    
    public void flipStrBitmapAt(int idx) {
        if (mStrBitmap != null) {
            mStrBitmap[idx] = ~mStrBitmap[idx];
        }
    }
    
    // Set record length and initialize related arrays.
    public void setRecordLength(int length) {
        mRecordLength = length;
        mNumTmpWords = length / 32;
        mNumWords = length / 64;
        mQuoteBitmap = new long[mNumWords];
    }
    
    // Context inference using Tokenizer.
    public int contextInference() {
        Tokenizer tkn = new Tokenizer();
        int[] startStates = { Tokenizer.OUT, Tokenizer.IN };
        boolean getStartState = false;
        int startState = Tokenizer.OUT;
        for (int j = 0; j < 2; j++) {
            mNumTrial++;
            int state = startStates[j];
            tkn.createIterator(new String(mRecord, StandardCharsets.UTF_8), state);
            while (true) {
                int tknStatus = tkn.hasNextToken();
                if (tknStatus == Tokenizer.END)
                    break;
                if (tknStatus == Tokenizer.ERROR) {
                    mNumTknErr++;
                    startState = tkn.oppositeState(state);
                    getStartState = true;
                    break;
                }
                tkn.nextToken();
            }
            if (getStartState)
                break;
        }
        if (startState == Tokenizer.IN) {
            mStartInStrBitmap = 0xffffffffffffffffL;
        } else {
            mStartInStrBitmap = 0L;
        }
        System.out.println("inference result num of trails: " + mNumTrial + " num of token error " + mNumTknErr);
        System.out.println("inference result " + startState + " " + getStartState);
        return getStartState ? startState : UNKNOWN;
    }
    
    // --- Non-Speculative Index Construction ---
    // This method closely emulates the C++ version using Panama Vector API calls.
    public void nonSpecIndexConstruction() {
        // Define structural character constants.
        byte QUOTE    = 0x22;
        byte COLON    = 0x3a;
        byte ESCAPE   = 0x5c;
        byte LBRACE   = 0x7b;
        byte RBRACE   = 0x7d;
        byte COMMA    = 0x2c;
        byte LBRACKET = 0x5b;
        byte RBRACKET = 0x5d;
        
        long colonbit0 = 0, quotebit0 = 0, escapebit0 = 0, lbracebit0 = 0, rbracebit0 = 0;
        long commabit0 = 0, lbracketbit0 = 0, rbracketbit0 = 0;
        long colonbit, quotebit, escapebit, lbracebit, rbracebit, commabit, lbracketbit, rbracketbit;
        long strMask;
        
        long lb_mask, rb_mask, cb_mask;
        long lb_bit = 0, rb_bit = 0, cb_bit = 0;
        long first, second;
        int cur_level = -1;
        int top_word = -1;
        long prev_iter_ends_odd_backslash = 0L;
        long prev_iter_inside_quote = mStartInStrBitmap;
        final long even_bits = 0x5555555555555555L;
        final long odd_bits = ~even_bits;
        
        // Process each 32-byte block (temporary word).
        for (int j = 0; j < mNumTmpWords; j++) {
            colonbit = quotebit = escapebit = lbracebit = rbracebit = commabit = lbracketbit = rbracketbit = 0;
            int off = j * 32;
        byte[] slice = Arrays.copyOfRange(mRecord, off, off + 32);
        long[] bits = processChunk(slice);
        colonbit    = bits[0];
        quotebit    = bits[1];
        escapebit   = bits[2];
        lbracebit   = bits[3];
        rbracebit   = bits[4];
        commabit    = bits[5];
        lbracketbit = bits[6];
        rbracketbit = bits[7];

        if ((j & 1) == 0) {
                // Save first half.
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
                // Combine two 32-bit halves into one 64-bit word.
                colonbit    = (colonbit << 32) | colonbit0;
                quotebit    = (quotebit << 32) | quotebit0;
                escapebit   = (escapebit << 32) | escapebit0;
                lbracebit   = (lbracebit << 32) | lbracebit0;
                rbracebit   = (rbracebit << 32) | rbracebit0;
                commabit    = (commabit << 32) | commabit0;
                lbracketbit = (lbracketbit << 32) | lbracketbit0;
                rbracketbit = (rbracketbit << 32) | rbracketbit0;
                top_word++;
                
                // Step 2: Update structural quote bitmap.
                long bs_bits = escapebit;
                long start_edges = bs_bits & ~(bs_bits << 1);
                long even_start_mask = even_bits ^ prev_iter_ends_odd_backslash;
                long even_starts = start_edges & even_start_mask;
                long odd_starts = start_edges & ~even_start_mask;
                AddResult evenRes = addWithOverflow(bs_bits, even_starts);
                long even_carries = evenRes.sum;
                AddResult oddRes = addWithOverflow(bs_bits, odd_starts);
                long odd_carries = oddRes.sum;
                odd_carries |= prev_iter_ends_odd_backslash;
                prev_iter_ends_odd_backslash = oddRes.overflow ? 1L : 0L;
                long even_carry_ends = even_carries & ~bs_bits;
                long odd_carry_ends = odd_carries & ~bs_bits;
                long even_start_odd_end = even_carry_ends & odd_bits;
                long odd_start_even_end = odd_carry_ends & even_bits;
                long odd_ends = even_start_odd_end | odd_start_even_end;
                long quote_bits = quotebit & ~odd_ends;
                mQuoteBitmap[++top_word] = quote_bits;
                
                // Step 3: Compute string mask.
               // call into your native computeStrMask, then xor in Java
                long str_mask = computeStrMask(quote_bits, prev_iter_inside_quote)
                            ^ prev_iter_inside_quote;

                // arithmetic right‑shift will sign‑extend, giving you 0xFFFF… or 0x0000…
                prev_iter_inside_quote = str_mask >> 63;

                
                // Step 4: Exclude characters inside strings.
                long tmp = ~strMask;
                colonbit   &= tmp;
                lbracebit  &= tmp;
                rbracebit  &= tmp;
                commabit   &= tmp;
                lbracketbit &= tmp;
                rbracketbit &= tmp;
                
                // Step 5: Generate leveled bitmaps.
                lb_mask = lbracebit | lbracketbit;
                rb_mask = rbracebit | rbracketbit;
                cb_mask = lb_mask | rb_mask;
                lb_bit = lb_mask & -lb_mask;
                rb_bit = rb_mask & -rb_mask;
                
                if (cb_mask == 0) {
                    if (cur_level >= 0 && cur_level <= mDepth) {
                        if (mLevColonBitmap[cur_level] == null) {
                            mLevColonBitmap[cur_level] = new long[mNumWords];
                        }
                        if (mLevCommaBitmap[cur_level] == null) {
                            mLevCommaBitmap[cur_level] = new long[mNumWords];
                        }
                        if (colonbit != 0) {
                            mLevColonBitmap[cur_level][top_word] = colonbit;
                        } else {
                            mLevCommaBitmap[cur_level][top_word] = commabit;
                        }
                    } else if (cur_level < 0) {
                        int idx = -cur_level;
                        if (mNegLevColonBitmap[idx] == null) {
                            mNegLevColonBitmap[idx] = new long[mNumWords];
                            if (cur_level < mMinNegativeLevel)
                                mMinNegativeLevel = cur_level;
                        }
                        if (mNegLevCommaBitmap[idx] == null) {
                            mNegLevCommaBitmap[idx] = new long[mNumWords];
                        }
                        if (colonbit != 0) {
                            mNegLevColonBitmap[idx][top_word] = colonbit;
                        } else {
                            mNegLevCommaBitmap[idx][top_word] = commabit;
                        }
                    }
                } else {
                    first = 1;
                    do {
                        if (cb_mask == 0) {
                            second = 1L << 63;
                        } else {
                            cb_bit = cb_mask & -cb_mask;
                            second = cb_bit;
                        }
                        if (cur_level >= 0 && cur_level <= mDepth) {
                            if (mLevColonBitmap[cur_level] == null) {
                                mLevColonBitmap[cur_level] = new long[mNumWords];
                            }
                            if (mLevCommaBitmap[cur_level] == null) {
                                mLevCommaBitmap[cur_level] = new long[mNumWords];
                            }
                            long mask = second - first;
                            if (cb_mask == 0)
                                mask |= second;
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
                                        mLevCommaBitmap[cur_level + 1] = new long[mNumWords];
                                    }
                                    mLevCommaBitmap[cur_level + 1][top_word] |= cb_bit;
                                }
                            }
                        } else if (cur_level < 0) {
                            int idx = -cur_level;
                            if (mNegLevColonBitmap[idx] == null) {
                                mNegLevColonBitmap[idx] = new long[mNumWords];
                            }
                            if (mNegLevCommaBitmap[idx] == null) {
                                mNegLevCommaBitmap[idx] = new long[mNumWords];
                            }
                            long mask = second - first;
                            if (cb_mask == 0)
                                mask |= second;
                            long colon_mask = mask & colonbit;
                            if (colon_mask != 0) {
                                mNegLevColonBitmap[idx][top_word] |= colon_mask;
                            } else {
                                mNegLevCommaBitmap[idx][top_word] |= (commabit & mask);
                            }
                            if (cb_mask != 0) {
                                if (cb_bit == rb_bit) {
                                    mNegLevColonBitmap[idx][top_word] |= cb_bit;
                                    mNegLevCommaBitmap[idx][top_word] |= cb_bit;
                                } else if (cb_bit == lb_bit) {
                                    if (cur_level + 1 == 0) {
                                        if (mLevCommaBitmap[0] == null) {
                                            mLevCommaBitmap[0] = new long[mNumWords];
                                        }
                                        mLevCommaBitmap[0][top_word] |= cb_bit;
                                    } else {
                                        int idx2 = -(cur_level + 1);
                                        if (mNegLevCommaBitmap[idx2] == null) {
                                            mNegLevCommaBitmap[idx2] = new long[mNumWords];
                                        }
                                        mNegLevCommaBitmap[idx2][top_word] |= cb_bit;
                                    }
                                }
                            }
                        }
                        if (cb_mask != 0) {
                            if (cb_bit == lb_bit) {
                                lb_mask &= (lb_mask - 1);
                                lb_bit = lb_mask & -lb_mask;
                                cur_level++;
                                if (mThreadId == 0 && cur_level == 0) {
                                    if (mLevCommaBitmap[cur_level] == null) {
                                        mLevCommaBitmap[cur_level] = new long[mNumWords];
                                    }
                                    mLevCommaBitmap[cur_level][top_word] |= cb_bit;
                                }
                            } else if (cb_bit == rb_bit) {
                                rb_mask &= (rb_mask - 1);
                                rb_bit = rb_mask & -rb_mask;
                                cur_level--;
                            }
                            first = second;
                            cb_mask &= (cb_mask - 1);
                            if (cur_level > mMaxPositiveLevel)
                                mMaxPositiveLevel = cur_level;
                            else if (cur_level < mMinNegativeLevel)
                                mMinNegativeLevel = cur_level;
                        } else {
                            first = 0;
                        }
                    } while (cb_mask != 0 || first != 0);
                }
            }
        }
        if (mDepth == MAX_LEVEL - 1)
            mDepth = mMaxPositiveLevel;
        mEndLevel = cur_level;
    }
    
    // --- Build String Mask Bitmap ---
    public void buildStringMaskBitmap() {
        if (mQuoteBitmap == null)
            mQuoteBitmap = new long[mNumWords];
        if (mColonBitmap == null)
            mColonBitmap = new long[mNumWords];
        if (mCommaBitmap == null)
            mCommaBitmap = new long[mNumWords];
        if (mStrBitmap == null)
            mStrBitmap = new long[mNumWords];
        if (mLbraceBitmap == null)
            mLbraceBitmap = new long[mNumWords];
        if (mRbraceBitmap == null)
            mRbraceBitmap = new long[mNumWords];
        if (mLbracketBitmap == null)
            mLbracketBitmap = new long[mNumWords];
        if (mRbracketBitmap == null)
            mRbracketBitmap = new long[mNumWords];
        
        byte QUOTE    = 0x22;
        byte COLON    = 0x3a;
        byte ESCAPE   = 0x5c;
        byte LBRACE   = 0x7b;
        byte RBRACE   = 0x7d;
        byte COMMA    = 0x2c;
        byte LBRACKET = 0x5b;
        byte RBRACKET = 0x5d;
        
        long colonbit0 = 0, quotebit0 = 0, escapebit0 = 0, lbracebit0 = 0, rbracebit0 = 0;
        long commabit0 = 0, lbracketbit0 = 0, rbracketbit0 = 0;
        long colonbit, quotebit, escapebit, lbracebit, rbracebit, commabit, lbracketbit, rbracketbit;
        long strMask;
        
        int top_word = -1;
        long prev_iter_ends_odd_backslash = 0L;
        long prev_iter_inside_quote = mStartInStrBitmap;
        final long even_bits = 0x5555555555555555L;
        final long odd_bits = ~even_bits;
        
        for (int j = 0; j < mNumTmpWords; j++) {
            colonbit = quotebit = escapebit = lbracebit = rbracebit = commabit = lbracketbit = rbracketbit = 0;
            int off = j * 32;
            byte[] slice = Arrays.copyOfRange(mRecord, off, off + 32);
            long[] bits = processChunk(slice);

            colonbit    = bits[0];
            quotebit    = bits[1];
            escapebit   = bits[2];
            lbracebit   = bits[3];
            rbracebit   = bits[4];
            commabit    = bits[5];
            lbracketbit = bits[6];
            rbracketbit = bits[7];
            
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
                colonbit    = (colonbit << 32) | colonbit0;
                quotebit    = (quotebit << 32) | quotebit0;
                escapebit   = (escapebit << 32) | escapebit0;
                lbracebit   = (lbracebit << 32) | lbracebit0;
                rbracebit   = (rbracebit << 32) | rbracebit0;
                commabit    = (commabit << 32) | commabit0;
                lbracketbit = (lbracketbit << 32) | lbracketbit0;
                rbracketbit = (rbracketbit << 32) | rbracketbit0;
                top_word++;
                
                mColonBitmap[top_word]    = colonbit;
                mCommaBitmap[top_word]    = commabit;
                mLbraceBitmap[top_word]   = lbracebit;
                mRbraceBitmap[top_word]   = rbracebit;
                mLbracketBitmap[top_word] = lbracketbit;
                mRbracketBitmap[top_word] = rbracketbit;
                
                long bs_bits = escapebit;
                long start_edges = bs_bits & ~(bs_bits << 1);
                long even_start_mask = even_bits ^ prev_iter_ends_odd_backslash;
                long even_starts = start_edges & even_start_mask;
                long odd_starts = start_edges & ~even_start_mask;
                AddResult evenRes = addWithOverflow(bs_bits, even_starts);
                long even_carries = evenRes.sum;
                AddResult oddRes = addWithOverflow(bs_bits, odd_starts);
                long odd_carries = oddRes.sum;
                odd_carries |= prev_iter_ends_odd_backslash;
                prev_iter_ends_odd_backslash = oddRes.overflow ? 1L : 0L;
                long even_carry_ends = even_carries & ~bs_bits;
                long odd_carry_ends = odd_carries & ~bs_bits;
                long even_start_odd_end = even_carry_ends & odd_bits;
                long odd_start_even_end = odd_carry_ends & even_bits;
                long odd_ends = even_start_odd_end | odd_start_even_end;
                long quote_bits = quotebit & ~odd_ends;
                mQuoteBitmap[top_word] = quote_bits;
                
                // call into your native computeStrMask, then xor in Java
                long str_mask = computeStrMask(quote_bits, prev_iter_inside_quote)
                            ^ prev_iter_inside_quote;

                // arithmetic right‑shift will sign‑extend, giving you 0xFFFF… or 0x0000…
                prev_iter_inside_quote = str_mask >> 63;

            }
        }
        mEndInStrBitmap = prev_iter_inside_quote;
    }
    
    // --- Build Leveled Bitmap ---
    public void buildLeveledBitmap() {
        long colonbit, commabit, lbracebit, rbracebit, lbracketbit, rbracketbit;
        long strMask;
        long lb_mask, rb_mask, cb_mask;
        long lb_bit, rb_bit; 
        long cb_bit = 0;
        long first, second;
        int cur_level = -1;
        
        for (int j = 0; j < mNumWords; j++) {
            colonbit    = mColonBitmap[j];
            commabit    = mCommaBitmap[j];
            lbracebit   = mLbraceBitmap[j];
            rbracebit   = mRbraceBitmap[j];
            lbracketbit = mLbracketBitmap[j];
            rbracketbit = mRbracketBitmap[j];
            strMask     = mStrBitmap[j];
            
            long tmp = ~strMask;
            colonbit   &= tmp;
            lbracebit  &= tmp;
            rbracebit  &= tmp;
            commabit   &= tmp;
            lbracketbit &= tmp;
            rbracketbit &= tmp;
            
            lb_mask = lbracebit | lbracketbit;
            rb_mask = rbracebit | rbracketbit;
            cb_mask = lb_mask | rb_mask;
            lb_bit = lb_mask & -lb_mask;
            rb_bit = rb_mask & -rb_mask;
            int top_word = j;
            if (cb_mask == 0) {
                if (cur_level >= 0 && cur_level <= mDepth) {
                    if (mLevColonBitmap[cur_level] == null)
                        mLevColonBitmap[cur_level] = new long[mNumWords];
                    if (mLevCommaBitmap[cur_level] == null)
                        mLevCommaBitmap[cur_level] = new long[mNumWords];
                    if (colonbit != 0)
                        mLevColonBitmap[cur_level][top_word] = colonbit;
                    else
                        mLevCommaBitmap[cur_level][top_word] = commabit;
                } else if (cur_level < 0) {
                    int idx = -cur_level;
                    if (mNegLevColonBitmap[idx] == null)
                        mNegLevColonBitmap[idx] = new long[mNumWords];
                    if (mNegLevCommaBitmap[idx] == null)
                        mNegLevCommaBitmap[idx] = new long[mNumWords];
                    if (colonbit != 0)
                        mNegLevColonBitmap[idx][top_word] = colonbit;
                    else
                        mNegLevCommaBitmap[idx][top_word] = commabit;
                }
            } else {
                first = 1;
                do {
                    if (cb_mask == 0)
                        second = 1L << 63;
                    else {
                        cb_bit = cb_mask & -cb_mask;
                        second = cb_bit;
                    }
                    if (cur_level >= 0 && cur_level <= mDepth) {
                        if (mLevColonBitmap[cur_level] == null)
                            mLevColonBitmap[cur_level] = new long[mNumWords];
                        if (mLevCommaBitmap[cur_level] == null)
                            mLevCommaBitmap[cur_level] = new long[mNumWords];
                        long mask = second - first;
                        if (cb_mask == 0)
                            mask |= second;
                        long colon_mask = mask & colonbit;
                        if (colon_mask != 0)
                            mLevColonBitmap[cur_level][top_word] |= colon_mask;
                        else
                            mLevCommaBitmap[cur_level][top_word] |= (commabit & mask);
                        if (cb_mask != 0) {
                            if (cb_bit == rb_bit) {
                                mLevColonBitmap[cur_level][top_word] |= cb_bit;
                                mLevCommaBitmap[cur_level][top_word] |= cb_bit;
                            } else if (cb_bit == lb_bit && cur_level + 1 <= mDepth) {
                                if (mLevCommaBitmap[cur_level + 1] == null)
                                    mLevCommaBitmap[cur_level + 1] = new long[mNumWords];
                                mLevCommaBitmap[cur_level + 1][top_word] |= cb_bit;
                            }
                        }
                    } else if (cur_level < 0) {
                        int idx = -cur_level;
                        if (mNegLevColonBitmap[idx] == null)
                            mNegLevColonBitmap[idx] = new long[mNumWords];
                        if (mNegLevCommaBitmap[idx] == null)
                            mNegLevCommaBitmap[idx] = new long[mNumWords];
                        long mask = second - first;
                        if (cb_mask == 0)
                            mask |= second;
                        long colon_mask = mask & colonbit;
                        if (colon_mask != 0)
                            mNegLevColonBitmap[idx][top_word] |= colon_mask;
                        else
                            mNegLevCommaBitmap[idx][top_word] |= (commabit & mask);
                        if (cb_mask != 0) {
                            if (cb_bit == rb_bit) {
                                mNegLevColonBitmap[idx][top_word] |= cb_bit;
                                mNegLevCommaBitmap[idx][top_word] |= cb_bit;
                            } else if (cb_bit == lb_bit) {
                                if (cur_level + 1 == 0) {
                                    if (mLevCommaBitmap[0] == null)
                                        mLevCommaBitmap[0] = new long[mNumWords];
                                    mLevCommaBitmap[0][top_word] |= cb_bit;
                                } else {
                                    int idx2 = -(cur_level + 1);
                                    if (mNegLevCommaBitmap[idx2] == null)
                                        mNegLevCommaBitmap[idx2] = new long[mNumWords];
                                    mNegLevCommaBitmap[idx2][top_word] |= cb_bit;
                                }
                            }
                        }
                    }
                    if (cb_mask != 0) {
                        if (cb_bit == lb_bit) {
                            lb_mask &= (lb_mask - 1);
                            lb_bit = lb_mask & -lb_mask;
                            cur_level++;
                            if (mThreadId == 0 && cur_level == 0) {
                                if (mLevCommaBitmap[cur_level] == null)
                                    mLevCommaBitmap[cur_level] = new long[mNumWords];
                                mLevCommaBitmap[cur_level][top_word] |= cb_bit;
                            }
                        } else if (cb_bit == rb_bit) {
                            rb_mask &= (rb_mask - 1);
                            rb_bit = rb_mask & -rb_mask;
                            cur_level--;
                        }
                        first = second;
                        cb_mask &= (cb_mask - 1);
                        if (cur_level > mMaxPositiveLevel)
                            mMaxPositiveLevel = cur_level;
                        else if (cur_level < mMinNegativeLevel)
                            mMinNegativeLevel = cur_level;
                    } else {
                        first = 0;
                    }
                } while (cb_mask != 0 || first != 0);
            }
        }
        if (mDepth == MAX_LEVEL - 1)
            mDepth = mMaxPositiveLevel;
        mEndLevel = cur_level;
    }
    
// private long movemaskVec(int offset, byte target) {
//     long mask = 0L;
//     // Process 32 bytes starting at offset.
//     for (int i = 0; i < 32 && (offset + i) < mRecord.length; i++) {
//         if (mRecord[offset + i] == target) {
//             mask |= (1L << i);
//         }
//     }
//     return mask;
// }

    
    // --- Helper: addWithOverflow ---
    private AddResult addWithOverflow(long a, long b) {
        long sum = a + b;
        boolean overflow = Long.compareUnsigned(sum, a) < 0;
        return new AddResult(sum, overflow);
    }
    
    private static class AddResult {
        long sum;
        boolean overflow;
        AddResult(long sum, boolean overflow) {
            this.sum = sum;
            this.overflow = overflow;
        }
    }
    
    // --- Helper: computeStringMask ---
    // Emulates a cumulative parity operation (similar in spirit to a carry-less multiply) that
    // produces a mask indicating positions that are inside a string.
    private long computeStringMask(long quoteBits, long prevInside) {
        long mask = 0L;
        boolean inQuote = (prevInside != 0);
        for (int bit = 0; bit < 64; bit++) {
            if (((quoteBits >> bit) & 1L) != 0) {
                inQuote = !inQuote;
            }
            if (inQuote) {
                mask |= (1L << bit);
            }
        }
        return mask ^ prevInside;
    }
}
