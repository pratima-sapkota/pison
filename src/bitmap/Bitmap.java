package bitmap;

public class Bitmap {
    public static final int MAX_LEVEL = 22;
    public static final int SEQUENTIAL = 1;
    public static final int PARALLEL = 2;

    // Package-private so other classes in the same package can access it
    int type;

    public Bitmap() {
        this.type = SEQUENTIAL;
    }

    public void setRecordLength(long length) {
        // Override in subclass if needed
    }

    public void indexConstruction() {
        // Override in subclass if needed
    }

    public void setStreamFlag(boolean flag) {
        // Override in subclass if needed
    }
    
}