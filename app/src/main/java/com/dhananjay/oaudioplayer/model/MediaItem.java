package com.dhananjay.oaudioplayer.model;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Created by Dhananjay on 02-06-2017.
 */
public class MediaItem implements Parcelable {
    public static final Parcelable.Creator<MediaItem> CREATOR = new Parcelable.Creator<MediaItem>() {
        @Override
        public MediaItem createFromParcel(Parcel source) {
            return new MediaItem(source);
        }

        @Override
        public MediaItem[] newArray(int size) {
            return new MediaItem[size];
        }
    };
    private String mTitle;
    private String mLocation;
    private String mImage;

    public MediaItem(String title, String location, String image) {
        mTitle = title;
        mLocation = location;
        mImage = image;
    }

    public MediaItem(Parcel source) {
        mTitle = source.readString();
        mLocation = source.readString();
        mImage = source.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mTitle);
        dest.writeString(mLocation);
        dest.writeString(mImage);
    }

    public String getTitle() {
        return mTitle;
    }

    public String getLocation() {
        return mLocation;
    }

    public String getImage() {
        return mImage;
    }

    @Override
    public int describeContents() {
        return 0;
    }
}