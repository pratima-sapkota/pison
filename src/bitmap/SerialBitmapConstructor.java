package bitmap;

import records.Record;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

public class SerialBitmapConstructor {
    // Constructs a SerialBitmap from a Record and a specified level number.
    public static SerialBitmap construct(Record record, int levelNum) {
        // Select the appropriate portion of the record's text.
        byte[] allBytes = record.text.getBytes(StandardCharsets.UTF_8);
        int    start    = record.recStartPos;
        int    len      = record.recLength > 0 
                        ? record.recLength 
                        : allBytes.length - start;

        byte[] slice    = Arrays.copyOfRange(allBytes, start, start + len);
        SerialBitmap bitmap = new SerialBitmap(slice, levelNum);
        bitmap.setRecordLength(len);
        bitmap.indexConstruction();

        System.out.println("Rec length: " + len + " Bitmap size: " + bitmap.getSize());
        return bitmap;
    }

    // Overloaded method that uses a default level number.
    public static SerialBitmap construct(Record record) {
        return construct(record, SerialBitmap.MAX_LEVEL);
    }
}
