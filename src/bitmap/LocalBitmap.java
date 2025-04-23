package bitmap;

import java.nio.charset.StandardCharsets;
import tokenizer.*;
import java.util.Arrays;

public class LocalBitmap extends Bitmap {
    // Constants
    private static final int MAX_LEVEL = 22;
    public static final int UNKNOWN = 9;
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
        for (int i = 0; i < MAX_LEVEL; ++i) {
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
        for (int i = 0; i < MAX_LEVEL; ++i) {
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
            // System.err.println("copyLevBitmapsToFinal: level " + level + " is out-of-bounds. Skipping.");
            return;
        }
        mFinalLevColonBitmap[level] = mLevColonBitmap[idx];
        mFinalLevCommaBitmap[level] = mLevCommaBitmap[idx];
    }
    
    public void copyNegLevBitmapsToFinal(int level, int idx) {
        if (level < 0 || level >= mFinalLevColonBitmap.length) {
            // System.err.println("copyNegLevBitmapsToFinal: level " + level + " is out-of-bounds. Skipping.");
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
        for (int j = 0; j < 2; ++j) {
            ++mNumTrial;
            int state = startStates[j];
            tkn.createIterator(mRecord, state);
            while (true) {
                int tknStatus = tkn.hasNextToken();
                if (tknStatus == Tokenizer.END)
                    break;
                if (tknStatus == Tokenizer.ERROR) {
                    ++mNumTknErr;
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

    private void ensureNegLevColonCapacity(int idx) {
        if (idx >= mNegLevColonBitmap.length) {
            int newSize = Math.max(idx + 1, mNegLevColonBitmap.length * 2);
            mNegLevColonBitmap = Arrays.copyOf(mNegLevColonBitmap, newSize);
        }
    }

    private void ensureNegLevCommaCapacity(int idx) {
        if (idx >= mNegLevCommaBitmap.length) {
            int newSize = Math.max(idx + 1, mNegLevCommaBitmap.length * 2);
            mNegLevCommaBitmap = Arrays.copyOf(mNegLevCommaBitmap, newSize);
        }
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
        
        long lbMask, rbMask, cbMask;
        long lbBit = 0, rbBit = 0, cbBit = 0;
        long first, second;
        int curLevel = -1;
        int topWord = -1;
        long prevIterEndsOddBackslash = 0L;
        long prevIterInsideQuote = mStartInStrBitmap;
        final long evenBits = 0x5555555555555555L;
        final long oddBits = ~evenBits;
        
        // Process each 32-byte block (temporary word).
        for (int j = 0; j < mNumTmpWords; ++j) {
            colonbit = quotebit = escapebit = lbracebit = rbracebit = commabit = lbracketbit = rbracketbit = 0;
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

        if ((j % 2) == 0) {
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
                
                // Step 2: Update structural quote bitmap.
                long bsBits = escapebit;
                long startEdges = bsBits & ~(bsBits << 1);
                long evenStartMask = evenBits ^ prevIterEndsOddBackslash;
                long evenStarts = startEdges & evenStartMask;
                long oddStarts = startEdges & ~evenStartMask;
                AddResult evenRes = addWithOverflow(bsBits, evenStarts);
                long evenCarries = evenRes.sum;
                AddResult oddRes = addWithOverflow(bsBits, oddStarts);
                long oddCarries = oddRes.sum;
                oddCarries |= prevIterEndsOddBackslash;
                prevIterEndsOddBackslash = oddRes.overflow ? 1L : 0L;
                long evenCarryEnds = evenCarries & ~bsBits;
                long oddCarryEnds = oddCarries & ~bsBits;
                long evenStartOddEnd = evenCarryEnds & oddBits;
                long oddStartEvenEnd = oddCarryEnds & evenBits;
                long oddEnds = evenStartOddEnd | oddStartEvenEnd;
                long quoteBits = quotebit & ~oddEnds;
                mQuoteBitmap[++topWord] = quoteBits;
                
                // Step 3: Compute string mask.
               // call into your native computeStrMask, then xor in Java
                long strMasks = JsonSimd.computeStrMask(quoteBits, prevIterInsideQuote)
                            ^ prevIterInsideQuote;

                // arithmetic right‑shift will sign‑extend, giving you 0xFFFF… or 0x0000…
                prevIterInsideQuote = strMasks >> 63;
    // System.out.println("prevIterInsideQuote " + prevIterInsideQuote);
                
                // Step 4: Exclude characters inside strings.
                long tmp = ~strMasks;
                colonbit   &= tmp;
                lbracebit  &= tmp;
                rbracebit  &= tmp;
                commabit   &= tmp;
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
                        if (mLevColonBitmap[curLevel] == null) {
                            mLevColonBitmap[curLevel] = new long[mNumWords];
                        }
                        if (mLevCommaBitmap[curLevel] == null) {
                            mLevCommaBitmap[curLevel] = new long[mNumWords];
                        }
                        if (colonbit != 0) {
                            mLevColonBitmap[curLevel][topWord] = colonbit;
                        } else {
                            mLevCommaBitmap[curLevel][topWord] = commabit;
                        }
                    } else if (curLevel < 0) {
                        int idx = -curLevel;
                        ensureNegLevColonCapacity(idx);
                        ensureNegLevCommaCapacity(idx);

                        if (mNegLevColonBitmap[idx] == null) {
                            mNegLevColonBitmap[idx] = new long[mNumWords];
                            if (curLevel < mMinNegativeLevel)
                                mMinNegativeLevel = curLevel;
                        }
                        if (mNegLevCommaBitmap[idx] == null) {
                            mNegLevCommaBitmap[idx] = new long[mNumWords];
                        }
                        if (colonbit != 0) {
                            mNegLevColonBitmap[idx][topWord] = colonbit;
                        } else {
                            mNegLevCommaBitmap[idx][topWord] = commabit;
                        }
                    }
                } else {
                    first = 1;
                    do {
                        if (cbMask == 0) {
                            second = 1L << 63;
                        } else {
                            cbBit = cbMask & -cbMask;
                            second = cbBit;
                        }
                        if (curLevel >= 0 && curLevel <= mDepth) {
                            if (mLevColonBitmap[curLevel] == null) {
                                mLevColonBitmap[curLevel] = new long[mNumWords];
                            }
                            if (mLevCommaBitmap[curLevel] == null) {
                                mLevCommaBitmap[curLevel] = new long[mNumWords];
                            }
                            long mask = second - first;
                            if (cbMask == 0)
                                mask |= second;
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
                                        mLevCommaBitmap[curLevel + 1] = new long[mNumWords];
                                    }
                                    mLevCommaBitmap[curLevel + 1][topWord] |= cbBit;
                                }
                            }
                        } else if (curLevel < 0) {
                            int idx = -curLevel;
                            ensureNegLevColonCapacity(idx);
                            ensureNegLevCommaCapacity(idx);

                            if (mNegLevColonBitmap[idx] == null) {
                                mNegLevColonBitmap[idx] = new long[mNumWords];
                            }
                            if (mNegLevCommaBitmap[idx] == null) {
                                mNegLevCommaBitmap[idx] = new long[mNumWords];
                            }
                            long mask = second - first;
                            if (cbMask == 0)
                                mask |= second;
                            long colonMask = mask & colonbit;
                            if (colonMask != 0) {
                                mNegLevColonBitmap[idx][topWord] |= colonMask;
                            } else {
                                mNegLevCommaBitmap[idx][topWord] |= (commabit & mask);
                            }
                            if (cbMask != 0) {
                                if (cbBit == rbBit) {
                                    mNegLevColonBitmap[idx][topWord] |= cbBit;
                                    mNegLevCommaBitmap[idx][topWord] |= cbBit;
                                } else if (cbBit == lbBit) {
                                    if (curLevel + 1 == 0) {
                                        if (mLevCommaBitmap[0] == null) {
                                            mLevCommaBitmap[0] = new long[mNumWords];
                                        }
                                        mLevCommaBitmap[0][topWord] |= cbBit;
                                    } else {
                                        int idx2 = -(curLevel + 1);
                                        if (mNegLevCommaBitmap[idx2] == null) {
                                            mNegLevCommaBitmap[idx2] = new long[mNumWords];
                                        }
                                        mNegLevCommaBitmap[idx2][topWord] |= cbBit;
                                    }
                                }
                            }
                        }
                        if (cbMask != 0) {
                            if (cbBit == lbBit) {
                                lbMask &= (lbMask - 1);
                                lbBit = lbMask & -lbMask;
                                ++curLevel;
                                if (mThreadId == 0 && curLevel == 0) {
                                    if (mLevCommaBitmap[curLevel] == null) {
                                        mLevCommaBitmap[curLevel] = new long[mNumWords];
                                    }
                                    mLevCommaBitmap[curLevel][topWord] |= cbBit;
                                }
                            } else if (cbBit == rbBit) {
                                rbMask &= (rbMask - 1);
                                rbBit = rbMask & -rbMask;
                                --curLevel;
                            }
                            first = second;
                            cbMask &= (cbMask - 1);
                            if (curLevel > mMaxPositiveLevel)
                                mMaxPositiveLevel = curLevel;
                            else if (curLevel < mMinNegativeLevel)
                                mMinNegativeLevel = curLevel;
                        } else {
                            first = 0;
                        }
                    } while (cbMask != 0 || first != 0);
                }
            }
        }
        if (mDepth == MAX_LEVEL - 1)
            mDepth = mMaxPositiveLevel;
        mEndLevel = curLevel;
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
        
        int topWord = -1;
        long prevIterEndsOddBackslash = 0L;
        long prevIterInsideQuote = mStartInStrBitmap;
        final long evenBits = 0x5555555555555555L;
        final long oddBits = ~evenBits;
        
        for (int j = 0; j < mNumTmpWords; ++j) {
            colonbit = quotebit = escapebit = lbracebit = rbracebit = commabit = lbracketbit = rbracketbit = 0;
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
                
                mColonBitmap[++topWord]    = colonbit;
                mCommaBitmap[topWord]    = commabit;
                mLbraceBitmap[topWord]   = lbracebit;
                mRbraceBitmap[topWord]   = rbracebit;
                mLbracketBitmap[topWord] = lbracketbit;
                mRbracketBitmap[topWord] = rbracketbit;
                
                long bsBits = escapebit;
                long startEdges = bsBits & ~(bsBits << 1);
                long evenStartMask = evenBits ^ prevIterEndsOddBackslash;
                long evenStarts = startEdges & evenStartMask;
                long oddStarts = startEdges & ~evenStartMask;
                AddResult evenRes = addWithOverflow(bsBits, evenStarts);
                long evenCarries = evenRes.sum;
                AddResult oddRes = addWithOverflow(bsBits, oddStarts);
                long oddCarries = oddRes.sum;
                oddCarries |= prevIterEndsOddBackslash;
                prevIterEndsOddBackslash = oddRes.overflow ? 1L : 0L;
                long evenCarryEnds = evenCarries & ~bsBits;
                long oddCarryEnds = oddCarries & ~bsBits;
                long evenStartOddEnd = evenCarryEnds & oddBits;
                long oddStartEvenEnd = oddCarryEnds & evenBits;
                long oddEnds = evenStartOddEnd | oddStartEvenEnd;
                long quoteBits = quotebit & ~oddEnds;
                mQuoteBitmap[topWord] = quoteBits;
                
                // call into your native computeStrMask, then xor in Java
                long strMasks = JsonSimd.computeStrMask(quoteBits, prevIterInsideQuote)
                            ^ prevIterInsideQuote;

                // arithmetic right‑shift will sign‑extend, giving you 0xFFFF… or 0x0000…
                prevIterInsideQuote = strMasks >> 63;

            }
        }
        mEndInStrBitmap = prevIterInsideQuote;
    }
    
    // --- Build Leveled Bitmap ---
    public void buildLeveledBitmap() {
        long colonbit, commabit, lbracebit, rbracebit, lbracketbit, rbracketbit;
        long strMask;
        long lbMask, rbMask, cbMask;
        long lbBit, rbBit; 
        long cbBit = 0;
        long first, second;
        int curLevel = -1;
        
        for (int j = 0; j < mNumWords; ++j) {
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
            
            lbMask = lbracebit | lbracketbit;
            rbMask = rbracebit | rbracketbit;
            cbMask = lbMask | rbMask;
            lbBit = lbMask & -lbMask;
            rbBit = rbMask & -rbMask;
            int topWord = j;
            if (cbMask == 0) {
                if (curLevel >= 0 && curLevel <= mDepth) {
                    if (mLevColonBitmap[curLevel] == null)
                        mLevColonBitmap[curLevel] = new long[mNumWords];
                    if (mLevCommaBitmap[curLevel] == null)
                        mLevCommaBitmap[curLevel] = new long[mNumWords];
                    if (colonbit != 0)
                        mLevColonBitmap[curLevel][topWord] = colonbit;
                    else
                        mLevCommaBitmap[curLevel][topWord] = commabit;
                } else if (curLevel < 0) {
                    int idx = -curLevel;
                    ensureNegLevColonCapacity(idx);
                    ensureNegLevCommaCapacity(idx);

                    if (mNegLevColonBitmap[idx] == null)
                        mNegLevColonBitmap[idx] = new long[mNumWords];
                    if (mNegLevCommaBitmap[idx] == null)
                        mNegLevCommaBitmap[idx] = new long[mNumWords];
                    if (colonbit != 0)
                        mNegLevColonBitmap[idx][topWord] = colonbit;
                    else
                        mNegLevCommaBitmap[idx][topWord] = commabit;
                }
            } else {
                first = 1;
                do {
                    if (cbMask == 0)
                        second = 1L << 63;
                    else {
                        cbBit = cbMask & -cbMask;
                        second = cbBit;
                    }
                    if (curLevel >= 0 && curLevel <= mDepth) {
                        if (mLevColonBitmap[curLevel] == null)
                            mLevColonBitmap[curLevel] = new long[mNumWords];
                        if (mLevCommaBitmap[curLevel] == null)
                            mLevCommaBitmap[curLevel] = new long[mNumWords];
                        long mask = second - first;
                        if (cbMask == 0)
                            mask |= second;
                        long colonMask = mask & colonbit;
                        if (colonMask != 0)
                            mLevColonBitmap[curLevel][topWord] |= colonMask;
                        else
                            mLevCommaBitmap[curLevel][topWord] |= (commabit & mask);
                        if (cbMask != 0) {
                            if (cbBit == rbBit) {
                                mLevColonBitmap[curLevel][topWord] |= cbBit;
                                mLevCommaBitmap[curLevel][topWord] |= cbBit;
                            } else if (cbBit == lbBit && curLevel + 1 <= mDepth) {
                                if (mLevCommaBitmap[curLevel + 1] == null)
                                    mLevCommaBitmap[curLevel + 1] = new long[mNumWords];
                                mLevCommaBitmap[curLevel + 1][topWord] |= cbBit;
                            }
                        }
                    } else if (curLevel < 0) {
                        int idx = -curLevel;
                        ensureNegLevColonCapacity(idx);
                        ensureNegLevCommaCapacity(idx);

                        if (mNegLevColonBitmap[idx] == null)
                            mNegLevColonBitmap[idx] = new long[mNumWords];
                        if (mNegLevCommaBitmap[idx] == null)
                            mNegLevCommaBitmap[idx] = new long[mNumWords];
                        long mask = second - first;
                        if (cbMask == 0)
                            mask |= second;
                        long colonMask = mask & colonbit;
                        if (colonMask != 0)
                            mNegLevColonBitmap[idx][topWord] |= colonMask;
                        else
                            mNegLevCommaBitmap[idx][topWord] |= (commabit & mask);
                        if (cbMask != 0) {
                            if (cbBit == rbBit) {
                                mNegLevColonBitmap[idx][topWord] |= cbBit;
                                mNegLevCommaBitmap[idx][topWord] |= cbBit;
                            } else if (cbBit == lbBit) {
                                if (curLevel + 1 == 0) {
                                    if (mLevCommaBitmap[0] == null)
                                        mLevCommaBitmap[0] = new long[mNumWords];
                                    mLevCommaBitmap[0][topWord] |= cbBit;
                                } else {
                                    int idx2 = -(curLevel + 1);
                                    if (mNegLevCommaBitmap[idx2] == null)
                                        mNegLevCommaBitmap[idx2] = new long[mNumWords];
                                    mNegLevCommaBitmap[idx2][topWord] |= cbBit;
                                }
                            }
                        }
                    }
                    if (cbMask != 0) {
                        if (cbBit == lbBit) {
                            lbMask &= (lbMask - 1);
                            lbBit = lbMask & -lbMask;
                            ++curLevel;
                            if (mThreadId == 0 && curLevel == 0) {
                                if (mLevCommaBitmap[curLevel] == null)
                                    mLevCommaBitmap[curLevel] = new long[mNumWords];
                                mLevCommaBitmap[curLevel][topWord] |= cbBit;
                            }
                        } else if (cbBit == rbBit) {
                            rbMask &= (rbMask - 1);
                            rbBit = rbMask & -rbMask;
                            --curLevel;
                        }
                        first = second;
                        cbMask &= (cbMask - 1);
                        if (curLevel > mMaxPositiveLevel)
                            mMaxPositiveLevel = curLevel;
                        else if (curLevel < mMinNegativeLevel)
                            mMinNegativeLevel = curLevel;
                    } else {
                        first = 0;
                    }
                } while (cbMask != 0 || first != 0);
            }
        }
        if (mDepth == MAX_LEVEL - 1)
            mDepth = mMaxPositiveLevel;
        mEndLevel = curLevel;
    }
    
// private long movemaskVec(int offset, byte target) {
//     long mask = 0L;
//     // Process 32 bytes starting at offset.
//     for (int i = 0; i < 32 && (offset + i) < mRecord.length; ++i) {
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
        for (int bit = 0; bit < 64; ++bit) {
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
