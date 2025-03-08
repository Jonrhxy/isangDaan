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
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;

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
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

public class TrackingService extends Service implements LocationListener {

    private static final String TAG = "TrackingService";

    // Constants
    private static final int STEPS_PER_KM = 1316;
    private static final int GOAL_STEPS = 1500;
    private static final float SPEED_THRESHOLD = 2.5f;
    private static final float ACCURACY_THRESHOLD = 30f;
    private static final float MAX_DISTANCE_DELTA = 50f;
    private static final float MIN_DISTANCE_DELTA = 3f;

    // SharedPreferences keys (common file for both classes)
    private static final String PREFS_NAME = "session_prefs";
    private static final String KEY_TOTAL_DISTANCE = "total_distance";
    private static final String KEY_TOTAL_ACTIVE_TIME = "accumulated_active_time";
    private static final String KEY_LAST_DATE = "lastDate";
    private static final String KEY_SELECTED_MODE_INDEX = "selected_mode_index";
    private static final String KEY_SESSION_ID = "session_id";

    // Emission factors
    private static final double EMISSION_FACTOR_CAR = 0.25;
    private static final double EMISSION_FACTOR_BUS = 0.08;
    private static final double EMISSION_FACTOR_MOTORCYCLE = 0.10;
    private static final double EMISSION_FACTOR_JEEPNEY = 0.15;
    private static final double EMISSION_FACTOR_TRUCK = 0.30;

    public enum TransportMode { CAR, BUS, MOTORCYCLE, JEEPNEY, TRUCK }
    private TransportMode selectedMode = TransportMode.CAR;

    // Tracking fields
    private LocationManager locationManager;
    private List<Location> locations = new ArrayList<>();
    private float totalDistance = 0;   // in meters
    private long startTime = 0;        // when tracking starts (ms)
    private long totalActiveTime = 0;  // accumulated active time (ms)
    private Handler handler = new Handler();

    // Foreground notification
    private static final String CHANNEL_ID = "tracking_channel";
    private static final int NOTIFICATION_ID = 101;
    private NotificationCompat.Builder notificationBuilder;
    private Runnable notificationUpdater;

    // Firestore
    private FirebaseFirestore db;
    private String displayName = "unknown";

    // Daily session id (formatted as yyyyMMdd)
    private String currentSessionId = null;
    private boolean isRewardGiven = false;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            checkAndResetDataIfNewDayService();
            loadServiceData();

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
            selectedMode = mapSpinnerIndexToMode(savedModeIndex);

