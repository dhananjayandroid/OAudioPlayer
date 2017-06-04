package com.dhananjay.oaudioplayer.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.dhananjay.oaudioplayer.services.MediaService;
import com.dhananjay.oaudioplayer.model.MediaItem;

import java.util.ArrayList;

/**
 * Base Activity class for all other activities containing common functionality extends {@link AppCompatActivity}
 *
 * @author Dhananjay Kumar
 */
public class BaseActivity extends AppCompatActivity {
    private static final String TAG = BaseActivity.class.getSimpleName();
    private boolean mServiceBound = false;
    private Intent serviceIntent;
    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound = false;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MediaService.MyBinder myBinder = (MediaService.MyBinder) service;
            MediaService mMediaService = myBinder.getService();
            Log.d(TAG, mMediaService.showDebugConnectedMsg());
            mServiceBound = true;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * Creates {@link MediaService} {@link Intent} with provided playlist
     *
     * @param sSamplePlaylist Arraylist of {@link MediaItem} for playlist
     * @return MediaService intent
     */
    protected Intent getMediaServiceIntent(ArrayList<MediaItem> sSamplePlaylist) {
        if (serviceIntent == null)
            serviceIntent = MediaService.createPlaylistIntent(this, sSamplePlaylist);
        return serviceIntent;
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        connectToService();
    }

    /**
     * binds the {@link MediaService}
     */
    private void connectToService() {
        Log.d(TAG, "connectToService()");
        Intent intent = new Intent(this, MediaService.class);
        bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Unbinds the {@link MediaService}
     */
    private void unbindService() {
        if (mServiceBound) {
            Log.d(TAG, "unbindService()");
            unbindService(mServiceConnection);
            mServiceBound = false;
        }
    }
}