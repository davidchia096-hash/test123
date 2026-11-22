package app.mockgps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Foreground service that feeds fake fixes into Android's location providers.
 *
 * It registers test providers for GPS, network and (Android 12+) the platform fused
 * provider, and also puts Google Play Services' fused provider into mock mode, so apps
 * using either LocationManager or FusedLocationProviderClient see the same position.
 *
 * Requires the app to be chosen under Developer options > Select mock location app.
 */
public class MockLocationService extends Service {

    public static final String ACTION_START_POINT = "app.mockgps.action.START_POINT";
    public static final String ACTION_START_ROUTE = "app.mockgps.action.START_ROUTE";
    public static final String ACTION_TOGGLE_PAUSE = "app.mockgps.action.TOGGLE_PAUSE";
    public static final String ACTION_STOP = "app.mockgps.action.STOP";

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_ROUTE_LATS = "routeLats";
    public static final String EXTRA_ROUTE_LNGS = "routeLngs";
    public static final String EXTRA_SPEED_KMH = "speedKmh";
    public static final String EXTRA_LOOP = "loop";
    public static final String EXTRA_DRIFT = "drift";

    private static final String CHANNEL_ID = "mock_location";
    private static final int NOTIFICATION_ID = 42;
    private static final long POINT_INTERVAL_MS = 1000;
    private static final long ROUTE_INTERVAL_MS = 500;
    private static final long NOTIFICATION_THROTTLE_MS = 2000;
    private static final double EARTH_RADIUS_M = 6371008.8;
    private static final double ALTITUDE_M = 15.0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> providers = new ArrayList<>();
    private final Random random = new Random();

    private LocationManager locationManager;
    private FusedLocationProviderClient fusedClient;
    private volatile boolean fusedMockOn;

