import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;


import bitmap.*;
import records.*;

public class Main {

    // Equivalent to the C++ query function
    public static String query(BitmapIterator iter) {
        StringBuilder output = new StringBuilder();
        if (iter.isObject()) {
            Set<String> keys = new HashSet<>(Arrays.asList("user", "retweet_count"));
            String key;
            while ((key = iter.moveToKey(keys)) != null) {
                if ("retweet_count".equals(key)) {
                    String value = iter.getValue();
                    output.append(value).append(";");
                    // no free() in Java
                } else {
                    if (!iter.down()) continue;  // enter "user"
                    if (iter.isObject() && iter.moveToKey("id")) {
                        String value = iter.getValue();
                        output.append(value).append(";");
                    }
                    iter.up();  // back out of "user"
                }
            }
        }
        return output.toString();
    }

    public static void main(String[] args) {
        // Parse keyword arguments
        Map<String, String> config = parseArgs(args);

        String filePath = "dataset/twitter_sample_small_records.json";
        int threadNum = 1;
        int levelNum = 2;  

        RecordSet recordSet = RecordLoader.loadRecordSet(filePath);        
        if (recordSet == null) {
            System.out.println("Unable to load record.");
            System.exit(-1);
        }

        int size = recordSet.numRecs;
        String output = "";

        Bitmap bm;

        for (int i=0; i < size; i++){
            bm = BitmapConstructor.construct(recordSet[i], 1, 2);
            BitmapIterator iter = BitmapConstructor.getIterator(bm);
            output.append(query(iter));
        }
        System.out.println("matches are: " + output);
    }
}
