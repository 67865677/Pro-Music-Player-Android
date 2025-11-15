package com.example.promusic;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;public class SongsFragment extends Fragment implements LocalSongAdapter.OnSongInteractionListener {
    private static final String TAG = "SongsFragment";
    private RecyclerView recyclerView;
    private View emptyStateView;
    private LocalSongAdapter adapter;
    private final List<Song> songList = new ArrayList<>();
    private boolean isFavorites = false;
    private boolean isRecentlyAdded = false;
    private boolean isRecentlyPlayed = false;
    private boolean isFrequentlyPlayed = false;
    private boolean isLongClickInProgress = false;
    private CustomActionModeListener actionModeListener;
    private ActivityResultLauncher<IntentSenderRequest> deleteLauncher;
    private BroadcastReceiver mediaScanReceiver;

    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof CustomActionModeListener) {
            actionModeListener = (CustomActionModeListener) context;
        } else {
            Log.e(TAG, "onAttach: Context must implement CustomActionModeListener");
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: Creating view");
        View view = inflater.inflate(R.layout.fragment_songs, container, false);
        recyclerView = view.findViewById(R.id.recycler_view_songs);
        emptyStateView = view.findViewById(R.id.empty_state_container);


        Bundle args = getArguments();
        if (args != null) {
            isFavorites = args.getBoolean("isFavorites", false);
            isRecentlyAdded = args.getBoolean("isRecentlyAdded", false);
            isRecentlyPlayed = args.getBoolean("isRecentlyPlayed", false);
            isFrequentlyPlayed = args.getBoolean("isFrequentlyPlayed", false);
            Log.d(TAG, "onCreateView: isFavorites=" + isFavorites + ", isRecentlyAdded=" + isRecentlyAdded +
                    ", isRecentlyPlayed=" + isRecentlyPlayed + ", isFrequentlyPlayed=" + isFrequentlyPlayed);
        }


        recyclerView.setLayoutManager(new LocalSongAdapter.CustomLinearLayoutManager(requireContext()));
        adapter = new LocalSongAdapter(songList, this);
        recyclerView.setAdapter(adapter);


        deleteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Toast.makeText(requireContext(), "songsRemoved", Toast.LENGTH_SHORT).show();
                        loadSongs();
                        clearSelection();
                    } else {
                        Toast.makeText(requireContext(), "deletionCanceled", Toast.LENGTH_SHORT).show();
                    }
                }
        );


        mediaScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "mediaScanReceiver: obtained broadcast, action=" + intent.getAction());
                if (Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(intent.getAction())) {
                    Log.d(TAG, "mediaScanReceiver: mediaScannerCompleted workUpdatingTheListOfSongs");
                    refreshSongList();
                }
            }
        };
        IntentFilter mediaScanFilter = new IntentFilter(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        try {
            ContextCompat.registerReceiver(requireContext(), mediaScanReceiver, mediaScanFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "onCreateView:registered mediaScanReceiver с RECEIVER_NOT_EXPORTED");
        } catch (Exception e) {
            Log.e(TAG, "onCreateView: registrationError mediaScanReceiver", e);
            Toast.makeText(requireContext(), "mediaScannerRegistrationError", Toast.LENGTH_SHORT).show();
        }


        adapter.preloadImages(requireContext());


        checkPermissionsAndLoadSongs();

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (mediaScanReceiver != null) {
            try {
                requireContext().unregisterReceiver(mediaScanReceiver);
                Log.d(TAG, "onDestroyView: cancellationOfRegistration mediaScanReceiver");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "onDestroyView: errorWhenCancelingRegistration mediaScanReceiver", e);
            }
        }
    }

    private void checkPermissionsAndLoadSongs() {
        Log.d(TAG, "checkPermissionsAndLoadSongs: Checking permissions");

        String permission;
        if (Build.VERSION.SDK_INT >= 33) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            loadSongs();
        } else {
            Log.w(TAG, "checkPermissionsAndLoadSongs: Permission not granted");
            showEmptyState();
        }
    }

    private void loadSongs() {
        Log.d(TAG, "loadSongs: Loading songs for playlist, current songList size=" + songList.size());
        songList.clear();
        Log.d(TAG, "loadSongs: Cleared songList");
        List<Song> loadedSongs;
        if (isFavorites) {
            loadedSongs = loadFavoritesSongs();
        } else if (isRecentlyAdded) {
            loadedSongs = loadRecentlyAddedSongs();
        } else if (isRecentlyPlayed) {
            loadedSongs = loadRecentlyPlayedSongs();
        } else if (isFrequentlyPlayed) {
            loadedSongs = loadFrequentlyPlayedSongs();
        } else {
            loadedSongs = loadAllSongs();
        }
        songList.addAll(loadedSongs);
        Log.d(TAG, "loadSongs: Loaded " + loadedSongs.size() + " songs, new songList size=" + songList.size());
        for (Song song : songList) {
            Log.d(TAG, "loadSongs: Song in list: " + song.getTitle() + ", ID=" + song.getId() + ", path=" + song.getData());
        }
        updateUI();
    }

    private void updateUI() {
        Log.d(TAG, "updateUI: Updating UI, songList size=" + songList.size());
        if (songList.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyStateView.setVisibility(View.VISIBLE);
            adapter.setActionMode(false);
            Log.d(TAG, "updateUI: Showing empty state");
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyStateView.setVisibility(View.GONE);
            Log.d(TAG, "updateUI: Updating adapter with " + songList.size() + " songs");
            adapter.updateSongs(songList);
            adapter.setActionMode(isLongClickInProgress);
            Log.d(TAG, "updateUI: Adapter updated, isActionMode=" + isLongClickInProgress);
        }


        if (isLongClickInProgress && actionModeListener != null) {
            actionModeListener.showCustomActionMode(getSelectedSongs().size());
            Log.d(TAG, "updateUI: Updated ActionMode with " + getSelectedSongs().size() + " selected songs");
        }
    }


    private void showEmptyState() {
        Log.d(TAG, "showEmptyState: Showing empty state");
        recyclerView.setVisibility(View.GONE);
        emptyStateView.setVisibility(View.VISIBLE);
        adapter.setActionMode(false);
    }

    private List<Song> loadAllSongs() {
        List<Song> songs = new ArrayList<>();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        try (Cursor cursor = requireContext().getContentResolver().query(uri, projection, selection, null, MediaStore.Audio.Media.TITLE)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    Song song = createSongFromCursor(cursor);
                    File file = new File(song.getData());
                    if (file.exists() && file.canRead()) {
                        songs.add(song);
                        Log.d(TAG, "loadAllSongs: Added song " + song.getTitle() + ", path=" + song.getData());
                    } else {
                        Log.w(TAG, "loadAllSongs: Skipping song " + song.getTitle() + ", file not found or inaccessible: " + song.getData());
                    }
                }
                Log.d(TAG, "loadAllSongs: Loaded " + songs.size() + " songs");
            } else {
                Log.w(TAG, "loadAllSongs: Cursor is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "loadAllSongs: Error loading songs", e);
            Toast.makeText(requireContext(), "errorLoadingSongs", Toast.LENGTH_SHORT).show();
        }
        return songs;
    }

    private List<Song> loadRecentlyAddedSongs() {
        List<Song> songs = new ArrayList<>();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media.DATE_ADDED + " > ?";
        String[] selectionArgs = {String.valueOf((System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) / 1000)};
        try (Cursor cursor = requireContext().getContentResolver().query(uri, projection, selection, selectionArgs, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    Song song = createSongFromCursor(cursor);
                    File file = new File(song.getData());
                    if (file.exists() && file.canRead()) {
                        songs.add(song);
                        Log.d(TAG, "loadRecentlyAddedSongs: Added song " + song.getTitle() + ", path=" + song.getData());
                    } else {
                        Log.w(TAG, "loadRecentlyAddedSongs: Skipping song " + song.getTitle() + ", file not found or inaccessible: " + song.getData());
                    }
                }
                Log.d(TAG, "loadRecentlyAddedSongs: Loaded " + songs.size() + " songs");
            } else {
                Log.w(TAG, "loadRecentlyAddedSongs: Cursor is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "loadRecentlyAddedSongs: Error loading recently added", e);
            Toast.makeText(requireContext(), "errorLoadingRecentlyAddedSongs", Toast.LENGTH_SHORT).show();
        }
        return songs;
    }

    private List<Song> loadFavoritesSongs() {
        List<Song> songs = new ArrayList<>();
        SharedPreferences prefs = requireContext().getSharedPreferences("PlaybackPrefs", Context.MODE_PRIVATE);
        Set<String> favoriteSongIds = new HashSet<>(prefs.getStringSet("favorites", new HashSet<>()));
        if (favoriteSongIds.isEmpty()) {
            Log.d(TAG, "loadFavoritesSongs: Favorites is empty");
            return songs;
        }

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media._ID + " IN (" + String.join(",", Collections.nCopies(favoriteSongIds.size(), "?")) + ")";
        String[] selectionArgs = favoriteSongIds.toArray(new String[0]);

        try (Cursor cursor = requireContext().getContentResolver().query(uri, projection, selection, selectionArgs, MediaStore.Audio.Media.TITLE)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    Song song = createSongFromCursor(cursor);
                    songs.add(song);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loadFavoritesSongs: Error loading favorites", e);
        }
        Log.d(TAG, "loadFavoritesSongs: Loaded " + songs.size() + " songs");
        return songs;
    }

    private List<Song> loadRecentlyPlayedSongs() {
        List<Song> songs = new ArrayList<>();
        SharedPreferences prefs = requireContext().getSharedPreferences("PlaybackPrefs", Context.MODE_PRIVATE);
        Set<String> playedSongIds = new HashSet<>();
        Map<String, Long> lastPlayedMap = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith("last_played_")) {
                String songId = entry.getKey().replace("last_played_", "");
                if (songId.matches("\\d+")) {
                    playedSongIds.add(songId);
                    lastPlayedMap.put(songId, (Long) entry.getValue());
                }
            }
        }

        if (playedSongIds.isEmpty()) {
            Log.d(TAG, "loadRecentlyPlayedSongs: Recently played is empty");
            return songs;
        }

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media._ID + " IN (" + String.join(",", Collections.nCopies(playedSongIds.size(), "?")) + ")";
        String[] selectionArgs = playedSongIds.toArray(new String[0]);

        try (Cursor cursor = requireContext().getContentResolver().query(uri, projection, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    Song song = createSongFromCursor(cursor);
                    String songId = String.valueOf(song.getId());
                    Long lastPlayed = lastPlayedMap.get(songId);
                    if (lastPlayed != null) {
                        song.setLastPlayed(lastPlayed);
                    }
                    songs.add(song);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loadRecentlyPlayedSongs: Error loading recently played", e);
        }


        songs.sort((s1, s2) -> Long.compare(s2.getLastPlayed(), s1.getLastPlayed()));
        Log.d(TAG, "loadRecentlyPlayedSongs: Loaded " + songs.size() + " songs");
        return songs;
    }

    private List<Song> loadFrequentlyPlayedSongs() {
        List<Song> songs = new ArrayList<>();
        SharedPreferences prefs = requireContext().getSharedPreferences("PlaybackPrefs", Context.MODE_PRIVATE);
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
                    Log.w(TAG, "loadFrequentlyPlayedSongs: Invalid play count for " + entry.getKey(), e);
                }
            }
        }

        if (frequentSongIds.isEmpty()) {
            Log.d(TAG, "loadFrequentlyPlayedSongs: Frequently played is empty");
            return songs;
        }

        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                MediaStore.Audio.Media._ID + " IN (" + String.join(",", Collections.nCopies(frequentSongIds.size(), "?")) + ")";
        String[] selectionArgs = frequentSongIds.toArray(new String[0]);

        try (Cursor cursor = requireContext().getContentResolver().query(uri, projection, selection, selectionArgs, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    Song song = createSongFromCursor(cursor);
                    songs.add(song);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loadFrequentlyPlayedSongs: Error loading frequently played", e);
        }
        Log.d(TAG, "loadFrequentlyPlayedSongs: Loaded " + songs.size() + " songs");
        return songs;
    }

    private Song createSongFromCursor(Cursor cursor) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
        String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
        String artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
        String data = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
        long albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
        long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
        Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
        Uri albumArtUri = Uri.parse("content://media/external/audio/albumart/" + albumId);
        return new Song(id, title, artist, uri, albumArtUri, duration, data);
    }

    @Override
    public void onSongClick(Song song) {
        if (isLongClickInProgress) {
            toggleSelection(song, adapter.getSongs().indexOf(song));
        } else {

            Intent intent = new Intent(requireContext(), MusicPlayerActivity.class);
            long[] songIds = new long[songList.size()];
            for (int i = 0; i < songList.size(); i++) {
                songIds[i] = songList.get(i).getId();
            }
            intent.putExtra("song_ids", songIds);
            intent.putExtra("current_index", songList.indexOf(song));
            startActivity(intent);
            Log.d(TAG, "onSongClick: Starting MusicPlayerActivity for " + song.getTitle() + ", index=" + songList.indexOf(song));
        }
    }

    @Override
    public void onSongLongClick(Song song, int position) {
        try {
            Log.d(TAG, "onSongLongClick: Starting long click for song = " + song.getTitle() + ", position = " + position);
            if (isLongClickInProgress) {
                Log.d(TAG, "onSongLongClick: Ignored due to ongoing long click processing");
                return;
            }
            isLongClickInProgress = true;

            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (!(holder instanceof LocalSongAdapter.SongViewHolder)) {
                Log.w(TAG, "onSongLongClick: Invalid or null ViewHolder for position = " + position);
                isLongClickInProgress = false;
                return;
            }

            boolean isSelected = !song.isSelected();
            song.setSelected(isSelected);
            adapter.notifyItemChanged(position, "selection_changed");

            ((LocalSongAdapter.SongViewHolder) holder).toggleSelection();
            int selectedCount = getSelectedSongs().size();
            Log.d(TAG, "onSongLongClick: Updated selectedCount to " + selectedCount);

            if (selectedCount > 0 && actionModeListener != null) {
                Log.d(TAG, "onSongLongClick: Activating custom action mode for selectedCount = " + selectedCount);
                adapter.setActionMode(true);
                actionModeListener.showCustomActionMode(selectedCount);
            }

            isLongClickInProgress = false;
            Log.d(TAG, "onSongLongClick: Toggled selection for " + song.getTitle() + ", isSelected = " + isSelected);
        } catch (Exception e) {
            Log.e(TAG, "Error in onSongLongClick: " + e.getMessage(), e);
            isLongClickInProgress = false;
            Toast.makeText(requireContext(), "errorWhenLongPressing", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onSongToggleSelection(Song song, int position) {
        try {
            Log.d(TAG, "onSongToggleSelection: Toggling selection for song = " + song.getTitle() + ", position = " + position);
            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (!(holder instanceof LocalSongAdapter.SongViewHolder)) {
                Log.w(TAG, "onSongToggleSelection: Invalid or null ViewHolder for position = " + position);
                return;
            }

            boolean isSelected = !song.isSelected();
            song.setSelected(isSelected);
            adapter.notifyItemChanged(position, "selection_changed");

            ((LocalSongAdapter.SongViewHolder) holder).toggleSelection();
            int selectedCount = getSelectedSongs().size();
            Log.d(TAG, "onSongToggleSelection: Updated selectedCount to " + selectedCount);

            if (selectedCount == 0 && actionModeListener != null) {
                Log.d(TAG, "onSongToggleSelection: Hiding custom action mode");
                adapter.setActionMode(false);
                actionModeListener.hideCustomActionMode();
            }

            Log.d(TAG, "onSongToggleSelection: Toggled selection for " + song.getTitle() + ", isSelected = " + isSelected);
        } catch (Exception e) {
            Log.e(TAG, "Error in onSongToggleSelection: " + e.getMessage(), e);
            Toast.makeText(requireContext(), "mistakeWhenChoosingASong", Toast.LENGTH_SHORT).show();
        }
    }

    public void deleteSelectedSongs() {
        Log.d(TAG, "deleteSelectedSongs: Initiating deletion process");
        List<Song> selected = getSelectedSongs();
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), "songsNotSelected", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "deleteSelectedSongs: No songs selected");
            return;
        }
        Log.d(TAG, "deleteSelectedSongs: Attempting to delete " + selected.size() + " songs");

        if (isFavorites) {
            SharedPreferences prefs = requireContext().getSharedPreferences("PlaybackPrefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            Set<String> favoriteSongIds = new HashSet<>(prefs.getStringSet("favorites", new HashSet<>()));
            for (Song song : selected) {
                favoriteSongIds.remove(String.valueOf(song.getId()));
                Log.d(TAG, "deleteSelectedSongs: Removed song ID=" + song.getId() + " from favorites");
            }
            editor.putStringSet("favorites", favoriteSongIds);
            editor.putInt("favorites_count", favoriteSongIds.size());
            editor.apply();
            Intent intent = new Intent("com.example.promusic.ACTION_FAVORITES_CHANGED");
            requireContext().sendBroadcast(intent);
            Log.d(TAG, "deleteSelectedSongs: Removed " + selected.size() + " songs from favorites, favorites_count=" + favoriteSongIds.size());
            clearSelection();
            loadSongs();
            return;
        }


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "requiresRecordPermissionToDeleteSongs", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "deleteSelectedSongs: WRITE_EXTERNAL_STORAGE permission not granted");
            return;
        }

        List<Song> songsToDelete = new ArrayList<>(selected);
        new AlertDialog.Builder(requireContext())
                .setTitle("deleteSongs")
                .setMessage("doYouReallyWantToDelete " + songsToDelete.size() + " songs?")
                .setPositiveButton("Да", (dialog, which) -> {
                    performDelete(songsToDelete);
                    dialog.dismiss();
                })
                .setNegativeButton("Нет", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void performDelete(List<Song> songsToDelete) {
        Log.d(TAG, "performDelete: Starting deletion of " + songsToDelete.size() + " songs");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            List<Uri> urisToDelete = new ArrayList<>();
            for (Song song : songsToDelete) {
                urisToDelete.add(song.getUri());
            }
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(requireContext().getContentResolver(), urisToDelete);
                IntentSenderRequest request = new IntentSenderRequest.Builder(pi.getIntentSender()).build();
                deleteLauncher.launch(request);
                Log.d(TAG, "performDelete: Launched delete request for " + urisToDelete.size() + " URIs");
            } catch (SecurityException e) {
                Log.e(TAG, "performDelete: Permission error launching delete request", e);
                Toast.makeText(requireContext(), "noPermissionToDeleteSongs", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "performDelete: Error launching delete request", e);
                Toast.makeText(requireContext(), "errorDeletingSongs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            return;
        }

        int failedCount = 0;
        List<Integer> removedPositions = new ArrayList<>();
        for (Song song : new ArrayList<>(songsToDelete)) {
            try {
                int position = songList.indexOf(song);
                if (position == -1) {
                    Log.w(TAG, "performDelete: Song not found in songList: " + song.getTitle());
                    failedCount++;
                    continue;
                }
                int result = requireContext().getContentResolver().delete(song.getUri(), null, null);
                if (result > 0) {
                    songList.remove(position);
                    removedPositions.add(position);
                    Log.d(TAG, "performDelete: Successfully deleted song: " + song.getTitle());
                } else {
                    failedCount++;
                    Log.w(TAG, "performDelete: Failed to delete song: " + song.getTitle() + ", no rows affected");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "performDelete: Permission error deleting song: " + song.getTitle(), e);
                failedCount++;
                Toast.makeText(requireContext(), "missingPermissionToDelete: " + song.getTitle(), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "performDelete: Error deleting song: " + song.getTitle(), e);
                failedCount++;
            }
        }

        if (failedCount > 0) {
            Toast.makeText(requireContext(), "failedToUninstall " + failedCount + " songs", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "performDelete: Failed to delete " + failedCount + " songs");
        }

        clearSelection();
        updateUI();
        for (int position : removedPositions) {
            adapter.notifyItemRemoved(position);
        }
        if (!songList.isEmpty()) {
            adapter.notifyItemRangeChanged(0, songList.size(), "selection_cleared");
        }
        Log.d(TAG, "performDelete: Deletion completed, updated UI, songList size=" + songList.size());
    }

    private void toggleSelection(Song song, int position) {
        song.setSelected(!song.isSelected());
        adapter.notifyItemChanged(position, "selection_changed");
        if (actionModeListener != null) {
            actionModeListener.showCustomActionMode(getSelectedSongs().size());
        }
        if (getSelectedSongs().isEmpty()) {
            isLongClickInProgress = false;
            if (actionModeListener != null) {
                actionModeListener.hideCustomActionMode();
            }
        }
        Log.d(TAG, "toggleSelection: Song " + song.getTitle() + " selected=" + song.isSelected() + ", selectedCount=" + getSelectedSongs().size());
    }

    public List<Song> getSelectedSongs() {
        return adapter.getSelectedSongs();
    }

    public void selectAll() {
        for (Song song : songList) {
            song.setSelected(true);
        }
        adapter.setActionMode(true);
        adapter.notifyItemRangeChanged(0, songList.size(), "selection_changed");
        if (actionModeListener != null) {
            actionModeListener.showCustomActionMode(getSelectedSongs().size());
        }
        Log.d(TAG, "selectAll: Selected " + getSelectedSongs().size() + " songs");
    }

    public void clearSelection() {
        for (Song song : songList) {
            song.setSelected(false);
        }
        isLongClickInProgress = false;
        adapter.setActionMode(false);
        adapter.notifyItemRangeChanged(0, songList.size(), "selection_cleared");
        if (actionModeListener != null) {
            actionModeListener.hideCustomActionMode();
        }
        Log.d(TAG, "clearSelection: Selection cleared");
    }

    public void shareSelectedSongs() {
        List<Song> selectedSongs = getSelectedSongs();
        if (selectedSongs.isEmpty()) {
            Log.d(TAG, "shareSelectedSongs: No songs selected");
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.setType("audio/*");
        ArrayList<Uri> uris = new ArrayList<>();
        for (Song song : selectedSongs) {
            uris.add(song.getUri());
        }
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        startActivity(Intent.createChooser(shareIntent, "shareSongs"));
        Log.d(TAG, "shareSelectedSongs: Sharing " + selectedSongs.size() + " songs");
        clearSelection();
    }

    public boolean isLongClickInProgress() {
        return isLongClickInProgress;
    }

    public void refreshSongList() {
        Log.d(TAG, "refreshSongList: startingToUpdateTheListOfSongs");


        String permission;
        if (Build.VERSION.SDK_INT >= 33) {
            permission = Manifest.permission.READ_MEDIA_AUDIO;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "refreshSongList: mediaAccessPermissionNotGranted");
            Toast.makeText(requireContext(), "Разрешение не предоставлено", Toast.LENGTH_SHORT).show();
            checkPermissionsAndLoadSongs();
            return;
        }



        List<File> directoriesToScan = new ArrayList<>();
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (musicDir.exists() && musicDir.isDirectory()) {
            directoriesToScan.add(musicDir);
            Log.d(TAG, "refreshSongList: addedDirectory Music: " + musicDir.getAbsolutePath());
        }
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir.exists() && downloadsDir.isDirectory()) {
            directoriesToScan.add(downloadsDir);
            Log.d(TAG, "refreshSongList: addedDownloadsDirectory: " + downloadsDir.getAbsolutePath());
        }
        File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage.exists() && externalStorage.isDirectory()) {
            directoriesToScan.add(externalStorage);
            Log.d(TAG, "refreshSongList: addedRootDirectory: " + externalStorage.getAbsolutePath());
        }


        List<String> filePaths = new ArrayList<>();
        for (File dir : directoriesToScan) {
            collectAudioFiles(dir, filePaths);
        }

        if (filePaths.isEmpty()) {
            Log.w(TAG, "refreshSongList: audioFilesNotFoundInScannedDirectories");
            requireActivity().runOnUiThread(() -> {
                Toast.makeText(requireContext(), "noAudioFilesFound", Toast.LENGTH_SHORT).show();
                loadSongs();
            });
            return;
        }

        Log.d(TAG, "refreshSongList: found " + filePaths.size() + " audioFilesToScan: " + filePaths);


        final int[] scanCount = {0};
        final int totalFiles = filePaths.size();


        MediaScannerConnection.scanFile(requireContext(), filePaths.toArray(new String[0]), null, (path, uri) -> {
            synchronized (scanCount) {
                scanCount[0]++;
                Log.d(TAG, "refreshSongList: fileScanned, path=" + path + ", URI=" + uri +
                        ", completed " + scanCount[0] + "/" + totalFiles);


                if (scanCount[0] >= totalFiles) {
                    Log.d(TAG, "refreshSongList: allFilesScannedWaitingForSynchronization MediaStore");

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Log.d(TAG, "refreshSongList: theDelayIsCompleteUpdatingTheListOfSongs");
                        requireActivity().runOnUiThread(() -> {
                            loadSongs();
                            Toast.makeText(requireContext(), "songListUpdated", Toast.LENGTH_SHORT).show();
                        });
                    }, 1000);
                }
            }
        });


        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(musicDir));
        requireContext().sendBroadcast(mediaScanIntent);
        Log.d(TAG, "refreshSongList: A broadcast message was sent to scan the Music directory");
    }

    private void collectAudioFiles(File dir, List<String> filePaths) {
        if (dir == null || !dir.isDirectory()) {
            Log.w(TAG, "collectAudioFiles: invalidDirectory: " + (dir != null ? dir.getAbsolutePath() : "null"));
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "collectAudioFiles:couldNotGetFilesFromTheDirectory: " + dir.getAbsolutePath());
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                collectAudioFiles(file, filePaths);
            } else if (file.isFile()) {
                String lowerName = file.getName().toLowerCase();
                if (lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                        lowerName.endsWith(".aac") || lowerName.endsWith(".ogg")) {
                    if (file.exists() && file.canRead()) {
                        filePaths.add(file.getAbsolutePath());
                        Log.d(TAG, "collectAudioFiles: audioFileFound: " + file.getAbsolutePath());
                    } else {
                        Log.w(TAG, "collectAudioFiles: inaccessibleFileSkipped: " + file.getAbsolutePath());
                    }
                }
            }
        }
    }
}