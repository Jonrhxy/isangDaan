package com.example.cverdetotoo;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public class TrackingService extends Service implements LocationListener {

    // Constants
    private static final int STEPS_PER_KM = 1316;
    private static final int GOAL_STEPS = 1500; // Step goal
    private static final float SPEED_THRESHOLD = 2.5f;
    private static final float ACCURACY_THRESHOLD = 30f;
    private static final float MAX_DISTANCE_DELTA = 50f;
    private static final float MIN_DISTANCE_DELTA = 3f;

    // SharedPreferences keys
    private static final String PREFS_NAME = "session_prefs";
    private static final String KEY_TOTAL_DISTANCE = "total_distance";
    private static final String KEY_TOTAL_ACTIVE_TIME = "total_active_time";
    private static final String KEY_LAST_DATE = "lastDate";
    private static final String KEY_SELECTED_MODE_INDEX = "selected_mode_index";

    // Optional separate service preferences
    private static final String SERVICE_PREFS = "service_prefs";
    private static final String SERVICE_DISTANCE = "service_distance";
    private static final String SERVICE_ACTIVE_TIME = "service_active_time";

    // Emission factors (kg CO₂ per km)
    private static final double EMISSION_FACTOR_CAR = 0.25;
    private static final double EMISSION_FACTOR_BUS = 0.08;
    private static final double EMISSION_FACTOR_MOTORCYCLE = 0.10;
    private static final double EMISSION_FACTOR_JEEPNEY = 0.15;
    private static final double EMISSION_FACTOR_TRUCK = 0.30;

    // Enum for transport mode
    public enum TransportMode { CAR, BUS, MOTORCYCLE, JEEPNEY, TRUCK }
    private TransportMode selectedMode = TransportMode.CAR;

    // Tracking fields
    private LocationManager locationManager;
    private List<Location> locations = new ArrayList<>();
    private float totalDistance = 0; // in meters
    private long startTime = 0;
    private long totalActiveTime = 0;
    private Handler handler = new Handler();

    // Foreground notification
    private static final String CHANNEL_ID = "tracking_channel";
    private static final int NOTIFICATION_ID = 101;
    private NotificationCompat.Builder notificationBuilder;
    private Runnable notificationUpdater;

    // Firestore instance (using displayName as doc ID)
    private FirebaseFirestore db;
    private String displayName = "unknown";

    // Reward flag so we only update once per session
    private boolean isRewardGiven = false;

    @Override
    public void onCreate() {
        super.onCreate();

        // Reset tracking data if a new day has begun
        checkAndResetDataIfNewDay();
        loadServiceData();

        // Retrieve selected mode from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
        selectedMode = mapSpinnerIndexToMode(savedModeIndex);

        // Get user info from FirebaseAuth; use displayName for doc ID
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null && currentUser.getDisplayName() != null) {
            displayName = currentUser.getDisplayName();
        }

        // Initialize Firestore
        db = FirebaseFirestore.getInstance();

        // Setup location manager
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        requestLocationUpdates();
        startTime = System.currentTimeMillis();

        // Immediately create the initial Firestore record with starting stats
        createInitialTrackingRecord();

        // Create channel & build the notification
        createNotificationChannel();
        buildNotification();

        // Repeatedly update notification, store records, and do real-time Firestore updates
        notificationUpdater = new Runnable() {
            @Override
            public void run() {
                updateNotification();
                storeTrackingRecordIfGoalReached();
                updateRealtimeFirestore();   // <-- NEW: real-time Firestore updates every second
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(notificationUpdater);
    }

    // Mapping function: adjust to match your spinner resource
    private TransportMode mapSpinnerIndexToMode(int index) {
        switch (index) {
            case 0: return TransportMode.CAR;
            case 1: return TransportMode.BUS;
            case 2: return TransportMode.MOTORCYCLE;
            case 3: return TransportMode.JEEPNEY;
            case 4: return TransportMode.TRUCK;
            default: return TransportMode.CAR;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelName = "Walking Tracker";
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Tracking your walk in background");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void buildNotification() {
        Intent notificationIntent = new Intent(this, GPS.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Walking Tracker")
                .setContentText("Initializing...")
                .setSmallIcon(R.drawable.custom_marker)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
    }

    private void updateNotification() {
        // Re-read the current mode from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
        selectedMode = mapSpinnerIndexToMode(savedModeIndex);

        // Calculate elapsed time
        long elapsedTime = totalActiveTime;
        if (startTime > 0) {
            elapsedTime += (System.currentTimeMillis() - startTime);
        }
        int totalSeconds = (int) (elapsedTime / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        String timeString = String.format("%02d:%02d", minutes, seconds);

        // Calculate distance and steps
        double distanceKm = totalDistance / 1000.0;
        int steps = (int) (distanceKm * STEPS_PER_KM);

        // Calculate CO₂ saved for the selected mode
        double emissionFactor;
        String modeText;
        switch (selectedMode) {
            case CAR:
                emissionFactor = EMISSION_FACTOR_CAR;
                modeText = "car";
                break;
            case BUS:
                emissionFactor = EMISSION_FACTOR_BUS;
                modeText = "bus";
                break;
            case MOTORCYCLE:
                emissionFactor = EMISSION_FACTOR_MOTORCYCLE;
                modeText = "motorcycle";
                break;
            case JEEPNEY:
                emissionFactor = EMISSION_FACTOR_JEEPNEY;
                modeText = "jeepney";
                break;
            case TRUCK:
                emissionFactor = EMISSION_FACTOR_TRUCK;
                modeText = "truck";
                break;
            default:
                emissionFactor = EMISSION_FACTOR_CAR;
                modeText = "car";
                break;
        }
        double co2Saved = distanceKm * emissionFactor;

        String contentText = "Time: " + timeString
                + " | Dist: " + String.format("%.2f km", distanceKm)
                + " | Steps: " + steps
                + " | CO₂: " + String.format("%.2f kg", co2Saved)
                + " (" + modeText + ")";

        notificationBuilder.setContentText(contentText);
        startForeground(NOTIFICATION_ID, notificationBuilder.build());

        // Broadcast updated info to GPS activity
        Intent updateIntent = new Intent("com.example.walktracker.TRACKING_UPDATE");
        updateIntent.putExtra("elapsedTime", elapsedTime);
        updateIntent.putExtra("totalDistance", totalDistance);
        updateIntent.putExtra("steps", steps);
        updateIntent.putExtra("co2Saved", co2Saved);
        updateIntent.putExtra("modeText", modeText);
        sendBroadcast(updateIntent);
    }

    /**
     * Real-time Firestore updates. Called every second in notificationUpdater.
     * Merges the latest stats into the same daily document.
     */
    private void updateRealtimeFirestore() {
        // Current day’s doc ID
        String dateDocId = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());

        // Compute updated stats
        double distanceKm = totalDistance / 1000.0;
        int steps = (int) (distanceKm * STEPS_PER_KM);

        long elapsedTime = totalActiveTime;
        if (startTime > 0) {
            elapsedTime += (System.currentTimeMillis() - startTime);
        }
        int totalSec = (int) (elapsedTime / 1000);
        int minutes = totalSec / 60;
        int seconds = totalSec % 60;
        String timeString = String.format("%02d:%02d", minutes, seconds);

        // Re-check selected mode
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
        selectedMode = mapSpinnerIndexToMode(savedModeIndex);

        double emissionFactor;
        switch (selectedMode) {
            case CAR:
                emissionFactor = EMISSION_FACTOR_CAR; break;
            case BUS:
                emissionFactor = EMISSION_FACTOR_BUS; break;
            case MOTORCYCLE:
                emissionFactor = EMISSION_FACTOR_MOTORCYCLE; break;
            case JEEPNEY:
                emissionFactor = EMISSION_FACTOR_JEEPNEY; break;
            case TRUCK:
                emissionFactor = EMISSION_FACTOR_TRUCK; break;
            default:
                emissionFactor = EMISSION_FACTOR_CAR; break;
        }
        double co2Saved = distanceKm * emissionFactor;

        // Partial data to merge
        Map<String, Object> partialData = new HashMap<>();
        partialData.put("distanceSoFarKm", String.format("%.2f", distanceKm));
        partialData.put("time", timeString);
        partialData.put("co2Saved", co2Saved);
        partialData.put("stepsSoFar", steps);
        partialData.put("timestamp", FieldValue.serverTimestamp());

        // Merge into the same daily document
        db.collection("Games")
                .document(displayName)
                .collection("trackingwalk")
                .document(dateDocId)
                .set(partialData, SetOptions.merge())
                .addOnSuccessListener(aVoid -> {
                    // Optionally log success
                })
                .addOnFailureListener(e -> {
                    // Optionally log error
                });
    }

    private void requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000,
                    1,
                    this
            );
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If the service is killed, restart with the last intent
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saveServiceData();
        locationManager.removeUpdates(this);
        handler.removeCallbacks(notificationUpdater);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Accumulate distance on location change
    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (location.hasAccuracy() && location.getAccuracy() > ACCURACY_THRESHOLD) return;

        if (!locations.isEmpty()) {
            Location lastLocation = locations.get(locations.size() - 1);
            float distanceDelta = lastLocation.distanceTo(location);
            if (distanceDelta < MIN_DISTANCE_DELTA || distanceDelta > MAX_DISTANCE_DELTA) return;
            long timeDelta = location.getTime() - lastLocation.getTime();
            if (timeDelta > 0) {
                float speed = distanceDelta / (timeDelta / 1000f);
                if (speed > SPEED_THRESHOLD) return;
            }
            totalDistance += distanceDelta;
        }
        locations.add(location);
    }

    // Save local data to SharedPreferences
    private void saveServiceData() {
        SharedPreferences prefs = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat(SERVICE_DISTANCE, totalDistance);
        long currentActiveTime = totalActiveTime;
        if (startTime > 0) {
            currentActiveTime += (System.currentTimeMillis() - startTime);
        }
        editor.putLong(SERVICE_ACTIVE_TIME, currentActiveTime);
        editor.apply();
    }

    // Load local data from SharedPreferences
    private void loadServiceData() {
        SharedPreferences prefs = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE);
        totalDistance = prefs.getFloat(SERVICE_DISTANCE, 0f);
        totalActiveTime = prefs.getLong(SERVICE_ACTIVE_TIME, 0L);
    }

    /**
     * Create an initial Firestore tracking record as soon as tracking begins.
     * This captures the exact starting values (typically zeros).
     */
    private void createInitialTrackingRecord() {
        String dateDocId = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        int pointsEarned = 0;
        double distanceKm = totalDistance / 1000.0;  // should be 0 at start
        int steps = (int)(distanceKm * STEPS_PER_KM);
        String timeString = "00:00"; // no time elapsed yet

        double emissionFactor;
        String modeText;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
        selectedMode = mapSpinnerIndexToMode(savedModeIndex);
        switch (selectedMode) {
            case CAR:
                emissionFactor = EMISSION_FACTOR_CAR;
                modeText = "car";
                break;
            case BUS:
                emissionFactor = EMISSION_FACTOR_BUS;
                modeText = "bus";
                break;
            case MOTORCYCLE:
                emissionFactor = EMISSION_FACTOR_MOTORCYCLE;
                modeText = "motorcycle";
                break;
            case JEEPNEY:
                emissionFactor = EMISSION_FACTOR_JEEPNEY;
                modeText = "jeepney";
                break;
            case TRUCK:
                emissionFactor = EMISSION_FACTOR_TRUCK;
                modeText = "truck";
                break;
            default:
                emissionFactor = EMISSION_FACTOR_CAR;
                modeText = "car";
                break;
        }
        double co2Saved = distanceKm * emissionFactor;

        String co2ComparisonBus = "CO₂ saved from walk compared to bus: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_BUS);
        String co2ComparisonJeepney = "CO₂ saved from walk compared to Jeepney: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_JEEPNEY);
        String co2ComparisonMotorcycle = "CO₂ saved from walk compared to Motorcycle: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_MOTORCYCLE);
        String co2ComparisonTruck = "CO₂ saved from walk compared to Truck: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_TRUCK);

        Map<String, Object> data = new HashMap<>();
        data.put("distanceSoFarKm", String.format("%.2f", distanceKm));
        data.put("time", timeString);
        data.put("co2Saved", co2Saved);
        data.put("mode", modeText);
        data.put("stepsSoFar", steps);
        data.put("pointsEarned", pointsEarned);
        data.put("co2ComparisonBus", co2ComparisonBus);
        data.put("co2ComparisonJeepney", co2ComparisonJeepney);
        data.put("co2ComparisonMotorcycle", co2ComparisonMotorcycle);
        data.put("co2ComparisonTruck", co2ComparisonTruck);
        data.put("timestamp", FieldValue.serverTimestamp());

        db.collection("Games")
                .document(displayName)  // Use displayName as document ID
                .collection("trackingwalk")
                .document(dateDocId)
                .set(data)
                .addOnSuccessListener(aVoid -> {
                    // Optionally log success
                })
                .addOnFailureListener(e -> {
                    // Optionally log error
                });
    }

    /**
     * Attempt to store (update) the Firestore record with earned points once the goal is reached.
     */
    private void storeTrackingRecordIfGoalReached() {
        if (isRewardGiven) return;  // Only update once per session

        double distanceKm = totalDistance / 1000.0;
        int steps = (int) (distanceKm * STEPS_PER_KM);
        if (steps < GOAL_STEPS) return;  // Goal not reached yet

        isRewardGiven = true;
        String dateDocId = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        int pointsEarned = 100;

        String co2ComparisonBus = "CO₂ saved from walk compared to bus: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_BUS);
        String co2ComparisonJeepney = "CO₂ saved from walk compared to Jeepney: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_JEEPNEY);
        String co2ComparisonMotorcycle = "CO₂ saved from walk compared to Motorcycle: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_MOTORCYCLE);
        String co2ComparisonTruck = "CO₂ saved from walk compared to Truck: " + String.format("%.2fkg", distanceKm * EMISSION_FACTOR_TRUCK);

        long elapsedTime = totalActiveTime;
        if (startTime > 0) {
            elapsedTime += (System.currentTimeMillis() - startTime);
        }
        int totalSec = (int) (elapsedTime / 1000);
        int minutes = totalSec / 60;
        int seconds = totalSec % 60;
        String timeString = String.format("%02d:%02d", minutes, seconds);
        String distanceString = String.format("%.2f", distanceKm);

        // Re-read selected mode from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
        selectedMode = mapSpinnerIndexToMode(savedModeIndex);

        double emissionFactor;
        String modeText;
        switch (selectedMode) {
            case CAR:
                emissionFactor = EMISSION_FACTOR_CAR;
                modeText = "car";
                break;
            case BUS:
                emissionFactor = EMISSION_FACTOR_BUS;
                modeText = "bus";
                break;
            case MOTORCYCLE:
                emissionFactor = EMISSION_FACTOR_MOTORCYCLE;
                modeText = "motorcycle";
                break;
            case JEEPNEY:
                emissionFactor = EMISSION_FACTOR_JEEPNEY;
                modeText = "jeepney";
                break;
            case TRUCK:
                emissionFactor = EMISSION_FACTOR_TRUCK;
                modeText = "truck";
                break;
            default:
                emissionFactor = EMISSION_FACTOR_CAR;
                modeText = "car";
                break;
        }
        double currentCo2Saved = distanceKm * emissionFactor;

        Map<String, Object> data = new HashMap<>();
        data.put("distanceSoFarKm", distanceString);
        data.put("time", timeString);
        data.put("co2Saved", currentCo2Saved);
        data.put("mode", modeText);
        data.put("stepsSoFar", steps);
        data.put("pointsEarned", pointsEarned);
        data.put("co2ComparisonBus", co2ComparisonBus);
        data.put("co2ComparisonJeepney", co2ComparisonJeepney);
        data.put("co2ComparisonMotorcycle", co2ComparisonMotorcycle);
        data.put("co2ComparisonTruck", co2ComparisonTruck);
        data.put("timestamp", FieldValue.serverTimestamp());

        db.collection("Games")
                .document(displayName)
                .collection("trackingwalk")
                .document(dateDocId)
                .set(data, SetOptions.merge())
                .addOnSuccessListener(aVoid -> {
                    // Increment parent's highScore by pointsEarned
                    db.collection("Games")
                            .document(displayName)
                            .update("highScore", FieldValue.increment(pointsEarned));
                })
                .addOnFailureListener(e -> {
                    // Optionally log error
                });
    }

    // The same overrides for provider status
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override
    public void onProviderEnabled(@NonNull String provider) { }
    @Override
    public void onProviderDisabled(@NonNull String provider) { }

    /**
     * Check if a new day has begun. If so, reset the tracking data and update the stored last date.
     */
    private void checkAndResetDataIfNewDay() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastDateMillis = prefs.getLong(KEY_LAST_DATE, 0);
        Calendar calCurrent = Calendar.getInstance();
        int currentDay = calCurrent.get(Calendar.DAY_OF_YEAR);
        Calendar calLast = Calendar.getInstance();
        calLast.setTimeInMillis(lastDateMillis);
        int lastDay = calLast.get(Calendar.DAY_OF_YEAR);
        if (lastDateMillis == 0 || currentDay != lastDay) {
            // Reset tracking data
            totalDistance = 0;
            totalActiveTime = 0;
            locations.clear();

            // Save the new date as the last recorded date
            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(KEY_LAST_DATE, System.currentTimeMillis());
            editor.apply();
        }
    }
}
