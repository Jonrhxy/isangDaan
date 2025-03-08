package com.example.cverdetotoo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.ScaleBarOverlay;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class GPS extends AppCompatActivity implements LocationListener {

    private static final String TAG = "GPS";

    // Constants
    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private static final int STEPS_PER_KM = 1316;
    private static final int GOAL_STEPS = 1500;
    private static final float SPEED_THRESHOLD = 2.5f;
    private static final float ACCURACY_THRESHOLD = 30f;
    private static final float MAX_DISTANCE_DELTA = 50f;
    private static final float MIN_DISTANCE_DELTA = 3f;

    // SharedPreferences keys (common file)
    private static final String PREFS_NAME = "session_prefs";
    private static final String KEY_TRACKING_STATE = "tracking_state";
    private static final String KEY_TOTAL_DISTANCE = "total_distance";
    private static final String KEY_ACCUMULATED_TIME = "accumulated_active_time";
    private static final String KEY_LAST_DATE = "lastDate";
    private static final String KEY_SELECTED_MODE_INDEX = "selected_mode_index";
    private static final String KEY_SESSION_ID = "session_id";

    // Emission factors
    private static final double EMISSION_FACTOR_CAR = 0.25;
    private static final double EMISSION_FACTOR_BUS = 0.08;
    private static final double EMISSION_FACTOR_MOTORCYCLE = 0.10;
    private static final double EMISSION_FACTOR_JEEPNEY = 0.15;
    private static final double EMISSION_FACTOR_TRUCK = 0.30;

    // Tracking states and modes
    private enum TrackingState { STOPPED, RUNNING, PAUSED }
    public enum TransportMode { CAR, BUS, MOTORCYCLE, JEEPNEY, TRUCK }
    private TrackingState trackingState = TrackingState.STOPPED;
    private TransportMode selectedMode = TransportMode.CAR;

    // UI
    private MapView mapView;
    private MyLocationNewOverlay locationOverlay;
    private Polyline polyline;
    private Polyline routeLine;
    private LocationManager locationManager;
    private TextView textDistanceValue, textTimeValue, textStepsValue, textCarbonEmission;
    private ProgressBar progressSteps;
    private Button buttonStartStop;
    private Spinner spinnerTransportMode;

    // Tracking variables
    private ArrayList<Location> locations = new ArrayList<>();
    private float totalDistance = 0; // in meters
    private long accumulatedActiveTime = 0; // in ms
    private long sessionStartTime = 0;
    private boolean isBadgePopupShown = false;

    // Firestore
    private FirebaseFirestore db;
    private String displayName = "unknown";

    // Daily session ID (yyyyMMdd)
    private String currentSessionId = null;

    // Flags
    private boolean recordExistsInFirestore = false;
    private boolean dailyRecordInitialized = false;

    // BroadcastReceiver for updates from TrackingService
    private final BroadcastReceiver trackingUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                long elapsedTime = intent.getLongExtra("elapsedTime", accumulatedActiveTime);
                float distance = intent.getFloatExtra("totalDistance", totalDistance);
                int steps = intent.getIntExtra("steps", 0);
                double co2Saved = intent.getDoubleExtra("co2Saved", 0.0);
                String modeText = intent.getStringExtra("modeText");

                // Update in-memory values
                accumulatedActiveTime = elapsedTime;
                totalDistance = distance;

                int totalSeconds = (int) (elapsedTime / 1000);
                int minutes = totalSeconds / 60;
                int seconds = totalSeconds % 60;
                String timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);

                double distanceKm = distance / 1000.0;
                String distanceString = String.format(Locale.getDefault(), "%.2f", distanceKm);

                textTimeValue.setText(timeString);
                textDistanceValue.setText(distanceString);
                textStepsValue.setText(String.valueOf(steps));
                textCarbonEmission.setText(String.format(Locale.getDefault(),
                        "Walking saved %.2f kg CO₂ vs. using a %s.", co2Saved, modeText));
            } catch (Exception e) {
                Log.e(TAG, "Error in trackingUpdateReceiver: " + e.getMessage());
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = FirebaseFirestore.getInstance();

        // Assume user is logged in; get displayName from FirebaseAuth.
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null && currentUser.getDisplayName() != null) {
            displayName = currentUser.getDisplayName();
        }
        Log.d(TAG, "onCreate: displayName=" + displayName);

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this));
        setContentView(R.layout.activity_gps);

        // Generate today's doc ID.
        currentSessionId = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_SESSION_ID, currentSessionId);
        editor.commit();

        buttonStartStop = findViewById(R.id.buttonStartStop);
        buttonStartStop.setEnabled(false);

        textDistanceValue = findViewById(R.id.textDistanceValue);
        textTimeValue = findViewById(R.id.textTimeValue);
        textStepsValue = findViewById(R.id.textStepsValue);
        textCarbonEmission = findViewById(R.id.textCarbonEmission);
        progressSteps = findViewById(R.id.progressSteps);
        spinnerTransportMode = findViewById(R.id.spinnerTransportMode);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.transport_modes, android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTransportMode.setAdapter(adapter);

        int savedModeIndex = prefs.getInt(KEY_SELECTED_MODE_INDEX, 0);
        spinnerTransportMode.setSelection(savedModeIndex);
        selectedMode = TransportMode.values()[savedModeIndex];
        spinnerTransportMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedMode = TransportMode.values()[position];
                SharedPreferences.Editor ed = prefs.edit();
                ed.putInt(KEY_SELECTED_MODE_INDEX, position);
                ed.commit();
                updateStats();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        mapView = findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        locationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mapView);
        Bitmap largeIcon = BitmapFactory.decodeResource(getResources(), R.drawable.custom_marker);
        Bitmap scaledIcon = Bitmap.createScaledBitmap(largeIcon, 64, 64, true);
        locationOverlay.setPersonIcon(scaledIcon);
        locationOverlay.setPersonHotspot(32f, 32f);

        polyline = new Polyline();
        polyline.setWidth(5f);
        polyline.setColor(0xFFFF0000);
        mapView.getOverlayManager().add(polyline);

        routeLine = new Polyline();
        routeLine.setWidth(5f);
        routeLine.setColor(Color.BLUE);
        routeLine.setPoints(Collections.emptyList());
        mapView.getOverlayManager().add(routeLine);

        CompassOverlay compassOverlay = new CompassOverlay(this, mapView);
        compassOverlay.enableCompass();
        mapView.getOverlays().add(compassOverlay);

        ScaleBarOverlay scaleBarOverlay = new ScaleBarOverlay(mapView);
        scaleBarOverlay.setAlignRight(true);
        mapView.getOverlays().add(scaleBarOverlay);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        requestLocationPermission();

        ImageView backArrow = findViewById(R.id.imageBackArrow);
        if (backArrow != null) {
            backArrow.setOnClickListener(v -> finish());
        }

        // Immediately fetch Firestore document.
        initializeDailyRecord();
        checkAndResetDataIfNewDay();

        IntentFilter filter = new IntentFilter("com.example.walktracker.TRACKING_UPDATE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackingUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(trackingUpdateReceiver, filter);
        }

        buttonStartStop.setOnClickListener(v -> {
            if (trackingState == TrackingState.RUNNING) {
                pauseTracking();
                stopTrackingService();
                buttonStartStop.setText("RESUME");
            } else {
                startTracking();
                if (!recordExistsInFirestore) {
                    createInitialTrackingRecord();
                }
                startTrackingService();
                buttonStartStop.setText("PAUSE");
            }
        });
    }

    /**
     * Fetch the Firestore document and write its numeric fields into SharedPreferences.
     * Then load the session data so the UI reflects the Firestore values.
     */
    private void initializeDailyRecord() {
        final String todayId = currentSessionId;
        Log.d(TAG, "initializeDailyRecord: Fetching doc from /Games/" + displayName + "/trackingwalk/" + todayId);

        db.collection("Games")
                .document(displayName)
                .collection("trackingwalk")
                .document(todayId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    Log.d(TAG, "Firestore fetch success for doc: " + todayId);
                    if (documentSnapshot.exists()) {
                        recordExistsInFirestore = true;
                        Log.d(TAG, "Doc exists! Attempting to parse numeric fields...");

                        Long activeTime = documentSnapshot.getLong("accumulatedActiveTime");
                        Log.d(TAG, "Firestore activeTime: " + activeTime);
                        long fetchedActiveTime = activeTime != null ? activeTime : 0;

                        String distanceStr = documentSnapshot.getString("distanceSoFarKm");
                        Log.d(TAG, "distanceSoFarKm from Firestore = " + distanceStr);
                        float fetchedDistance = 0f;
                        if (distanceStr != null) {
                            try {
                                fetchedDistance = Float.parseFloat(distanceStr) * 1000; // km => m
                            } catch (NumberFormatException e) {
                                fetchedDistance = 0f;
                            }
                        }

                        // Immediately store fetched values in SharedPreferences synchronously.
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putLong(KEY_ACCUMULATED_TIME, fetchedActiveTime);
                        ed.putFloat(KEY_TOTAL_DISTANCE, fetchedDistance);
                        ed.commit(); // use commit() to ensure values are saved before loadSessionData()

                        Log.d(TAG, "After fetch => wrote to SharedPreferences: activeTime=" + fetchedActiveTime + ", totalDistance=" + fetchedDistance);
                    } else {
                        recordExistsInFirestore = false;
                        Log.d(TAG, "Doc does NOT exist at that path!");
                    }
                    Log.d(TAG, "After fetch: recordExistsInFirestore=" + recordExistsInFirestore);
                    loadSessionData();
                    updateStartButtonText();
                    buttonStartStop.setEnabled(true);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching doc: " + e.getMessage());
                    recordExistsInFirestore = false;
                    buttonStartStop.setEnabled(true);
                    loadSessionData();
                    updateStartButtonText();
                });
    }

    private void updateStartButtonText() {
        Log.d(TAG, "updateStartButtonText: recordExistsInFirestore=" + recordExistsInFirestore
                + ", accumulatedActiveTime=" + accumulatedActiveTime
                + ", totalDistance=" + totalDistance);
        if (recordExistsInFirestore && (accumulatedActiveTime > 0 || totalDistance > 0)) {
            buttonStartStop.setText("RESUME");
        } else {
            buttonStartStop.setText("START");
        }
    }

    private void checkAndResetDataIfNewDay() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastDateMillis = prefs.getLong(KEY_LAST_DATE, 0);
            Calendar calCurrent = Calendar.getInstance();
            int currentDay = calCurrent.get(Calendar.DAY_OF_YEAR);
            Calendar calLast = Calendar.getInstance();
            calLast.setTimeInMillis(lastDateMillis);
            int lastDay = calLast.get(Calendar.DAY_OF_YEAR);
            if (lastDateMillis == 0 || currentDay != lastDay) {
                Log.d(TAG, "New day—reset local session data.");
                clearSessionData();
                SharedPreferences.Editor editor = prefs.edit();
                editor.putLong(KEY_LAST_DATE, System.currentTimeMillis());
                editor.commit();
            } else {
                Log.d(TAG, "Same day—no reset needed.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in checkAndResetDataIfNewDay: " + e.getMessage());
        }
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
        } else {
            refreshMap();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshMap();
                requestLocationUpdates();
            } else {
                Toast.makeText(this, "Location permission required for tracking.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void refreshMap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationOverlay.enableMyLocation();
            locationOverlay.enableFollowLocation();
            locationOverlay.runOnFirstFix(() -> runOnUiThread(() -> {
                IGeoPoint currentLocation = locationOverlay.getMyLocation();
                if (currentLocation != null) {
                    zoomToLocation(currentLocation.getLatitude(), currentLocation.getLongitude(), 19.0);
                }
            }));
            if (!mapView.getOverlays().contains(locationOverlay)) {
                mapView.getOverlays().add(locationOverlay);
            }
            mapView.invalidate();
        }
    }

    private void requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 1, this);
            } catch (Exception e) {
                Log.e(TAG, "Error requesting location updates: " + e.getMessage());
            }
        }
    }

    private int dpToPx(int dp) {
        float scale = getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    private void startTracking() {
        trackingState = TrackingState.RUNNING;
        if (!(recordExistsInFirestore && (totalDistance > 0 || accumulatedActiveTime > 0))) {
            totalDistance = 0;
            accumulatedActiveTime = 0;
            locations.clear();
            polyline.setPoints(new ArrayList<>());
            textDistanceValue.setText("0");
            textTimeValue.setText("00:00");
            textStepsValue.setText("0");
            progressSteps.setProgress(0);
            textCarbonEmission.setText("CO₂ saved: 0 kg");
        }
        sessionStartTime = System.currentTimeMillis();
        isBadgePopupShown = false;
        requestLocationUpdates();
    }

    private void pauseTracking() {
        trackingState = TrackingState.PAUSED;
        accumulatedActiveTime += (System.currentTimeMillis() - sessionStartTime);
        try {
            locationManager.removeUpdates(this);
        } catch (Exception e) {
            Log.e(TAG, "Error removing location updates: " + e.getMessage());
        }
        saveSessionData();
    }

    private void resumeTracking() {
        trackingState = TrackingState.RUNNING;
        sessionStartTime = System.currentTimeMillis();
        requestLocationUpdates();
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (trackingState != TrackingState.RUNNING) return;
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
        updatePolyline();
        updateStats();
        routeLine.setPoints(Collections.emptyList());
        mapView.invalidate();
    }

    private void updatePolyline() {
        try {
            ArrayList<GeoPoint> geoPoints = new ArrayList<>();
            for (Location loc : locations) {
                geoPoints.add(new GeoPoint(loc.getLatitude(), loc.getLongitude()));
            }
            polyline.setPoints(geoPoints);
            mapView.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error updating polyline: " + e.getMessage());
        }
    }

    private void applyGradientToText(TextView textView, int startColor, int endColor) {
        try {
            textView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            float textHeight = textView.getTextSize();
            Shader textShader = new LinearGradient(0, 0, 0, textHeight, new int[]{startColor, endColor}, null, Shader.TileMode.CLAMP);
            textView.getPaint().setShader(textShader);
            textView.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error applying gradient: " + e.getMessage());
        }
    }

    private void updateStats() {
        try {
            long elapsedTime = accumulatedActiveTime;
            if (trackingState == TrackingState.RUNNING) {
                elapsedTime += (System.currentTimeMillis() - sessionStartTime);
            }
            int totalSeconds = (int) (elapsedTime / 1000);
            int minutes = totalSeconds / 60;
            int seconds = totalSeconds % 60;
            String timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);

            double distanceKm = totalDistance / 1000.0;
            String distanceString = String.format(Locale.getDefault(), "%.2f", distanceKm);

            int realStepCount = (int) (distanceKm * STEPS_PER_KM);
            if (realStepCount >= GOAL_STEPS) { realStepCount = GOAL_STEPS; }
            textStepsValue.setText(String.valueOf(realStepCount));
            progressSteps.setProgress(Math.min(realStepCount, GOAL_STEPS));

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
            double emissionSaved = distanceKm * emissionFactor;
            textCarbonEmission.setText(String.format(Locale.getDefault(),
                    "Walking saved %.2f kg CO₂ vs. using a %s.", emissionSaved, modeText));

            textDistanceValue.setText(distanceString);
            textTimeValue.setText(timeString);

            int startColor = Color.parseColor("#00215E");
            int endColor = Color.parseColor("#2C4E80");
            applyGradientToText(textDistanceValue, startColor, endColor);
            applyGradientToText(textTimeValue, startColor, endColor);
            applyGradientToText(textStepsValue, startColor, endColor);
        } catch (Exception e) {
            Log.e(TAG, "Error updating stats: " + e.getMessage());
        }
    }

    private void createInitialTrackingRecord() {
        try {
            if (currentSessionId == null) {
                currentSessionId = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
            }
            double distanceKm = totalDistance / 1000.0;
            int steps = (int) (distanceKm * STEPS_PER_KM);
            String timeString = "00:00";

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

            String co2ComparisonBus = "CO₂ saved from walk compared to bus: " +
                    String.format("%.2fkg", distanceKm * 0.08);
            String co2ComparisonJeepney = "CO₂ saved from walk compared to Jeepney: " +
                    String.format("%.2fkg", distanceKm * 0.15);
            String co2ComparisonMotorcycle = "CO₂ saved from walk compared to Motorcycle: " +
                    String.format("%.2fkg", distanceKm * 0.10);
            String co2ComparisonTruck = "CO₂ saved from walk compared to Truck: " +
                    String.format("%.2fkg", distanceKm * 0.30);

            Map<String, Object> data = new HashMap<>();
            data.put("date", currentSessionId);
            data.put("distanceSoFarKm", String.format(Locale.getDefault(), "%.2f", distanceKm));
            data.put("time", timeString);
            data.put("co2Saved", co2Saved);
            data.put("mode", modeText);
            data.put("stepsSoFar", steps);
            data.put("pointsEarned", 0);
            data.put("timestamp", FieldValue.serverTimestamp());
            data.put("accumulatedActiveTime", accumulatedActiveTime);
            data.put("co2ComparisonBus", co2ComparisonBus);
            data.put("co2ComparisonJeepney", co2ComparisonJeepney);
            data.put("co2ComparisonMotorcycle", co2ComparisonMotorcycle);
            data.put("co2ComparisonTruck", co2ComparisonTruck);

            db.collection("Games")
                    .document(displayName)
                    .collection("trackingwalk")
                    .document(currentSessionId)
                    .set(data)
                    .addOnSuccessListener(aVoid ->
                            Toast.makeText(GPS.this, "Session started! Tracking record created.", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to create tracking record: " + e.getMessage());
                        Toast.makeText(GPS.this, "Failed to create tracking record.", Toast.LENGTH_SHORT).show();
                    });
        } catch (Exception e) {
            Log.e(TAG, "Exception in createInitialTrackingRecord: " + e.getMessage());
        }
    }

    private void storeTrackingRecord(String distance, String time, double co2Saved, String mode, int steps) {
        try {
            int pointsEarned = 100;
            double distanceKm = totalDistance / 1000.0;

            String co2ComparisonBus = "CO₂ saved from walk compared to bus: " +
                    String.format("%.2fkg", distanceKm * 0.08);
            String co2ComparisonJeepney = "CO₂ saved from walk compared to Jeepney: " +
                    String.format("%.2fkg", distanceKm * 0.15);
            String co2ComparisonMotorcycle = "CO₂ saved from walk compared to Motorcycle: " +
                    String.format("%.2fkg", distanceKm * 0.10);
            String co2ComparisonTruck = "CO₂ saved from walk compared to Truck: " +
                    String.format("%.2fkg", distanceKm * 0.30);

            Map<String, Object> data = new HashMap<>();
            data.put("distanceSoFarKm", distance);
            data.put("time", time);
            data.put("co2Saved", co2Saved);
            data.put("mode", mode);
            data.put("stepsSoFar", steps);
            data.put("pointsEarned", pointsEarned);
            data.put("timestamp", FieldValue.serverTimestamp());
            data.put("accumulatedActiveTime", accumulatedActiveTime);
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
                        Toast.makeText(GPS.this, "Goal reached! Session record saved.", Toast.LENGTH_SHORT).show();
                        db.collection("Games")
                                .document(displayName)
                                .update("highScore", FieldValue.increment(pointsEarned))
                                .addOnSuccessListener(unused ->
                                        Toast.makeText(GPS.this, "HighScore updated!", Toast.LENGTH_SHORT).show())
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Failed to update highScore: " + e.getMessage());
                                    Toast.makeText(GPS.this, "Failed to update highScore.", Toast.LENGTH_SHORT).show();
                                });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to save session record: " + e.getMessage());
                        Toast.makeText(GPS.this, "Failed to save session record.", Toast.LENGTH_SHORT).show();
                    });
        } catch (Exception e) {
            Log.e(TAG, "Exception in storeTrackingRecord: " + e.getMessage());
        }
    }

    private void zoomToLocation(double latitude, double longitude, double zoomLevel) {
        try {
            mapView.getController().setZoom(zoomLevel);
            mapView.getController().setCenter(new GeoPoint(latitude, longitude));
        } catch (Exception e) {
            Log.e(TAG, "Error zooming to location: " + e.getMessage());
        }
    }

    private void showBadgePopup() {
        try {
            Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(R.layout.popup_badge_earned);
            dialog.setCancelable(false);

            TextView textBadgeLabel = dialog.findViewById(R.id.textBadgeLabel);
            TextView textTitle = dialog.findViewById(R.id.textTitle);
            TextView textSubMessage = dialog.findViewById(R.id.textSubMessage);
            Button buttonContinue = dialog.findViewById(R.id.buttonContinue);

            textBadgeLabel.setText("Gold Star Badge");
            textTitle.setText("Well done!");
            textSubMessage.setText("You’ve earned the Gold Star Badge for tracking your travel!");
            buttonContinue.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing badge popup: " + e.getMessage());
        }
    }

    private void saveSessionData() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            int stateValue;
            switch (trackingState) {
                case RUNNING: stateValue = 1; break;
                case PAUSED:  stateValue = 2; break;
                default:      stateValue = 0; break;
            }
            editor.putInt(KEY_TRACKING_STATE, stateValue);
            editor.putFloat(KEY_TOTAL_DISTANCE, totalDistance);
            editor.putLong(KEY_ACCUMULATED_TIME, accumulatedActiveTime);
            editor.commit(); // use commit for immediate saving

            Log.d(TAG, "saveSessionData: stateValue=" + stateValue
                    + ", totalDistance=" + totalDistance
                    + ", accumulatedActiveTime=" + accumulatedActiveTime);
        } catch (Exception e) {
            Log.e(TAG, "Error saving session data: " + e.getMessage());
        }
    }

    private void loadSessionData() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int stateValue = prefs.getInt(KEY_TRACKING_STATE, 0);

            if (stateValue == 1) {
                trackingState = TrackingState.RUNNING;
                buttonStartStop.setText("PAUSE");
            } else if (stateValue == 2) {
                trackingState = TrackingState.PAUSED;
                buttonStartStop.setText("RESUME");
            } else {
                trackingState = TrackingState.STOPPED;
                buttonStartStop.setText("START");
            }

            totalDistance = prefs.getFloat(KEY_TOTAL_DISTANCE, 0f);
            accumulatedActiveTime = prefs.getLong(KEY_ACCUMULATED_TIME, 0L);

            double distanceKm = totalDistance / 1000.0;
            int realStepCount = (int) (distanceKm * STEPS_PER_KM);
            textStepsValue.setText(String.valueOf(realStepCount));
            progressSteps.setProgress(Math.min(realStepCount, GOAL_STEPS));

            int totalSec = (int) (accumulatedActiveTime / 1000);
            int minutes = totalSec / 60;
            int seconds = totalSec % 60;
            textDistanceValue.setText(String.format(Locale.getDefault(), "%.2f", distanceKm));
            textTimeValue.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));

            Log.d(TAG, "loadSessionData: stateValue=" + stateValue
                    + ", totalDistance=" + totalDistance
                    + ", accumulatedActiveTime=" + accumulatedActiveTime);
        } catch (Exception e) {
            Log.e(TAG, "Error loading session data: " + e.getMessage());
        }
    }

    private void clearSessionData() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(KEY_TRACKING_STATE);
            editor.remove(KEY_TOTAL_DISTANCE);
            editor.remove(KEY_ACCUMULATED_TIME);
            editor.commit();

            totalDistance = 0;
            accumulatedActiveTime = 0;
            locations.clear();
            isBadgePopupShown = false;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing session data: " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSessionData();
        try {
            unregisterReceiver(trackingUpdateReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver: " + e.getMessage());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        checkAndResetDataIfNewDay();
        // Re-fetch Firestore doc so even if local data is cleared, we restore it
        initializeDailyRecord();
        refreshMap();
        try {
            IntentFilter filter = new IntentFilter("com.example.walktracker.TRACKING_UPDATE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(trackingUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(trackingUpdateReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error registering receiver on resume: " + e.getMessage());
        }
    }

    private void startTrackingService() {
        try {
            Intent serviceIntent = new Intent(this, TrackingService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error starting TrackingService: " + e.getMessage());
        }
    }

    private void stopTrackingService() {
        try {
            stopService(new Intent(this, TrackingService.class));
        } catch (Exception e) {
            Log.e(TAG, "Error stopping TrackingService: " + e.getMessage());
        }
    }
}
