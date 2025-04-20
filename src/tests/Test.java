package tests;

import bitmap.*;
import records.*;

public class Test {

    // Equivalent to the C++ query function
    // public static String query(BitmapIterator iter) {
    //     StringBuilder output = new StringBuilder();
    //     while (iter.isArray() && iter.moveNext()) {
    //         if (!iter.down()) continue;
    //         if (iter.isObject() && iter.moveToKey("user")) {
    //             if (!iter.down()) continue;
    //             if (iter.isObject() && iter.moveToKey("id")) {
    //                 String value = iter.getValue();
    //                 output.append(value).append(";");
    //             }
    //             iter.up();
    //         }
    //         iter.up();
    //     }
    //     return output.toString();
    // }

    // public static void main(String[] args) {
    //     String filePath = "../dataset/twitter_sample_large_record.json";
    //     Record rec = RecordLoader.loadRecord(filePath);
    //     if (rec == null) {
    //         System.out.println("record loading fails.");
    //         System.exit(-1);
    //     }
    //     System.out.println("record loading succeeds.");

    //     // int threadNum = 16;
    //     // int levelNum = 3;

    //     // // Construct bitmap and create an iterator to query the record
    //     // Bitmap bm = BitmapConstructor.construct(rec, threadNum, levelNum);
    //     // BitmapIterator iter = BitmapConstructor.getIterator(bm);
    //     // String output = query(iter);

    //     // System.out.println("matches are: " + output);
    // }
}
