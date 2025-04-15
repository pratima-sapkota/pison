package bitmap;

public class ParallelBitmapIterator extends BitmapIterator {
    // Constants (adjust values as needed)
    public static final int MAX_NUM_ELE = 1000000;
    public static final int SINGLE_THREAD_MAX_ARRAY_SIZE = 100000;
    public static final int MAX_LEVEL = 10;         // example value
    public static final int MAX_FIELD_SIZE = 256;
    public static final int MAX_THREAD = 16;         

    private ParallelBitmap mParallelBitmap;
    private IterCtxInfo[] mCtxInfo = new IterCtxInfo[MAX_LEVEL];
    private boolean[] mPosArrAlloc = new boolean[MAX_LEVEL];
    private int mCurLevel, mTopLevel, mCurChunkId;
    private String mKey = "";
    private boolean mFindDomArray, mCopiedIterator;

    // Global metadata arrays (for per-thread bitmaps)
    private ParallelBitmapMetadata[] pbMetadata = new ParallelBitmapMetadata[MAX_THREAD];
    private CommaPosInfo[] commaPosInfo = new CommaPosInfo[MAX_THREAD];

    // Default constructor
    public ParallelBitmapIterator() {
        // Empty
    }

    // Main constructor
    public ParallelBitmapIterator(ParallelBitmap pbm) {
        mParallelBitmap = pbm;
        mCurLevel = -1;
        mTopLevel = -1;
        mCurChunkId = 0;
        mFindDomArray = false;
        mCopiedIterator = false;
        for (int i = 0; i < MAX_LEVEL; i++) {
            mPosArrAlloc[i] = false;
            mCtxInfo[i] = new IterCtxInfo();
        }
        for (int i = 0; i < MAX_THREAD; i++) {
            pbMetadata[i] = new ParallelBitmapMetadata();
            commaPosInfo[i] = new CommaPosInfo();
        }
        gatherParallelBitmapInfo();
        down(); // Position to the first level.
    }

    // Creates a copy of this iterator.
    public ParallelBitmapIterator getCopy() {
        ParallelBitmapIterator copy = new ParallelBitmapIterator();
        copy.mParallelBitmap = this.mParallelBitmap;
        copy.mCurLevel = this.mCurLevel;
        copy.mTopLevel = this.mTopLevel;
        copy.mCurChunkId = this.mCurChunkId;
        copy.mFindDomArray = this.mFindDomArray;
        if (mTopLevel >= 0) {
            // Shallow–copy context info; deep copy positions array if needed.
            copy.mCtxInfo[mCurLevel].type = this.mCtxInfo[mCurLevel].type;
            copy.mCtxInfo[mCurLevel].positions = this.mCtxInfo[mCurLevel].positions.clone();
            copy.mCtxInfo[mCurLevel].start_idx = this.mCtxInfo[mCurLevel].start_idx;
            copy.mCtxInfo[mCurLevel].end_idx = this.mCtxInfo[mCurLevel].end_idx;
            copy.mCtxInfo[mCurLevel].cur_idx = -1;
            copy.mPosArrAlloc[mCurLevel] = this.mPosArrAlloc[mCurLevel];
            for (int i = mCurLevel + 1; i < MAX_LEVEL; i++) {
                copy.mPosArrAlloc[i] = false;
            }
        }
        copy.mCopiedIterator = true;
        return copy;
    }

    // Moves one level up in the nested record.
    public boolean up() {
        if (mCurLevel == mTopLevel) return false;
        mCurLevel--;
        return true;
    }

