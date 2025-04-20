package bitmap;

import tokenizer.*;

public class SerialBitmapIterator extends BitmapIterator {
    // Constants (adjust these values as needed)
    public static final int MAX_NUM_ELE = 1000000;
    public static final int MAX_FIELD_SIZE = 256;
    public static final int MAX_LEVEL = 10;
    public static final int OBJECT = 1;
    public static final int ARRAY = 2;

    private SerialBitmap mSerialBitmap;
    private IterCtxInfo[] mCtxInfo;      // per-level context info
    private boolean[] mPosArrAlloc;      // flags that positions array has been allocated
    private int mCurLevel;             // current level (depth)
    private int mTopLevel;             // top level of the record
    private String mKey;               // used when extracting key names
    private int mVisitedFields;        // counter for visited key fields

    public SerialBitmapIterator() {
        mCtxInfo = new IterCtxInfo[MAX_LEVEL];
        mPosArrAlloc = new boolean[MAX_LEVEL];
        for (int i = 0; i < MAX_LEVEL; i++) {
            mCtxInfo[i] = new IterCtxInfo();
            mPosArrAlloc[i] = false;
        }
    }

    public SerialBitmapIterator(SerialBitmap sbm) {
        this();
        mSerialBitmap = sbm;
        mCurLevel = -1;
        mTopLevel = -1;
        mVisitedFields = 0;
        // Initially, point to the first level in the record.
        down();
    }

    // Creates a copy of the iterator (useful for parallel querying)
    public SerialBitmapIterator getCopy() {
        SerialBitmapIterator sbi = new SerialBitmapIterator();
        sbi.mSerialBitmap = this.mSerialBitmap;
        sbi.mCurLevel = this.mCurLevel;
        sbi.mTopLevel = this.mCurLevel;
        if (sbi.mTopLevel >= 0) {
            sbi.mCtxInfo[mCurLevel].type = this.mCtxInfo[mCurLevel].type;
            sbi.mCtxInfo[mCurLevel].positions = this.mCtxInfo[mCurLevel].positions;
            sbi.mCtxInfo[mCurLevel].start_idx = this.mCtxInfo[mCurLevel].start_idx;
            sbi.mCtxInfo[mCurLevel].end_idx = this.mCtxInfo[mCurLevel].end_idx;
            sbi.mCtxInfo[mCurLevel].cur_idx = -1;
            if (mCurLevel + 1 < MAX_LEVEL) {
                sbi.mCtxInfo[mCurLevel + 1].positions = null;
            }
        }
        return sbi;
    }

    // Moves back one level in the nested record.
    public boolean up() {
        if (mCurLevel == mTopLevel) return false;
        mCurLevel--;
        return true;
    }

    // Moves down into a nested object or array.
    public boolean down() {
        // Validate current level against the SerialBitmap depth.
        if (mCurLevel < mTopLevel || mCurLevel > mSerialBitmap.getDepth()) return false;
        mCurLevel++;
        long startPos, endPos;

        if (mCurLevel == mTopLevel + 1) {
            if (mTopLevel == -1) {
                long recordLength = mSerialBitmap.getRecordLength();
                startPos = 0;
                endPos = recordLength;
                // Allocate a positions array (here using recordLength/8 as an estimate)
                mCtxInfo[mCurLevel].positions = new long[(int)(recordLength / 8) + 1];
                mPosArrAlloc[mCurLevel] = true;
            } else {
                long curIdx = mCtxInfo[mCurLevel - 1].cur_idx;
                startPos = mCtxInfo[mCurLevel - 1].positions[(int) curIdx];
                endPos = mCtxInfo[mCurLevel - 1].positions[(int) curIdx + 1];
                if (mCtxInfo[mCurLevel].positions == null || !mPosArrAlloc[mCurLevel]) {
                    mCtxInfo[mCurLevel].positions = new long[MAX_NUM_ELE / 8 + 1];
                    mPosArrAlloc[mCurLevel] = true;
                }
            }
            mCtxInfo[mCurLevel].start_idx = 0;
            mCtxInfo[mCurLevel].cur_idx = -1;
            mCtxInfo[mCurLevel].end_idx = -1;
        } else {
            long curIdx = mCtxInfo[mCurLevel - 1].cur_idx;
            if (curIdx > mCtxInfo[mCurLevel - 1].end_idx) {
                mCurLevel--;
                return false;
            }
            startPos = mCtxInfo[mCurLevel - 1].positions[(int) curIdx];
            endPos = mCtxInfo[mCurLevel - 1].positions[(int) curIdx + 1];
            // Reuse the positions array from the previous level.
            mCtxInfo[mCurLevel].positions = mCtxInfo[mCurLevel - 1].positions;
            mCtxInfo[mCurLevel].start_idx = mCtxInfo[mCurLevel - 1].end_idx + 1;
            mCtxInfo[mCurLevel].cur_idx = mCtxInfo[mCurLevel - 1].end_idx;
            mCtxInfo[mCurLevel].end_idx = mCtxInfo[mCurLevel - 1].end_idx;
        }

        // Skip whitespace to find the next non-space character.
        int i = (int) startPos;
        if (startPos > 0 || mCurLevel > 0) i++;
        char ch = mSerialBitmap.getRecord().charAt(i);
        while (i < endPos && (ch == ' ' || ch == '\n')) {
            ch = mSerialBitmap.getRecord().charAt(++i);
        }
        if (mSerialBitmap.getRecord().charAt(i) == '{') {
            mCtxInfo[mCurLevel].type = OBJECT;
            generateColonPositions(i, endPos, mCurLevel, mCtxInfo[mCurLevel]);
            return true;
        } else if (mSerialBitmap.getRecord().charAt(i) == '[') {
            mCtxInfo[mCurLevel].type = ARRAY;
            generateCommaPositions(i, endPos, mCurLevel, mCtxInfo[mCurLevel]);
            return true;
        }
        mCurLevel--;
        return false;
    }

