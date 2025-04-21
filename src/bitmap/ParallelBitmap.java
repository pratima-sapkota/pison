package bitmap;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import records.Record;

public class ParallelBitmap extends Bitmap {
    public static final int MAX_THREAD = 70;
    public static final int SPECULATIVE = 10;
    public static final int NONSPECULATIVE = 11;

    private LocalBitmap[] mBitmaps;
    private int mThreadNum;
    private byte[] mRecord;
    private long mRecordLength;
    private int mDepth;
    private int mParallelMode;

    public ParallelBitmap(byte[] record, int threadNum, int depth) {
        this(record, record.length, threadNum, depth);
    }

    public ParallelBitmap(byte[] record, long recLen, int threadNum, int depth) {
        this.mRecord = record;
        this.mDepth = depth;
        this.mThreadNum = threadNum;
        this.mRecordLength = recLen;
        this.mBitmaps = new LocalBitmap[threadNum];

        int chunkLen = (int)(recLen / threadNum);
        if (chunkLen % 64 > 0) {
            chunkLen += 64 - (chunkLen % 64);
        }

        int curLen = 0;
        int startIdx = 0;
        this.mParallelMode = NONSPECULATIVE;

        for (int i = 0; i < threadNum; ++i) {
            mBitmaps[i] = new LocalBitmap(mRecord, depth);
            mBitmaps[i].setThreadId(i);
            if (i < threadNum - 1) {
                int padLen = 0;
                while (startIdx + chunkLen + padLen - 1 < record.length
                        && record[startIdx + chunkLen + padLen - 1] == '\\') {
                    padLen += 64;
                }
                mBitmaps[i].setRecordLength(chunkLen + padLen);
                startIdx += chunkLen + padLen;
                curLen += (chunkLen + padLen);
            } else {
                int lastChunkLen = (int)(recLen - curLen);
                mBitmaps[i].setRecordLength(lastChunkLen);
            }

            if (mBitmaps[i].contextInference() == LocalBitmap.UNKNOWN) {
                this.mParallelMode = SPECULATIVE;
            }
        }
    }

    public int parallelMode() {
        return mParallelMode;
    }

    public long getRecordLength() {
        return mRecordLength;
    }

    public int getThreadNum(){
        return mThreadNum;
    }

    public int getDepth() {
        return mDepth;
    }

    public byte[] getRecord() {
        return mRecord;
    }

    public LocalBitmap[] getBitmaps() {
        return mBitmaps;
    }

    public void setRecordLength(long length) {
        this.mRecordLength = length;
    }

    public void rectifyStringMaskBitmaps() {
        System.out.println("start verification");
        long prev = mBitmaps[0].getEndInStrBitmap();
        for (int i = 1; i < mThreadNum; ++i) {
            if (prev != mBitmaps[i].getStartInStrBitmap()) {
                mBitmaps[i].setStartInStrBitmap(prev);
                System.out.println("flip for " + i + "th thread");
                for (int j = 0; j < mBitmaps[i].getNumWords(); ++j) {
                    mBitmaps[i].flipStrBitmapAt(j);
                }
                if (mBitmaps[i].getEndInStrBitmap() == 0) {
                    mBitmaps[i].setEndInStrBitmap(~0L);
                } else {
                    mBitmaps[i].setEndInStrBitmap(0L);
                }
            }
            prev = mBitmaps[i].getEndInStrBitmap();
        }
        System.out.println("end verification");
    }

    public void mergeBitmaps() {
        System.out.println("start merge");
        int curLevel = mBitmaps[0].getEndLevel();
        System.out.println("initial level before merge " + curLevel);
        long offset = 0;

        for (int i = 0; i <= mBitmaps[0].getMaxPositiveLevel(); ++i) {
            mBitmaps[0].copyLevBitmapsToFinal(i, i);
        }

        offset += mBitmaps[0].getNumWords();
        mBitmaps[0].setStartWordId(0);
        mBitmaps[0].setEndWordId(offset);

        // System.out.println("Initial bitmap length: " + offset);
        // System.out.println("Initial bitmap level before merge: " + curLevel);
        for (int i = 1; i < mThreadNum; ++i) {
            mBitmaps[i].setStartWordId(offset);
            mBitmaps[i].setEndWordId(offset + mBitmaps[i].getNumWords());

            for (int j = 1; j <= -mBitmaps[i].getMinNegativeLevel() && (curLevel - j + 1) >= 0; ++j) {
                int targetLevel = curLevel - j + 1;
                mBitmaps[i].copyNegLevBitmapsToFinal(targetLevel, j);
                // if (targetLevel >= 0 && targetLevel < mBitmaps[i].getFinalLevSize()) {
                    
                // } else {
                //     System.err.println("Skipping negative level index " + targetLevel + " for bitmap index " + i);
                // }
            }

            for (int j = 0; j <= mBitmaps[i].getMaxPositiveLevel() && (curLevel + j + 1) >= 0; ++j) {
                int targetLevel = curLevel + j + 1;
                mBitmaps[i].copyLevBitmapsToFinal(targetLevel, j);

                // if (targetLevel >= 0 && targetLevel < mBitmaps[i].getFinalLevSize()) {
                // } else {
                //     System.err.println("Skipping positive level index " + targetLevel + " for bitmap index " + i);
                // }
            }

            curLevel += (mBitmaps[i].getEndLevel() + 1);
            offset += mBitmaps[i].getNumWords();
        }
        // System.out.println("final level after merge " + curLevel);
        // System.out.println("Final bitmap length: " + offset);
        // System.out.println("Final bitmap level after merge: " + curLevel);
    }

    public LocalBitmap getBitmap(int i) {
        return mBitmaps[i];
    }
}
