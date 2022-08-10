/*
 * Copyright 2012 Marcelo Vanzin
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.vanzin.ashuffler;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.Serializable;

/**
 * Information about the current track being played.
 * <p>
 * Caches the tags from the track that the player's UI and notifications
 * use, to avoid having to re-fetch them.
 */
class TrackInfo implements Serializable {

    public static final long serialVersionUID = 4735383483858487459L;

    private final String path;
    private final String title;
    private final String album;
    private final String artist;
    private final int trackNumber;
    private final int discNumber;
    private final int duration;
    private int elapsedTime;

    private transient Bitmap artwork;
    private transient boolean artworkIsSet;

    public TrackInfo(String path, MediaMetadataRetriever md, int duration) {
        this.path = path;
        this.title =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        this.album =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        this.artist =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        this.trackNumber = parseInt(
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
        this.duration = duration;

        String discNumberStr =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER);
        if (discNumberStr != null) {
            this.discNumber = parseInt(discNumberStr);
        } else {
            this.discNumber = -1;
        }
    }

    public String getPath() {
        return path;
    }

    public String getTitle() {
        return title;
    }

    public String getAlbum() {
        return album;
    }

    public String getArtist() {
        return artist;
    }

    public int getTrackNumber() {
        return trackNumber;
    }

    public int getDiscNumber() {
        return discNumber;
    }

    public int getDuration() {
        return duration;
    }

    public Bitmap getArtwork() {
        if (!new File(path).isFile()) {
            return null;
        }

        if (!artworkIsSet) {
          MediaMetadataRetriever mmr = new MediaMetadataRetriever();
          mmr.setDataSource(getPath());

          byte[] coverArt = mmr.getEmbeddedPicture();
          if (coverArt != null) {
              artwork = BitmapFactory.decodeByteArray(coverArt, 0, coverArt.length);
          }
        }

        artworkIsSet = true;
        return artwork;
    }

    public int getElapsedTime() {
        return elapsedTime;
    }

    public void setElapsedTime(int time) {
        this.elapsedTime = time;
    }

    private static int parseInt(String intish) {
        if (intish == null) {
            return 1;
        }
        int idx = intish.indexOf("/");
        if (idx == -1) {
            idx = intish.indexOf("-");
        }
        return Integer.parseInt(
            (idx >= 0) ? intish.substring(0, idx) : intish);
    }

}

