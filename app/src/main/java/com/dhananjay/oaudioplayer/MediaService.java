package com.dhananjay.oaudioplayer;


import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.dhananjay.oaudioplayer.model.MediaItem;

import java.io.IOException;
import java.util.ArrayList;


public class MediaService extends Service {
    private static final String TAG = MediaService.class.getSimpleName();

    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_START = TAG + ".ACTION_START";
    private static final String ACTION_QUIT = TAG + ".ACTION_QUIT";
    private static final String ACTION_PREV = TAG + ".ACTION_PREV";
    private static final String ACTION_PLAY = TAG + ".ACTION_PLAY";
    private static final String ACTION_NEXT = TAG + ".ACTION_NEXT";

    private static final String EXTRA_PLAYLIST = "extraPlaylist";

    private MediaPlayer mMediaPlayer;
    private boolean mIsBuffering = true;
    private boolean mIsReady = false;

    private Notification mNotification;
    private ArrayList<RemoteViews> mRemoteViews;

    private ArrayList<MediaItem> mPlaylist;
    private MediaItem mCurrent;

    private String mTrackImageUrl;
    private int mTrackImageId;
    private IBinder mBinder = new MyBinder();

    private MediaPlayer.OnPreparedListener mMediaPrepared = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(MediaPlayer mp) {
            Log.d(TAG, "MediaPlayer.onPrepared");
            mIsReady = true;
            mIsBuffering = false;
            onCommandPlay();
        }
    };

    private MediaPlayer.OnCompletionListener mMediaCompleted = new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
            Log.d(TAG, "MediaPlayer.onCompletion");
            onCommandQuit();
        }
    };

    private MediaPlayer.OnInfoListener mMediaInfo = new MediaPlayer.OnInfoListener() {
        @Override
        public boolean onInfo(MediaPlayer mp, int what, int extra) {
            Log.d(TAG, "MediaPlayer.onInfo: " + what + ", " + extra);
            switch (what) {
                case MediaPlayer.MEDIA_INFO_BUFFERING_START:
                    mIsBuffering = true;
                    updateRemoteViews();
                    break;

                case MediaPlayer.MEDIA_INFO_BUFFERING_END:
                    mIsBuffering = false;
                    updateRemoteViews();
                    break;
            }

            return true;
        }
    };

    private MediaPlayer.OnErrorListener mMediaError = new MediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            Log.e(TAG, "MediaPlayer.onError: " + what + ", " + extra);
            onCommandQuit();
            return true;
        }
    };

    private BroadcastReceiver mButtonReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            Log.d(TAG, "onReceive: " + intent.getAction());

            if (ACTION_QUIT.equals(action)) {
                onCommandQuit();
            } else if (ACTION_PREV.equals(action)) {
                onCommandPrev();
            } else if (ACTION_PLAY.equals(action)) {
                onCommandPlayPause();
            } else if (ACTION_NEXT.equals(action)) {
                onCommandNext();
            }
        }
    };

    public static Intent createPlaylistIntent(Context context, ArrayList<MediaItem> playlist) {
        return new Intent(context, MediaService.class).setAction(ACTION_START)
                .putParcelableArrayListExtra(EXTRA_PLAYLIST, playlist);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "onBind");
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mMediaPlayer = new MediaPlayer();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_QUIT);
        intentFilter.addAction(ACTION_PREV);
        intentFilter.addAction(ACTION_PLAY);
        intentFilter.addAction(ACTION_NEXT);

        registerReceiver(mButtonReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mButtonReceiver);
        mMediaPlayer.release();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent.getAction();

        Log.d(TAG, "onStartCommand: " + intent.getAction());

        if (ACTION_START.equals(action)) {
            ArrayList<MediaItem> playlist = intent.getParcelableArrayListExtra(EXTRA_PLAYLIST);
            onCommandStart(playlist);
            return START_STICKY;
        }

        stopSelf();
        return Service.START_STICKY_COMPATIBILITY;
    }

    private void onCommandStart(ArrayList<MediaItem> playlist) {
        RemoteViews collapsed = new RemoteViews(getPackageName(), R.layout.notification_collapsed);
        RemoteViews bigContentView = new RemoteViews(getPackageName(), R.layout.notification_expanded);

        mPlaylist = playlist;
        mCurrent = null;

        mRemoteViews = new ArrayList<>(2);
        mRemoteViews.add(collapsed);
        mRemoteViews.add(bigContentView);

        mNotification = new NotificationCompat.Builder(this).setTicker("Media Service started...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContent(collapsed)
                .setAutoCancel(false)
                .setOngoing(true)
                .setCustomBigContentView(bigContentView)
                .build();

        updateRemoteViews();

        startForeground(NOTIFICATION_ID, mNotification);

        startPlaying(mPlaylist.get(0));
    }

    private PendingIntent getButtonPendingIntent(String action) {
        Intent intent = new Intent(action);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateRemoteViews() {
        for (RemoteViews remoteViews : mRemoteViews) {
            remoteViews.setOnClickPendingIntent(R.id.media_quit,
                    getButtonPendingIntent(ACTION_QUIT));
            remoteViews.setOnClickPendingIntent(R.id.track_prev,
                    getButtonPendingIntent(ACTION_PREV));
            remoteViews.setOnClickPendingIntent(R.id.track_play,
                    getButtonPendingIntent(ACTION_PLAY));
            remoteViews.setOnClickPendingIntent(R.id.track_next,
                    getButtonPendingIntent(ACTION_NEXT));

            remoteViews.setTextViewText(R.id.track_title,
                    (mCurrent != null ? mCurrent.getTitle() : getString(R.string.loading)));

            if (mCurrent != null) {
                if (mTrackImageUrl == null || !mTrackImageUrl.equals(mCurrent.getImage())) {
                    mTrackImageUrl = mCurrent.getImage();
                    mTrackImageId = getResources().getIdentifier(mTrackImageUrl,
                            "drawable", getPackageName());
                }
            } else {
                mTrackImageId = 0;
            }

            remoteViews.setImageViewResource(R.id.track_image, mTrackImageId);

            remoteViews.setBoolean(R.id.track_prev, "setEnabled", canGoPrev());
            remoteViews.setBoolean(R.id.track_play, "setEnabled", mIsReady);
            remoteViews.setBoolean(R.id.track_next, "setEnabled", canGoNext());

            remoteViews.setViewVisibility(R.id.track_buffering,
                    mIsBuffering ? View.VISIBLE : View.GONE);
            remoteViews.setViewVisibility(R.id.track_play,
                    !mIsBuffering ? View.VISIBLE : View.GONE);

            if (mIsReady) {
                try {
                    if (mMediaPlayer.isPlaying()) {
                        remoteViews.setImageViewResource(R.id.track_play,
                                android.R.drawable.ic_media_pause);
                    } else {
                        remoteViews.setImageViewResource(R.id.track_play,
                                android.R.drawable.ic_media_play);
                    }
                } catch (IllegalStateException e) {
                    //
                }
            }
        }

        NotificationManagerCompat managerCompat = NotificationManagerCompat.from(this);
        managerCompat.notify(NOTIFICATION_ID, mNotification);
    }

    private void onCommandQuit() {
        mIsReady = false;
        mMediaPlayer.reset();
        stopForeground(true);
        stopSelf();
    }

    private void onCommandPlayPause() {
        try {
            if (mMediaPlayer.isPlaying()) {
                onCommandPause();
            } else {
                onCommandPlay();
            }
            updateRemoteViews();
        } catch (IllegalStateException e) {
            Log.e(TAG, "onCommandPlayPause", e);
            onCommandQuit();
        }
    }

    private void onCommandPlay() {
        try {
            mMediaPlayer.start();
            updateRemoteViews();
        } catch (IllegalStateException e) {
            Log.e(TAG, "onCommandPlay", e);
            onCommandQuit();
        }
    }

    private void onCommandPause() {
        try {
            mMediaPlayer.pause();
            updateRemoteViews();
        } catch (IllegalStateException e) {
            Log.e(TAG, "onCommandPause", e);
            onCommandQuit();
        }
    }

    private boolean canGoPrev() {
        return (mPlaylist != null && mCurrent != null &&
                mPlaylist.indexOf(mCurrent) - 1 >= 0);
    }

    private boolean canGoNext() {
        return (mPlaylist != null && mCurrent != null &&
                mPlaylist.indexOf(mCurrent) + 1 < mPlaylist.size());
    }

    private void onCommandPrev() {
        int index = mPlaylist.indexOf(mCurrent) - 1;
        if (index >= 0) {
            startPlaying(mPlaylist.get(index));
        }
    }

    private void onCommandNext() {
        int index = mPlaylist.indexOf(mCurrent) + 1;
        if (index < mPlaylist.size()) {
            startPlaying(mPlaylist.get(index));
        }
    }

    private void startPlaying(MediaItem item) {
        mCurrent = item;
        try {
            mIsReady = false;
            mIsBuffering = true;
            mMediaPlayer.reset();
            mMediaPlayer.setOnPreparedListener(mMediaPrepared);
            mMediaPlayer.setOnCompletionListener(mMediaCompleted);
            mMediaPlayer.setOnInfoListener(mMediaInfo);
            mMediaPlayer.setOnErrorListener(mMediaError);
            AssetFileDescriptor afd = getResources().openRawResourceFd(getResources().getIdentifier(item.getLocation(),
                    "raw", getPackageName()));
            mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mMediaPlayer.prepareAsync();

            updateRemoteViews();
        } catch (IOException e) {
            Log.e(TAG, "startPlaying", e);
            onCommandQuit();
        }
    }

    public String showDebugConnectedMsg() {
        return "Service is connected";
    }

    public class MyBinder extends Binder {
        public MediaService getService() {
            return MediaService.this;
        }
    }
}