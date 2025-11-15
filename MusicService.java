package com.example.promusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class MusicService extends Service {
    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "music_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS = "PlaybackPrefs";
    private static final String PREF_FAVORITES = "favorites";

    private final IBinder binder = new MusicBinder();
    private final List<Song> songList = new ArrayList<>();
    private final List<Song> originalSongList = new ArrayList<>();
    private int currentSongIndex = 0;
    private boolean isRepeating = false, isShuffling = false;
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSessionCompat;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean isUpdatingFromBroadcast = false;
    private boolean isExplicitlyStopped = false;
    private String currentSongPath = null;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            updateNotificationWithProgress();
            timerHandler.postDelayed(this, 1000);
        }
    };

    private void startNotificationTimer() {
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.post(timerRunnable);
    }

    private void stopNotificationTimer() {
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void updateNotificationWithProgress() {
        Notification notification = buildNotification();
        if (notification != null) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private final BroadcastReceiver favoritesChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.promusic.ACTION_FAVORITES_CHANGED".equals(intent.getAction())) {
                Log.d(TAG, "favoritesChangedReceiver: Received ACTION_FAVORITES_CHANGED");
                isUpdatingFromBroadcast = true;
                startForegroundNotification();
                isUpdatingFromBroadcast = false;
            }
        }
    };

    public boolean isRepeating() {
        return  isRepeating;
    }

    public class MusicBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved: appRemovedFromRecentTasksClearState");
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "onTaskRemoved: errorStoppingReleasingMediaPlayer", e);
            }
            mediaPlayer = null;
        }
        currentSongPath = null;
        isExplicitlyStopped = true;
        songList.clear();
        originalSongList.clear();
        currentSongIndex = 0;
        stopNotificationTimer();
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Service created");
        createNotificationChannel();
        mediaSessionCompat = new MediaSessionCompat(this, TAG);
        mediaSessionCompat.setActive(true);

        mediaSessionCompat.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                Log.d(TAG, "MediaSession: onPlay");
                playPause();
            }

            @Override
            public void onPause() {
                Log.d(TAG, "MediaSession: onPause");
                playPause();
            }

            @Override
            public void onSkipToNext() {
                Log.d(TAG, "MediaSession: onSkipToNext");
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                Log.d(TAG, "MediaSession: onSkipToPrevious");
                playPrevious();
            }

            @Override
            public void onStop() {
                Log.d(TAG, "MediaSession: onStop");
                pause();
                stopForeground(true);
                stopSelf();
            }

            @Override
            public void onSeekTo(long pos) {
                Log.d(TAG, "MediaSession: onSeekTo, pos=" + pos);
                seekTo((int) pos);
            }
        });

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setupAudioFocus();

        IntentFilter favoritesFilter = new IntentFilter("com.example.promusic.ACTION_FAVORITES_CHANGED");
        try {
            ContextCompat.registerReceiver(this, favoritesChangedReceiver, favoritesFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "onCreate: registered favoritesChangedReceiver с RECEIVER_NOT_EXPORTED");
        } catch (Exception e) {
            Log.e(TAG, "onCreate: registrationError favoritesChangedReceiver", e);
        }
    }

    private void setupAudioFocus() {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build();
        int result = audioManager.requestAudioFocus(focusRequest);
        Log.d(TAG, "setupAudioFocus: requestAudioFocus result=" + result);
    }


    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            Log.d(TAG, "onAudioFocusChange: focusChange=" + focusChange + ", isExplicitlyStopped=" + isExplicitlyStopped);
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    Log.d(TAG, "AUDIOFOCUS_LOSS or TRANSIENT: pausePlayback");
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        savePlaybackPosition();
                        isExplicitlyStopped = true;
                        sendSongChanged();
                        startForegroundNotification();
                        stopNotificationTimer();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: loweringTheVolume");
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.setVolume(0.2f, 0.2f);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    Log.d(TAG, "AUDIOFOCUS_GAIN: restoringTheVolume, isExplicitlyStopped=" + isExplicitlyStopped);
                    if (mediaPlayer != null && !isExplicitlyStopped) {
                        mediaPlayer.setVolume(1.0f, 1.0f);
                        if (!mediaPlayer.isPlaying()) {
                            int result = audioManager.requestAudioFocus(focusRequest);
                            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                                mediaPlayer.start();
                                isExplicitlyStopped = false;
                                startForegroundNotification();
                                startNotificationTimer();
                                sendSongChanged();
                                Log.d(TAG, "AUDIOFOCUS_GAIN:playbackResumed");
                            } else {
                                Log.e(TAG, "AUDIOFOCUS_GAIN: audioFocusRequestDeclined");
                            }
                        }
                    } else {
                        Log.d(TAG, "AUDIOFOCUS_GAIN: playbackNotResumed, isExplicitlyStopped=" + isExplicitlyStopped);
                        startForegroundNotification();
                    }
                    break;
            }
        }
    };
    private boolean isAppInForeground() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> appProcesses = activityManager.getRunningAppProcesses();
        if (appProcesses == null) {
            return false;
        }
        final String packageName = getPackageName();
        for (android.app.ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called, intent=" + intent);
        return binder;
    }

    private void sendPlaybackState() {
        Intent intent = new Intent("com.example.mickey.PLAYBACK_STATE");
        intent.setPackage(getApplicationContext().getPackageName());
        intent.putExtra("is_playing", mediaPlayer != null && mediaPlayer.isPlaying());
        intent.putExtra("current_index", currentSongIndex);
        intent.putParcelableArrayListExtra("song_list", new ArrayList<>(songList));
        sendBroadcast(intent);
        Log.d(TAG, "[sendPlaybackState] BROADCAST SENT! isPlaying=" + (mediaPlayer != null && mediaPlayer.isPlaying()) + ", currentIndex=" + currentSongIndex);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: summonedFrom intent=" + intent);


        if (intent == null) {
            Log.e(TAG, "onStartCommand: Intent == null (probablyARestartOfTheService)");
            if (!isAppInForeground() || isExplicitlyStopped) {
                stopForeground(true);
                stopSelf();
                Log.d(TAG, "onStartCommand: App not in foreground or playback stopped, service stopped");
            } else {
                startForegroundNotification();
                Log.d(TAG, "onStartCommand: appInForegroundUpdateTheNotification");
            }
            return START_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: actionReceived=" + action);


        if (action == null) {
            Log.e(TAG, "onStartCommand: action == null");
            if (!isAppInForeground() || isExplicitlyStopped) {
                stopForeground(true);
                stopSelf();
                Log.d(TAG, "onStartCommand: App not in foreground or playback stopped, service stopped");
            } else {
                startForegroundNotification();
                Log.d(TAG, "onStartCommand: appInForegroundUpdateTheNotification");
            }
            return START_STICKY;
        }


        switch (action) {
            case "PLAY":
                long[] songIds = intent.getLongArrayExtra("song_ids");
                int index = intent.getIntExtra("current_index", 0);
                if (songIds != null && songIds.length > 0 && isAppInForeground()) {
                    List<Song> newSongList = new ArrayList<>();
                    for (long songId : songIds) {
                        if (songId <= 0) {
                            Log.w(TAG, "onStartCommand:  songId=" + songId);
                            continue;
                        }
                        Song song = getSongById(songId);
                        if (song != null) {
                            newSongList.add(song);
                            Log.d(TAG, "onStartCommand: addedASongTo newSongList: " + song.getTitle() + ", путь=" + song.getData());
                        } else {
                            Log.w(TAG, "onStartCommand: couldNotLoadASongFor songId=" + songId);
                        }
                    }
                    if (!newSongList.isEmpty()) {
                        setSongList(newSongList, index);
                        isExplicitlyStopped = false;
                        playSongAtIndex(index);
                        startForegroundNotification();
                        Log.d(TAG, "onStartCommand: playActionPlaybackStartedSongListSize=" + newSongList.size() + ", index=" + index);
                    } else {
                        Log.e(TAG, "onStartCommand: actionPLAYButNoValidSongs");
                        sendSongChangedError("No valid songs found");
                        stopForeground(true);
                    }
                } else {
                    Log.e(TAG, "onStartCommand: PLAY action, but song_ids empty, null or application not in foreground");
                    sendSongChangedError("No songs provided or app not in foreground");
                    stopForeground(true);
                }
                break;
            case "PLAY_PAUSE":
                Log.d(TAG, "onStartCommand: PLAY_PAUSE");
                playPause();
                startForegroundNotification();
                sendPlaybackState();
                break;
            case "PAUSE":
                Log.d(TAG, "onStartCommand: PAUSE");
                pause();
                isExplicitlyStopped = true;
                startForegroundNotification();
                sendPlaybackState();
                break;
            case "NEXT":
                Log.d(TAG, "onStartCommand: NEXT");
                if (isAppInForeground() && mediaPlayer != null) {
                    playNext();
                    isExplicitlyStopped = false;
                    startForegroundNotification();
                } else {
                    Log.d(TAG, "onStartCommand: NEXT ignoredTheAppIsNotInForegroundOr mediaPlayer null");
                    startForegroundNotification();
                }
                break;
            case "PREVIOUS":
                Log.d(TAG, "onStartCommand: PREVIOUS");
                if (isAppInForeground() && mediaPlayer != null) {
                    playPrevious();
                    isExplicitlyStopped = false;
                    startForegroundNotification();
                } else {
                    Log.d(TAG, "onStartCommand: PREVIOUS ignoredTheAppDoesnT в foreground или mediaPlayer null");
                    startForegroundNotification();
                }
                break;
            case "STOP":
                Log.d(TAG, "onStartCommand: STOP");
                pause();
                isExplicitlyStopped = true;
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            case "TOGGLE_FAVORITE":
                Log.d(TAG, "onStartCommand: TOGGLE_FAVORITE");
                toggleFavorite();
                break;
            case "CHECK_PLAYBACK_STATE":
                Log.d(TAG, "onStartCommand: CHECK_PLAYBACK_STATE");
                sendPlaybackState();
                startForegroundNotification();
                return START_NOT_STICKY;
            default:
                Log.e(TAG, "onStartCommand: unknownAction: " + action);
                stopForeground(true);
        }

        return START_STICKY;
    }

    public void setSongList(List<Song> list, int index) {
        Log.d(TAG, "setSongList: Called with list.size=" + (list == null ? 0 : list.size()) + ", index=" + index);
        if (list == null || list.isEmpty()) {
            Log.e(TAG, "setSongList: List is null or empty");
            sendSongChangedError("No songs available");
            return;
        }

        songList.clear();
        originalSongList.clear();
        for (Song song : list) {
            if (song != null && song.getData() != null && !song.getData().isEmpty()) {
                File file = new File(song.getData());
                if (file.exists() && file.canRead()) {
                    songList.add(song);
                    originalSongList.add(song);
                    Log.d(TAG, "setSongList: Added song: " + song.getTitle() + ", data=" + song.getData());
                } else {
                    Log.w(TAG, "setSongList: Invalid or inaccessible file for song: " + song.getTitle() + ", data=" + song.getData());
                }
            } else {
                Log.w(TAG, "setSongList: Invalid song data: " + (song != null ? song.getTitle() : "null"));
            }
        }

        if (songList.isEmpty()) {
            Log.e(TAG, "setSongList: No valid songs after filtering");
            sendSongChangedError("No valid songs available");
            return;
        }

        currentSongIndex = Math.max(0, Math.min(index, songList.size() - 1));
        Log.d(TAG, "setSongList: Set currentSongIndex=" + currentSongIndex + ", songList.size=" + songList.size());
        playSongAtIndex(currentSongIndex);
    }

    public void setRepeating(boolean repeating) {
        Log.d(TAG, "setRepeating: " + repeating);
        isRepeating = repeating;
        if (mediaPlayer != null) {
            mediaPlayer.setLooping(isRepeating);
            Log.d(TAG, "setRepeating: mediaPlayer.setLooping(" + isRepeating + ")");
        }

        sendSongChanged();
    }

    public void setShuffling(boolean shuffling) {
        Log.d(TAG, "setShuffling: " + shuffling);
        if (isShuffling == shuffling) return;
        isShuffling = shuffling;
        if (!shuffling) restoreOriginalOrder();
    }

    private void savePlaybackPosition() {
        if (mediaPlayer != null && currentSongIndex >= 0 && currentSongIndex < songList.size()) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("last_playback_position", mediaPlayer.getCurrentPosition());
            editor.putString("last_song_path", songList.get(currentSongIndex).getData());
            editor.apply();
            Log.d(TAG, "savePlaybackPosition: Saved position=" + mediaPlayer.getCurrentPosition() + ", song=" + songList.get(currentSongIndex).getTitle());
        }
    }

    public void playPause() {
        Log.d(TAG, "playPause: called, isExplicitlyStopped=" + isExplicitlyStopped + ", mediaPlayer=" + (mediaPlayer != null ? "not null" : "null") + ", isPlaying=" + (mediaPlayer != null && mediaPlayer.isPlaying()));


        if (!songList.isEmpty() && currentSongIndex >= 0 && currentSongIndex < songList.size()) {
            Song song = songList.get(currentSongIndex);
            File file = new File(song.getData());
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "playPause: fileNotFoundOrInaccessible: " + song.getData());
                sendSongChangedError("File not found: " + song.getTitle());
                startForegroundNotification();
                return;
            }

            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                try {
                    mediaPlayer.pause();
                    savePlaybackPosition();
                    isExplicitlyStopped = true;
                    Log.d(TAG, "playPause: suspendedEstablished isExplicitlyStopped=true");
                    stopNotificationTimer();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "playPause: IllegalStateException onPause", e);
                    sendSongChangedError("Playback error: Invalid player state");
                    resetAndPlayCurrentSong();
                }
            } else {
                int result = audioManager.requestAudioFocus(focusRequest);
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    try {
                        if (mediaPlayer == null || !song.getData().equals(currentSongPath)) {
                            Log.d(TAG, "playPause: mediaPlayer null orTheSongHasChangedWeLaunchANewSong");
                            playSongAtIndex(currentSongIndex);

                            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                            String lastSongPath = prefs.getString("last_song_path", null);
                            if (lastSongPath != null && lastSongPath.equals(song.getData())) {
                                int lastPosition = prefs.getInt("last_playback_position", 0);
                                mediaPlayer.seekTo(lastPosition);
                                Log.d(TAG, "playPause: restoredPlaybackPosition: " + lastPosition);
                            }
                        } else {
                            mediaPlayer.start();
                            isExplicitlyStopped = false;
                            Log.d(TAG, "playPause: startedInstalled isExplicitlyStopped=false");
                            startNotificationTimer();
                        }
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "playPause: failedToStartMediaPlayerReset", e);
                        sendSongChangedError("Playback error: Invalid player state");
                        resetAndPlayCurrentSong();
                    }
                } else {
                    Log.e(TAG, "playPause: audioFocusRequestDeclined");
                    sendSongChangedError("Cannot play: Audio focus denied");
                }
            }

            startForegroundNotification();

            new Thread(() -> {
                sendSongChanged();
                sendPlaybackState();
            }).start();
        } else {
            Log.e(TAG, "playPause: noSongsAvailable");
            sendSongChangedError("No songs available");
            startForegroundNotification();
        }
    }
    private void resetAndPlayCurrentSong() {
        Log.d(TAG, "resetAndPlayCurrentSong: Attempting to reset and play current song, isExplicitlyStopped=" + isExplicitlyStopped);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "resetAndPlayCurrentSong: errorStopRelease mediaPlayer", e);
            }
            mediaPlayer = null;
        }
        if (!songList.isEmpty() && currentSongIndex >= 0 && currentSongIndex < songList.size()) {
            Song song = songList.get(currentSongIndex);
            File file = new File(song.getData());
            if (file.exists() && file.canRead()) {
                isExplicitlyStopped = false;
                Log.d(TAG, "resetAndPlayCurrentSong: Resetting and playing song, set isExplicitlyStopped=false");
                playSongAtIndex(currentSongIndex);
            } else {
                Log.e(TAG, "resetAndPlayCurrentSong: File not found or inaccessible: " + song.getData());
                sendSongChangedError("File not found: " + song.getTitle());
                isExplicitlyStopped = true;
                Log.d(TAG, "resetAndPlayCurrentSong: Set isExplicitlyStopped=true due to inaccessible file");
            }
        } else {
            Log.e(TAG, "resetAndPlayCurrentSong: No valid songs available");
            sendSongChangedError("No valid songs available");
            isExplicitlyStopped = true;
            Log.d(TAG, "resetAndPlayCurrentSong: Set isExplicitlyStopped=true due to invalid song list");
        }
    }

    public void playNext() {
        Log.d(TAG, "playNext: called");
        if (songList.isEmpty()) {
            Log.d(TAG, "playNext: songList is empty");
            sendSongChangedError("No songs available");
            return;
        }
        int prevIndex = currentSongIndex;
        if (isShuffling) {
            int next;
            do {
                next = new Random().nextInt(songList.size());
            } while (songList.size() > 1 && next == currentSongIndex);
            currentSongIndex = next;
            Log.d(TAG, "playNext: shuffling, prev=" + prevIndex + ", next=" + currentSongIndex);
        } else {
            currentSongIndex = (currentSongIndex + 1) % songList.size();
            Log.d(TAG, "playNext: not shuffling, prev=" + prevIndex + ", next=" + currentSongIndex);
        }
        playSongAtIndex(currentSongIndex);
    }

    public void playPrevious() {
        Log.d(TAG, "playPrevious: called");
        if (songList.isEmpty()) {
            Log.d(TAG, "playPrevious: songList is empty");
            sendSongChangedError("No songs available");
            return;
        }
        int prevIndex = currentSongIndex;
        if (songList.size() > 1) {
            currentSongIndex = (currentSongIndex - 1 + songList.size()) % songList.size();
            Log.d(TAG, "playPrevious: prev=" + prevIndex + ", new index=" + currentSongIndex);
        }
        playSongAtIndex(currentSongIndex);
    }

    public void pause() {
        Log.d(TAG, "pause: called");
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            Log.d(TAG, "pause: pausing player");
            mediaPlayer.pause();
            savePlaybackPosition();
            isExplicitlyStopped = true;
            stopNotificationTimer();
            sendSongChanged();
            startForegroundNotification();
        } else {
            Log.d(TAG, "pause: mediaPlayer is null or not playing");
            stopNotificationTimer();
        }
    }

    public void seekTo(int progress) {
        Log.d(TAG, "seekTo: " + progress);
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(progress);
            Log.d(TAG, "seekTo: seeked to " + progress);
        } else {
            Log.d(TAG, "seekTo: mediaPlayer is null");
        }
    }

    private void playSongAtIndex(int index) {
        Log.d(TAG, "=== [playSongAtIndex] Entry. Requested index: " + index + ", songList.size=" + songList.size() + ", currentSongIndex=" + currentSongIndex + ", isExplicitlyStopped=" + isExplicitlyStopped);
        if (songList.isEmpty() || index < 0 || index >= songList.size()) {
            Log.e(TAG, "[playSongAtIndex] Invalid index or empty list!");
            sendSongChangedError("Invalid index or empty song list");
            isExplicitlyStopped = true;
            Log.d(TAG, "[playSongAtIndex] Set isExplicitlyStopped=true due to invalid index or empty list");
            stopNotificationTimer();
            return;
        }

        currentSongIndex = index;
        Song song = songList.get(currentSongIndex);

        File file = new File(song.getData());
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "[playSongAtIndex] File not found or inaccessible: " + song.getData());
            sendSongChangedError("File not found or inaccessible: " + song.getTitle());
            isExplicitlyStopped = true;
            Log.d(TAG, "[playSongAtIndex] Set isExplicitlyStopped=true due to inaccessible file");
            stopNotificationTimer();
            return;
        }

        if (mediaPlayer != null && mediaPlayer.isPlaying() && song.getData().equals(currentSongPath)) {
            Log.d(TAG, "[playSongAtIndex] theSongIsAlreadyPlayingSkipTheReCreation");
            isExplicitlyStopped = false;
            Log.d(TAG, "[playSongAtIndex] Set isExplicitlyStopped=false as song is already playing");
            return;
        }

        try {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "[playSongAtIndex] errorStopRelease mediaPlayer", e);
                }
                mediaPlayer = null;
            }

            int result = audioManager.requestAudioFocus(focusRequest);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.e(TAG, "[playSongAtIndex] Audio focus request failed");
                sendSongChangedError("Cannot play: Audio focus denied");
                isExplicitlyStopped = true;
                Log.d(TAG, "[playSongAtIndex] Set isExplicitlyStopped=true due to audio focus failure");
                stopNotificationTimer();
                return;
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(song.getData());
            currentSongPath = song.getData();

            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();

                    SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                    String lastSongPath = prefs.getString("last_song_path", null);
                    if (lastSongPath != null && lastSongPath.equals(song.getData()) && !isExplicitlyStopped) {
                        int lastPosition = prefs.getInt("last_playback_position", 0);
                        mp.seekTo(lastPosition);
                        Log.d(TAG, "[playSongAtIndex][onPrepared] restoredPlaybackPosition: " + lastPosition + " forTheSong: " + song.getTitle());
                    } else {
                        mp.seekTo(0);
                        Log.d(TAG, "[playSongAtIndex][onPrepared] newSongResetPositionTo0For: " + song.getTitle());

                        prefs.edit().putInt("last_playback_position", 0).apply();
                    }
                    isExplicitlyStopped = false;
                    Log.d(TAG, "[playSongAtIndex][onPrepared] Started, set isExplicitlyStopped=false");
                    sendSongChanged();
                    startForegroundNotification();
                    startNotificationTimer();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "[playSongAtIndex][onPrepared] IllegalStateException", e);
                    sendSongChangedError("Playback error: " + song.getTitle());
                    isExplicitlyStopped = true;
                    Log.d(TAG, "[playSongAtIndex][onPrepared] Set isExplicitlyStopped=true due to IllegalStateException");
                    stopNotificationTimer();
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "[playSongAtIndex][onError] what=" + what + ", extra=" + extra + ", file=" + song.getData());
                sendSongChangedError("Playback error: " + song.getTitle() + " (code: " + what + ")");
                isExplicitlyStopped = true;
                Log.d(TAG, "[playSongAtIndex][onError] Set isExplicitlyStopped=true due to error");
                stopNotificationTimer();
                return true;
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "[playSongAtIndex][onCompletion] Song completed, isRepeating=" + isRepeating);
                if (!isRepeating) {
                    isExplicitlyStopped = false;
                    Log.d(TAG, "[playSongAtIndex][onCompletion] Set isExplicitlyStopped=false before playNext");
                    playNext();
                }
            });

            mediaPlayer.setLooping(isRepeating);
            try {
                mediaPlayer.prepareAsync();
                Log.d(TAG, "[playSongAtIndex] Started async prepare for " + song.getTitle());
            } catch (Exception e) {
                Log.e(TAG, "[playSongAtIndex] Failed to prepare MediaPlayer for " + song.getTitle(), e);
                sendSongChangedError("Failed to prepare: " + song.getTitle());
                isExplicitlyStopped = true;
                Log.d(TAG, "[playSongAtIndex] Set isExplicitlyStopped=true due to prepare failure");
                stopNotificationTimer();
            }
        } catch (Exception e) {
            Log.e(TAG, "[playSongAtIndex] ERROR initializing MediaPlayer for " + song.getTitle(), e);
            sendSongChangedError("Error opening file: " + song.getTitle());
            isExplicitlyStopped = true;
            Log.d(TAG, "[playSongAtIndex] Set isExplicitlyStopped=true due to MediaPlayer initialization error");
            stopNotificationTimer();
        }
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, MusicService.class);
        intent.setAction(action);
        intent.setPackage(getPackageName());
        Log.d(TAG, "servicePendingIntent: Creating PendingIntent for action=" + action + ", requestCode=" + requestCode);

        PendingIntent pendingIntent = PendingIntent.getForegroundService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
        Log.d(TAG, "servicePendingIntent: PendingIntent created for action=" + action);
        return pendingIntent;
    }

    private Notification buildNotification() {
        if (songList.isEmpty() || currentSongIndex < 0 || currentSongIndex >= songList.size()) {
            Log.e(TAG, "buildNotification: Empty songList or invalid index!");
            return null;
        }
        Song song = songList.get(currentSongIndex);
        Log.d(TAG, "buildNotification: Creating notification for song: " + song.getTitle());

        PendingIntent prevIntent = servicePendingIntent("PREVIOUS", 1);
        PendingIntent playPauseIntent = servicePendingIntent("PLAY_PAUSE", 2);
        PendingIntent nextIntent = servicePendingIntent("NEXT", 3);
        PendingIntent closeIntent = servicePendingIntent("STOP", 4);
        PendingIntent likeIntent = servicePendingIntent("TOGGLE_FAVORITE", 5);

        boolean isFavorite = isSongFavorite(song);
        int likeIcon = isFavorite ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline;
        Log.d(TAG, "buildNotification: isFavorite=" + isFavorite + ", likeIcon=" + likeIcon);

        int playPauseIcon = isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play;

        Bitmap albumArt = getAlbumArt(song.getData());

        int duration = getDuration();
        int position = getCurrentPosition();
        boolean playing = isPlaying();

        mediaSessionCompat.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.getArtist())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                .build());

        mediaSessionCompat.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        position, playing ? 1.0f : 0f)
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SEEK_TO
                ).build());

        Intent notificationIntent = createNotificationIntent(song, currentSongIndex, songList);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(song.getTitle())
                .setContentText(song.getArtist())
                .setSmallIcon(R.drawable.ic_music_note)
                .setLargeIcon(albumArt)
                .setContentIntent(contentIntent)
                .setOngoing(playing)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(likeIcon, "Like", likeIntent)
                .addAction(R.drawable.ic_previous, "Prev", prevIntent)
                .addAction(playPauseIcon, playing ? "Pause" : "Play", playPauseIntent)
                .addAction(R.drawable.ic_next, "Next", nextIntent)
                .addAction(R.drawable.ic_close, "Close", closeIntent)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(1, 2, 3)
                        .setMediaSession(mediaSessionCompat.getSessionToken()));

        Notification notification = builder.build();
        Log.d(TAG, "buildNotification: Notification created for song: " + song.getTitle());
        return notification;
    }

    private void startForegroundNotification() {
        if (songList.isEmpty() || currentSongIndex < 0 || currentSongIndex >= songList.size()) {
            Log.w(TAG, "startForegroundNotification: Skipping a notification, an empty list, or an invalid index");
            stopForeground(true);
            return;
        }

        Notification notification = buildNotification();
        if (notification != null) {
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "startForegroundNotification: notificationStartedForSong: " + songList.get(currentSongIndex).getTitle());
        } else {
            Log.w(TAG, "startForegroundNotification: nullNotificationStop foreground");
            stopForeground(true);
        }
    }
    private Bitmap getAlbumArt(String path) {
        Log.d(TAG, "getAlbumArt: path=" + path);
        if (path == null) return BitmapFactory.decodeResource(getResources(), R.drawable.player);
        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(path);
            byte[] artBytes = mmr.getEmbeddedPicture();
            if (artBytes != null) {
                Log.d(TAG, "getAlbumArt: loaded embedded album art");
                return BitmapFactory.decodeByteArray(artBytes, 0, artBytes.length);
            }
        } catch (Exception e) {
            Log.e(TAG, "getAlbumArt: error", e);
        }
        Log.d(TAG, "getAlbumArt: using default album art");
        return BitmapFactory.decodeResource(getResources(), R.drawable.player);
    }

    private boolean isSongFavorite(Song song) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> favorites = prefs.getStringSet(PREF_FAVORITES, new HashSet<>());
        boolean isFavorite = favorites.contains(String.valueOf(song.getId()));
        Log.d(TAG, "isSongFavorite: Song " + song.getTitle() + ", songId=" + song.getId() + ", isFavorite=" + isFavorite);
        return isFavorite;
    }

    public void toggleFavorite() {
        if (songList.isEmpty() || currentSongIndex < 0 || currentSongIndex >= songList.size()) {
            Log.e(TAG, "toggleFavorite: Empty list or invalid index");
            return;
        }
        Song song = songList.get(currentSongIndex);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> favorites = new HashSet<>(prefs.getStringSet(PREF_FAVORITES, new HashSet<>()));
        String songIdStr = String.valueOf(song.getId());
        boolean isFavorite = favorites.contains(songIdStr);

        if (isFavorite) {
            favorites.remove(songIdStr);
            Log.d(TAG, "toggleFavorite: Song " + song.getTitle() + " removed from favorites");
        } else {
            favorites.add(songIdStr);
            Log.d(TAG, "toggleFavorite: Song " + song.getTitle() + " added to favorites");
        }
        prefs.edit().putStringSet(PREF_FAVORITES, favorites).apply();

        if (!isUpdatingFromBroadcast) {
            Intent intent = new Intent("com.example.promusic.ACTION_FAVORITES_CHANGED");
            intent.setPackage(getPackageName());
            intent.putExtra("song_id", song.getId());
            intent.putExtra("is_favorite", !isFavorite);
            isUpdatingFromBroadcast = true;
            sendBroadcast(intent);
            Log.d(TAG, "toggleFavorite: Broadcast sent for songId=" + song.getId() + ", isFavorite=" + !isFavorite);
            isUpdatingFromBroadcast = false;
        } else {
            Log.d(TAG, "toggleFavorite: Skipped broadcast due to isUpdatingFromBroadcast=true");
        }

        startForegroundNotification();
    }

    private void createNotificationChannel() {
        Log.d(TAG, "createNotificationChannel: called");
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }



    @Override
    public boolean onUnbind(Intent intent) {
        Log.e(TAG, "onUnbind: called! intent=" + intent);
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        Log.e(TAG, "onRebind: called! intent=" + intent);
        super.onRebind(intent);
    }

    @Override
    public void onDestroy() {
        Log.e(TAG, "onDestroy: Service destroyed!");
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        stopForeground(true);
        if (mediaSessionCompat != null) {
            mediaSessionCompat.release();
        }
        stopNotificationTimer();
        try {
            unregisterReceiver(favoritesChangedReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "onDestroy: Failed to unregister receivers", e);
        }
        super.onDestroy();
    }

    private void restoreOriginalOrder() {
        Log.d(TAG, "restoreOriginalOrder: called");
        if (!originalSongList.isEmpty() && !songList.equals(originalSongList)) {
            songList.clear();
            songList.addAll(originalSongList);
            Log.d(TAG, "restoreOriginalOrder: restored, songList.size=" + songList.size());
            playSongAtIndex(currentSongIndex);
        } else {
            Log.d(TAG, "restoreOriginalOrder: order already correct, doing nothing");
        }
    }

    private void sendSongChangedError(String errorMsg) {
        Log.e(TAG, "[sendSongChangedError] " + errorMsg);
        Intent intent = new Intent("com.example.promusic.SONG_CHANGED");
        intent.setPackage(getApplicationContext().getPackageName());
        intent.putExtra("current_index", currentSongIndex);
        intent.putExtra("error", errorMsg);
        intent.putParcelableArrayListExtra("song_list", new ArrayList<>(songList));
        sendBroadcast(intent);
    }

    private void sendSongChanged() {
        Log.d(TAG, "sendSongChanged: current_index=" + currentSongIndex + ", list.size=" + songList.size());
        if (songList.isEmpty() || currentSongIndex < 0 || currentSongIndex >= songList.size()) {
            Log.e(TAG, "sendSongChanged: Skipping broadcast: invalid index or empty list");
            return;
        }

        Song song = songList.get(currentSongIndex);
        boolean isPlaying = mediaPlayer != null && mediaPlayer.isPlaying();
        if (song.getData() == null || song.getData().isEmpty()) {
            Log.w(TAG, "sendSongChanged: Song data is null or empty for title=" + song.getTitle());
        }
        if (lastSentIndex != currentSongIndex || !lastSentIsPlaying.equals(isPlaying) || !lastSentSongList.equals(songList) || lastSentIsRepeating != isRepeating) {
            Log.d(TAG, "sendSongChanged: Current index=" + currentSongIndex + ", songList.size=" + songList.size() +
                    ", isPlaying=" + isPlaying + ", isRepeating=" + isRepeating + ", Song=" + song.getTitle() + " (" + song.getData() + ")");
            Intent intent = new Intent("com.example.promusic.SONG_CHANGED");
            intent.setPackage(getApplicationContext().getPackageName());
            intent.putExtra("current_index", currentSongIndex);
            intent.putExtra("is_playing", isPlaying);
            intent.putExtra("is_repeating", isRepeating);
            intent.putParcelableArrayListExtra("song_list", new ArrayList<>(songList));
            sendBroadcast(intent);
            Log.d(TAG, "sendSongChanged: Broadcast sent!");
            lastSentIndex = currentSongIndex;
            lastSentIsPlaying = isPlaying;
            lastSentIsRepeating = isRepeating;
            lastSentSongList = new ArrayList<>(songList);
        } else {
            Log.d(TAG, "sendSongChanged: Skipping broadcast: data and state unchanged");
        }
    }

    private int lastSentIndex = -1;
    private Boolean lastSentIsPlaying = false;
    private boolean lastSentIsRepeating = false;
    private ArrayList<Song> lastSentSongList = new ArrayList<>();

    public boolean isPlaying() {
        boolean playing = mediaPlayer != null && mediaPlayer.isPlaying();
        Log.d(TAG, "isPlaying: " + playing);
        return playing;
    }

    public int getDuration() {
        if (mediaPlayer != null) {
            try {
                int duration = mediaPlayer.getDuration();
                Log.d(TAG, "[getDuration] duration=" + duration + ", isPlaying=" + mediaPlayer.isPlaying() + ", isInitialized=" + (duration > 0));
                return Math.max(duration, 0);
            } catch (IllegalStateException e) {
                Log.e(TAG, "[getDuration] IllegalStateException!", e);
                return 0;
            }
        }
        Log.d(TAG, "[getDuration] mediaPlayer == null!");
        return 0;
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                int pos = mediaPlayer.getCurrentPosition();
                Log.d(TAG, "[getCurrentPosition] pos=" + pos + ", isPlaying=" + mediaPlayer.isPlaying());
                return Math.max(pos, 0);
            } catch (IllegalStateException e) {
                Log.e(TAG, "[getCurrentPosition] IllegalStateException!", e);
                return 0;
            }
        }
        Log.d(TAG, "[getCurrentPosition] mediaPlayer == null!");
        return 0;
    }

    public int getCurrentSongIndex() {
        Log.d(TAG, "getCurrentSongIndex: " + currentSongIndex);
        return currentSongIndex;
    }

    public List<Song> getSongList() {
        Log.d(TAG, "getSongList: size=" + songList.size());
        return new ArrayList<>(songList);
    }

    private Intent createNotificationIntent(Song song, int currentSongIndex, List<Song> songList) {
        Log.d(TAG, "createNotificationIntent: song=" + song.getTitle() + ", index=" + currentSongIndex + ", songList.size=" + songList.size());
        long[] ids = new long[songList.size()];
        for (int i = 0; i < songList.size(); i++) {
            ids[i] = songList.get(i).getId();
        }
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("song_ids", ids);
        intent.putExtra("current_index", currentSongIndex);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Log.d(TAG, "createNotificationIntent: Intent created, song_ids.length=" + ids.length + ", current_index=" + currentSongIndex);
        return intent;
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
                Log.d(TAG, "getSongById: createdASongWithATitle=" + title + ", path=" + data);
                return song;
            } else {
                Log.w(TAG, "getSongById:theCursorIsEmptyOrNullFor songId=" + id);
            }
        } catch (Exception e) {
            Log.e(TAG, "getSongById: requestError songId=" + id, e);
        }
        return null;
    }
}

