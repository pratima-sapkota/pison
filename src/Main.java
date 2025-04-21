import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

import bitmap.*;
import records.*;

public class Main {

    public static String query(BitmapIterator iter) {
    StringBuilder output = new StringBuilder();
    if (!iter.isObject()) return "";

    // build a Set<byte[]> of the two field‐names
    Set<byte[]> keys = new HashSet<>();
    keys.add("user".getBytes(StandardCharsets.UTF_8));
    keys.add("retweet_count".getBytes(StandardCharsets.UTF_8));

    byte[] keyBytes;
    byte[] idKey = "id".getBytes(StandardCharsets.UTF_8);

    while ((keyBytes = iter.moveToKey(keys)) != null) {
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        if ("retweet_count".equals(key)) {
            byte[] valBytes = iter.getValue();
            String val = new String(valBytes, StandardCharsets.UTF_8);

            output.append(val).append(";");
        }
        else if ("user".equals(key)) {
            System.out.println("user: " + key);
            if (!iter.down()) continue;

            // look for "id" inside the user object
            if (iter.isObject() && iter.moveToKey(idKey)) {
                byte[] idValBytes = iter.getValue();
                String idVal = new String(idValBytes, StandardCharsets.UTF_8);
                System.out.println("user id: " + idVal);
                output.append(idVal).append(";");
            }
            iter.up();
        }
    }


    return output.toString();
}

    public static void main(String[] args) {
        // Arguments
        String filePath = "dataset/twitter_sample_large_record.json";
        List<String> keys = Arrays.asList("retweet_count", "user", "name");


       records.Record record = RecordLoader.loadRecord(filePath);
        if (record == null) {
            System.out.println("Record loading fails.");
            System.exit(-1);
        }
        System.out.println("Record length: " + record.recLength);
        
        int threadNum = 3;
        int levelNum = 2;  
        Bitmap bm = BitmapConstructor.construct(record, threadNum, levelNum);
        BitmapIterator iter = BitmapConstructor.getIterator(bm);
        
        List<String> allUserIds = new ArrayList<>();
        // Iterate through the bitmap and extract user IDs
        String output = query(iter);
        String trimmed = record.content.trim();
        if (trimmed.startsWith("[")) {
            // A JSON array of tweet‑objects
            allUserIds.addAll(
                iter.getQueryResponse(trimmed, keys)
            );
        } else {
            // NDJSON or single object
            for (String line : record.content.split("\\r?\\n")) {
                String ln = line.trim();
                if (ln.isEmpty()) continue;
                allUserIds.addAll(
                    iter.getQueryResponse(ln, keys)
                );
            }
        }
        System.out.println("\n");
        System.out.print("matches are: ");
        for (String id : allUserIds) {
            System.out.print(id + ",;0,;");
        }
        
    }
}
