package tokenizer;

public class Tokenizer {
    // States
    public static final int IN = 10;
    public static final int OUT = 11;
    public static final int TRUE = 100;
    public static final int ERROR = 101;
    public static final int END = 102;
    
    // Token types
    public static final int LCB = 1;     // '{'
    public static final int RCB = 2;     // '}'
    public static final int LB = 3;      // '['
    public static final int RB = 4;      // ']'
    public static final int COM = 5;     // ','
    public static final int COLON = 6;   // ':'
    public static final int PRI = 7;     // primitive
    public static final int STRING = 8;  // "abc"
    public static final int UNKNOWN = 9;
    public static final int INVALID = -1; // invalid
    
    private String mChunk;
    private int mStartState;
    private int mCurPos;
    private int mCurTknType;
    private int mNextTknPos;

    // Empty Constructor
    public Tokenizer() {
    }
    
    // Constructor with chunk and state
    public Tokenizer(String chunk, int state) {
        createIterator(chunk, state);
    }
    
    // Creates an iterator on the provided chunk with the given start state.
    public void createIterator(String chunk, int state) {
        mChunk = chunk;
        mStartState = state;
        mCurPos = 0;
        mCurTknType = UNKNOWN;
        mNextTknPos = 0;
    }
    
    // Toggles the state between IN and OUT
    public int oppositeState(int state) {
        if (state == IN)
            return OUT;
        if (state == OUT)
            return IN;
        return UNKNOWN;
    }
    
    // Private helper to process a string token.
    // Note: The parameter 'pos' is passed by value; its updated value is stored in mNextTknPos.
    private int getStringToken(int pos) {
        while (pos < mChunk.length()) {
            int escapeCnt = 0;
            while (pos < mChunk.length() && mChunk.charAt(pos) == '\\') {
                ++escapeCnt;
                ++pos;
            }
            if (pos < mChunk.length() && mChunk.charAt(pos) == '"') {
                ++pos;
                if (escapeCnt % 2 == 0) {
                    mCurTknType = STRING;
                    mNextTknPos = pos;
                    return TRUE;
                }
            } else {
                ++pos;
            }
        }
        return END;
    }
    
    // Determines if the next valid token exists and sets up token type and next token position.
    public int hasNextToken() {
        if (mCurPos >= mChunk.length())
            return END;
        
        if (mCurPos == 0 && mStartState == IN) {
            int pos = mCurPos;
            return getStringToken(pos);
        }
        
        int pos = mCurPos;
        while (pos < mChunk.length()) {
            char ch = mChunk.charAt(pos);
            switch(ch) {
                case '\t':
                case '\n':
                case ' ':
                    ++pos;
                    break;
                case '{':
                    ++pos;
                    mCurTknType = LCB;
                    mNextTknPos = pos;
                    return TRUE;
                case '}':
                    ++pos;
                    mCurTknType = RCB;
                    mNextTknPos = pos;
                    return TRUE;
                case '[':
                    ++pos;
                    mCurTknType = LB;
                    mNextTknPos = pos;
                    return TRUE;
                case ']':
                    ++pos;
                    mCurTknType = RB;
                    mNextTknPos = pos;
                    return TRUE;
                case ',':
                    ++pos;
                    mCurTknType = COM;
                    mNextTknPos = pos;
                    return TRUE;
                case ':':
                    ++pos;
                    mCurTknType = COLON;
                    mNextTknPos = pos;
                    return TRUE;
                case '"':
                    ++pos;
                    return getStringToken(pos);
                case 't': {
                    if (pos + 3 < mChunk.length() &&
                        mChunk.charAt(pos + 1) == 'r' &&
                        mChunk.charAt(pos + 2) == 'u' &&
                        mChunk.charAt(pos + 3) == 'e') {
                        pos += 4;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 'r': {
                    if (pos + 3 < mChunk.length() &&
                        mChunk.charAt(pos + 2) == 'u' &&
                        mChunk.charAt(pos + 3) == 'e') {
                        pos += 3;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 'e': {
                    if (pos + 1 < mChunk.length() &&
                        (mChunk.charAt(pos + 1) == ',' ||
                         mChunk.charAt(pos + 1) == ']' ||
                         mChunk.charAt(pos + 1) == '}')) {
                        ++pos;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 'f': {
                    if (pos + 4 < mChunk.length() &&
                        mChunk.charAt(pos + 1) == 'a' &&
                        mChunk.charAt(pos + 2) == 'l' &&
                        mChunk.charAt(pos + 3) == 's' &&
                        mChunk.charAt(pos + 4) == 'e') {
                        pos += 5;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 'a': {
                    if (pos + 3 < mChunk.length() &&
                        mChunk.charAt(pos + 1) == 'l' &&
                        mChunk.charAt(pos + 2) == 's' &&
                        mChunk.charAt(pos + 3) == 'e') {
                        pos += 4;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 's': {
                    if (pos + 1 < mChunk.length() &&
                        mChunk.charAt(pos + 1) == 'e') {
                        pos += 2;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 'n': {
                    if (pos + 3 < mChunk.length() &&
                        mChunk.charAt(pos + 1) == 'u' &&
                        mChunk.charAt(pos + 2) == 'l' &&
                        mChunk.charAt(pos + 3) == 'l') {
                        pos += 4;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 'u': {
                    if (pos + 2 < mChunk.length() &&
                        mChunk.charAt(pos + 1) == 'l' &&
                        mChunk.charAt(pos + 2) == 'l') {
                        pos += 3;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else if (pos + 1 < mChunk.length() &&
                               mChunk.charAt(pos + 1) == 'e') {
                        pos += 2;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case 'l': {
                    if (pos + 1 < mChunk.length() &&
                        (mChunk.charAt(pos + 1) == 'l' ||
                         mChunk.charAt(pos + 1) == ',' ||
                         mChunk.charAt(pos + 1) == ']' ||
                         mChunk.charAt(pos + 1) == '}')) {
                        ++pos;
                        if (pos < mChunk.length() && mChunk.charAt(pos) == 'l')
                            ++pos;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else if (pos + 2 < mChunk.length() &&
                               mChunk.charAt(pos + 1) == 's' &&
                               mChunk.charAt(pos + 2) == 'e') {
                        pos += 3;
                        mCurTknType = PRI;
                        mNextTknPos = pos;
                        return TRUE;
                    } else {
                        return ERROR;
                    }
                }
                case '-':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                case '.': {
                    ++pos;
                    if (pos < mChunk.length()) {
                        char nextCh = mChunk.charAt(pos);
                        if (nextCh == '}' || nextCh == ']' || nextCh == ' ' ||
                            nextCh == '\t' || nextCh == ',') {
                            mCurTknType = PRI;
                            mNextTknPos = pos;
                            return TRUE;
                        }
                        if (nextCh == '"') {
                            return ERROR;
                        }
                    }
                    break;
                }
                default:
                    return ERROR;
            }
        }
        return END;
    }
    
    // Returns the type of the next token and advances the current position.
    public int nextToken() {
        mCurPos = mNextTknPos;
        return mCurTknType;
    }
}
