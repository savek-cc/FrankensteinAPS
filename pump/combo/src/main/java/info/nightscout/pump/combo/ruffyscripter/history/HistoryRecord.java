package info.nightscout.pump.combo.ruffyscripter.history;

public abstract class HistoryRecord {
    public final long timestamp;

    protected HistoryRecord(long timestamp) {
        this.timestamp = timestamp;
    }
}
