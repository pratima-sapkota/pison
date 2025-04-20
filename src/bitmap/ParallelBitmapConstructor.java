package bitmap;

import records.Record;
import java.util.concurrent.*;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;


public class ParallelBitmapConstructor {

    private static ParallelBitmap parallelBitmap;

    public static ParallelBitmap construct(Record record, int threadNum, int levelNum) {
        String recordText = record.getText();
        if (record.getRecStartPos() > 0)
            recordText = recordText.substring(record.getRecStartPos());
        System.out.println("Record text: " + recordText);
        long length = (record.getRecLength() > 0) ? record.getRecLength() : recordText.length();

        parallelBitmap = new ParallelBitmap(recordText, length, threadNum, levelNum);

        ExecutorService executor = Executors.newFixedThreadPool(threadNum);

        int mode = parallelBitmap.parallelMode();
        try {
            if (mode == ParallelBitmap.NONSPECULATIVE) {
                List<Future<?>> futures = new ArrayList<>(threadNum);
                for (int i = 0; i < threadNum; ++i) {
                    final int threadId = i;
                    executor.submit(() -> {
                    try {
                            nonSpecIndexConstruction(threadId);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }}
                    );
                }
                try {
                    for (Future<?> future : futures) {
                        future.get();
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            } else {
                for (int i = 0; i < threadNum; ++i) {
                    final int threadId = i;
                    executor.submit(() -> {
                        try {
                            buildStringMaskBitmap(threadId);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }}
                    );
                }
                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

                parallelBitmap.rectifyStringMaskBitmaps();

                executor = Executors.newFixedThreadPool(threadNum); // restart executor
                for (int i = 0; i < threadNum; ++i) {
                    final int threadId = i;
                    executor.submit(() -> {
                        try {
                            buildLeveledBitmap(threadId);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    });
                }
            }

            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            parallelBitmap.mergeBitmaps();

        } catch (InterruptedException e) {
            System.out.println("Thread execution interrupted: " + e.getMessage());
            return null;
        }

        return parallelBitmap;
    }

    public static void nonSpecIndexConstruction(int threadId) {
        System.out.printf("thread %d starts building structural indexes.%n", threadId);
        long start = System.nanoTime();

        // your core work
        parallelBitmap.getBitmap(threadId)
                       .nonSpecIndexConstruction();

        long durationUs = (System.nanoTime() - start) / 1_000; 
        System.out.printf("%dth thread finishes structural index construction (%.3f ms).%n", 
                          threadId, durationUs/1000.0);
    }

    /** builds bitmap index in speculative mode (Step 1–3) */
    public static void buildStringMaskBitmap(int threadId) {
        System.out.printf("%dth thread starts building string mask bitmap.%n", threadId);

        parallelBitmap.getBitmap(threadId)
                       .buildStringMaskBitmap();

        System.out.printf("%dth thread finishes building string mask bitmap.%n", threadId);
    }

    /** finish the last two steps to finish structural index construction */
    public static void buildLeveledBitmap(int threadId) {
        System.out.printf("%dth thread starts building leveled bitmap.%n", threadId);

        parallelBitmap.getBitmap(threadId)
                       .buildLeveledBitmap();

        System.out.printf("%dth thread finishes building leveled bitmap.%n", threadId);
    }
}