    // Current job
    private boolean routeMode;
    private boolean drift;
    private double pointLat;
    private double pointLng;
    private double[] routeLats;
    private double[] routeLngs;
    private double[] cumulative; // distance from start to each vertex, metres
    private double speedMs;
    private boolean loop;
    private double travelled;
    private double lastBearing;
    private long lastTickNanos;
    private long lastNotificationMs;
    private double driftNorth;
    private double driftEast;

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_START_POINT.equals(action) || ACTION_START_ROUTE.equals(action)) {
            if (!enterForeground()) {
                fail(MockState.ERROR_START_FAILED);
                return START_NOT_STICKY;
            }
            if (!setUpProviders()) {
                fail(MockState.ERROR_NOT_MOCK_APP);
                return START_NOT_STICKY;
            }
            configure(intent, ACTION_START_ROUTE.equals(action));
            handler.removeCallbacks(tick);
            handler.post(tick);
        } else if (ACTION_TOGGLE_PAUSE.equals(action)) {
            if (routeMode && !providers.isEmpty()) {
                MockState s = MockState.get();
                s.paused = !s.paused;
                lastNotificationMs = 0;
                MockState.publish();
            } else if (providers.isEmpty()) {
                stopEverything();
            }
        } else {
            // ACTION_STOP, or an unexpected restart with no intent.
            stopEverything();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        tearDownProviders();
        resetState();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------
    // Job setup

    private void configure(Intent intent, boolean route) {
        MockState s = MockState.get();
        routeMode = route;
        drift = intent.getBooleanExtra(EXTRA_DRIFT, false);
        driftNorth = 0;
        driftEast = 0;
        lastTickNanos = 0;
        lastNotificationMs = 0;
        travelled = 0;
        lastBearing = 0;
        s.error = null;
        s.paused = false;
        s.finished = false;
        s.progress = 0;

        if (route) {
            double[] lats = intent.getDoubleArrayExtra(EXTRA_ROUTE_LATS);
            double[] lngs = intent.getDoubleArrayExtra(EXTRA_ROUTE_LNGS);
            loop = intent.getBooleanExtra(EXTRA_LOOP, false);
            speedMs = Math.max(0.1, intent.getDoubleExtra(EXTRA_SPEED_KMH, 5.0) / 3.6);
            buildRoute(lats, lngs, loop);
            s.mode = MockState.MODE_ROUTE;
            s.routeLength = totalLength();
        } else {
            pointLat = intent.getDoubleExtra(EXTRA_LAT, 0);
            pointLng = intent.getDoubleExtra(EXTRA_LNG, 0);
            routeLats = null;
            routeLngs = null;
            cumulative = null;
            s.mode = MockState.MODE_POINT;
            s.routeLength = 0;
        }
    }

    private void buildRoute(double[] lats, double[] lngs, boolean closed) {
        if (lats == null || lngs == null || lats.length == 0 || lats.length != lngs.length) {
            lats = new double[]{0};
            lngs = new double[]{0};
        }
        int n = lats.length;
        boolean addClosing = closed && n > 1
                && (lats[0] != lats[n - 1] || lngs[0] != lngs[n - 1]);
        int m = addClosing ? n + 1 : n;
        routeLats = Arrays.copyOf(lats, m);
        routeLngs = Arrays.copyOf(lngs, m);
        if (addClosing) {
            routeLats[n] = lats[0];
            routeLngs[n] = lngs[0];
        }
        cumulative = new double[m];
        for (int i = 1; i < m; i++) {
            cumulative[i] = cumulative[i - 1]
                    + distance(routeLats[i - 1], routeLngs[i - 1], routeLats[i], routeLngs[i]);
        }
    }

    private double totalLength() {
        return cumulative == null || cumulative.length == 0 ? 0 : cumulative[cumulative.length - 1];
    }

    // ---------------------------------------------------------------------------------
    // Providers

    private boolean setUpProviders() {
        if (!providers.isEmpty()) return true; // already running; just swap the job

        List<String> wanted = new ArrayList<>();
        wanted.add(LocationManager.GPS_PROVIDER);
        wanted.add(LocationManager.NETWORK_PROVIDER);
        if (Build.VERSION.SDK_INT >= 31) wanted.add(LocationManager.FUSED_PROVIDER);

        for (String name : wanted) {
            try {
                try {
                    locationManager.removeTestProvider(name);
                } catch (IllegalArgumentException ignored) {
                    // No stale test provider to clear.
                }
                addTestProvider(name);
                locationManager.setTestProviderEnabled(name, true);
                providers.add(name);
            } catch (SecurityException e) {
                // Not selected as the mock location app.
                tearDownProviders();
                return false;
            } catch (IllegalArgumentException e) {
                // This provider can't be mocked on this device; carry on with the others.
            }
        }
        if (providers.isEmpty()) return false;
        startPlayServicesMock();
        return true;
    }

    @SuppressWarnings("deprecation")
    private void addTestProvider(String name) {
        if (Build.VERSION.SDK_INT >= 31) {
            ProviderProperties props = new ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build();
            locationManager.addTestProvider(name, props);
        } else {
            locationManager.addTestProvider(name, false, false, false, false,
                    true, true, true, android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE);
        }
    }

    private void tearDownProviders() {
        for (String name : providers) {
            try {
                locationManager.removeTestProvider(name);
            } catch (RuntimeException ignored) {
                // Already gone or permission revoked; nothing left to undo.
            }
        }
        providers.clear();
        stopPlayServicesMock();
    }

    private void startPlayServicesMock() {
        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(this);
            fusedClient.setMockMode(true)
                    .addOnSuccessListener(v -> fusedMockOn = true)
                    .addOnFailureListener(e -> fusedMockOn = false);
        } catch (RuntimeException e) {
            // Device without Google Play Services: the platform providers still work.
            fusedClient = null;
            fusedMockOn = false;
        }
    }

    private void stopPlayServicesMock() {
        if (fusedClient != null) {
            try {
                fusedClient.setMockMode(false);
            } catch (RuntimeException ignored) {
                // Nothing to restore.
            }
        }
        fusedClient = null;
        fusedMockOn = false;
    }

    // ---------------------------------------------------------------------------------
    // Ticking

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (providers.isEmpty()) return;
            MockState s = MockState.get();

            long now = SystemClock.elapsedRealtimeNanos();
            double dt = lastTickNanos == 0 ? 0 : (now - lastTickNanos) / 1e9;
            lastTickNanos = now;

            double lat;
            double lng;
            double bearing = lastBearing;
            double speed = 0;

            if (routeMode) {
                double total = totalLength();
                if (!s.paused && !s.finished) travelled += speedMs * dt;
                if (total <= 0) {
                    travelled = 0;
                } else if (travelled >= total) {
                    if (loop) {
                        travelled %= total;
                    } else {
                        travelled = total;
                        s.finished = true;
                    }
                }
                double[] pos = positionAlongRoute(travelled);
                lat = pos[0];
                lng = pos[1];
                if (!Double.isNaN(pos[2])) bearing = pos[2];
                speed = (s.paused || s.finished || total <= 0) ? 0 : speedMs;
                s.progress = total > 0 ? travelled / total : 1;
            } else {
                lat = pointLat;
                lng = pointLng;
            }
            lastBearing = bearing;

            if (drift) {
                // Mean-reverting random walk of a few metres, like a real receiver at rest.
                driftNorth = driftNorth * 0.85 + random.nextGaussian() * 0.6;
                driftEast = driftEast * 0.85 + random.nextGaussian() * 0.6;
                lat += Math.toDegrees(driftNorth / EARTH_RADIUS_M);
                lng += Math.toDegrees(driftEast / (EARTH_RADIUS_M * Math.cos(Math.toRadians(lat))));
            }

            if (!push(lat, lng, bearing, speed)) return;

            s.lat = lat;
            s.lng = lng;
            s.bearing = bearing;
            s.speed = speed;
            MockState.publish();
            maybeUpdateNotification();

            handler.postDelayed(this, routeMode ? ROUTE_INTERVAL_MS : POINT_INTERVAL_MS);
        }
    };

    /** Returns {lat, lng, bearing or NaN} at the given distance along the route. */
    private double[] positionAlongRoute(double d) {
        int m = routeLats.length;
        if (m == 1) return new double[]{routeLats[0], routeLngs[0], Double.NaN};
        int i = 0;
        while (i < m - 2 && cumulative[i + 1] < d) i++;
        double seg = cumulative[i + 1] - cumulative[i];
        double t = seg <= 0 ? 0 : Math.min(1, Math.max(0, (d - cumulative[i]) / seg));
        double lat = routeLats[i] + (routeLats[i + 1] - routeLats[i]) * t;
        double dLng = routeLngs[i + 1] - routeLngs[i];
        if (dLng > 180) dLng -= 360;
        if (dLng < -180) dLng += 360;
        double lng = routeLngs[i] + dLng * t;
        if (lng > 180) lng -= 360;
        if (lng < -180) lng += 360;
        double brg = seg <= 0 ? Double.NaN
                : bearing(routeLats[i], routeLngs[i], routeLats[i + 1], routeLngs[i + 1]);
        return new double[]{lat, lng, brg};
    }

    private boolean push(double lat, double lng, double bearing, double speed) {
        for (String name : providers) {
            try {
                locationManager.setTestProviderLocation(name, makeLocation(name, lat, lng, bearing, speed));
            } catch (SecurityException e) {
                // Mock app selection was revoked while running.
                fail(MockState.ERROR_NOT_MOCK_APP);
                return false;
            } catch (IllegalArgumentException ignored) {
                // Provider vanished; skip this round.
            }
        }
        if (fusedMockOn && fusedClient != null) {
            try {
                fusedClient.setMockLocation(makeLocation("fused", lat, lng, bearing, speed));
            } catch (RuntimeException ignored) {
                // Play Services refused; platform providers already have the fix.
            }
        }
        return true;
    }

    private Location makeLocation(String provider, double lat, double lng, double bearing, double speed) {
        boolean network = LocationManager.NETWORK_PROVIDER.equals(provider);
        float accuracy = network ? 12f : (drift ? 3f + random.nextFloat() * 3f : 3.5f);

        Location l = new Location(provider);
        l.setLatitude(lat);
        l.setLongitude(lng);
        l.setAltitude(ALTITUDE_M + (drift ? random.nextGaussian() * 0.8 : 0));
        l.setAccuracy(accuracy);
        l.setSpeed((float) speed);
        l.setBearing((float) ((bearing % 360 + 360) % 360));
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 26) {
            l.setVerticalAccuracyMeters(network ? 8f : 3f);
            l.setSpeedAccuracyMetersPerSecond(0.5f);
            l.setBearingAccuracyDegrees(speed > 0 ? 8f : 180f);
        }
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            Bundle extras = new Bundle();
            extras.putInt("satellites", 12);
            l.setExtras(extras);
        }
        return l;
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle helpers

    private boolean enterForeground() {
        Notification n = buildNotification(getString(R.string.notif_starting));
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            // e.g. SecurityException on Android 14 if location permission was revoked.
            return false;
        }
    }

    private void fail(String error) {
        handler.removeCallbacks(tick);
        tearDownProviders();
        resetState();
        MockState.get().error = error;
        MockState.publish();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopEverything() {
        handler.removeCallbacks(tick);
        tearDownProviders();
        resetState();
        MockState.publish();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void resetState() {
        MockState s = MockState.get();
        s.mode = MockState.MODE_IDLE;
        s.paused = false;
        s.finished = false;
        s.speed = 0;
        s.progress = 0;
        s.routeLength = 0;
        MockState.publish();
    }

    // ---------------------------------------------------------------------------------
    // Notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void maybeUpdateNotification() {
        long now = SystemClock.elapsedRealtime();
        if (lastNotificationMs != 0 && (!routeMode || now - lastNotificationMs < NOTIFICATION_THROTTLE_MS)) return;
        lastNotificationMs = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(describe()));
    }

    private String describe() {
        MockState s = MockState.get();
        if (!routeMode) {
            return String.format(Locale.US, "%.6f, %.6f", pointLat, pointLng);
        }
        String dist = formatDistance(travelled) + " / " + formatDistance(totalLength());
        if (s.finished) return getString(R.string.notif_arrived) + " · " + dist;
        if (s.paused) return getString(R.string.notif_paused) + " · " + dist;
        return String.format(Locale.US, "%.0f km/h · %s", speedMs * 3.6, dist);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);

        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        b.setSmallIcon(R.drawable.ic_stat_pin)
                .setContentTitle(getString(routeMode ? R.string.notif_title_route : R.string.notif_title_point))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .setColor(getColor(R.color.accent));

        if (routeMode && !MockState.get().finished) {
            boolean paused = MockState.get().paused;
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, paused ? R.drawable.ic_play : R.drawable.ic_pause),
                    getString(paused ? R.string.action_resume : R.string.action_pause),
                    servicePendingIntent(ACTION_TOGGLE_PAUSE, 2)).build());
        }
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop),
                getString(R.string.action_stop),
                servicePendingIntent(ACTION_STOP, 1)).build());

        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent i = new Intent(this, MockLocationService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ---------------------------------------------------------------------------------
    // Geo maths

    static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static String formatDistance(double m) {
        return m < 1000 ? String.format(Locale.US, "%.0f m", m)
                : String.format(Locale.US, "%.2f km", m / 1000);
    }
}