    // Moves one level down into an object or array.
    public boolean down() {
        if (mCurLevel < mTopLevel || mCurLevel > mParallelBitmap.getDepth())
            return false;
        mCurLevel++;
        long startPos, endPos;
        int threadNum = mParallelBitmap.getThreadNum();

        if (mCurLevel == mTopLevel + 1) {
            if (mTopLevel == -1) {
                startPos = 0;
                endPos = mParallelBitmap.getRecordLength();
                mCtxInfo[mCurLevel].positions = new long[(int)(endPos / threadNum + 1)];
                mPosArrAlloc[mCurLevel] = true;
            } else {
                int curIdx = mCtxInfo[mCurLevel - 1].cur_idx;
                startPos = mCtxInfo[mCurLevel - 1].positions[curIdx];
                endPos = mCtxInfo[mCurLevel - 1].positions[curIdx + 1];
                mCtxInfo[mCurLevel].positions = new long[MAX_NUM_ELE / threadNum + 1];
                mPosArrAlloc[mCurLevel] = true;
            }
            mCtxInfo[mCurLevel].start_idx = 0;
            mCtxInfo[mCurLevel].cur_idx = -1;
            mCtxInfo[mCurLevel].end_idx = -1;
        } else {
            int curIdx = mCtxInfo[mCurLevel - 1].cur_idx;
            if (curIdx > mCtxInfo[mCurLevel - 1].end_idx) {
                mCurLevel--;
                return false;
            }
            startPos = mCtxInfo[mCurLevel - 1].positions[curIdx];
            endPos = mCtxInfo[mCurLevel - 1].positions[curIdx + 1];
            mCtxInfo[mCurLevel].positions = mCtxInfo[mCurLevel - 1].positions;
            mCtxInfo[mCurLevel].start_idx = mCtxInfo[mCurLevel - 1].end_idx + 1;
            mCtxInfo[mCurLevel].cur_idx = mCtxInfo[mCurLevel - 1].end_idx;
            mCtxInfo[mCurLevel].end_idx = mCtxInfo[mCurLevel - 1].end_idx;
        }

        // Skip white spaces.
        int i = (int) startPos;
        if (startPos > 0 || mCurLevel > 0) i++;
        char ch = mParallelBitmap.getRecord().charAt(i);
        while (i < endPos && (ch == ' ' || ch == '\n')) {
            ch = mParallelBitmap.getRecord().charAt(++i);
        }
        // Decide based on the first non–whitespace char.
        if (mParallelBitmap.getRecord().charAt(i) == '{') {
            mCtxInfo[mCurLevel].type = IterCtxInfo.OBJECT;
            generateColonPositions(i, endPos, mCurLevel, mCtxInfo[mCurLevel].positions);
            return true;
        } else if (mParallelBitmap.getRecord().charAt(i) == '[') {
            mCtxInfo[mCurLevel].type = IterCtxInfo.ARRAY;
            if (!mFindDomArray && (endPos - i + 1) > SINGLE_THREAD_MAX_ARRAY_SIZE) {
                generateCommaPositionsParallel(i, endPos, mCurLevel, mCtxInfo[mCurLevel].positions);
                mFindDomArray = true;
            } else {
                generateCommaPositions(i, endPos, mCurLevel, mCtxInfo[mCurLevel].positions);
            }
            return true;
        }
        mCurLevel--;
        return false;
    }

    public boolean isObject() {
        return mCurLevel >= 0 && mCurLevel <= mParallelBitmap.getDepth() &&
               mCtxInfo[mCurLevel].type == IterCtxInfo.OBJECT;
    }

    public boolean isArray() {
        return mCurLevel >= 0 && mCurLevel <= mParallelBitmap.getDepth() &&
               mCtxInfo[mCurLevel].type == IterCtxInfo.ARRAY;
    }

    public boolean moveNext() {
        if (mCurLevel < 0 || mCurLevel > mParallelBitmap.getDepth() ||
            mCtxInfo[mCurLevel].type != IterCtxInfo.ARRAY)
            return false;
        long nextIdx = mCtxInfo[mCurLevel].cur_idx + 1;
        if (nextIdx >= mCtxInfo[mCurLevel].end_idx) return false;
        mCtxInfo[mCurLevel].cur_idx = (int) nextIdx;
        return true;
    }

    public int keySize() {
        return mKey.length();
    }

    public String getKey() {
        return mKey;
    }

