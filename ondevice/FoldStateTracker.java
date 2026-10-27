package juloo.keyboard2;

import android.content.Context;

/** On-device build replacement for FoldStateTracker: avoids the androidx
    dependency. Reports the device as never unfolded. */
public class FoldStateTracker {
    public FoldStateTracker(Context context) {}

    public static boolean isFoldableDevice(Context context) { return false; }

    public boolean isUnfolded() { return false; }

    public void close() {}

    public void setChangedCallback(Runnable _changedCallback) {}
}