            // Use today's date as doc ID
            currentSessionId = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_SESSION_ID, currentSessionId);
            editor.commit(); // Use commit() for synchronous saving

            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null && currentUser.getDisplayName() != null) {
                displayName = currentUser.getDisplayName();
            }
            Log.d(TAG, "onCreate: Using displayName=" + displayName);

            db = FirebaseFirestore.getInstance();

            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            requestLocationUpdates();
            startTime = System.currentTimeMillis();

            createNotificationChannel();
            buildNotification();

            notificationUpdater = new Runnable() {
                @Override
                public void run() {
                    updateNotification();
                    storeTrackingRecordIfGoalReached();
                    updateRealtimeFirestore();
                    handler.postDelayed(this, 1000);
                }
            };
            handler.post(notificationUpdater);
        } catch (Exception e) {
            Log.e(TAG, "Error in TrackingService onCreate: " + e.getMessage());
        }
    }

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

    private void checkAndResetDataIfNewDayService() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastDateMillis = prefs.getLong(KEY_LAST_DATE, 0);
            Calendar calCurrent = Calendar.getInstance();
            int currentDay = calCurrent.get(Calendar.DAY_OF_YEAR);
            Calendar calLast = Calendar.getInstance();
            calLast.setTimeInMillis(lastDateMillis);
            int lastDay = calLast.get(Calendar.DAY_OF_YEAR);

            if (lastDateMillis == 0 || currentDay != lastDay) {
                Log.d(TAG, "New day in Service—resetting data.");
                totalDistance = 0;
                totalActiveTime = 0;
                locations.clear();
                isRewardGiven = false;

                SharedPreferences.Editor editor = prefs.edit();
                editor.putLong(KEY_LAST_DATE, System.currentTimeMillis());
                editor.commit();
            } else {
                Log.d(TAG, "Same day in Service—no reset needed.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in checkAndResetDataIfNewDayService: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelName = "Walking Tracker";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Tracking your walk in background");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void buildNotification() {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "Error building notification: " + e.getMessage());
        }
    }

    private void updateNotification() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
            selectedMode = mapSpinnerIndexToMode(savedModeIndex);

            long elapsedTime = totalActiveTime;
            if (startTime > 0) {
                elapsedTime += (System.currentTimeMillis() - startTime);
            }
            int totalSeconds = (int) (elapsedTime / 1000);
            int minutes = totalSeconds / 60;
            int seconds = totalSeconds % 60;
            String timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);

            double distanceKm = totalDistance / 1000.0;
            int steps = (int) (distanceKm * STEPS_PER_KM);
            if (steps >= GOAL_STEPS) { steps = GOAL_STEPS; }

            double emissionFactor;
            String modeText;
            switch (selectedMode) {
                case CAR:
                    emissionFactor = EMISSION_FACTOR_CAR; modeText = "car"; break;
                case BUS:
                    emissionFactor = EMISSION_FACTOR_BUS; modeText = "bus"; break;
                case MOTORCYCLE:
                    emissionFactor = EMISSION_FACTOR_MOTORCYCLE; modeText = "motorcycle"; break;
                case JEEPNEY:
                    emissionFactor = EMISSION_FACTOR_JEEPNEY; modeText = "jeepney"; break;
                case TRUCK:
                    emissionFactor = EMISSION_FACTOR_TRUCK; modeText = "truck"; break;
                default:
                    emissionFactor = EMISSION_FACTOR_CAR; modeText = "car"; break;
            }
            double co2Saved = distanceKm * emissionFactor;

            RemoteViews notificationLayout = new RemoteViews(getPackageName(), R.layout.notification_foreground);
            if (steps >= GOAL_STEPS) {
                notificationLayout.setTextViewText(R.id.textTime, "Goal Reached!");
                notificationLayout.setTextViewText(R.id.textDistance, "");
                notificationLayout.setTextViewText(R.id.textSteps, "");
                notificationLayout.setTextViewText(R.id.textCO2, "");
            } else {
                notificationLayout.setTextViewText(R.id.textTime, "Time: " + timeString);
                notificationLayout.setTextViewText(R.id.textDistance, "Distance: " + String.format(Locale.getDefault(), "%.2f km", distanceKm));
                notificationLayout.setTextViewText(R.id.textSteps, "Steps: " + steps);
                notificationLayout.setTextViewText(R.id.textCO2, "CO₂: " + String.format(Locale.getDefault(), "%.2f kg", co2Saved));
            }
            notificationBuilder.setCustomContentView(notificationLayout);
            startForeground(NOTIFICATION_ID, notificationBuilder.build());

            // Broadcast update to UI
            Intent updateIntent = new Intent("com.example.walktracker.TRACKING_UPDATE");
            updateIntent.putExtra("elapsedTime", elapsedTime);
            updateIntent.putExtra("totalDistance", totalDistance);
            updateIntent.putExtra("steps", steps);
            updateIntent.putExtra("co2Saved", co2Saved);
            updateIntent.putExtra("modeText", modeText);
            sendBroadcast(updateIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification: " + e.getMessage());
        }
    }

    private void updateRealtimeFirestore() {
        try {
            double distanceKm = totalDistance / 1000.0;
            int steps = (int) (distanceKm * STEPS_PER_KM);
            long elapsedTime = totalActiveTime;
            if (startTime > 0) {
                elapsedTime += (System.currentTimeMillis() - startTime);
            }
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
            selectedMode = mapSpinnerIndexToMode(savedModeIndex);

            double emissionFactor;
            switch (selectedMode) {
                case CAR:        emissionFactor = EMISSION_FACTOR_CAR; break;
                case BUS:        emissionFactor = EMISSION_FACTOR_BUS; break;
                case MOTORCYCLE: emissionFactor = EMISSION_FACTOR_MOTORCYCLE; break;
                case JEEPNEY:    emissionFactor = EMISSION_FACTOR_JEEPNEY; break;
                case TRUCK:      emissionFactor = EMISSION_FACTOR_TRUCK; break;
                default:         emissionFactor = EMISSION_FACTOR_CAR; break;
            }
            double co2Saved = distanceKm * emissionFactor;

            String co2ComparisonBus = "CO₂ saved from walk compared to bus: " +
                    String.format("%.2fkg", distanceKm * 0.08);
            String co2ComparisonJeepney = "CO₂ saved from walk compared to Jeepney: " +
                    String.format("%.2fkg", distanceKm * 0.15);
            String co2ComparisonMotorcycle = "CO₂ saved from walk compared to Motorcycle: " +
                    String.format("%.2fkg", distanceKm * 0.10);
            String co2ComparisonTruck = "CO₂ saved from walk compared to Truck: " +
                    String.format("%.2fkg", distanceKm * 0.30);

            Map<String, Object> partialData = new HashMap<>();
            partialData.put("distanceSoFarKm", String.format(Locale.getDefault(), "%.2f", distanceKm));
            partialData.put("time", String.format(Locale.getDefault(), "%02d:%02d", (int)(elapsedTime / 60000), (int)((elapsedTime / 1000) % 60)));
            partialData.put("co2Saved", co2Saved);
            partialData.put("stepsSoFar", steps);
            partialData.put("timestamp", FieldValue.serverTimestamp());
            partialData.put("accumulatedActiveTime", elapsedTime);
            partialData.put("co2ComparisonBus", co2ComparisonBus);
            partialData.put("co2ComparisonJeepney", co2ComparisonJeepney);
            partialData.put("co2ComparisonMotorcycle", co2ComparisonMotorcycle);
            partialData.put("co2ComparisonTruck", co2ComparisonTruck);

            db.collection("Games")
                    .document(displayName)
                    .collection("trackingwalk")
                    .document(currentSessionId)
                    .set(partialData, SetOptions.merge())
                    .addOnFailureListener(e -> Log.e(TAG, "Realtime Firestore update failed: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "Error in updateRealtimeFirestore: " + e.getMessage());
        }
    }

    private void requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000,
                        1,
                        this
                );
            } catch (Exception e) {
                Log.e(TAG, "Error requesting location updates: " + e.getMessage());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            saveServiceData();
            locationManager.removeUpdates(this);
            handler.removeCallbacks(notificationUpdater);
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage());
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "Error in onLocationChanged: " + e.getMessage());
        }
    }

    private void saveServiceData() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat(KEY_TOTAL_DISTANCE, totalDistance);
            long currentActiveTime = totalActiveTime;
            if (startTime > 0) {
                currentActiveTime += (System.currentTimeMillis() - startTime);
            }
            editor.putLong(KEY_TOTAL_ACTIVE_TIME, currentActiveTime);
            editor.commit(); // Use commit() to ensure immediate saving
            Log.d(TAG, "saveServiceData: totalDistance=" + totalDistance
                    + ", totalActiveTime=" + currentActiveTime);
        } catch (Exception e) {
            Log.e(TAG, "Error saving service data: " + e.getMessage());
        }
    }

    private void loadServiceData() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            totalDistance = prefs.getFloat(KEY_TOTAL_DISTANCE, 0f);
            totalActiveTime = prefs.getLong(KEY_TOTAL_ACTIVE_TIME, 0L);
            Log.d(TAG, "loadServiceData: totalDistance=" + totalDistance
                    + ", totalActiveTime=" + totalActiveTime);
        } catch (Exception e) {
            Log.e(TAG, "Error loading service data: " + e.getMessage());
        }
    }

    private void storeTrackingRecordIfGoalReached() {
        try {
            if (isRewardGiven) return;
            double distanceKm = totalDistance / 1000.0;
            int steps = (int) (distanceKm * STEPS_PER_KM);
            if (steps < GOAL_STEPS) return;

            isRewardGiven = true;
            int pointsEarned = 100;

            long elapsedTime = totalActiveTime;
            if (startTime > 0) {
                elapsedTime += (System.currentTimeMillis() - startTime);
            }
            int totalSec = (int) (elapsedTime / 1000);
            int minutes = totalSec / 60;
            int seconds = totalSec % 60;
            String timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
            String distanceString = String.format(Locale.getDefault(), "%.2f", distanceKm);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
            selectedMode = mapSpinnerIndexToMode(savedModeIndex);

            double emissionFactor;
            String modeText;
            switch (selectedMode) {
                case CAR:
                    emissionFactor = EMISSION_FACTOR_CAR; modeText = "car"; break;
                case BUS:
                    emissionFactor = EMISSION_FACTOR_BUS; modeText = "bus"; break;
                case MOTORCYCLE:
                    emissionFactor = EMISSION_FACTOR_MOTORCYCLE; modeText = "motorcycle"; break;
                case JEEPNEY:
                    emissionFactor = EMISSION_FACTOR_JEEPNEY; modeText = "jeepney"; break;
                case TRUCK:
                    emissionFactor = EMISSION_FACTOR_TRUCK; modeText = "truck"; break;
                default:
                    emissionFactor = EMISSION_FACTOR_CAR; modeText = "car"; break;
            }
            double currentCo2Saved = distanceKm * emissionFactor;

            String co2ComparisonBus = "CO₂ saved from walk compared to bus: " +
                    String.format("%.2fkg", distanceKm * 0.08);
            String co2ComparisonJeepney = "CO₂ saved from walk compared to Jeepney: " +
                    String.format("%.2fkg", distanceKm * 0.15);
            String co2ComparisonMotorcycle = "CO₂ saved from walk compared to Motorcycle: " +
                    String.format("%.2fkg", distanceKm * 0.10);
            String co2ComparisonTruck = "CO₂ saved from walk compared to Truck: " +
                    String.format("%.2fkg", distanceKm * 0.30);

            Map<String, Object> data = new HashMap<>();
            data.put("distanceSoFarKm", distanceString);
            data.put("time", timeString);
            data.put("co2Saved", currentCo2Saved);
            data.put("mode", modeText);
            data.put("stepsSoFar", steps);
            data.put("pointsEarned", pointsEarned);
            data.put("timestamp", FieldValue.serverTimestamp());
            data.put("accumulatedActiveTime", elapsedTime);
            data.put("co2ComparisonBus", co2ComparisonBus);
            data.put("co2ComparisonJeepney", co2ComparisonJeepney);
            data.put("co2ComparisonMotorcycle", co2ComparisonMotorcycle);
            data.put("co2ComparisonTruck", co2ComparisonTruck);

            db.collection("Games")
                    .document(displayName)
                    .collection("trackingwalk")
                    .document(currentSessionId)
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> {
                        db.collection("Games")
                                .document(displayName)
                                .update("highScore", FieldValue.increment(pointsEarned));
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to update tracking record on goal: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "Error in storeTrackingRecordIfGoalReached: " + e.getMessage());
        }
    }

    @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}

}