    public boolean moveToKey(String key) {
        if (mCurLevel < 0 || mCurLevel > mParallelBitmap.getDepth() ||
            mCtxInfo[mCurLevel].type != IterCtxInfo.OBJECT)
            return false;
        int curIdx = mCtxInfo[mCurLevel].cur_idx + 1;
        int endIdx = mCtxInfo[mCurLevel].end_idx;
        while (curIdx < endIdx) {
            long colonPos = mCtxInfo[mCurLevel].positions[curIdx];
            FieldQuoteResult res = findFieldQuotePos(colonPos);
            if (res == null) return false;
            int keySize = (int)(res.end - res.start - 1);
            if (keySize == key.length()) {
                String foundKey = mParallelBitmap.getRecord().substring((int) res.start + 1, (int) res.end);
                if (foundKey.equals(key)) {
                    mKey = foundKey;
                    mCtxInfo[mCurLevel].cur_idx = curIdx;
                    return true;
                }
            }
            curIdx++;
        }
        return false;
    }

    public String moveToKey(java.util.Set<String> keySet) {
        if (keySet.isEmpty() || mCurLevel < 0 || mCurLevel > mParallelBitmap.getDepth() ||
            mCtxInfo[mCurLevel].type != IterCtxInfo.OBJECT)
            return null;
        int curIdx = mCtxInfo[mCurLevel].cur_idx + 1;
        int endIdx = mCtxInfo[mCurLevel].end_idx;
        while (curIdx < endIdx) {
            long colonPos = mCtxInfo[mCurLevel].positions[curIdx];
            FieldQuoteResult res = findFieldQuotePos(colonPos);
            if (res == null) return null;
            String foundKey = mParallelBitmap.getRecord().substring((int) res.start + 1, (int) res.end);
            if (keySet.contains(foundKey)) {
                mCtxInfo[mCurLevel].cur_idx = curIdx;
                keySet.remove(foundKey);
                return foundKey;
            }
            curIdx++;
        }
        mCtxInfo[mCurLevel].cur_idx = curIdx;
        return null;
    }

    public int numArrayElements() {
        if (mCurLevel >= 0 && mCurLevel <= mParallelBitmap.getDepth() &&
            mCtxInfo[mCurLevel].type == IterCtxInfo.ARRAY) {
            return mCtxInfo[mCurLevel].end_idx - mCtxInfo[mCurLevel].start_idx;
        }
        return 0;
    }

    public boolean moveToIndex(int index) {
        if (mCurLevel < 0 || mCurLevel > mParallelBitmap.getDepth() ||
            mCtxInfo[mCurLevel].type != IterCtxInfo.ARRAY)
            return false;
        long nextIdx = mCtxInfo[mCurLevel].start_idx + index;
        if (nextIdx > mCtxInfo[mCurLevel].end_idx) return false;
        mCtxInfo[mCurLevel].cur_idx = (int) nextIdx;
        return true;
    }

    public String getValue() {
        if (mCurLevel < 0 || mCurLevel > mParallelBitmap.getDepth())
            return null;
        int curIdx = mCtxInfo[mCurLevel].cur_idx;
        int nextIdx = curIdx + 1;
        if (nextIdx > mCtxInfo[mCurLevel].end_idx)
            return null;
        long curPos = mCtxInfo[mCurLevel].positions[curIdx];
        long nextPos = mCtxInfo[mCurLevel].positions[nextIdx];
        if (mCtxInfo[mCurLevel].type == IterCtxInfo.OBJECT && nextIdx < mCtxInfo[mCurLevel].end_idx) {
            FieldQuoteResult res = findFieldQuotePos(nextPos);
            if (res == null)
                return "";
            nextPos = res.start;
        }
        int textLength = (int) (nextPos - curPos - 1);
        if (textLength <= 0) return "";
        return mParallelBitmap.getRecord().substring((int) curPos + 1, (int) curPos + 1 + textLength);
    }

    // ---- Private Helper Methods ----

    // Saves metadata from the linked bitmap chunks.
    private void gatherParallelBitmapInfo() {
        int chunkNum = mParallelBitmap.getThreadNum();
        for (int chunkId = 0; chunkId < chunkNum; chunkId++) {
            LocalBitmap bitmap = mParallelBitmap.getBitmaps()[chunkId];
            pbMetadata[chunkId].startWordId = (int) bitmap.getStartWordId();
            pbMetadata[chunkId].endWordId = (int) bitmap.getEndWordId();
            pbMetadata[chunkId].quoteBitmap = bitmap.getQuoteBitmap();
            pbMetadata[chunkId].levColonBitmap = bitmap.getFinalLevColonBitmap();
            pbMetadata[chunkId].levCommaBitmap = bitmap.getFinalLevCommaBitmap();
        }
    }

