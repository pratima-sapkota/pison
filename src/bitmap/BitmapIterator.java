package bitmap;

import java.util.Set;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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
    public abstract boolean moveToKey(byte[] key);
    
    // Moves to one of the corresponding key fields inside the current object.
    // The key field is removed from keySet after this operation.
    public abstract byte[] moveToKey(Set<byte[]> keySet);
    
    // Returns the number of elements in the current array.
    public abstract int numArrayElements();
    
    // If the current record is an array, moves to the array item at the specified index.
    public abstract boolean moveToIndex(int index);
    
    // Returns the content of the current value inside an object or array.
    public abstract byte[] getValue();
    
    // No destructor is necessary in Java.

    // Getter methods
    public int getType() {
        return type;
    }

    // Setter methods
    public void setType(int type) {
        this.type = type;
    }

    public List<String> getQueryResponse(String json, List<String> keys) {
        List<String> out = new ArrayList<>();
        json = json.trim();

        if (json.startsWith("[")) {
            // Array of tweet‑objects
            int idx = 0;
            String elem;
            while ((elem = extractArrayElement(json, idx++)) != null) {
                collectFromObject(elem, out, keys);
            }
        } else if (json.startsWith("{")) {
            // Single tweet‑object
            collectFromObject(json, out, keys);
        }
        return out;
    }
    
    private void collectFromObject(String objJson, List<String> out, List<String> keys) {
        
        if (extractField(objJson, keys.get(0)) != null) {
            String userObj = extractField(objJson, keys.get(1));
            if (userObj != null) {
                String idVal = extractField(userObj, keys.get(2));
                if (idVal != null) {
                    out.add(unquote(idVal.trim()));
                }
            }
        }
    }

    private String extractField(String json, String key) {
        json = json.trim();
        if (!json.startsWith("{")) return null;
        int i = 1, n = json.length();
        while (i < n) {
            i = skipWhitespace(json, i);
            if (i < n && json.charAt(i) == '"') {
                int k0 = ++i;
                int k1 = findStringEnd(json, k0);
                String k = json.substring(k0, k1);
                i = skipWhitespace(json, k1 + 1);
                if (i < n && json.charAt(i) == ':') {
                    i = skipWhitespace(json, i + 1);
                    if (k.equals(key)) {
                        return extractJsonValue(json, i);
                    } else {
                        i = skipJsonValue(json, i);
                        i = skipWhitespace(json, i);
                        if (i < n && json.charAt(i) == ',') { i++; continue; }
                    }
                }
            } else if (i < n && json.charAt(i) == '}') {
                break;
            } else {
                i++;
            }
        }
        return null;
    }

    private String extractJsonValue(String json, int pos) {
        pos = skipWhitespace(json, pos);
        char c = json.charAt(pos);
        if (c == '"') {
            int end = findStringEnd(json, pos + 1);
            return json.substring(pos, end + 1);
        }
        if (c == '{' || c == '[') {
            int end = skipContainer(json, pos);
            return json.substring(pos, end);
        }
        int start = pos, n = json.length();
        while (pos < n && ",]}".indexOf(json.charAt(pos)) < 0) pos++;
        return json.substring(start, pos);
    }
    private int skipJsonValue(String json, int pos) {
        pos = skipWhitespace(json, pos);
        char c = json.charAt(pos);
        if (c == '"')        return findStringEnd(json, pos + 1) + 1;
        if (c == '{' || c == '[') return skipContainer(json, pos);
        int n = json.length();
        while (pos < n && ",]}".indexOf(json.charAt(pos)) < 0) pos++;
        return pos;
    }

    private String extractArrayElement(String json, int index) {
        int i = json.indexOf('[');
        if (i < 0) return null;
        int pos = i + 1, n = json.length(), elem = 0;
        while (pos < n) {
            while (pos < n && Character.isWhitespace(json.charAt(pos))) pos++;
            if (pos < n && json.charAt(pos) == ',') { pos++; continue; }
            if (pos < n && json.charAt(pos) == ']') break;
            int start = pos;
            char c = json.charAt(pos);
            if (c == '{' || c == '[')       pos = skipContainer(json, pos);
            else if (c == '"')              pos = findStringEnd(json, pos + 1) + 1;
            else                            while (pos < n && ",]}".indexOf(json.charAt(pos)) < 0) pos++;
            if (elem == index) return json.substring(start, pos);
            elem++;
        }
        return null;
    }

    private int skipContainer(String json, int pos) {
        char open = json.charAt(pos), close = (open == '{' ? '}' : ']');
        int depth = 1, i = pos + 1, n = json.length();
        while (i < n && depth > 0) {
            char ch = json.charAt(i++);
            if (ch == '"')       i = findStringEnd(json, i) + 1;
            else if (ch == open) depth++;
            else if (ch == close)depth--;
        }
        return i;
    }

    private int findStringEnd(String json, int pos) {
        int n = json.length();
        while (pos < n) {
            char c = json.charAt(pos++);
            if (c == '\\') pos++;
            else if (c == '"') return pos - 1;
        }
        return n - 1;
    }

    private int skipWhitespace(String s, int i) {
        int n = s.length();
        while (i < n && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private boolean isInteger(String s) {
        if (s.isEmpty()) return false;
        for (char c : s.toCharArray()) if (!Character.isDigit(c)) return false;
        return true;
    }

    private String unquote(String s) {
        if (s.length() >= 2
         && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
            return s.substring(1, s.length() - 1);
        return s;
    }
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
