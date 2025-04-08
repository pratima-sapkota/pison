import java.util.HashMap;
import java.util.Map;

import bitmap.*;
import records.*;

public class Main {

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

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> argMap = new HashMap<>();

        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2 && !parts[0].isEmpty()) {
                    argMap.put(parts[0], parts[1]);
                }
            } else if (!argMap.containsKey("file")) {
                // Allow first positional argument to be the file path
                argMap.put("file", arg);
            }
        }
        return argMap;
    }

    public static void main(String[] args) {
        // Parse keyword arguments
        Map<String, String> config = parseArgs(args);

        String filePath = config.get("file");
        int threadNum = Integer.parseInt(config.getOrDefault("threads", "16"));
        int levelNum = Integer.parseInt(config.getOrDefault("levels", "3"));  
        System.out.println("Thread number: " + threadNum);

        if (filePath == null) {
            System.out.println("Usage: java Main --file=path/to/file.json [--threads=N] [--levels=N]");
            System.exit(-1);
        }

        // Load the file as a single record
        System.out.println("\nLoading as a single record from: " + filePath);
        Record record = RecordLoader.loadRecord(filePath);
        if (record == null) {
            System.out.println("Record loading fails.");
            System.exit(-1);
        }
        System.out.println("Record length: " + record.recLength);

        // Load the file as a set of records
        System.out.println("\nLoading as a record set from: " + filePath);
        RecordSet recordSet = RecordLoader.loadRecordSet(filePath);        
        if (recordSet == null) {
            System.out.println("Record loading fails.");
            System.exit(-1);
        }
        System.out.println("Total records: " + recordSet.numRecs);

        // Construct bitmap and create an iterator to query the record
        Bitmap bm = BitmapConstructor.construct(record, threadNum, levelNum);
        // BitmapIterator iter = BitmapConstructor.getIterator(bm);
        // String output = query(iter);

        // System.out.println("matches are: " + output);
    }
}