    // Generates positions for colon characters.
    private void generateColonPositions(long startPos, long endPos, int level, long[] colonPositions) {
        int startChunk = -1, endChunk = -1;
        int threadNum = mParallelBitmap.getThreadNum();
        for (int i = mCurChunkId; i < threadNum; i++) {
            if (pbMetadata[i].startWordId <= startPos / 64)
                startChunk = i;
            if (pbMetadata[i].endWordId >= Math.ceil((double) endPos / 64) && endChunk == -1)
                endChunk = i;
            if (startChunk > -1 && endChunk > -1) break;
        }
        if (startChunk == 0 && endChunk == -1) endChunk = 0;
        mCurChunkId = startChunk;
        for (int curChunk = startChunk; curChunk <= endChunk; curChunk++) {
            long[] levels = pbMetadata[curChunk].levColonBitmap[level];
            if (levels == null)
                continue;
            long curStartPos = pbMetadata[curChunk].startWordId;
            long curEndPos = pbMetadata[curChunk].endWordId;
            long st = Math.max(curStartPos, startPos / 64);
            long ed = Math.min(curEndPos, (long) Math.ceil((double) endPos / 64));
            for (long i = st; i < ed; i++) {
                int idx = (curChunk >= 1) ? (int) (i - curStartPos) : (int) i;
                long colonBit = levels[idx];
                while (colonBit != 0) {
                    long offset = i * 64 + Long.numberOfTrailingZeros(colonBit);
                    if (startPos <= offset && offset <= endPos)
                        mCtxInfo[level].positions[++mCtxInfo[level].end_idx] = offset;
                    colonBit &= colonBit - 1;
                }
            }
        }
    }

    // Generates comma positions sequentially.
    private void generateCommaPositions(long startPos, long endPos, int level, long[] commaPositions) {
        int startChunk = -1, endChunk = -1;
        int chunkNum = mParallelBitmap.getThreadNum();
        for (int i = mCurChunkId; i < chunkNum; i++) {
            if (pbMetadata[i].startWordId <= startPos / 64)
                startChunk = i;
            if (pbMetadata[i].endWordId >= Math.ceil((double) endPos / 64) && endChunk == -1)
                endChunk = i;
            if (startChunk > -1 && endChunk > -1) break;
        }
        if (startChunk == 0 && endChunk == -1) endChunk = 0;
        mCurChunkId = startChunk;
        for (int curChunk = startChunk; curChunk <= endChunk; curChunk++) {
            long[] levels = pbMetadata[curChunk].levCommaBitmap[level];
            if (levels == null)
                continue;
            long curStartPos = pbMetadata[curChunk].startWordId;
            long curEndPos = pbMetadata[curChunk].endWordId;
            long st = Math.max(curStartPos, startPos / 64);
            long ed = Math.min(curEndPos, (long) Math.ceil((double) endPos / 64));
            for (long i = st; i < ed; i++) {
                int idx = (curChunk >= 1) ? (int) (i - curStartPos) : (int) i;
                long commaBit = levels[idx];
                while (commaBit != 0) {
                    long offset = i * 64 + Long.numberOfTrailingZeros(commaBit);
                    if (startPos <= offset && offset <= endPos)
                        mCtxInfo[level].positions[++mCtxInfo[level].end_idx] = offset;
                    commaBit &= commaBit - 1;
                }
            }
        }
    }

