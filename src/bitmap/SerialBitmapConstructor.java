package bitmap;

import records.Record;

import java.nio.charset.StandardCharsets;

public class SerialBitmapConstructor {
    // Constructs a SerialBitmap from a Record and a specified level number.
    public static SerialBitmap construct(Record record, int levelNum) {
        // Select the appropriate portion of the record's text.
        String recordText;
        if (record.recStartPos > 0) {
            recordText = record.text.substring(record.recStartPos);
        } else {
            recordText = record.text;
        }

        // Determine the length.
        long length;
        if (record.recLength > 0) {
            length = record.recLength;
        } else {
            length = record.text.length();
        }

        // Convert the selected text to a byte array (using UTF-8 encoding).
        byte[] recordBytes = recordText.getBytes(StandardCharsets.UTF_8);
        
        // Create the SerialBitmap and perform index construction.
        SerialBitmap bitmap = new SerialBitmap(recordBytes, levelNum);
        System.out.println("Record length: " + length);
        System.out.println("Bitmap length: " + bitmap.getLength());
        System.out.println("Bitmap size: " + bitmap.getSize());
        
        bitmap.setRecordLength(length);
        bitmap.indexConstruction();
        return bitmap;
    }

    // Overloaded method that uses a default level number.
    public static SerialBitmap construct(Record record) {
        return construct(record, SerialBitmap.MAX_LEVEL);
    }
}