    // Returns true if the current level points to an object.
    public boolean isObject() {
        return mCurLevel >= 0 && mCurLevel <= mSerialBitmap.getDepth() &&
               mCtxInfo[mCurLevel].type == OBJECT;
    }

    // Returns true if the current level points to an array.
    public boolean isArray() {
        return mCurLevel >= 0 && mCurLevel <= mSerialBitmap.getDepth() &&
               mCtxInfo[mCurLevel].type == ARRAY;
    }

    // Advances to the next array element.
    public boolean moveNext() {
        if (mCurLevel < 0 || mCurLevel > mSerialBitmap.getDepth() ||
            mCtxInfo[mCurLevel].type != ARRAY) return false;
        long nextIdx = mCtxInfo[mCurLevel].cur_idx + 1;
        if (nextIdx >= mCtxInfo[mCurLevel].end_idx) return false;
        mCtxInfo[mCurLevel].cur_idx = (int) nextIdx;
        return true;
    }

    // Moves to a key field by name within an object.
    public boolean moveToKey(String key) {
        if (mCurLevel < 0 || mCurLevel > mSerialBitmap.getDepth() ||
            mCtxInfo[mCurLevel].type != OBJECT) return false;
        long curIdx = mCtxInfo[mCurLevel].cur_idx + 1;
        long endIdx = mCtxInfo[mCurLevel].end_idx;
        while (curIdx < endIdx) {
            long colonPos = mCtxInfo[mCurLevel].positions[(int) curIdx];
            FieldQuotePos pos = findFieldQuotePos(colonPos);
            if (pos == null) return false;
            mVisitedFields++;
            int keySize = (int) (pos.end - pos.start - 1);
            if (keySize == key.length()) {
                String extracted = mSerialBitmap.getRecord().substring((int) pos.start + 1, (int) pos.end);
                if (extracted.equals(key)) {
                    mCtxInfo[mCurLevel].cur_idx = (int) curIdx;
                    return true;
                }
            }
            curIdx++;
        }
        mCtxInfo[mCurLevel].cur_idx = (int) curIdx;
        return false;
    }

    // Moves to one of the keys from the provided set; the matching key is removed from the set.
    public String moveToKey(java.util.Set<String> keySet) {
        if (keySet == null || keySet.isEmpty() || mCurLevel < 0 ||
            mCurLevel > mSerialBitmap.getDepth() || mCtxInfo[mCurLevel].type != OBJECT)
            return null;
        long curIdx = mCtxInfo[mCurLevel].cur_idx + 1;
        long endIdx = mCtxInfo[mCurLevel].end_idx;
        while (curIdx < endIdx) {
            long colonPos = mCtxInfo[mCurLevel].positions[(int) curIdx];
            FieldQuotePos pos = findFieldQuotePos(colonPos);
            if (pos == null) return null;
            mVisitedFields++;
            boolean hasKey = false;
            String keyFound = null;
            for (String key : keySet) {
                int keySize = (int) (pos.end - pos.start - 1);
                if (keySize == key.length()) {
                    if (!hasKey) {
                        keyFound = mSerialBitmap.getRecord().substring((int) pos.start + 1, (int) pos.end);
                        hasKey = true;
                    }
                    if (keyFound.equals(key)) {
                        mCtxInfo[mCurLevel].cur_idx = (int) curIdx;
                        keySet.remove(key);
                        return key;
                    }
                }
            }
            curIdx++;
        }
        mCtxInfo[mCurLevel].cur_idx = (int) curIdx;
        return null;
    }

    // Returns the number of elements in the current array.
    public int numArrayElements() {
        if (mCurLevel >= 0 && mCurLevel <= mSerialBitmap.getDepth() &&
            mCtxInfo[mCurLevel].type == ARRAY) {
            return mCtxInfo[mCurLevel].end_idx - mCtxInfo[mCurLevel].start_idx;
        }
        return 0;
    }

