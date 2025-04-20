package bitmap;

import records.Record;

public class BitmapConstructor {

    public static Bitmap construct(Record record, int threadNum, int levelNum) {
        System.out.println("\nConstructing bitmap with " + threadNum + " threads and " + levelNum + " levels.");
        Bitmap bm;
        if (threadNum == 1) {
            System.out.println("Using serial bitmap constructor.");
            bm = SerialBitmapConstructor.construct(record, levelNum);
            bm.type = Bitmap.SEQUENTIAL;
        } else {
            System.out.println("Using parallel bitmap constructor.");
            bm = ParallelBitmapConstructor.construct(record, threadNum, levelNum);
            bm.type = Bitmap.PARALLEL;
        }
        return bm;
    }

    public static BitmapIterator getIterator(Bitmap bm) {
        BitmapIterator bi;
        if (bm.type == Bitmap.SEQUENTIAL) {
            bi = new SerialBitmapIterator((SerialBitmap) bm);
            bi.setType(Bitmap.SEQUENTIAL);
        } else {
            bi = new ParallelBitmapIterator((ParallelBitmap) bm);
            bi.setType(Bitmap.PARALLEL);
        }
        return bi;
    }
}

