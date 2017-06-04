package com.dhananjay.oaudioplayer.services;


import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import com.dhananjay.oaudioplayer.R;
import com.dhananjay.oaudioplayer.model.MediaItem;

import java.io.IOException;
import java.util.ArrayList;

/**
 * {@link Service} class to perform media play with notification in status bar
 *
 * @author Dhananjay Kumar
 */
public class MediaService extends Service implements AudioManager.OnAudioFocusChangeListener {
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
    private AudioManager audioManager;
    //Handle incoming phone calls
    private boolean ongoingCall = false;
    private PhoneStateListener phoneStateListener;
    private TelephonyManager telephonyManager;

    private IBinder mBinder = new MyBinder();

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

    private MediaPlayer.OnPreparedListener mMediaPrepared = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(MediaPlayer mp) {
            Log.d(TAG, "MediaPlayer.onPrepared");
            mIsReady = true;
            mIsBuffering = false;
            onCommandPlay();
        }
    };

    /**
     * {@link BroadcastReceiver} for media play, pause, next and previous button actions
     */
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
    //Becoming noisy
    private BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //pause audio on ACTION_AUDIO_BECOMING_NOISY
            onCommandPause();
        }
    };

    /**
     * Creates {@link MediaService} {@link Intent} with the provided playlist
     *
     * @param context  Context from where the service is starting
     * @param playlist provide media items to play
     * @return Intent for starting {@link MediaService}
     */
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

        // Pause MediaPlayer on incoming call,
        // Resume on hangup.
        callStateListener();
        //ACTION_AUDIO_BECOMING_NOISY -- change in audio outputs -- BroadcastReceiver
        registerBecomingNoisyReceiver();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mButtonReceiver);
        if (mMediaPlayer != null) mMediaPlayer.release();
        removeAudioFocus();
        //Disable the PhoneStateListener
        if (phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        //unregister BroadcastReceivers
        unregisterReceiver(becomingNoisyReceiver);
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

    /**
     * Performs actions related to media player when Service onStartCommand method is called
     *
     * @param playlist {@link ArrayList} of {@link MediaItem}s
     */
    private void onCommandStart(ArrayList<MediaItem> playlist) {
        //Request audio focus
        if (!requestAudioFocus()) {
            //Could not gain focus
            stopSelf();
        }
        RemoteViews collapsed = new RemoteViews(getPackageName(), R.layout.notification_collapsed);
        RemoteViews bigContentView = new RemoteViews(getPackageName(), R.layout.notification_expanded);

        mPlaylist = playlist;
        mCurrent = null;

        mRemoteViews = new ArrayList<>(2);
        mRemoteViews.add(collapsed);
        mRemoteViews.add(bigContentView);

        // Create Notifications with remote views
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

    /**
     * Update Remote Views of notification bar
     */
    private void updateRemoteViews() {
        for (RemoteViews remoteViews : mRemoteViews) {
            remoteViews.setOnClickPendingIntent(R.id.ib_media_quit,
                    getButtonPendingIntent(ACTION_QUIT));
            remoteViews.setOnClickPendingIntent(R.id.ib_track_prev,
                    getButtonPendingIntent(ACTION_PREV));
            remoteViews.setOnClickPendingIntent(R.id.ib_track_play,
                    getButtonPendingIntent(ACTION_PLAY));
            remoteViews.setOnClickPendingIntent(R.id.ib_track_next,
                    getButtonPendingIntent(ACTION_NEXT));

            remoteViews.setTextViewText(R.id.tv_track_title,
                    (mCurrent != null ? mCurrent.getTitle() : getString(R.string.loading)));

            if (mCurrent != null) {
                if (mTrackImageId == 0 || !mTrackImageUrl.equals(mCurrent.getImage())) {
                    mTrackImageUrl = mCurrent.getImage();
                    mTrackImageId = getResources().getIdentifier(mTrackImageUrl,
                            "drawable", getPackageName());
                }
            } else {
                mTrackImageId = 0;
            }

            remoteViews.setImageViewResource(R.id.iv_track_image, mTrackImageId);

            remoteViews.setBoolean(R.id.ib_track_prev, "setEnabled", canGoPrev());
            remoteViews.setBoolean(R.id.ib_track_play, "setEnabled", mIsReady);
            remoteViews.setBoolean(R.id.ib_track_next, "setEnabled", canGoNext());

            remoteViews.setViewVisibility(R.id.pb_track_buffering,
                    mIsBuffering ? View.VISIBLE : View.GONE);
            remoteViews.setViewVisibility(R.id.ib_track_play,
                    !mIsBuffering ? View.VISIBLE : View.GONE);

            if (mIsReady) {
                try {
                    if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                        remoteViews.setImageViewResource(R.id.ib_track_play,
                                android.R.drawable.ic_media_pause);
                    } else {
                        remoteViews.setImageViewResource(R.id.ib_track_play,
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

    /**
     * Perform actions if Player is not closing
     */
    private void onCommandQuit() {
        mIsReady = false;
        if (mMediaPlayer != null) mMediaPlayer.reset();
        stopForeground(true);
        stopSelf();
    }

    /**
     * Toggle the Play Pause action on media player
     */
    private void onCommandPlayPause() {
        try {
            if (mMediaPlayer == null) {
                initMediaPlayerIfClosed();
            } else if (mMediaPlayer.isPlaying()) {
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

    /**
     * Plays the Media player
     */
    private void onCommandPlay() {
        try {
            mMediaPlayer.start();
            updateRemoteViews();
        } catch (IllegalStateException e) {
            Log.e(TAG, "onCommandPlay", e);
            onCommandQuit();
        }
    }

    /**
     * Initiate the media player if it's closed
     *
     * @return true if media player was closed before
     */
    private boolean initMediaPlayerIfClosed() {
        if (mMediaPlayer == null) {
            if (!requestAudioFocus()) {
                //Could not gain focus
                stopSelf();
            } else {
                mMediaPlayer = new MediaPlayer();
                startPlaying(mCurrent);
            }
            return true;
        }
        return false;
    }

    /**
     * Pause the media player if playing
     */
    private void onCommandPause() {
        try {
            mMediaPlayer.pause();
            updateRemoteViews();
        } catch (IllegalStateException e) {
            Log.e(TAG, "onCommandPause", e);
            onCommandQuit();
        }
    }

    /**
     * Checks if there is any item for previous action
     *
     * @return true if any previous media item exists
     */
    private boolean canGoPrev() {
        return (mPlaylist != null && mCurrent != null &&
                mPlaylist.indexOf(mCurrent) - 1 >= 0);
    }

    /**
     * Checks if there is any item for next action
     *
     * @return true if any next media item exists
     */
    private boolean canGoNext() {
        return (mPlaylist != null && mCurrent != null &&
                mPlaylist.indexOf(mCurrent) + 1 < mPlaylist.size());
    }

    /**
     * Play previous media-item
     */
    private void onCommandPrev() {
        int index = mPlaylist.indexOf(mCurrent) - 1;
        if (index >= 0) {
            startPlaying(mPlaylist.get(index));
        }
    }

    /**
     * Play next media-item
     */
    private void onCommandNext() {
        int index = mPlaylist.indexOf(mCurrent) + 1;
        if (index < mPlaylist.size()) {
            startPlaying(mPlaylist.get(index));
        }
    }

    /**
     * Start playing the provided media item
     *
     * @param item media-item to play
     */
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

    @Override
    public void onAudioFocusChange(int focusState) {
        //Invoked when the audio focus of the system is updated.
        switch (focusState) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // resume playback
                if (!initMediaPlayerIfClosed()) {
                    if (!mMediaPlayer.isPlaying()) onCommandPlay();
                }
                mMediaPlayer.setVolume(1.0f, 1.0f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time: stop playback and release media player
                if (mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
                updateRemoteViews();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                if (mMediaPlayer.isPlaying()) onCommandPause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                if (mMediaPlayer.isPlaying()) mMediaPlayer.setVolume(0.1f, 0.1f);
                break;
        }
    }

    /**
     * Requests Audio focus
     *
     * @return true if audio focus is gained
     */
    private boolean requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            //Focus gained
            return true;
        }
        //Could not gain focus
        return false;
    }

    /**
     * removes audio focus
     *
     * @return true if audio focus is removed or doesn't exist here
     */
    private boolean removeAudioFocus() {
        return audioManager == null || AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager.abandonAudioFocus(this);
    }

    /**
     * Register ACTION_AUDIO_BECOMING_NOISY broadcast receiver
     */
    private void registerBecomingNoisyReceiver() {
        //register after getting audio focus
        IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(becomingNoisyReceiver, intentFilter);
    }

    /**
     * Handles incoming phone call events
     */
    private void callStateListener() {
        // Get the telephony manager
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        //Starting listening for PhoneState changes
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                switch (state) {
                    //if at least one call exists or the phone is ringing
                    //pause the MediaPlayer
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                    case TelephonyManager.CALL_STATE_RINGING:
                        if (mMediaPlayer != null) {
                            onCommandPause();
                            ongoingCall = true;
                        }
                        break;
                    case TelephonyManager.CALL_STATE_IDLE:
                        // Phone idle. Start playing.
                        if (mMediaPlayer != null) {
                            if (ongoingCall) {
                                ongoingCall = false;
                                onCommandPlay();
                            }
                        }
                        break;
                }
            }
        };
        // Register the listener with the telephony manager
        // Listen for changes to the device call state.
        telephonyManager.listen(phoneStateListener,
                PhoneStateListener.LISTEN_CALL_STATE);
    }

    public class MyBinder extends Binder {
        public MediaService getService() {
            return MediaService.this;
        }
    }

}