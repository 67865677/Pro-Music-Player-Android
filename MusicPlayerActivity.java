package com.example.promusic;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicPlayerActivity extends AppCompatActivity {
    private static final String TAG = "MusicPlayerActivity";
    private static final String PREFS = "PlaybackPrefs";
    private static final String PREF_FAVORITES = "favorites";

    private MusicService musicService;
    private boolean isBound = false;
    private ImageButton btnPlay, btnRepeat, btnShuffle, btnFavorite, btnPrevious, btnNext;
    private SeekBar seekBarTime;
    private TextView tvTime, tvTitle, tvArtist, tvDuration;
    private ImageView albumArtImageView, backgroundImageView;
    private final List<Song> songList = new ArrayList<>();
    private int currentSongIndex = 0;
    private boolean isRepeating = false, isShuffling = false;
    private boolean isUpdatingFromBroadcast = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateTimeRunnable;
    private final Map<String, Bitmap> albumArtCache = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiver songChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "songChangedReceiver: obtained broadcast, action=" + intent.getAction());
            if ("com.example.promusic.SONG_CHANGED".equals(intent.getAction())) {
                if (musicService != null) {
                    currentSongIndex = musicService.getCurrentSongIndex();
                    isRepeating = musicService.isRepeating();
                    Log.d(TAG, "songChangedReceiver: currentSongIndex=" + currentSongIndex +
                            ", isRepeating=" + isRepeating + ", songList.size=" + songList.size());
                    if (!songList.isEmpty() && currentSongIndex >= 0 && currentSongIndex < songList.size()) {
                        Song song = songList.get(currentSongIndex);
                        long songId = song.getId();
                        if (songId > 0 && musicService.isPlaying()) {
                            Log.d(TAG, "songChangedReceiver: calling onTrackPlayed for songId=" + songId);
                            onTrackPlayed(songId);

                            song.setLastPlayed(System.currentTimeMillis());
                        } else {
                            Log.w(TAG, "songChangedReceiver: invalid songId=" + songId + " orTheTrackIsNotPlaying");
                        }
                    } else {
                        Log.w(TAG, "songChangedReceiver: invalidSongListOr currentSongIndex, " +
                                "songList.size=" + songList.size() + ", currentSongIndex=" + currentSongIndex);
                    }
                    updateUIForCurrentSong();
                } else {
                    Log.w(TAG, "songChangedReceiver: musicService equals null");
                }
            } else if ("com.example.promusic.ACTION_FAVORITES_CHANGED".equals(intent.getAction())) {
                updateFavoriteButton();
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            isBound = true;
            Log.d(TAG, "onServiceConnected: theServiceIsLinked, musicService=" + musicService);
            if (!songList.isEmpty()) {
                musicService.setSongList(songList, currentSongIndex);
                currentSongIndex = musicService.getCurrentSongIndex();
                Log.d(TAG, "onServiceConnected: songList transferredToTheService, size=" + songList.size());
                if (currentSongIndex >= 0 && currentSongIndex < songList.size() && musicService.isPlaying()) {
                    Song song = songList.get(currentSongIndex);
                    long songId = song.getId();
                    if (songId > 0) {
                        Log.d(TAG, "onServiceConnected: call onTrackPlayed for songId=" + songId);
                        onTrackPlayed(songId);
                        song.setLastPlayed(System.currentTimeMillis());
                    }
                }
            }
            updateUIForCurrentSong();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            musicService = null;
            Log.w(TAG, "onServiceDisconnected: service off");
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "onCreate: start MusicPlayerActivity");

        // Инициализация UI
        btnPlay = findViewById(R.id.btnPlay);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnShuffle = findViewById(R.id.btnShuffle);
        btnFavorite = findViewById(R.id.btnFavorite);
        btnPrevious = findViewById(R.id.btnPrevious);
        btnNext = findViewById(R.id.btnNext);
        seekBarTime = findViewById(R.id.seekBarTime);
        tvTime = findViewById(R.id.tvTime);
        tvTitle = findViewById(R.id.tvTitle);
        tvArtist = findViewById(R.id.tvArtist);
        tvDuration = findViewById(R.id.tvDuration);
        albumArtImageView = findViewById(R.id.albumArtImageView);
        backgroundImageView = findViewById(R.id.backgroundImageView);
        Log.d(TAG, "onCreate: UI elementsAreInitialized");


        Intent intent = getIntent();
        extractSongListFromIntent(intent);
        currentSongIndex = intent.getIntExtra("current_index", 0);
        if (currentSongIndex < 0 || (currentSongIndex >= songList.size() && !songList.isEmpty())) {
            currentSongIndex = 0;
            Log.w(TAG, "onCreate: invalid currentSongIndex, resetTo 0");
        }
        Log.d(TAG, "onCreate: currentSongIndex из Intent = " + currentSongIndex);


        tvTitle.setText(getString(R.string.unknown_song));
        tvArtist.setText(getString(R.string.unknown_artist));
        tvDuration.setText(getString(R.string.zero_time));
        tvTime.setText(getString(R.string.zero_time));
        seekBarTime.setMax(1);
        seekBarTime.setProgress(0);
        albumArtImageView.setImageResource(R.drawable.player);
        backgroundImageView.setImageResource(R.drawable.player);
        updatePlayButton(false);
        updateFavoriteButton(0, false);
        updateButtonStates();


        Intent serviceIntent = new Intent(this, MusicService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        Log.d(TAG, "onCreate: theServiceIsUpAndRunning");


        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.promusic.SONG_CHANGED");
        filter.addAction("com.example.promusic.ACTION_FAVORITES_CHANGED");
        try {
            ContextCompat.registerReceiver(this, songChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "onCreate: registered songChangedReceiver с RECEIVER_NOT_EXPORTED");
        } catch (Exception e) {
            Log.e(TAG, "onCreate: registrationError songChangedReceiver", e);
        }


        btnPlay.setOnClickListener(v -> {
            if (isBound && musicService != null) {
                Log.d(TAG, "btnPlay: buttonPressed Play, isBound=true");
                executor.execute(() -> {
                    boolean wasPlaying = musicService.isPlaying();
                    sendServiceAction(this, "PLAY_PAUSE");
                    runOnUiThread(() -> {
                        boolean isPlaying = musicService.isPlaying();
                        updatePlayButton(isPlaying);
                        updateSeekBarAndTime();
                        if (!wasPlaying && isPlaying && !songList.isEmpty() &&
                                currentSongIndex >= 0 && currentSongIndex < songList.size()) {
                            Song song = songList.get(currentSongIndex);
                            long songId = song.getId();
                            if (songId > 0) {
                                Log.d(TAG, "btnPlay: calling onTrackPlayed для songId=" + songId);
                                onTrackPlayed(songId);
                                song.setLastPlayed(System.currentTimeMillis());
                            } else {
                                Log.w(TAG, "btnPlay: invalid songId=" + songId);
                            }
                        }
                    });
                });
            } else {
                Log.w(TAG, "btnPlay: theServiceIsNotBindedWeInitiateRebinding");
                bindService(new Intent(this, MusicService.class), serviceConnection, Context.BIND_AUTO_CREATE);
                Toast.makeText(this, "connectingToTheService...", Toast.LENGTH_SHORT).show();
            }
        });

        btnNext.setOnClickListener(v -> {
            if (isBound && musicService != null) {
                Log.d(TAG, "btnNext: buttonPressed Next");
                executor.execute(() -> {
                    sendServiceAction(this, "NEXT");
                    runOnUiThread(this::updateUIForCurrentSong);
                });
            }
        });

        btnPrevious.setOnClickListener(v -> {
            if (isBound && musicService != null) {
                Log.d(TAG, "btnPrevious: buttonPressed Previous");
                executor.execute(() -> {
                    sendServiceAction(this, "PREVIOUS");
                    runOnUiThread(this::updateUIForCurrentSong);
                });
            }
        });

        btnRepeat.setOnClickListener(v -> {
            if (isBound && musicService != null) {
                isRepeating = !musicService.isRepeating();
                musicService.setRepeating(isRepeating);
                btnRepeat.setImageResource(isRepeating ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
                btnRepeat.setAlpha(isRepeating ? 1.0f : 0.5f);
                Log.d(TAG, "btnRepeat: replayChanged, isRepeating=" + isRepeating);
            }
        });

        btnShuffle.setOnClickListener(v -> {
            isShuffling = !isShuffling;
            if (isBound && musicService != null) {
                musicService.setShuffling(isShuffling);
                Log.d(TAG, "btnShuffle: shuffleChanged, isShuffling=" + isShuffling);
            }
            btnShuffle.setAlpha(isShuffling ? 1.0f : 0.5f);
        });

        btnFavorite.setOnClickListener(v -> {
            Log.d(TAG, "btnFavorite: theFavoritesButtonIsClicked");
            onFavoriteButtonClick();
        });

        seekBarTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvTime.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                handler.removeCallbacks(updateTimeRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (isBound && musicService != null) {
                    musicService.seekTo(seekBar.getProgress());
                    updateSeekBarAndTime();
                    handler.post(updateTimeRunnable);
                }
            }
        });

        //
        handler.post(() -> {
            if (isBound && musicService != null && !isFinishing()) {
                syncWithService();
                updateUIForCurrentSong();
            }
        });
    }
    private void debugSharedPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Map<String, ?> allPrefs = prefs.getAll();
        Log.d(TAG, "debugSharedPreferences: content=" + allPrefs.toString());
    }

    public void onFavoriteButtonClick() {
        Log.d(TAG, "onFavoriteButtonClick: called");
        if (musicService == null || songList.isEmpty() || currentSongIndex < 0 || currentSongIndex >= songList.size()) {
            Log.e(TAG, "onFavoriteButtonClick: incorrectState");
            return;
        }
        Song song = songList.get(currentSongIndex);
        long songId = song.getId();
        Log.d(TAG, "onFavoriteButtonClick: songId=" + songId);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> favorites = new HashSet<>(prefs.getStringSet(PREF_FAVORITES, new HashSet<>()));
        String songIdStr = String.valueOf(songId);
        boolean isFavorite = favorites.contains(songIdStr);

        if (isFavorite) {
            favorites.remove(songIdStr);
        } else {
            favorites.add(songIdStr);
        }
        prefs.edit().putStringSet(PREF_FAVORITES, favorites).apply();
        Log.d(TAG, "onFavoriteButtonClick: favoritesUpdated, isFavorite=" + !isFavorite);

        if (!isUpdatingFromBroadcast) {
            isUpdatingFromBroadcast = true;
            Intent intent = new Intent("com.example.promusic.ACTION_FAVORITES_CHANGED");
            intent.setPackage(getPackageName());
            intent.putExtra("song_id", songId);
            intent.putExtra("is_favorite", !isFavorite);
            sendBroadcast(intent);
            Log.d(TAG, "onFavoriteButtonClick: sentBroadcastFor songId=" + songId + ", isFavorite=" + !isFavorite);
            isUpdatingFromBroadcast = false;
        } else {
            Log.d(TAG, "onFavoriteButtonClick: missedBroadcastDueTo isUpdatingFromBroadcast=true");
        }

        updateFavoriteButton(songId, !isFavorite);
    }

    private boolean isSongFavorite(long songId) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> favorites = prefs.getStringSet(PREF_FAVORITES, new HashSet<>());
        return favorites.contains(String.valueOf(songId));
    }

    private void updateFavoriteButton(long songId, boolean isFavorite) {
        Log.d(TAG, "updateFavoriteButton: songId=" + songId + ", isFavorite=" + isFavorite);
        btnFavorite.setImageResource(isFavorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
    }

    private void updateFavoriteButton() {
        if (!songList.isEmpty() && currentSongIndex >= 0 && currentSongIndex < songList.size()) {
            Song song = songList.get(currentSongIndex);
            long songId = song.getId();
            boolean isFavorite = isSongFavorite(songId);
            updateFavoriteButton(songId, isFavorite);
        } else {
            Log.w(TAG, "updateFavoriteButton: invalidSongListOr currentSongIndex");
            updateFavoriteButton(0, false);
        }
    }

    private void updateUIForCurrentSong() {
        Log.d(TAG, "updateUIForCurrentSong: start, songList.size=" + songList.size() + ", currentSongIndex=" + currentSongIndex);
        if (!isBound || musicService == null) {
            Log.w(TAG, "updateUIForCurrentSong: theServiceIsNotTied orTheActivityEnds");
            tvTitle.setText(getString(R.string.unknown_song));
            tvArtist.setText(getString(R.string.unknown_artist));
            tvDuration.setText(getString(R.string.zero_time));
            tvTime.setText(getString(R.string.zero_time));
            seekBarTime.setMax(1);
            seekBarTime.setProgress(0);
            albumArtImageView.setImageResource(R.drawable.player);
            backgroundImageView.setImageResource(R.drawable.player);
            updatePlayButton(false);
            updateFavoriteButton(0, false);
            updateButtonStates();
            return;
        }

        executor.execute(() -> {
            try {
                List<Song> serviceSongList = musicService.getSongList();
                int serviceIndex = musicService.getCurrentSongIndex();
                isRepeating = musicService.isRepeating();
                if (!serviceSongList.isEmpty() && !serviceSongList.equals(songList)) {
                    songList.clear();
                    songList.addAll(serviceSongList);
                    currentSongIndex = serviceIndex;
                }

                if (songList.isEmpty() || currentSongIndex < 0 || currentSongIndex >= songList.size()) {
                    Log.w(TAG, "updateUIForCurrentSong: incorrectStateStubShowing");
                    runOnUiThread(() -> {
                        tvTitle.setText(getString(R.string.unknown_song));
                        tvArtist.setText(getString(R.string.unknown_artist));
                        tvDuration.setText(getString(R.string.zero_time));
                        tvTime.setText(getString(R.string.zero_time));
                        seekBarTime.setMax(1);
                        seekBarTime.setProgress(0);
                        albumArtImageView.setImageResource(R.drawable.player);
                        backgroundImageView.setImageResource(R.drawable.player);
                        updatePlayButton(false);
                        updateFavoriteButton(0, false);
                        updateButtonStates();
                    });
                    return;
                }

                Song currentSong = songList.get(currentSongIndex);
                int duration = musicService.getDuration();
                int position = musicService.getCurrentPosition();
                boolean isPlaying = musicService.isPlaying();


                Bitmap albumArt = albumArtCache.get(currentSong.getData());
                if (albumArt == null) {
                    albumArt = loadAlbumArt(currentSong.getData());
                    if (albumArt != null) {
                        albumArtCache.put(currentSong.getData(), albumArt);
                    }
                }

                Bitmap finalAlbumArt = albumArt;
                runOnUiThread(() -> {
                    tvTitle.setText(currentSong.getTitle() != null ? currentSong.getTitle() : getString(R.string.unknown_song));
                    tvArtist.setText(currentSong.getArtist() != null ? currentSong.getArtist() : getString(R.string.unknown_artist));
                    tvDuration.setText(duration > 0 ? formatTime(duration) : getString(R.string.zero_time));
                    tvTime.setText(formatTime(position));
                    seekBarTime.setMax(duration > 0 ? duration : 1);
                    seekBarTime.setProgress(Math.max(0, Math.min(position, duration)));
                    albumArtImageView.setImageBitmap(finalAlbumArt != null ? finalAlbumArt : BitmapFactory.decodeResource(getResources(), R.drawable.player));
                    backgroundImageView.setImageBitmap(finalAlbumArt != null ? finalAlbumArt : BitmapFactory.decodeResource(getResources(), R.drawable.player));
                    updatePlayButton(isPlaying);
                    updateFavoriteButton(currentSong.getId(), isSongFavorite(currentSong.getId()));
                    btnRepeat.setImageResource(isRepeating ? R.drawable.ic_repeat_on : R.drawable.ic_repeat_off);
                    btnRepeat.setAlpha(isRepeating ? 1.0f : 0.5f);
                    updateButtonStates();

                    if (isPlaying) {
                        startUpdatingTime();
                    } else {
                        stopUpdatingTime();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateUIForCurrentSong: synchronizationError", e);
                runOnUiThread(() -> {
                    tvTitle.setText(getString(R.string.unknown_song));
                    tvArtist.setText(getString(R.string.unknown_artist));
                    tvDuration.setText(getString(R.string.zero_time));
                    tvTime.setText(getString(R.string.zero_time));
                    albumArtImageView.setImageResource(R.drawable.player);
                    backgroundImageView.setImageResource(R.drawable.player);
                    updatePlayButton(false);
                    updateFavoriteButton(0, false);
                    updateButtonStates();
                });
            }
        });
    }
    private void syncWithService() {
        if (musicService != null && !songList.isEmpty()) {
            List<Song> serviceSongList = musicService.getSongList();
            int serviceIndex = musicService.getCurrentSongIndex();
            if (!serviceSongList.isEmpty() && !serviceSongList.equals(songList)) {
                songList.clear();
                songList.addAll(serviceSongList);
                currentSongIndex = serviceIndex;
                Log.d(TAG, "syncWithService: synchronizedWith MusicService, songList.size=" + songList.size() + ", currentSongIndex=" + currentSongIndex);
            }
        }
    }
    private void onTrackPlayed(long songId) {
        if (songId <= 0) {
            Log.w(TAG, "onTrackPlayed: invalid songId=" + songId);
            return;
        }
        Log.d(TAG, "onTrackPlayed: update SharedPreferences for songId=" + songId);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();


        Set<String> recentlyPlayed = new HashSet<>(prefs.getStringSet("recently_played_ids", new HashSet<>()));
        String songIdStr = String.valueOf(songId);
        if (recentlyPlayed.add(songIdStr)) {
            editor.putStringSet("recently_played_ids", recentlyPlayed);
            Log.d(TAG, "onTrackPlayed: added songId=" + songId + "  recently_played_ids");
        } else {
            Log.d(TAG, "onTrackPlayed: songId=" + songId + " alreadyIn recently_played_ids");
        }


        String playCountKey = "play_count_" + songId;
        int currentCount = prefs.getInt(playCountKey, 0);
        editor.putInt(playCountKey, currentCount + 1);


        editor.putLong("last_played_" + songId, System.currentTimeMillis());

        editor.apply();
        Log.d(TAG, "onTrackPlayed: updated songId=" + songId + ", playCount=" + (currentCount + 1));
        debugSharedPreferences();


        Intent intent = new Intent("com.example.mickey.ACTION_PLAYLIST_UPDATED");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        Log.d(TAG, "onTrackPlayed: sent broadcast ACTION_PLAYLIST_UPDATED");
    }

    private void extractSongListFromIntent(Intent intent) {
        songList.clear();
        long[] ids = intent.getLongArrayExtra("song_ids");
        if (ids == null || ids.length == 0) {
            Log.w(TAG, "extractSongListFromIntent: emptyOrMissingArray song_ids");
            return;
        }
        Log.d(TAG, "extractSongListFromIntent:received " + ids.length + " ID tracks");
        for (long id : ids) {
            if (id <= 0) {
                Log.w(TAG, "extractSongListFromIntent: invalidTrackID: " + id);
                continue;
            }
            Song song = getSongById(id);
            if (song != null) {
                songList.add(song);
                Log.d(TAG, "extractSongListFromIntent: Добавлена песня с ID=" + id + ", title=" + song.getTitle());
            } else {
                Log.w(TAG, "extractSongListFromIntent:couldnTFindASongFor songId=" + id);
            }
        }
        if (songList.isEmpty()) {
            Log.e(TAG, "extractSongListFromIntent:Failed to generate songList, all songs are unavailable");
        } else {
            Log.d(TAG, "extractSongListFromIntent: songList.size=" + songList.size() +
                    ", songList=" + songList);
        }
    }

    private Song getSongById(long id) {
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
        };
        String selection = MediaStore.Audio.Media._ID + " = ?";
        String[] selectionArgs = { String.valueOf(id) };
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long songId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                String artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                String data = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                long albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
                File file = new File(data);
                if (!file.exists() || !file.canRead()) {
                    Log.w(TAG, "getSongById: theFileIsNotAvailableOrDoesNotExist: " + data);
                    return null;
                }
                Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(songId));
                Uri albumArtUri = Uri.parse("content://media/external/audio/albumart/" + albumId);
                Song song = new Song(songId, title, artist, uri, albumArtUri, duration, data);

                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                song.setLastPlayed(prefs.getLong("last_played_" + songId, 0));
                Log.d(TAG, "getSongById:createdASongWithtitle=" + title + ", path=" + data);
                return song;
            } else {
                Log.w(TAG, "getSongById: theCursorIsEmptyOrNullFor songId=" + id);
            }
        } catch (Exception e) {
            Log.e(TAG, "getSongById: requestError songId=" + id, e);
        }
        return null;
    }

    private String formatTime(int millis) {
        int totalSeconds = millis / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void updateSeekBarAndTime() {
        if (isBound && musicService != null && musicService.isPlaying()) {
            try {
                int pos = musicService.getCurrentPosition();
                int dur = musicService.getDuration();
                tvTime.setText(formatTime(pos));
                tvDuration.setText(formatTime(Math.max(dur, 0)));
                seekBarTime.setMax(dur > 0 ? dur : 1);
                seekBarTime.setProgress(Math.max(0, Math.min(pos, dur)));
            } catch (Exception e) {
                Log.e(TAG, "timeUpdateError/SeekBar: " + e.getMessage(), e);
            }
        }
    }

    private void updatePlayButton(boolean isPlaying) {
        Log.d(TAG, "updatePlayButton: isPlaying=" + isPlaying);
        try {
            btnPlay.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            btnPlay.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "updatePlayButton: playButtonUpdateError", e);
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
            btnPlay.setEnabled(false);
        }
    }

    private Bitmap loadAlbumArt(String path) {
        if (path == null || path.isEmpty()) {
            Log.e(TAG, "loadAlbumArt: theFilePathIsEmptyOr null");
            return null;
        }
        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(path);
            byte[] data = mmr.getEmbeddedPicture();
            if (data != null) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap != null) {
                    Log.d(TAG, "loadAlbumArt: coverUploadedForThePath: " + path);
                    return Bitmap.createScaledBitmap(bitmap, 200, 200, true);
                } else {
                    Log.w(TAG, "loadAlbumArt: couldNotDecodeTheCoverForThePath: " + path);
                }
            } else {
                Log.w(TAG, "loadAlbumArt: coverNotFoundForPath: " + path);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadAlbumArt: errorLoadingCoverForPath: " + path, e);
        }
        return null;
    }

    private void updateButtonStates() {
        boolean hasMultiple = songList.size() > 1;
        boolean isServiceBound = isBound && musicService != null;
        btnShuffle.setEnabled(isServiceBound && hasMultiple);
        btnShuffle.setAlpha(isShuffling && isServiceBound ? 1.0f : 0.5f);
        btnPrevious.setEnabled(isServiceBound && hasMultiple);
        btnPrevious.setAlpha(isServiceBound && hasMultiple ? 1.0f : 0.5f);
        btnNext.setEnabled(isServiceBound && hasMultiple);
        btnNext.setAlpha(isServiceBound && hasMultiple ? 1.0f : 0.5f);
        btnRepeat.setEnabled(isServiceBound);
        btnRepeat.setAlpha(isRepeating && isServiceBound ? 1.0f : 0.5f);
        Log.d(TAG, "updateButtonStates: hasMultiple=" + hasMultiple + ", isServiceBound=" + isServiceBound);
    }

    private void startUpdatingTime() {
        stopUpdatingTime();
        updateTimeRunnable = new Runnable() {
            @Override
            public void run() {
                if (isBound && musicService != null && musicService.isPlaying()) {
                    int pos = musicService.getCurrentPosition();
                    int dur = musicService.getDuration();
                    tvTime.setText(formatTime(pos));
                    seekBarTime.setMax(dur > 0 ? dur : 1);
                    seekBarTime.setProgress(Math.max(0, Math.min(pos, dur)));
                    Log.d(TAG, "updateTimeRunnable: position=" + pos);
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(updateTimeRunnable);
    }

    private void stopUpdatingTime() {
        if (updateTimeRunnable != null) {
            handler.removeCallbacks(updateTimeRunnable);
            updateTimeRunnable = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: update UI");
        if (!isBound) {
            Intent intent = new Intent(this, MusicService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "onResume: bindingTo MusicService");
        } else if (musicService != null) {

            List<Song> serviceSongList = musicService.getSongList();
            int serviceIndex = musicService.getCurrentSongIndex();
            if (!serviceSongList.isEmpty()) {
                songList.clear();
                songList.addAll(serviceSongList);
                currentSongIndex = serviceIndex;
                Log.d(TAG, "onResume: synchronizedWith MusicService, songList.size=" + songList.size() + ", currentSongIndex=" + currentSongIndex);
            }
        }
        updateUIForCurrentSong();
        startUpdatingTime();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUpdatingTime();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        try {
            unregisterReceiver(songChangedReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "onDestroy: failedToUnregisterTheReceiver", e);
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent: aNew intent, action=" + intent.getAction());
        setIntent(intent);
        extractSongListFromIntent(intent);
        currentSongIndex = intent.getIntExtra("current_index", 0);
        if (currentSongIndex < 0 || (currentSongIndex >= songList.size() && !songList.isEmpty())) {
            currentSongIndex = 0;
            Log.w(TAG, "onNewIntent: invalid currentSongIndex, resetTo 0");
        }
        Log.d(TAG, "onNewIntent: extracted currentSongIndex=" + currentSongIndex + ", songList.size=" + songList.size());

        if (isBound && musicService != null) {
            if (!songList.isEmpty()) {
                musicService.setSongList(songList, currentSongIndex);
                Log.d(TAG, "onNewIntent: installedSongListIn MusicService, size=" + songList.size() + ", currentSongIndex=" + currentSongIndex);
                if (currentSongIndex >= 0 && currentSongIndex < songList.size() && musicService.isPlaying()) {
                    Song song = songList.get(currentSongIndex);
                    long songId = song.getId();
                    if (songId > 0) {
                        Log.d(TAG, "onNewIntent: call onTrackPlayed for songId=" + songId);
                        onTrackPlayed(songId);
                        song.setLastPlayed(System.currentTimeMillis());
                    }
                }
            } else {
                Log.w(TAG, "onNewIntent: songList пуст, skippingInstallation в MusicService");
            }
        } else {
            Log.w(TAG, "onNewIntent: serviceUnboundDeferredUpdateInOnResume");
        }
        updateUIForCurrentSong();
    }

    public static void sendServiceAction(Context context, String action) {
        Intent intent = new Intent(context, MusicService.class);
        intent.setAction(action);
        context.startService(intent);
    }
}