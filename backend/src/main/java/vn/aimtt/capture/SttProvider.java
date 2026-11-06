package vn.aimtt.capture;

import java.util.List;
import java.util.function.BooleanSupplier;

public interface SttProvider {
    record Segment(long startMs, long endMs, String text) {}
    List<Segment> transcribe(byte[] audio, String suffix, String model, BooleanSupplier keepLease);
}
