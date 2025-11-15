package com.example.promusic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements CustomActionModeListener {
    private static final String TAG = "MainActivity";
    private BottomNavigationView bottomNavigationView;
    private View actionModeView;
    private TextView actionModeTitle;
    private MaterialToolbar toolbar;
    private AppBarLayout appBarLayout;
    private ViewPager2 viewPager;
    private boolean isCustomActionModeActive = false;
    private Miniplayer miniplayer;
    private View miniPlayerLayout;
    private boolean isPlaying = false;
    private boolean hasPlaybackStarted = false;
    private Song currentSong;
    private BroadcastReceiver songChangedReceiver;
    private BroadcastReceiver phoneStateReceiver;
    private BroadcastReceiver mediaChangeReceiver;
    private String lastSongPath = null;
    private Bitmap lastValidAlbumArt = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_list_music);
            Log.d(TAG, "onCreate: MainActivity started");

            appBarLayout = findViewById(R.id.app_bar_layout);
            toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
                Log.d(TAG, "onCreate: Toolbar initialized successfully");
            } else {
                Log.e(TAG, "onCreate: Toolbar not found");
            }

            viewPager = findViewById(R.id.view_pager);
            TabLayout tabLayout = findViewById(R.id.tab_layout);
            bottomNavigationView = findViewById(R.id.bottom_navigation);
            miniPlayerLayout = findViewById(R.id.mini_player_layout);

            if (viewPager == null || tabLayout == null || bottomNavigationView == null) {
                Log.e(TAG, "onCreate: ViewPager, TabLayout or BottomNavigationView not found in layout");
                return;
            }
            Log.d(TAG, "onCreate: ViewPager, TabLayout, and BottomNavigationView found");


            bottomNavigationView.setOnItemSelectedListener(item -> {
                if (isCustomActionModeActive) {
                    Log.d(TAG, "BottomNavigationView: Navigation blocked because CustomActionMode is active");
                    Toast.makeText(MainActivity.this, "navigationIsLockedInThe выбора", Toast.LENGTH_SHORT).show();
                    return false;
                }
                int itemId = item.getItemId();
                Log.d(TAG, "BottomNavigationView: Selected item ID = " + itemId);
                if (itemId == R.id.nav_current_song) {
                    Log.d(TAG, "Navigating to PlaylistsActivity");
                    Intent intent = new Intent(MainActivity.this, PlaylistsActivity.class);
                    startActivity(intent);
                    return true;
                }
                return false;
            });

            if (miniPlayerLayout != null) {
                Log.d(TAG, "onCreate: Mini player layout found with ID mini_player_layout");
                View miniPlayerView = miniPlayerLayout;
                if (miniPlayerView != null) {
                    Log.d(TAG, "onCreate: Mini player view found, initializing Miniplayer");
                    miniplayer = new Miniplayer(miniPlayerView);
                    miniplayer.setOnPlayPauseClickListener(v -> {
                        Log.d(TAG, "Miniplayer: Play/Pause button clicked");
                        MusicPlayerActivity.sendServiceAction(this, "PLAY_PAUSE");

                        Intent checkIntent = new Intent(this, MusicService.class);
                        checkIntent.setAction("CHECK_PLAYBACK_STATE");
                        checkIntent.setPackage(getPackageName());
                        startService(checkIntent);
                    });
                    miniplayer.setOnNextClickListener(v -> {
                        Log.d(TAG, "Miniplayer: Next track clicked");
                        MusicPlayerActivity.sendServiceAction(this, "NEXT");
                    });
                    miniplayer.setOnRootClickListener(v -> {
                        Log.d(TAG, "Miniplayer: Root layout clicked, launching MusicPlayerActivity");
                        Intent intent = new Intent(MainActivity.this, MusicPlayerActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(intent);
                    });
                    miniplayer.setVisible(false);
                    miniPlayerLayout.setVisibility(View.GONE);
                    Log.d(TAG, "onCreate: Miniplayer initialized and set to invisible");
                } else {
                    Log.e(TAG, "onCreate: Mini player view not found in mini_player_layout");
                }
            } else {
                Log.e(TAG, "onCreate: Mini player layout not found");
            }

            songChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.d(TAG, "songChangedReceiver: Received broadcast with action=" + intent.getAction());
                    String action = intent.getAction();
                    if (action == null) {
                        Log.w(TAG, "songChangedReceiver: Received null action");
                        return;
                    }

                    if ("com.example.promusic.SONG_CHANGED".equals(action) || "com.example.promusic.PLAYBACK_STATE".equals(action)) {
                        int index = intent.getIntExtra("current_index", -1);
                        ArrayList<Song> receivedSongList = intent.getParcelableArrayListExtra("song_list");
                        String error = intent.getStringExtra("error");

                        boolean newIsPlaying = intent.getBooleanExtra("is_playing", isPlaying);

                        Log.d(TAG, "songChangedReceiver: action=" + action + ", index=" + index + ", songList size=" +
                                (receivedSongList != null ? receivedSongList.size() : "null") + ", error=" + error +
                                ", isPlaying=" + newIsPlaying);

                        if (error != null) {
                            Log.e(TAG, "songChangedReceiver: Error received: " + error);
                            if (miniplayer != null) {
                                miniplayer.setVisible(false);
                                miniPlayerLayout.setVisibility(View.GONE);
                                Log.d(TAG, "songChangedReceiver: Miniplayer set to invisible due to error");
                            }
                            currentSong = null;
                            isPlaying = false;
                            hasPlaybackStarted = false;
                            return;
                        }

                        if (receivedSongList != null && index >= 0 && index < receivedSongList.size()) {
                            currentSong = receivedSongList.get(index);
                            Log.d(TAG, "songChangedReceiver: Valid song received: title=" + currentSong.getTitle() +
                                    ", path=" + currentSong.getData());

                            if ("com.example.promusic.SONG_CHANGED".equals(action)) {
                                Intent checkIntent = new Intent(context, MusicService.class);
                                checkIntent.setAction("CHECK_PLAYBACK_STATE");
                                checkIntent.setPackage(getPackageName());
                                context.startService(checkIntent);
                                Log.d(TAG, "songChangedReceiver: Sent CHECK_PLAYBACK_STATE intent");
                            }

                            isPlaying = newIsPlaying;
                            hasPlaybackStarted = true;
                            if (viewPager.getCurrentItem() == 0) {
                                updateMiniPlayer(currentSong.getTitle(), currentSong.getData(), isPlaying);
                                if (miniplayer != null) {
                                    miniplayer.setVisible(true);
                                    miniPlayerLayout.setVisibility(View.VISIBLE);
                                    Log.d(TAG, "songChangedReceiver: Miniplayer updated and set to visible on SongsFragment");
                                }
                            } else {
                                Log.d(TAG, "songChangedReceiver: Not updating miniplayer, not on SongsFragment (currentItem=" + viewPager.getCurrentItem() + ")");
                                if (miniplayer != null) {
                                    miniplayer.setVisible(false);
                                    miniPlayerLayout.setVisibility(View.GONE);
                                    Log.d(TAG, "songChangedReceiver: Miniplayer set to invisible on OnlineFragment");
                                }
                            }
                        } else {
                            Log.w(TAG, "songChangedReceiver: Invalid song list or index, index=" + index +
                                    ", songList=" + (receivedSongList == null ? "null" : receivedSongList.size()));
                            currentSong = null;
                            isPlaying = false;
                            hasPlaybackStarted = false;
                            updateMiniPlayer(null, null, false);
                            if (miniplayer != null) {
                                miniplayer.setVisible(false);
                                miniPlayerLayout.setVisibility(View.GONE);
                                Log.d(TAG, "songChangedReceiver: Miniplayer set to invisible");
                            }
                        }
                    } else {
                        Log.w(TAG, "songChangedReceiver: Unknown action received: " + action);
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction("com.example.promusic.SONG_CHANGED");
            filter.addAction("com.example.promusic.PLAYBACK_STATE");
            try {
                ContextCompat.registerReceiver(this, songChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
                Log.d(TAG, "onCreate: registered songChangedReceiver с RECEIVER_NOT_EXPORTED");
            } catch (Exception e) {
                Log.e(TAG, "onCreate: registrationError songChangedReceiver", e);
            }


            phoneStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                    Log.d(TAG, "phoneStateReceiver: Received phone state change, state=" + state);
                    if (TelephonyManager.EXTRA_STATE_RINGING.equals(state) || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                        Log.d(TAG, "phoneStateReceiver: Incoming or outgoing call detected, pausing playback");
                        MusicPlayerActivity.sendServiceAction(context, "PAUSE");
                        if (isCustomActionModeActive) {
                            hideCustomActionMode();
                            Log.d(TAG, "phoneStateReceiver: CustomActionMode closed due to call");
                        }
                        isPlaying = false;
                    } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                        Log.d(TAG, "phoneStateReceiver: Call ended, requesting playback state");
                        requestPlaybackState();
                    }
                }
            };

            IntentFilter phoneStateFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            try {
                ContextCompat.registerReceiver(this, phoneStateReceiver, phoneStateFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                Log.d(TAG, "onCreate: Registered phoneStateReceiver with RECEIVER_NOT_EXPORTED");
            } catch (Exception e) {
                Log.e(TAG, "onCreate: Failed to register phoneStateReceiver", e);
                Toast.makeText(this, "callHandlingError", Toast.LENGTH_SHORT).show();
            }


            mediaChangeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    Log.d(TAG, "mediaChangeReceiver: Received broadcast with action=" + action);
                    if (Intent.ACTION_MEDIA_SCANNER_SCAN_FILE.equals(action)) {
                        String path = intent.getData() != null ? intent.getData().getPath() : null;
                        if (path != null && (path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".aac") || path.endsWith(".ogg"))) {
                            Log.d(TAG, "mediaChangeReceiver: New audio file detected, path=" + path);

                            MediaScannerConnection.scanFile(context, new String[]{path}, null, (scannedPath, uri) -> {
                                Log.d(TAG, "mediaChangeReceiver: File scanned, path=" + scannedPath + ", uri=" + uri);
                                runOnUiThread(() -> {
                                    SongsFragment songsFragment = findSongsFragment();
                                    if (songsFragment != null && viewPager.getCurrentItem() == 0) {
                                        songsFragment.refreshSongList();
                                        Log.d(TAG, "mediaChangeReceiver: SongsFragment refreshed");
                                        Toast.makeText(context, "newSongAdded", Toast.LENGTH_SHORT).show();
                                    }
                                });
                            });
                        }
                    }
                }
            };

            IntentFilter mediaFilter = new IntentFilter(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaFilter.addDataScheme("file");
            try {
                ContextCompat.registerReceiver(this, mediaChangeReceiver, mediaFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
                Log.d(TAG, "onCreate: Registered mediaChangeReceiver with RECEIVER_NOT_EXPORTED");
            } catch (Exception e) {
                Log.e(TAG, "onCreate: Failed to register mediaChangeReceiver", e);
                Toast.makeText(this, "newFileTrackingError", Toast.LENGTH_SHORT).show();
            }

            setupViewPager(viewPager);
            new TabLayoutMediator(tabLayout, viewPager,
                    (tab, position) -> tab.setText(getTabTitle(position))
            ).attach();

            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    Log.d(TAG, "onPageSelected: Switched to tab position=" + position + " (" + (position == 0 ? "Local" : "Online") + ")");
                    bottomNavigationView.setVisibility(position == 1 ? View.GONE : View.VISIBLE);
                    Log.d(TAG, "onPageSelected: BottomNavigationView visibility set to " + (position == 1 ? "GONE" : "VISIBLE"));

                    if (position == 1) {
                        if (miniplayer != null) {
                            miniplayer.setVisible(false);
                            miniPlayerLayout.setVisibility(View.GONE);
                            Log.d(TAG, "onPageSelected: Miniplayer hidden for OnlineFragment");
                        }
                        if (hasPlaybackStarted && isPlaying) {
                            MusicPlayerActivity.sendServiceAction(MainActivity.this, "PAUSE");
                            isPlaying = false;

                            Intent checkIntent = new Intent(MainActivity.this, MusicService.class);
                            checkIntent.setAction("CHECK_PLAYBACK_STATE");
                            checkIntent.setPackage(getPackageName());
                            startService(checkIntent);
                            Log.d(TAG, "onPageSelected: Playback paused and CHECK_PLAYBACK_STATE sent for OnlineFragment");
                        }
                    } else if (position == 0 && miniplayer != null && currentSong != null && hasPlaybackStarted) {
                        Log.d(TAG, "onPageSelected: Miniplayer exists, currentSong=" + currentSong.getTitle() + ", isPlaying=" + isPlaying);
                        miniplayer.setVisible(true);
                        miniPlayerLayout.setVisibility(View.VISIBLE);
                        updateMiniPlayer(currentSong.getTitle(), currentSong.getData(), isPlaying);
                        Log.d(TAG, "onPageSelected: Miniplayer updated and set to visible on SongsFragment");
                    } else {
                        Log.w(TAG, "onPageSelected: Miniplayer not updated, miniplayer=" + (miniplayer == null ? "null" : "not null") +
                                ", currentSong=" + (currentSong == null ? "null" : currentSong.getTitle()) +
                                ", currentItem=" + position + ", hasPlaybackStarted=" + hasPlaybackStarted);
                        if (miniplayer != null) {
                            miniplayer.setVisible(false);
                            miniPlayerLayout.setVisibility(View.GONE);
                            Log.d(TAG, "onPageSelected: Miniplayer set to invisible");
                        }
                    }
                    if (isCustomActionModeActive && position != 0) {
                        hideCustomActionMode();
                        Log.d(TAG, "onPageSelected: Hiding customActionMode due to tab switch to position " + position);
                    }
                }
            });

            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    SongsFragment songsFragment = findSongsFragment();
                    if (songsFragment != null && !songsFragment.getSelectedSongs().isEmpty()) {
                        songsFragment.clearSelection();
                        Log.d(TAG, "handleOnBackPressed: Selection cleared in SongsFragment");
                    } else {
                        moveTaskToBack(true);
                        Log.d(TAG, "handleOnBackPressed: Moved to background, playback should continue");
                    }
                }
            });

            requestPlaybackState();
            Log.d(TAG, "onCreate: Playback state requested");
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: ", e);
            throw new RuntimeException("MainActivity initialization error", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Checking miniplayer state");
        long startTime = System.nanoTime();


        requestPlaybackState();


        if (viewPager.getCurrentItem() == 0 && miniplayer != null && currentSong != null && hasPlaybackStarted) {
            updateMiniPlayer(currentSong.getTitle(), currentSong.getData(), isPlaying);
            Log.d(TAG, "onResume: Miniplayer updated with cached song: " + currentSong.getTitle());
        } else {
            Log.d(TAG, "onResume: Hiding miniplayer, not on SongsFragment or no current song");
            if (miniplayer != null) {
                miniplayer.setVisible(false);
                miniPlayerLayout.setVisibility(View.GONE);
            }
        }
        Log.d(TAG, "onResume: Execution time=" + (System.nanoTime() - startTime) / 1_000_000 + "ms");
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (songChangedReceiver != null) {
            try {
                unregisterReceiver(songChangedReceiver);
                Log.d(TAG, "onDestroy: Unregistered songChangedReceiver");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "onDestroy: Failed to unregister songChangedReceiver", e);
            }
        }

        if (phoneStateReceiver != null) {
            try {
                unregisterReceiver(phoneStateReceiver);
                Log.d(TAG, "onDestroy: Unregistered phoneStateReceiver");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "onDestroy: Failed to unregister phoneStateReceiver", e);
            }
        }

        if (mediaChangeReceiver != null) {
            try {
                unregisterReceiver(mediaChangeReceiver);
                Log.d(TAG, "onDestroy: Unregistered mediaChangeReceiver");
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "onDestroy: Failed to unregister mediaChangeReceiver", e);
            }
        }
    }

    private void setupViewPager(ViewPager2 viewPager) {
        ViewPagerAdapter adapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(adapter);
        Log.d(TAG, "setupViewPager: ViewPager adapter set");
    }

    private String getTabTitle(int position) {
        return position == 0 ? "Local" : "Online";
    }

    private void requestPlaybackState() {
        Intent checkIntent = new Intent(this, MusicService.class);
        checkIntent.setAction("CHECK_PLAYBACK_STATE");
        checkIntent.setPackage(getPackageName());
        startService(checkIntent);
        Log.d(TAG, "requestPlaybackState: Sent CHECK_PLAYBACK_STATE intent");
    }

    @Override
    public void showCustomActionMode(int selectedCount) {
        Log.d(TAG, "showCustomActionMode called with selectedCount: " + selectedCount);
        if (appBarLayout != null && toolbar != null) {
            isCustomActionModeActive = true;
            appBarLayout.removeView(toolbar);

            if (actionModeView == null) {
                actionModeView = LayoutInflater.from(this).inflate(R.layout.custom_action_mode, appBarLayout, false);
                appBarLayout.addView(actionModeView);
                actionModeTitle = actionModeView.findViewById(R.id.action_mode_title);
                ImageView actionModeOverflow = actionModeView.findViewById(R.id.action_mode_overflow);
                ImageView actionModeClose = actionModeView.findViewById(R.id.action_mode_close);

                actionModeClose.setOnClickListener(v -> hideCustomActionMode());
                actionModeOverflow.setOnClickListener(this::showActionModeMenu);
            }

            if (actionModeTitle != null) {
                actionModeTitle.setText(getString(R.string.selected_count, selectedCount));
                Log.d(TAG, "showCustomActionMode: Updated title with selectedCount = " + selectedCount);
            } else {
                Log.e(TAG, "showCustomActionMode: action_mode_title not found");
            }


            if (bottomNavigationView != null) {
                bottomNavigationView.setEnabled(false);
                Log.d(TAG, "showCustomActionMode: BottomNavigationView disabled");
            }
        } else {
            Log.e(TAG, "showCustomActionMode: app_bar_layout or toolbar not found");
        }
    }

    private void showActionModeMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.menu_action_mode, popup.getMenu());
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            Fragment currentFragment = getCurrentFragment();
            if (currentFragment instanceof SongsFragment) {
                SongsFragment songsFragment = (SongsFragment) currentFragment;
                if (id == R.id.action_select_all) {
                    songsFragment.selectAll();
                    return true;
                } else if (id == R.id.action_delete) {
                    songsFragment.deleteSelectedSongs();
                    return true;
                } else if (id == R.id.action_share) {
                    songsFragment.shareSelectedSongs();
                    return true;
                }
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void hideCustomActionMode() {
        Log.d(TAG, "hideCustomActionMode called");
        if (appBarLayout != null && actionModeView != null) {
            appBarLayout.removeView(actionModeView);
            appBarLayout.addView(toolbar);
            actionModeView = null;
            actionModeTitle = null;
            isCustomActionModeActive = false;

            SongsFragment songsFragment = findSongsFragment();
            if (songsFragment != null && !songsFragment.isLongClickInProgress()) {
                songsFragment.clearSelection();
            }


            if (bottomNavigationView != null) {
                bottomNavigationView.setEnabled(true);
                Log.d(TAG, "hideCustomActionMode: BottomNavigationView enabled");
            }
        }
    }

    private SongsFragment findSongsFragment() {
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof SongsFragment) {
                return (SongsFragment) fragment;
            }
        }
        return null;
    }

    private Fragment getCurrentFragment() {
        int position = viewPager.getCurrentItem();
        return getSupportFragmentManager().findFragmentByTag("f" + position);
    }

    private static class ViewPagerAdapter extends FragmentStateAdapter {
        private static final int TAB_COUNT = 2;

        ViewPagerAdapter(FragmentActivity fragmentActivity) {
            super(fragmentActivity);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return position == 0 ? new SongsFragment() : new OnlineFragment();
        }

        @Override
        public int getItemCount() {
            return TAB_COUNT;
        }
    }

    /**
     * Updates the mini-player: sets song title, album art, and play/pause state.
     *
     * @param songTitle Song title, can be null
     * @param songPath  File path to the song, can be null
     * @param isPlaying True if playing, false if paused
     */
    public void updateMiniPlayer(String songTitle, String songPath, boolean isPlaying) {
        Log.d(TAG, "updateMiniPlayer: received songTitle=" + songTitle + ", songPath=" + songPath + ", isPlaying=" + isPlaying);
        if (miniplayer == null) {
            Log.w(TAG, "updateMiniPlayer: Miniplayer is null");
            return;
        }


        if (viewPager.getCurrentItem() == 1) {
            miniplayer.setVisible(false);
            miniPlayerLayout.setVisibility(View.GONE);
            Log.d(TAG, "updateMiniPlayer: Miniplayer hidden for OnlineFragment");
            return;
        }

        miniplayer.setPlaying(isPlaying);
        this.isPlaying = isPlaying;


        if (!hasPlaybackStarted) {
            miniplayer.setVisible(false);
            miniPlayerLayout.setVisibility(View.GONE);
            Log.d(TAG, "updateMiniPlayer: Miniplayer hidden, hasPlaybackStarted=" + hasPlaybackStarted);
            return;
        }

        boolean isSongUnchanged = songTitle != null && songTitle.equals(currentSong != null ? currentSong.getTitle() : null)
                && songPath != null && songPath.equals(lastSongPath);

        if (!isSongUnchanged) {
            miniplayer.setTitle(songTitle != null ? songTitle : "Unknown");
            if (songTitle != null && songPath != null) {
                currentSong = new Song(0, songTitle, "", null, null, 0, songPath);
            } else {
                currentSong = null;
            }

            if (songPath != null && !songPath.equals(lastSongPath)) {

                miniplayer.setAlbumArt(BitmapFactory.decodeResource(getResources(), R.drawable.player));

                new Thread(() -> {
                    Bitmap albumArt = Utils.loadAlbumArt(songPath);
                    runOnUiThread(() -> {
                        if (albumArt != null) {
                            lastValidAlbumArt = albumArt;
                            lastSongPath = songPath;
                            miniplayer.setAlbumArt(albumArt);
                            Log.d(TAG, "updateMiniPlayer: Loaded new album art for path=" + songPath);
                        } else {
                            lastValidAlbumArt = null;
                            lastSongPath = songPath;
                            Log.w(TAG, "updateMiniPlayer: Album art is null for path=" + songPath);
                        }
                    });
                }).start();
            } else if (songPath == null) {
                lastValidAlbumArt = null;
                lastSongPath = null;
                miniplayer.setAlbumArt(BitmapFactory.decodeResource(getResources(), R.drawable.player));
            }
        }

        miniplayer.setVisible(true);
        miniPlayerLayout.setVisibility(View.VISIBLE);
        Log.d(TAG, "updateMiniPlayer: Miniplayer updated, albumArt=" + (lastValidAlbumArt != null ? "not null" : "null"));
    }
}