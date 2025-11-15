package com.example.promusic;

import static androidx.media3.common.MediaLibraryInfo.TAG;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int SPLASH_DISPLAY_LENGTH = 2000;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        handler = new Handler(Looper.getMainLooper());

        TextView textView = findViewById(R.id.text_view);
        ImageView iconView = findViewById(R.id.icon_view);
        String fullText = getString(R.string.app_name);


        ObjectAnimator iconFadeIn = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f);
        iconFadeIn.setDuration(500);
        iconFadeIn.start();

        textView.setText("");
        int[] index = {0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (index[0] <= fullText.length()) {
                    if (index[0] < fullText.length()) {
                        textView.append(String.valueOf(fullText.charAt(index[0])));
                        ObjectAnimator scaleX = ObjectAnimator.ofFloat(textView, "scaleX", 0.5f, 1f);
                        ObjectAnimator scaleY = ObjectAnimator.ofFloat(textView, "scaleY", 0.5f, 1f);
                        scaleX.setDuration(150);
                        scaleY.setDuration(150);
                        scaleX.start();
                        scaleY.start();
                    }
                    index[0]++;
                    handler.postDelayed(this, 150);
                }
            }
        }, 500);


        checkAndRequestPermissions();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void checkAndRequestPermissions() {
        Log.d(TAG, "checkAndRequestPermissions: SDK=" + Build.VERSION.SDK_INT);

        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33) {

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }


        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG, "checkAndRequestPermissions: All permissions granted");
            checkMediaAccess();
        } else {
            Log.d(TAG, "checkAndRequestPermissions: Requesting permissions: " + permissionsToRequest);
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void checkMediaAccess() {

        try {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.Audio.Media._ID};
            Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                cursor.close();
                Log.d(TAG, "Media access confirmed");
                goToMainActivity();
            } else {
                throw new SecurityException("Cannot access media");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Media access denied", e);

            String[] permissions = Build.VERSION.SDK_INT >= 33 ?
                    new String[]{Manifest.permission.READ_MEDIA_AUDIO} :
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {

                checkMediaAccess();
            } else {
                Toast.makeText(this, "Permission is not given - the songs will not load. Allow in settings!", Toast.LENGTH_LONG).show();

                handler.postDelayed(() -> {
                    startActivity(new Intent(SplashActivity.this, MainActivity.class));
                    finish();
                }, 2000);
            }
        }
    }

    private void goToMainActivity() {
        handler.postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, SPLASH_DISPLAY_LENGTH);
    }
}