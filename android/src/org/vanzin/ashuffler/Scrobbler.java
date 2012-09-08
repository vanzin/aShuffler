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

import android.content.Context;
import android.content.Intent;

/**
 * Listener that implements Last.fm scrobbling.
 * <p>
 * Currently uses the Simple Last.fm Scrobbler API.
 * <p>
 * See: http://code.google.com/p/a-simple-lastfm-scrobbler/wiki/Developers
 */
class Scrobbler implements PlayerListener {

    private final static int ST_START = 0;
    private final static int ST_RESUME = 1;
    private final static int ST_PAUSE = 2;
    private final static int ST_COMPLETE = 3;

    private boolean isPaused;
    private Context context;

    public Scrobbler(Context context) {
        this.context = context;
    }

    @Override
    public void trackStateChanged(PlayerState state,
                                  TrackInfo track,
                                  PlayerListener.TrackState trackState) {
        switch (trackState) {
        case COMPLETE:
            scrobble(track, ST_COMPLETE);
            isPaused = false;
            break;
        case PAUSE:
            scrobble(track, ST_PAUSE);
            isPaused = true;
            break;
        case PLAY:
            scrobble(track, isPaused ? ST_RESUME : ST_START);
            isPaused = false;
            break;
        case STOP:
            scrobble(track, ST_PAUSE);
            isPaused = false;
            break;
        }
    }

    private void scrobble(TrackInfo track, int state) {
        Intent i = new Intent("com.adam.aslfms.notify.playstatechanged");
        i.putExtra("app-name", "Album Shuffler");
        i.putExtra("app-package", "org.vanzin.ashuffler");
        i.putExtra("state", state);
        i.putExtra("artist", track.getArtist());
        i.putExtra("track", track.getTitle());
        i.putExtra("album", track.getAlbum());
        i.putExtra("duration", track.getDuration() / 1000);
        context.sendBroadcast(i);
    }

}

