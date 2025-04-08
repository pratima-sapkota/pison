package bitmap;

import java.util.Set;

public abstract class BitmapIterator {
    // Constants (from #defines)
    public static final int OBJECT = 1;
    public static final int ARRAY = 2;
    public static final int PRIMITIVE = 3;
    public static final int ERR = -1;
    public static final int MAX_FIELD_SIZE = 1000;
    
    // Private type (if needed by internal logic)
    private int type;
    
    // Public field representing number of visited fields.
    public int mVisitedFields;
    
    // Creates a copy of the iterator. Often used for parallel querying.
    public abstract BitmapIterator getCopy();
    
    // Moves up to the object or array which contains the current nested record.
    public abstract boolean up();
    
    // Moves down to the start of the nested object or array.
    public abstract boolean down();
    
    // Returns true if the iterator currently points to an object.
    public abstract boolean isObject();
    
    // Returns true if the iterator currently points to an array.
    public abstract boolean isArray();
    
    // Moves the iterator to the next array item.
    public abstract boolean moveNext();
    
    // Moves the iterator to the corresponding key field inside the current object.
    public abstract boolean moveToKey(String key);
    
    // Moves to one of the corresponding key fields inside the current object.
    // The key field is removed from keySet after this operation.
    public abstract String moveToKey(Set<String> keySet);
    
    // Returns the number of elements in the current array.
    public abstract int numArrayElements();
    
    // If the current record is an array, moves to the array item at the specified index.
    public abstract boolean moveToIndex(int index);
    
    // Returns the content of the current value inside an object or array.
    public abstract String getValue();
    
    // No destructor is necessary in Java.
}

// Simple data holding class translated from the C++ struct IterCtxInfo.
class IterCtxInfo {
    // current thread id for parsing and querying (used during leveled bitmap iteration)
    public int thread_id;
    // OBJECT or ARRAY
    public int type;
    // Position array for colon and comma positions.
    public long[] positions;
    // Start index of the record position array at the current level.
    public long start_idx;
    // End index of the record position array at the current level.
    public long end_idx;
    // Current index of the record position array at the current level.
    public long cur_idx;
    // The current level.
    public int level;
}

// Simple data holding class translated from the C++ struct KeyPos.
class KeyPos {
    public long start;
    public long end;
}
