package records;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class RecordLoader {
    private static final int MAX_PAD = 64;
    private static final int MIN_RECORD_SIZE = 1; // Adjust as needed

    public static Record loadRecord(String filePath) {
        System.out.println("Loading record from: " + filePath);
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(filePath));
            System.out.println("File size: " + bytes.length);

            String recordText = new String(bytes, StandardCharsets.UTF_8);
            int size = recordText.length();
            int remain = MAX_PAD - (size % MAX_PAD);
            StringBuilder sb = new StringBuilder(recordText);
            
            for (int i = 0; i < remain; i++) {
                sb.append('d');
            }

            recordText = sb.toString();
            Record record = new Record(recordText, 0, recordText.length(), true);
            System.out.println("Record length: " + record.recLength);
            return record;
        } catch (IOException e) {
            e.printStackTrace(); // This will give you more details
            System.out.println("Fail to load the input record into memory");
            return null;
        }
    }

    public static RecordSet loadRecordSet(String filePath) {
        RecordSet rs = new RecordSet();
        StringBuilder fullText = new StringBuilder();
        int startPos = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() <= MIN_RECORD_SIZE) continue;
                int remain = MAX_PAD - (line.length() % MAX_PAD);
                StringBuilder paddedLine = new StringBuilder(line);
                for (int i = 0; i < remain; i++) {
                    paddedLine.append('d');
                }
                String padded = paddedLine.toString();
                if (padded.length() > MIN_RECORD_SIZE) {
                    fullText.append(padded);
                    Record record = new Record();
                    record.recStartPos = startPos;
                    record.recLength = padded.length();
                    startPos += padded.length();
                    rs.recs.add(record);
                    rs.numRecs++;
                }
            }
        } catch (IOException e) {
            System.out.println("Fail open the file.");
        }
        String concatenated = fullText.toString();
        for (int i = 0; i < rs.recs.size(); i++) {
            rs.recs.get(i).text = concatenated;
            if (i < rs.recs.size() - 1) {
                rs.recs.get(i).canDeleteText = false;
            }
        }
        return rs;
    }
}
