package com.example.promusic;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.Fade;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

public class OnlineFragment extends Fragment implements OnlineSongAdapter.OnSongClickListener {

    private static final String TAG = "OnlineFragment";
    private static final long LOADING_TIMEOUT_MS = 10000;

    private FirebaseFirestore db;
    private SearchView searchView;
    private OnlineSongAdapter songAdapter;
    private List<OnlineSong> songList;
    private List<OnlineSong> filteredSongList;
    private ExoPlayer player;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Context mContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar progressBar;
    private OnlineSong currentSong;
    private Runnable loadingTimeoutRunnable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new Fade());
        setExitTransition(new Fade());
        db = FirebaseFirestore.getInstance();
        songList = new ArrayList<>();
        filteredSongList = new ArrayList<>();

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (mContext == null) {
                        Log.e(TAG, "onCreate: mContext is null during permission callback");
                        return;
                    }
                    if (isGranted) {
                        Log.d(TAG, "Permission granted for WRITE_EXTERNAL_STORAGE");
                        Toast.makeText(mContext, "permissionToRecordHasBeenObtained", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.e(TAG, "Permission denied for WRITE_EXTERNAL_STORAGE");
                        Toast.makeText(mContext, "writePermissionRejected", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_online, container, false);

        mContext = getContext();
        if (mContext == null) {
            Log.e(TAG, "onCreateView: Context is null");
            Toast.makeText(getActivity(), "contextIsNotAvailable", Toast.LENGTH_SHORT).show();
            return view;
        }

        player = new ExoPlayer.Builder(mContext).build();
        Log.d(TAG, "onCreateView: ExoPlayer initialized");

        progressBar = view.findViewById(R.id.progress_bar);

        searchView = view.findViewById(R.id.search_view);
        setupSearchView();

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view_songs);
        recyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        songAdapter = new OnlineSongAdapter(this);
        recyclerView.setAdapter(songAdapter);
        Log.d(TAG, "onCreateView: RecyclerView adapter set");

        loadSongs();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (currentSong != null) {
                    int position = filteredSongList.indexOf(currentSong);
                    if (position != -1) {
                        Log.d(TAG, "onPlaybackStateChanged: State = " + state + ", notifying position: " + position);
                        songAdapter.notifyItemChanged(position);
                    }
                    if (state == Player.STATE_ENDED) {
                        Log.d(TAG, "onPlaybackStateChanged: Song ended: " + currentSong.getTitle());
                        player.seekTo(0);
                        player.pause();
                        Toast.makeText(mContext, "songCompleted: " + currentSong.getTitle(), Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (currentSong != null) {
                    int position = filteredSongList.indexOf(currentSong);
                    if (position != -1) {
                        Log.d(TAG, "onIsPlayingChanged: isPlaying = " + isPlaying + ", notifying position: " + position);
                        songAdapter.notifyItemChanged(position);
                    }
                }
            }
        });

        return view;
    }

    private void loadSongs() {
        progressBar.setVisibility(View.VISIBLE);
        db.collection("songs")
                .addSnapshotListener((value, error) -> {
                    progressBar.setVisibility(View.GONE);
                    if (mContext == null) {
                        Log.e(TAG, "loadSongs: mContext is null");
                        return;
                    }
                    if (error != null) {
                        Log.e(TAG, "loadSongs: Error loading songs", error);
                        Toast.makeText(mContext, "errorLoadingSongs: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (value != null) {
                        List<OnlineSong> newSongs = new ArrayList<>();
                        for (QueryDocumentSnapshot document : value) {
                            try {
                                OnlineSong song = document.toObject(OnlineSong.class);

                                if (song.getUrl() != null && !song.getUrl().isEmpty()) {
                                    if (song.getTitle() == null || song.getTitle().isEmpty()) {
                                        song.setTitle("untitled (" + document.getId() + ")");
                                        Log.w(TAG, "loadSongs: Set default title for song: " + document.getId());
                                    }
                                    newSongs.add(song);
                                } else {
                                    Log.w(TAG, "loadSongs: Skipping invalid song, missing URL: " + document.getId());
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "loadSongs: Error parsing song document: " + document.getId(), e);
                            }
                        }
                        songList.clear();
                        songList.addAll(newSongs);
                        filteredSongList.clear();
                        filteredSongList.addAll(newSongs);
                        try {
                            songAdapter.updateSongs(filteredSongList);
                            Log.d(TAG, "loadSongs: Songs loaded, count = " + songList.size());
                        } catch (Exception e) {
                            Log.e(TAG, "loadSongs: Error updating adapter", e);
                            Toast.makeText(mContext, "songListUpdateError", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.w(TAG, "loadSongs: Snapshot value is null");
                    }
                });
    }

    private void setupSearchView() {
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterSongs(newText);
                return true;
            }
        });
        Log.d(TAG, "setupSearchView: SearchView listener set");
    }

    private void filterSongs(String query) {
        List<OnlineSong> newFilteredList = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            newFilteredList.addAll(songList);
        } else {
            String lowerQuery = query.toLowerCase();
            for (OnlineSong song : songList) {
                String title = song.getTitle() != null ? song.getTitle().toLowerCase() : "";
                String artist = song.getArtist() != null ? song.getArtist().toLowerCase() : "";
                if (title.contains(lowerQuery) || artist.contains(lowerQuery)) {
                    newFilteredList.add(song);
                }
            }
        }
        filteredSongList.clear();
        filteredSongList.addAll(newFilteredList);
        try {
            songAdapter.updateSongs(filteredSongList);
            Log.d(TAG, "filterSongs: Filtered songs, count = " + filteredSongList.size());
        } catch (Exception e) {
            Log.e(TAG, "filterSongs: Error updating adapter", e);
            if (mContext != null) {
                Toast.makeText(mContext, "songFilteringError", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onPlayClick(OnlineSong song) {
        Log.d(TAG, "onPlayClick: Started for song: " + (song != null ? song.getTitle() : "null"));
        if (mContext == null) {
            Log.e(TAG, "onPlayClick: mContext is null");
            return;
        }
        if (song == null || song.getUrl() == null || song.getUrl().isEmpty()) {
            Log.e(TAG, "onPlayClick: Song or URL is null or empty");
            Toast.makeText(mContext, "errorSongURLIsMissing", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int position = filteredSongList.indexOf(song);
            Log.d(TAG, "onPlayClick: Song position in filtered list: " + position + ", Song title: " + song.getTitle());
            if (position == -1) {
                Log.e(TAG, "onPlayClick: Song not found in filtered list");
                return;
            }

            if (loadingTimeoutRunnable != null) {
                handler.removeCallbacks(loadingTimeoutRunnable);
                Log.d(TAG, "onPlayClick: Removed previous loading timeout");
            }

            if (currentSong != null && currentSong.getUrl().equals(song.getUrl())) {
                if (player.isPlaying()) {
                    Log.d(TAG, "onPlayClick: Pausing current song: " + song.getTitle());
                    player.pause();
                    Toast.makeText(mContext, "pause: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                    songAdapter.notifyItemChanged(position);
                } else {
                    Log.d(TAG, "onPlayClick: Resuming or restarting song: " + song.getTitle());
                    if (player.getPlaybackState() == Player.STATE_ENDED) {
                        player.seekTo(0);
                        Log.d(TAG, "onPlayClick: Song ended, resetting position to start");
                    }
                    player.play();
                    Toast.makeText(mContext, "resume: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                    songAdapter.notifyItemChanged(position);
                }
            } else {
                Log.d(TAG, "onPlayClick: Playing new song: " + song.getTitle());
                int oldPosition = currentSong != null ? filteredSongList.indexOf(currentSong) : -1;
                if (oldPosition != -1) {
                    Log.d(TAG, "onPlayClick: Stopping old song: " + currentSong.getTitle() + ", oldPosition: " + oldPosition);
                    player.stop();
                    songAdapter.notifyItemChanged(oldPosition);
                }
                currentSong = song;
                MediaItem mediaItem = MediaItem.fromUri(song.getUrl());
                Log.d(TAG, "onPlayClick: Setting media item: " + song.getUrl());
                player.setMediaItem(mediaItem);
                player.prepare();

                loadingTimeoutRunnable = () -> {
                    if (player != null && !player.isPlaying() && currentSong != null && currentSong.getUrl().equals(song.getUrl())) {
                        Log.e(TAG, "onPlayClick: Loading timeout for song: " + song.getTitle());
                        Toast.makeText(mContext, "Error loading the song. Disable the VPN and try again.", Toast.LENGTH_LONG).show();
                        player.stop();
                        int positionToNotify = filteredSongList.indexOf(currentSong);
                        currentSong = null;
                        if (positionToNotify != -1) {
                            Log.d(TAG, "onPlayClick: Loading timeout, notifying adapter for position: " + positionToNotify);
                            songAdapter.notifyItemChanged(positionToNotify);
                        }
                    }
                };
                handler.postDelayed(loadingTimeoutRunnable, LOADING_TIMEOUT_MS);
                Log.d(TAG, "onPlayClick: Started loading timeout for: " + song.getTitle());

                player.play();
                Log.d(TAG, "onPlayClick: Started playing song: " + song.getTitle() + ", isPlaying: " + player.isPlaying());
                Toast.makeText(mContext, "playback: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                songAdapter.notifyItemChanged(position);
            }
        } catch (Exception e) {
            Log.e(TAG, "onPlayClick: Error playing song: " + song.getTitle(), e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "unknownError";
            Log.d(TAG, "onPlayClick: Error message: " + errorMessage);
            if (loadingTimeoutRunnable != null) {
                handler.removeCallbacks(loadingTimeoutRunnable);
                Log.d(TAG, "onPlayClick: Removed loading timeout due to error");
            }
            Toast.makeText(mContext, "reproductionError: " + errorMessage + ". turnOffYourVPNToPlayASong.", Toast.LENGTH_LONG).show();
            if (currentSong != null && currentSong.getUrl().equals(song.getUrl())) {
                int positionToNotify = filteredSongList.indexOf(currentSong);
                currentSong = null;
                player.stop();
                if (positionToNotify != -1) {
                    Log.d(TAG, "onPlayClick: Error occurred, notifying adapter for position: " + positionToNotify);
                    songAdapter.notifyItemChanged(positionToNotify);
                }
            }
        }
    }

    @Override
    public void onStopClick() {
        if (mContext == null) {
            Log.e(TAG, "onStopClick: mContext is null");
            return;
        }
        if (player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING)) {
            int position = currentSong != null ? filteredSongList.indexOf(currentSong) : -1;
            player.stop();
            currentSong = null;
            Log.d(TAG, "onStopClick: Playback stopped");
            Toast.makeText(mContext, "playbackStopped", Toast.LENGTH_SHORT).show();
            if (position != -1) {
                songAdapter.notifyItemChanged(position);
            }
        }
    }

    @Override
    public void onDownloadClick(OnlineSong song) {
        Log.d(TAG, "onDownloadClick: Started for song: " + (song != null ? song.getTitle() : "null"));
        if (mContext == null) {
            Log.e(TAG, "onDownloadClick: mContext is null");
            return;
        }
        if (song == null || song.getUrl() == null || song.getUrl().isEmpty()) {
            Log.e(TAG, "onDownloadClick: Song or URL is null or empty");
            Toast.makeText(mContext, "errorSongURLIsMissing", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(mContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "onDownloadClick: Requesting WRITE_EXTERNAL_STORAGE permission");
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }

        try {
            DownloadManager downloadManager = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                Log.e(TAG, "onDownloadClick: DownloadManager is null");
                Toast.makeText(mContext, "errorDownloadManagerIsNotAvailable", Toast.LENGTH_SHORT).show();
                return;
            }

            Uri audioUri = Uri.parse(song.getUrl());
            DownloadManager.Request audioRequest = new DownloadManager.Request(audioUri);
            audioRequest.setTitle("download: " + song.getTitle());
            audioRequest.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            audioRequest.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, song.getTitle() + ".mp3");
            long audioDownloadId = downloadManager.enqueue(audioRequest);
            Log.d(TAG, "onDownloadClick: Audio download started for: " + song.getTitle() + ", downloadId=" + audioDownloadId);
            Toast.makeText(mContext, "downloadStarted: " + song.getTitle(), Toast.LENGTH_SHORT).show();

            Runnable checkAudioDownloadStatus = new Runnable() {
                @Override
                public void run() {
                    DownloadManager.Query query = new DownloadManager.Query();
                    query.setFilterById(audioDownloadId);
                    Cursor cursor = downloadManager.query(query);
                    if (cursor.moveToFirst()) {
                        int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        if (statusIndex >= 0) {
                            int status = cursor.getInt(statusIndex);
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                Log.d(TAG, "onDownloadClick: Audio download completed for: " + song.getTitle());
                                Toast.makeText(mContext, "downloadComplete: " + song.getTitle(), Toast.LENGTH_SHORT).show();
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                Log.e(TAG, "onDownloadClick: Audio download failed for: " + song.getTitle());
                                int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                                String reason = reasonIndex >= 0 ? getDownloadErrorReason(cursor.getInt(reasonIndex)) : "unknownCause";
                                Log.e(TAG, "onDownloadClick: Download failure reason: " + reason);
                                Toast.makeText(mContext, "downloadError: " + reason + ". disableTheVPNAndTryAgain.", Toast.LENGTH_LONG).show();
                            } else {
                                handler.postDelayed(this, 1000);
                            }
                        }
                        cursor.close();
                    }
                }
            };
            handler.post(checkAudioDownloadStatus);
        } catch (Exception e) {
            Log.e(TAG, "onDownloadClick: Error downloading song: " + song.getTitle(), e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "unknownError";
            Log.d(TAG, "onDownloadClick: Error message: " + errorMessage);
            Toast.makeText(mContext, "downloadError: " + errorMessage + ". disableTheVPNAndTryAgain.", Toast.LENGTH_LONG).show();
        }
    }

    private String getDownloadErrorReason(int reasonCode) {
        switch (reasonCode) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "notEnoughSpaceOnTheDevice";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "httpErrorCheckURLOrDisableVPN)";
            case DownloadManager.ERROR_FILE_ERROR:
                return "fileSystemError";
            default:
                return "errorCode: " + reasonCode;
        }
    }

    @Override
    public OnlineSong getCurrentSong() {
        return currentSong;
    }

    @Override
    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public ExoPlayer getExoPlayer() {
        return player;
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Fragment paused, stopping playback");
        if (player != null && (player.isPlaying() || player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING)) {
            Log.d(TAG, "onPause: Current song: " + (currentSong != null ? currentSong.getTitle() : "null") + ", isPlaying: " + player.isPlaying());
            int position = currentSong != null ? filteredSongList.indexOf(currentSong) : -1;
            player.stop();
            currentSong = null;
            Log.d(TAG, "onPause: Playback stopped, currentSong cleared");
            if (position != -1) {
                Log.d(TAG, "onPause: Notifying adapter for position: " + position);
                songAdapter.notifyItemChanged(position);
            }
        } else {
            Log.d(TAG, "onPause: No playback to stop");
        }
        if (loadingTimeoutRunnable != null) {
            handler.removeCallbacks(loadingTimeoutRunnable);
            Log.d(TAG, "onPause: Removed loading timeout");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (player != null) {
            player.release();
            Log.d(TAG, "onDestroyView: ExoPlayer released");
        }
        handler.removeCallbacksAndMessages(null);
        mContext = null;
    }
}