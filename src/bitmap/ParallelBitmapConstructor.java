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

        long length = (record.getRecLength() > 0) ? record.getRecLength() : recordText.length();

        parallelBitmap = new ParallelBitmap(recordText, length, threadNum, levelNum);

        ExecutorService executor = Executors.newFixedThreadPool(threadNum);

        int mode = parallelBitmap.parallelMode();
        try {
            if (mode == ParallelBitmap.NONSPECULATIVE) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threadNum; i++) {
                    final int threadId = i;
                    futures.add(executor.submit(() -> {
                        parallelBitmap.getBitmap(threadId).nonSpecIndexConstruction();
                    }));
                }
                try {
                    for (Future<?> future : futures) {
                        future.get();
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            } else {
                for (int i = 0; i < threadNum; i++) {
                    final int threadId = i;
                    executor.submit(() -> parallelBitmap.getBitmap(threadId).buildStringMaskBitmap());
                }
                executor.shutdown();
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

                parallelBitmap.rectifyStringMaskBitmaps();

                executor = Executors.newFixedThreadPool(threadNum); // restart executor
                for (int i = 0; i < threadNum; i++) {
                    final int threadId = i;
                    executor.submit(() -> parallelBitmap.getBitmap(threadId).buildLeveledBitmap());
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
}
