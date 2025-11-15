package com.example.promusic;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaylistsActivity extends AppCompatActivity {
    private static final String TAG = "PlaylistsActivity";
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isFinishingActivity = false;

    private static final int[][] CARD_BACKGROUNDS = {
            {R.id.recently_added_card, R.drawable.gradient_dark_yellow},
            {R.id.recently_played_card, R.drawable.gradient_dark_blue},
            {R.id.frequently_played_card, R.drawable.gradient_dark_green},
            {R.id.favorites_card, R.drawable.gradient_dark_red}
    };

    private final Map<Integer, Integer> backgroundMap = new HashMap<>();
    private List<Integer> lastCounts = new ArrayList<>();


    private boolean forceUpdateCards = false;

    private final BroadcastReceiver favoritesChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "favoritesChangedReceiver: obtained broadcast, action=" + intent.getAction());
            if ("com.example.promusic.ACTION_FAVORITES_CHANGED".equals(intent.getAction())) {
                forceUpdateCards = true;
                updateFavoritesCard();
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        long startTime = System.nanoTime();
        Log.d(TAG, "onResume: resume PlaylistsActivity");
        overridePendingTransition(0, 0);

        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        long lastUpdateTime = prefs.getLong("last_cards_update_time", 0);
        if (!forceUpdateCards && System.currentTimeMillis() - lastUpdateTime < 10_000) {
            Log.d(TAG, "onResume: skipping setupCards, latestData");
            updateCards(new ArrayList<>(List.of(
                    prefs.getInt("recently_added_count", 0),
                    prefs.getInt("recently_played_count", 0),
                    prefs.getInt("frequently_played_count", 0),
                    prefs.getInt("favorites_count", 0)
            )));
            return;
        }

        forceUpdateCards = false;
        debugSharedPreferences();
        setupCards();

        Log.d(TAG, "onResume: completed, time=" + (System.nanoTime() - startTime) / 1_000_000 + "ms");
    }


    private final BroadcastReceiver playlistUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "playlistUpdatedReceiver: obtained broadcast, action=" + intent.getAction());
            if ("com.example.promusic.ACTION_PLAYLIST_UPDATED".equals(intent.getAction())) {
                debugSharedPreferences();
                setupCards();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: launching PlaylistsActivity");
        overridePendingTransition(0, 0);
        setContentView(R.layout.activity_playlists);


        for (int[] mapping : CARD_BACKGROUNDS) {
            backgroundMap.put(mapping[0], mapping[1]);
        }
        Log.d(TAG, "onCreate: backgroundMapInitializedSize=" + backgroundMap.size());


        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            toolbar.setNavigationOnClickListener(v -> {
                Log.d(TAG, "onCreate: backButtonPressed");
                navigateToMainActivity();
            });
        } else {
            Log.e(TAG, "onCreate: Toolbar notFound");
        }

        setupPermissionLauncher();
        checkPermissions();


        IntentFilter favoritesFilter = new IntentFilter("com.example.promusic.ACTION_FAVORITES_CHANGED");
        IntentFilter playlistFilter = new IntentFilter("com.example.promusic.ACTION_PLAYLIST_UPDATED");
        try {
            ContextCompat.registerReceiver(this, favoritesChangedReceiver, favoritesFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "onCreate: registered favoritesChangedReceiver с RECEIVER_NOT_EXPORTED");
            ContextCompat.registerReceiver(this, playlistUpdatedReceiver, playlistFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "onCreate: registered playlistUpdatedReceiver с RECEIVER_NOT_EXPORTED");
        } catch (Exception e) {
            Log.e(TAG, "onCreate: registrationError BroadcastReceiver", e);
        }


        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.d(TAG, "handleOnBackPressed: theSystemBackButtonIsPressed");
                navigateToMainActivity();
            }
        });


        setupCards();
    }

    private void navigateToMainActivity() {
        long startTime = System.nanoTime();
        Log.d(TAG, "navigateToMainActivity: beginningOfTheTransition к MainActivity");
        if (isFinishingActivity) {
            Log.w(TAG, "navigateToMainActivity: alreadyCompletionIgnore");
            return;
        }
        isFinishingActivity = true;

        Intent intent = new Intent(PlaylistsActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.putExtra("fragment_position", 0);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();

        Log.d(TAG, "navigateToMainActivity: theTransitionIsCompleteTime=" + (System.nanoTime() - startTime) / 1_000_000 + "ms");
    }

    private void setupPermissionLauncher() {
        Log.d(TAG, "setupPermissionLauncher: initialization");
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    Log.d(TAG, "setupPermissionLauncher: resolutionResult=" + isGranted);
                    if (isGranted) {
                        setupCards();
                    } else {
                        showPermissionDeniedDialog();
                        setupCards();
                    }
                }
        );
    }

    private void showPermissionDeniedDialog() {
        Log.d(TAG, "showPermissionDeniedDialog: dialogDisplay");
        new AlertDialog.Builder(this)
                .setTitle("accessDenied")
                .setMessage("Without permission, the number of tracks is not shown. Open Settings?")
                .setPositiveButton("settings", (dialog, which) -> {
                    Log.d(TAG, "showPermissionDeniedDialog: theSettingsButtonIsClicked");
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("cancel", (dialog, which) -> {
                    Log.d(TAG, "showPermissionDeniedDialog: theCancelButtonIsClicked");
                    Toast.makeText(this, "trackCountNotUpdated", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void checkPermissions() {
        Log.d(TAG, "checkPermissions: checkingPermissions");
        String permission;

        if (Build.VERSION.SDK_INT >= 33) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        Log.d(TAG, "checkPermissions: resolution=" + permission);
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "checkPermissions: permissionGranted");
            setupCards();
        } else {
            Log.d(TAG, "checkPermissions: requestingPermission");
            requestPermissionLauncher.launch(permission);
        }
    }

    private void setupCards() {
        long startTime = System.nanoTime();
        Log.d(TAG, "setupCards: startingABackgroundTask");

        executor.execute(() -> {
            Log.d(TAG, "setupCards: runningOnABackgroundThread");
            SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            long lastUpdateTime = prefs.getLong("last_cards_update_time", 0);
            boolean forceUpdate = System.currentTimeMillis() - lastUpdateTime > 30_000;

            int recentlyAddedCount = prefs.getInt("recently_added_count", -1);
            int recentlyPlayedCount = prefs.getInt("recently_played_count", -1);
            int frequentlyPlayedCount = prefs.getInt("frequently_played_count", -1);
            int favoritesCount = prefs.getInt("favorites_count", -1);

            if (forceUpdate || recentlyAddedCount == -1 || recentlyPlayedCount == -1 ||
                    frequentlyPlayedCount == -1 || favoritesCount == -1) {
                Log.d(TAG, "setupCards: completeDataUpdate");
                recentlyAddedCount = getRecentlyAddedCount();
                recentlyPlayedCount = getRecentlyPlayedCount();
                frequentlyPlayedCount = getFrequentlyPlayedCount();
                favoritesCount = getFavoritesCount();

                editor.putInt("recently_added_count", recentlyAddedCount);
                editor.putInt("recently_played_count", recentlyPlayedCount);
                editor.putInt("frequently_played_count", frequentlyPlayedCount);
                editor.putInt("favorites_count", favoritesCount);
                editor.putLong("last_cards_update_time", System.currentTimeMillis());
                editor.apply();
            } else {
                Log.d(TAG, "setupCards: usingCachedData");
            }

            List<Integer> newCounts = new ArrayList<>(List.of(recentlyAddedCount, recentlyPlayedCount, frequentlyPlayedCount, favoritesCount));
            updateCards(newCounts);

            Log.d(TAG, "setupCards: completedTime=" + (System.nanoTime() - startTime) / 1_000_000 + "ms");
        });
    }



    private void updateFavoritesCard() {
        Log.d(TAG, "updateFavoritesCard: updateYourFavoritesCard");
        executor.execute(() -> {
            SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
            int favoritesCount = prefs.getInt("favorites_count", 0);
            List<Integer> newCounts = new ArrayList<>(lastCounts);
            newCounts.set(3, favoritesCount);
            updateCards(newCounts);
            Log.d(TAG, "updateFavoritesCard: favorites_count=" + favoritesCount);
        });
    }

    private void updateCards(List<Integer> newCounts) {
        if (newCounts == null || newCounts.size() != 4) {
            Log.w(TAG, "updateCards:notEnoughDataToUpdate (" + (newCounts == null ? "null" : newCounts.size()) + ")");
            return;
        }

        handler.post(() -> {
            if (isFinishing()) {
                Log.w(TAG, "updateCards: activityEndsSkipTheUpdate UI");
                return;
            }
            Log.d(TAG, "updateCards:update UI, counts=" + newCounts);
            setupCard(R.id.recently_added_card, R.id.recently_added_text, R.id.recently_added_tracks_text,
                    R.plurals.recently_added_tracks, newCounts.get(0), RecentlyAddedActivity.class);
            setupCard(R.id.recently_played_card, R.id.recently_played_text, R.id.recently_played_tracks_text,
                    R.plurals.recently_played_tracks, newCounts.get(1), RecentlyPlayedActivity.class);
            setupCard(R.id.frequently_played_card, R.id.frequently_played_text, R.id.frequently_played_tracks_text,
                    R.plurals.frequently_played_tracks, newCounts.get(2), FrequentlyPlayedActivity.class);
            setupCard(R.id.favorites_card, R.id.favorites_text, R.id.favorites_tracks_text,
                    R.plurals.favorites_tracks, newCounts.get(3), FavoritesActivity.class);
            Log.d(TAG, "updateCards: UI updated");
            lastCounts = new ArrayList<>(newCounts);
        });
    }

    private void setupCard(int cardId, int titleTextId, int textId, int pluralId, int quantity, Class<?> activityClass) {
        Log.d(TAG, "setupCard: CardId=" + cardId + ", quantity=" + quantity);
        CardView card = findViewById(cardId);
        TextView titleTextView = findViewById(titleTextId);
        TextView textView = findViewById(textId);
        String tag = getResources().getResourceEntryName(cardId);

        if (card != null) {
            Integer backgroundResId = backgroundMap.get(cardId);
            int resId = backgroundResId != null ? backgroundResId : R.drawable.gradient_dark_yellow;
            card.setBackground(ContextCompat.getDrawable(this, resId));
            Log.d(TAG, "setupCard: backgroundSetFor " + tag + ", resId=" + resId);

            card.setOnClickListener(v -> {
                Log.d(TAG, "setupCard: theCardIsPressedStart " + activityClass.getSimpleName());
                Intent intent = new Intent(this, activityClass);
                startActivity(intent);
                overridePendingTransition(0, 0);
            });
        } else {
            Log.e(TAG, "setupCard: " + tag + " notFound");
        }

        if (titleTextView != null) {
            Log.d(TAG, "setupCard: theHeaderIsSetTo" + tag);
        } else {
            Log.e(TAG, "setupCard: headerView " + titleTextId + " notFound");
        }

        if (textView != null) {
            String trackCountText = getResources().getQuantityString(pluralId, quantity, quantity);
            textView.setText(trackCountText);
            Log.d(TAG, "setupCard: setTheNumberOfTracksFor " + tag + ": " + trackCountText);
        } else {
            Log.e(TAG, "setupCard: textType " + textId + " notFound");
        }
    }


    private int countValidSongs(Set<String> songIds) {
        if (songIds == null || songIds.isEmpty()) {
            Log.d(TAG, "countValidSongs: emptyListID");
            return 0;
        }


        Set<String> validIds = new HashSet<>();
        for (String id : songIds) {
            if (id != null && id.matches("\\d+")) {
                validIds.add(id);
            } else {
                Log.w(TAG, "countValidSongs: invalidSongID: " + id);
            }
        }

        if (validIds.isEmpty()) {
            Log.d(TAG, "countValidSongs: noValidIDsAfterFiltering");
            return 0;
        }

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Audio.Media._ID};
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media._ID + " IN (" + String.join(",", Collections.nCopies(validIds.size(), "?")) + ")";
        String[] selectionArgs = validIds.toArray(new String[0]);

        try (Cursor cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null)) {
            int count = cursor != null ? cursor.getCount() : 0;
            Log.d(TAG, "countValidSongs: found " + count + " tracksFor ID: " + validIds);
            return count;
        } catch (SecurityException e) {
            Log.e(TAG, "countValidSongs: accessTo MediaStore", e);
            handler.post(() -> Toast.makeText(this, "mediaPermissionRequired", Toast.LENGTH_SHORT).show());
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "countValidSongs: requestError MediaStore", e);
            return 0;
        }
    }


    private void cleanInvalidSongIds(String prefix, Set<String> songIds) {
        if (songIds == null || songIds.isEmpty()) {
            return;
        }

        Set<String> validIds = new HashSet<>();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Audio.Media._ID};
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media._ID + " IN (" + String.join(",", Collections.nCopies(songIds.size(), "?")) + ")";
        String[] selectionArgs = songIds.toArray(new String[0]);

        try (Cursor cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    validIds.add(String.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanInvalidSongIds: mediastoreRequestErrorFor " + prefix, e);
            return;
        }

        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (String id : songIds) {
            if (!validIds.contains(id)) {
                editor.remove(prefix + id);
                Log.d(TAG, "cleanInvalidSongIds: removedObsoleteKey" + prefix + id);
            }
        }
        if (prefix.equals("favorites")) {
            editor.putStringSet("favorites", validIds);
        }
        editor.apply();
    }

    private int getRecentlyAddedCount() {
        Log.d(TAG, "getRecentlyAddedCount: mediaInquiry");
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Audio.Media._ID};
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media.DATE_ADDED + " > ?";
        String[] selectionArgs = {String.valueOf((System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) / 1000)};
        try (Cursor cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null)) {
            int count = cursor != null ? cursor.getCount() : 0;
            Log.d(TAG, "getRecentlyAddedCount: found " + count + " tracks");
            return count;
        } catch (SecurityException e) {
            Log.e(TAG, "getRecentlyAddedCount: accessToMediaStoreDenied", e);
            handler.post(() -> Toast.makeText(this, "mediaPermissionRequired", Toast.LENGTH_SHORT).show());
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "getRecentlyAddedCount: requestError MediaStore", e);
            return 0;
        }
    }

    private int getRecentlyPlayedCount() {
        Log.d(TAG, "getRecentlyPlayedCount: calculationBy last_played_* в SharedPreferences");
        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        Set<String> playedSongIds = new HashSet<>();

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("last_played_")) {
                String songId = key.replace("last_played_", "");
                if (songId.matches("\\d+")) {
                    playedSongIds.add(songId);
                }
            }
        }

        Log.d(TAG, "getRecentlyPlayedCount: foundLast_played_For " + playedSongIds.size() + " tracks");

        if (playedSongIds.isEmpty()) {
            return 0;
        }


        cleanInvalidSongIds("last_played_", playedSongIds);
        cleanInvalidSongIds("play_count_", playedSongIds);


        return countValidSongs(playedSongIds);
    }

    private int getFrequentlyPlayedCount() {
        Log.d(TAG, "getFrequentlyPlayedCount: requestSettings");
        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        Set<String> frequentSongIds = new HashSet<>();

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("play_count_")) {
                try {
                    Object value = entry.getValue();
                    int playCount = value instanceof Integer ? (Integer) value : Integer.parseInt((String) value);
                    if (playCount >= 5) {
                        String songId = entry.getKey().replace("play_count_", "");
                        if (songId.matches("\\d+")) {
                            frequentSongIds.add(songId);
                        }
                    }
                } catch (NumberFormatException | ClassCastException e) {
                    Log.w(TAG, "getFrequentlyPlayedCount: invalidNumberOfPlaysFor " + entry.getKey(), e);
                }
            }
        }

        Log.d(TAG, "getFrequentlyPlayedCount: frequentSongIds=" + frequentSongIds);
        if (frequentSongIds.isEmpty()) {
            return 0;
        }


        cleanInvalidSongIds("play_count_", frequentSongIds);
        cleanInvalidSongIds("last_played_", frequentSongIds);


        return countValidSongs(frequentSongIds);
    }

    private int getFavoritesCount() {
        Log.d(TAG, "getFavoritesCount:requestSettings");
        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        Set<String> favoriteSongIds = new HashSet<>(prefs.getStringSet("favorites", new HashSet<>()));

        Log.d(TAG, "getFavoritesCount: favoriteSongIds=" + favoriteSongIds);
        if (favoriteSongIds.isEmpty()) {
            Log.d(TAG, "getFavoritesCount: favoriteSongIds is empty");
            return 0;
        }


        cleanInvalidSongIds("favorites", favoriteSongIds);

        int count = countValidSongs(favoriteSongIds);
        Log.d(TAG, "getFavoritesCount: Valid songs count=" + count);
        return count;
    }

    private void debugSharedPreferences() {
        SharedPreferences prefs = getSharedPreferences("PlaybackPrefs", MODE_PRIVATE);
        Map<String, ?> allPrefs = prefs.getAll();
        StringBuilder sb = new StringBuilder();
        sb.append("debugSharedPreferences: content={\n");
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            sb.append("  ").append(key).append("=").append(value);
            if (key.startsWith("play_count_") || key.startsWith("last_played_") || key.equals("favorites")) {
                sb.append(" (playlistMeaning)");
            }
            sb.append("\n");
        }
        sb.append("}");
        Log.d(TAG, sb.toString());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "onOptionsItemSelected: element=" + item.getItemId());
        if (item.getItemId() == android.R.id.home) {
            Log.d(TAG, "onOptionsItemSelected: theHomeButtonIsPressed");
            navigateToMainActivity();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: pause");
        overridePendingTransition(0, 0);
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: cleaningStarts");
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(favoritesChangedReceiver);
            unregisterReceiver(playlistUpdatedReceiver);
        } catch (Exception e) {
            Log.e(TAG, "onDestroy: errorWhenUnregisteringReceivers", e);
        }
        Log.d(TAG, "onDestroy: Executor and HandlerCleaned");
    }
}