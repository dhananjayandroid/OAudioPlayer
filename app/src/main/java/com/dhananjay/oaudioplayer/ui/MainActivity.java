package com.dhananjay.oaudioplayer.ui;

import android.os.Bundle;
import android.view.View;

import com.dhananjay.oaudioplayer.R;
import com.dhananjay.oaudioplayer.model.MediaItem;

import java.util.ArrayList;

public class MainActivity extends BaseActivity {
    // ArrayList of MediaItems for playlist
    private static final ArrayList<MediaItem> sSamplePlaylist = new ArrayList<>();

    static {
        // Put audio files in raw folder and image in drawable
        sSamplePlaylist.add(new MediaItem("Opus Audio", "my_file", "ic_music"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        findViewById(R.id.btn_media_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(getMediaServiceIntent(sSamplePlaylist));
            }
        });
    }

}