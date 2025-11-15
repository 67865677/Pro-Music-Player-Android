package com.example.promusic;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

public class Miniplayer {
    private static final String TAG = "Miniplayer";
    private final View rootView;
    private final ImageView albumArtView;
    private final TextView titleView;
    private final ImageButton playPauseButton;
    private final ImageButton nextButton;
    private Bitmap lastAlbumArt = null;
    public Miniplayer(View rootView) {
        this.rootView = rootView;
        albumArtView = rootView.findViewById(R.id.mini_player_album_art);
        titleView = rootView.findViewById(R.id.mini_player_title);
        playPauseButton = rootView.findViewById(R.id.mini_player_play_pause);
        nextButton = rootView.findViewById(R.id.mini_player_next);
        if (albumArtView == null || titleView == null || playPauseButton == null || nextButton == null) {
            Log.e(TAG, "One or more mini player views not found: " +
                    "albumArtView=" + albumArtView + ", titleView=" + titleView +
                    ", playPauseButton=" + playPauseButton + ", nextButton=" + nextButton);
        } else {
            Log.d(TAG, "Miniplayer initialized successfully");
        }
    }

    public void setTitle(String title) {
        if (titleView != null) {
            titleView.setText(title != null ? title : "");
            Log.d(TAG, "setTitle: Set title to " + title);
        } else {
            Log.e(TAG, "setTitle: titleView is null");
        }
    }





    public void setAlbumArt(@Nullable Bitmap albumArt) {
        Log.d(TAG, "setAlbumArt: albumArt=" + (albumArt != null ? "not null" : "null"));
        if (albumArtView == null) {
            Log.e(TAG, "setAlbumArt: albumArtView is null");
            return;
        }


        if (albumArt == lastAlbumArt) {
            Log.d(TAG, "setAlbumArt: Skipping, albumArt unchanged");
            return;
        }

        lastAlbumArt = albumArt;
        if (albumArt != null) {
            Glide.with(albumArtView.getContext())
                    .load(albumArt)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .placeholder(R.drawable.player)
                    .error(R.drawable.player)
                    .into(albumArtView);
            Log.d(TAG, "setAlbumArt: Set album art bitmap");
        } else {
            albumArtView.setImageResource(R.drawable.player);
            Log.d(TAG, "setAlbumArt: Set default album art due to null bitmap");
        }
    }
    public void setPlaying(boolean isPlaying) {

        Log.d(TAG, "setPlaying: called with isPlaying=" + isPlaying);
        if (playPauseButton != null) {
            playPauseButton.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            Log.d(TAG, "setPlaying: Set playing state to " + isPlaying);
        } else {
            Log.e(TAG, "setPlaying: playPauseButton is null");
        }
    }

    public void setVisible(boolean visible) {
        if (rootView != null) {
            rootView.setVisibility(visible ? View.VISIBLE : View.GONE);
            Log.d(TAG, "setVisible: Set visibility to " + visible);
        } else {
            Log.e(TAG, "setVisible: rootView is null");
        }
    }

    public void setOnPlayPauseClickListener(View.OnClickListener listener) {
        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(listener);
            Log.d(TAG, "setOnPlayPauseClickListener: Listener set");
        } else {
            Log.e(TAG, "setOnPlayPauseClickListener: playPauseButton is null");
        }
    }

    public void setOnNextClickListener(View.OnClickListener listener) {
        if (nextButton != null) {
            nextButton.setOnClickListener(listener);
            Log.d(TAG, "setOnNextClickListener: Listener set");
        } else {
            Log.e(TAG, "setOnNextClickListener: nextButton is null");
        }
    }

    public void setOnRootClickListener(View.OnClickListener listener) {
        if (rootView != null) {
            rootView.setOnClickListener(listener);
            Log.d(TAG, "setOnRootClickListener: Listener set");
        } else {
            Log.e(TAG, "setOnRootClickListener: rootView is null");
        }
    }
}