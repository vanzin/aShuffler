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

import android.media.MediaMetadataRetriever;

import java.io.Serializable;

class TrackInfo implements Serializable {

    public static final long serialVersionUID = 4735383483858487457L;

    private final String title;
    private final String album;
    private final String artist;
    private final int trackNumber;
    private final int discNumber;
    private final int duration;
    private String artwork;

    public TrackInfo(MediaMetadataRetriever md, int duration) {
        this.title =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        this.album =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        this.artist =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        this.trackNumber = Integer.parseInt(
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
        this.duration = duration;

        String discNumberStr =
            md.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER);
        if (discNumberStr != null) {
            this.discNumber = Integer.parseInt(discNumberStr);
        } else {
            this.discNumber = -1;
        }
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

    public String getArtwork() {
        return artwork;
    }

    public void setArtwork(String artwork) {
        this.artwork = artwork;
    }

}