    // Generates comma positions in parallel using Java threads.
    private void generateCommaPositionsParallel(long startPos, long endPos, int level, long[] commaPositions) {
        int startChunk = -1, endChunk = -1;
        int chunkNum = mParallelBitmap.getThreadNum();
        for (int i = mCurChunkId; i < chunkNum; i++) {
            if (pbMetadata[i].startWordId <= startPos / 64)
                startChunk = i;
            if (pbMetadata[i].endWordId >= Math.ceil((double) endPos / 64) && endChunk == -1)
                endChunk = i;
            if (startChunk > -1 && endChunk > -1) break;
        }
        if (startChunk == 0 && endChunk == -1) endChunk = 0;
        mCurChunkId = startChunk;
        Thread[] threads = new Thread[MAX_THREAD];
        for (int i = startChunk; i <= endChunk; i++) {
            final int tid = i;
            commaPosInfo[tid].threadId = tid;
            commaPosInfo[tid].level = level;
            commaPosInfo[tid].startPos = startPos;
            commaPosInfo[tid].endPos = endPos;
            commaPosInfo[tid].commaPositions = new long[MAX_NUM_ELE / mParallelBitmap.getThreadNum() + 1];
            commaPosInfo[tid].topCommaPositions = -1;
            threads[tid] = new Thread(() -> {
                long[] levels = pbMetadata[tid].levCommaBitmap[level];
                if (levels == null)
                    return;
                long curStartPos = pbMetadata[tid].startWordId;
                long curEndPos = pbMetadata[tid].endWordId;
                long st = Math.max(curStartPos, startPos / 64);
                long ed = Math.min(curEndPos, (long) Math.ceil((double) endPos / 64));
                for (long j = st; j < ed; j++) {
                    int idx = (tid >= 1) ? (int) (j - curStartPos) : (int) j;
                    long commaBit = levels[idx];
                    while (commaBit != 0) {
                        long offset = j * 64 + Long.numberOfTrailingZeros(commaBit);
                        if (startPos <= offset && offset <= endPos)
                            commaPosInfo[tid].commaPositions[++commaPosInfo[tid].topCommaPositions] = offset;
                        commaBit &= commaBit - 1;
                    }
                }
            });
            threads[tid].start();
        }
        for (int i = startChunk; i <= endChunk; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            for (int j = 0; j <= commaPosInfo[i].topCommaPositions; j++) {
                commaPositions[++mCtxInfo[level].end_idx] = commaPosInfo[i].commaPositions[j];
            }
        }
    }

    // Finds the start and end positions of field quotes around a colon.
    private FieldQuoteResult findFieldQuotePos(long colonPos) {
        long wId = colonPos / 64;
        long startQuote = 0, endQuote = 0;
        int curChunk = -1;
        int chunkNum = mParallelBitmap.getThreadNum();
        for (int i = mCurChunkId; i < chunkNum; i++) {
            if (wId >= pbMetadata[i].startWordId && wId < pbMetadata[i].endWordId) {
                curChunk = i;
                break;
            }
        }
        if (curChunk == -1)
            return null;
        while (wId >= 0) {
            if (wId < pbMetadata[curChunk].startWordId) {
                if (--curChunk == -1)
                    return null;
            }
            int quoteId = (int) (wId - pbMetadata[curChunk].startWordId);
            long quoteBit = pbMetadata[curChunk].quoteBitmap[quoteId];
            long offset = wId * 64 + Long.numberOfTrailingZeros(quoteBit);
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
                quoteBit &= quoteBit - 1;
                offset = wId * 64 + Long.numberOfTrailingZeros(quoteBit);
            }
            if (startQuote != 0 && endQuote == 0)
                endQuote = startQuote;
            else if (startQuote != 0 && endQuote != 0)
                return new FieldQuoteResult(startQuote, endQuote);
            wId--;
        }
        return null;
    }

    // ----- Inner Classes -----

    public static class ParallelBitmapMetadata {
        public int startWordId;
        public int endWordId;
        public long[] quoteBitmap;
        public long[][] levColonBitmap;
        public long[][] levCommaBitmap;
    }

    public static class CommaPosInfo {
        public int threadId;
        public int level;
        public long startPos;
        public long endPos;
        public long[] commaPositions;
        public int topCommaPositions;
    }

    public static class FieldQuoteResult {
        public long start, end;

        public FieldQuoteResult(long s, long e) {
            start = s;
            end = e;
        }
    }

    // Minimal placeholder for IterCtxInfo.
    public static class IterCtxInfo {
        public static final int OBJECT = 1;
        public static final int ARRAY = 2;
        public int type;
        public long[] positions;
        public int start_idx, end_idx, cur_idx;

        public IterCtxInfo() {
            type = 0;
            positions = null;
            start_idx = end_idx = cur_idx = 0;
        }
    }
}
