package app.mockgps;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Process-wide snapshot of what the mock service is doing. The service writes it,
 * the activity listens and forwards it to the web UI.
 */
public final class MockState {

    public interface Listener {
        void onStateChanged(MockState state);
    }

    public static final String MODE_IDLE = "idle";
    public static final String MODE_POINT = "point";
    public static final String MODE_ROUTE = "route";

    public static final String ERROR_NOT_MOCK_APP = "not_mock_app";
    public static final String ERROR_START_FAILED = "start_failed";

    private static final MockState INSTANCE = new MockState();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Listener listener; // main thread only

    public volatile String mode = MODE_IDLE;
    public volatile boolean paused;
    public volatile boolean finished;
    public volatile double lat;
    public volatile double lng;
    public volatile double bearing;
    /** Metres per second. */
    public volatile double speed;
    /** 0..1 along the route. */
    public volatile double progress;
    /** Total route length in metres (0 for point mode). */
    public volatile double routeLength;
    /** One-shot error code; cleared once delivered to the UI. */
    public volatile String error;

    private MockState() {
    }

    public static MockState get() {
        return INSTANCE;
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public boolean isRunning() {
        return !MODE_IDLE.equals(mode);
    }

    /** Notify the listener on the main thread. Safe to call from any thread. */
    public static void publish() {
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onStateChanged(INSTANCE);
        });
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("mode", mode);
            o.put("paused", paused);
            o.put("finished", finished);
            o.put("lat", lat);
            o.put("lng", lng);
            o.put("bearing", bearing);
            o.put("speed", speed);
            o.put("progress", progress);
            o.put("routeLength", routeLength);
            o.put("error", error == null ? JSONObject.NULL : error);
        } catch (JSONException ignored) {
            // Only thrown for NaN/Infinity, which we never store.
        }
        return o.toString();
    }
}