    // Moves to the array element at the given index.
    public boolean moveToIndex(int index) {
        if (mCurLevel < 0 || mCurLevel > mSerialBitmap.getDepth() ||
            mCtxInfo[mCurLevel].type != ARRAY) return false;
        long nextIdx = mCtxInfo[mCurLevel].start_idx + index;
        if (nextIdx > mCtxInfo[mCurLevel].end_idx) return false;
        mCtxInfo[mCurLevel].cur_idx = (int) nextIdx;
        return true;
    }

    // Returns the value of the current field (as a String).
    public String getValue() {
        if (mCurLevel < 0 || mCurLevel > mSerialBitmap.getDepth()) return null;
        long curIdx = mCtxInfo[mCurLevel].cur_idx;
        long nextIdx = curIdx + 1;
        if (nextIdx > mCtxInfo[mCurLevel].end_idx) return null;
        long curPos = mCtxInfo[mCurLevel].positions[(int) curIdx];
        long nextPos = mCtxInfo[mCurLevel].positions[(int) nextIdx];
        if (mCtxInfo[mCurLevel].type == OBJECT && nextIdx < mCtxInfo[mCurLevel].end_idx) {
            FieldQuotePos pos = findFieldQuotePos(nextPos);
            if (pos == null)
                return "";
            nextPos = pos.start;
        }
        long textLength = nextPos - curPos - 1;
        if (textLength <= 0) return "";
        return mSerialBitmap.getRecord().substring((int) curPos + 1, (int) curPos + 1 + (int) textLength);
    }

    // --- Helper Methods ---

    // Scans the given portion of the record to collect positions of colon characters.
    // The discovered positions are stored in the passed IterCtxInfo object's positions array.
    private void generateColonPositions(long startPos, long endPos, int level, IterCtxInfo ctx) {
        long st = startPos / 64;
        long ed = (long) Math.ceil((double) endPos / 64);
        ctx.end_idx = -1;
        for (long i = st; i < ed; i++) {
            // Retrieve the i-th element in the level’s colon bitmap.
            long colonBit = mSerialBitmap.getLevColonBitmap(level, (int)i);
            while (colonBit != 0) {
                long offset = i * 64 + Long.numberOfTrailingZeros(colonBit);
                if (startPos <= offset && offset <= endPos) {
                    ctx.end_idx++;
                    // (Assumes positions array is large enough.)
                    ctx.positions[(int) ctx.end_idx] = offset;
                }
                colonBit &= (colonBit - 1);
            }
        }
    }

    // Scans the given portion of the record to collect positions of comma characters.
    private void generateCommaPositions(long startPos, long endPos, int level, IterCtxInfo ctx) {
        long st = startPos / 64;
        long ed = (long) Math.ceil((double) endPos / 64);
        ctx.end_idx = -1;
        for (long i = st; i < ed; i++) {
            long commaBit = mSerialBitmap.getLevCommaBitmap(level, (int)i);
            while (commaBit != 0) {
                long offset = i * 64 + Long.numberOfTrailingZeros(commaBit);
                if (startPos <= offset && offset <= endPos) {
                    ctx.end_idx++;
                    ctx.positions[(int) ctx.end_idx] = offset;
                }
                commaBit &= (commaBit - 1);
            }
        }
    }

    // Given a colon position, tries to locate the positions of the surrounding quotation marks.
    // Returns a FieldQuotePos object containing the start and end offsets (or null if not found).
    private FieldQuotePos findFieldQuotePos(long colonPos) {
        long w_id = colonPos / 64;
        long startQuote = 0;
        long endQuote = 0;
        while (w_id >= 0) {
            long quoteBit = mSerialBitmap.getQuoteBitmap((int)w_id);
            long offset = w_id * 64 + Long.numberOfTrailingZeros(quoteBit);
            while (quoteBit != 0 && offset < colonPos) {
                if (endQuote != 0) {
                    startQuote = offset;
                } else if (startQuote == 0) {
                    startQuote = offset;
                } else if (endQuote == 0) {
                    endQuote = offset;
                } else {
                    startQuote = endQuote;
                    endQuote = offset;
                }
                quoteBit &= (quoteBit - 1);
                offset = w_id * 64 + Long.numberOfTrailingZeros(quoteBit);
            }
            if (startQuote != 0 && endQuote == 0) {
                endQuote = startQuote;
                return new FieldQuotePos(startQuote, endQuote);
            } else if (startQuote != 0 && endQuote != 0) {
                return new FieldQuotePos(startQuote, endQuote);
            }
            w_id--;
        }
        return null;
    }

    // --- Inner Helper Classes ---

    // Simple context to hold iteration state for a given level.
    public static class IterCtxInfo {
        public int type;       // Either OBJECT or ARRAY
        public long[] positions;  // Positions of colons or commas
        public int start_idx;
        public int end_idx;
        public int cur_idx;

        public IterCtxInfo() {
            // Allocate an initial capacity; in production consider using an ArrayList or similar.
            positions = new long[1024];
        }
    }

    // Simple class to encapsulate the start and end positions of a quoted field.
    public static class FieldQuotePos {
        public long start;
        public long end;

        public FieldQuotePos(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}
