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
    private String mRecord;
    private long mRecordLength;
    private int mDepth;
    private int mParallelMode;

    public ParallelBitmap(String record, int threadNum, int depth) {
        this(record, record.length(), threadNum, depth);
    }

    public ParallelBitmap(String record, long recLen, int threadNum, int depth) {
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
            mBitmaps[i] = new LocalBitmap(mRecord.substring(startIdx).getBytes(StandardCharsets.UTF_8), depth);
            mBitmaps[i].setThreadId(i);
            if (i < threadNum - 1) {
                int padLen = 0;
                while (startIdx + chunkLen + padLen - 1 < record.length()
                        && record.charAt(startIdx + chunkLen + padLen - 1) == '\\') {
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

    public void setRecordLength(long length) {
        this.mRecordLength = length;
    }

    public void rectifyStringMaskBitmaps() {
        long prev = mBitmaps[0].getEndInStrBitmap();
        for (int i = 1; i < mThreadNum; ++i) {
            if (prev != mBitmaps[i].getStartInStrBitmap()) {
                mBitmaps[i].setStartInStrBitmap(prev);
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
    }

    public void mergeBitmaps() {
        int curLevel = mBitmaps[0].getEndLevel();
        long offset = 0;

        for (int i = 0; i <= mBitmaps[0].getMaxPositiveLevel(); ++i) {
            mBitmaps[0].copyLevBitmapsToFinal(i, i);
        }

        offset += mBitmaps[0].getNumWords();
        mBitmaps[0].setStartWordId(0);
        mBitmaps[0].setEndWordId(offset);

        System.out.println("Initial bitmap length: " + offset);
        System.out.println("Initial bitmap level: " + curLevel);
        for (int i = 1; i < mThreadNum; ++i) {
            mBitmaps[i].setStartWordId(offset);
            mBitmaps[i].setEndWordId(offset + mBitmaps[i].getNumWords());

            for (int j = 1; j <= -mBitmaps[i].getMinNegativeLevel(); ++j) {
                int targetLevel = curLevel - j + 1;
                if (targetLevel >= 0 && targetLevel < mBitmaps[i].getFinalLevSize()) {
                    mBitmaps[i].copyNegLevBitmapsToFinal(targetLevel, j);
                } else {
                    System.err.println("Skipping negative level index " + targetLevel + " for bitmap index " + i);
                }
            }

            for (int j = 0; j <= mBitmaps[i].getMaxPositiveLevel(); ++j) {
                int targetLevel = curLevel + j + 1;
                if (targetLevel >= 0 && targetLevel < mBitmaps[i].getFinalLevSize()) {
                    mBitmaps[i].copyLevBitmapsToFinal(targetLevel, j);
                } else {
                    System.err.println("Skipping positive level index " + targetLevel + " for bitmap index " + i);
                }
            }

            curLevel += (mBitmaps[i].getEndLevel() + 1);
            offset += mBitmaps[i].getNumWords();
        }
        System.out.println("Final bitmap length: " + offset);
        System.out.println("Final bitmap level: " + curLevel);
    }

    public LocalBitmap getBitmap(int i) {
        return mBitmaps[i];
    }
}
