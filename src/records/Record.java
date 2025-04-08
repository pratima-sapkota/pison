package records;

public class Record {
    public String text;
    public int recStartPos;
    public int recLength;
    public boolean canDeleteText = true;

    public Record() {
        this.text = null;
        this.recStartPos = 0;
        this.recLength = 0;
        this.canDeleteText = true;
    }

    // Constructor with arguments
    public Record(String text, int start, int length, boolean canDelete) {
        this.text = text;
        this.recStartPos = start;
        this.recLength = length;
        this.canDeleteText = canDelete;
    }

    public int getRecLength() {
        return this.recLength;
    }

    public int getRecStartPos() {
        return this.recStartPos;
    }

    public String getText() {
        return this.text;
    }
}
