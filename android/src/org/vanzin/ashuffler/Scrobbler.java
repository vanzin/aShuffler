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
 * Currently uses the ScrobbleDroid API.
 * <p>
 * See: http://code.google.com/p/scrobbledroid/wiki/DeveloperAPI
 */
class Scrobbler implements PlayerListener {

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
            scrobble(track, false);
            break;
        case PAUSE:
            scrobble(track, false);
            break;
        case PLAY:
            scrobble(track, true);
            break;
        case STOP:
            scrobble(track, false);
            break;
        }
    }

    private void scrobble(TrackInfo track, boolean playing) {
        Intent i = new Intent("net.jjc1138.android.scrobbler.action.MUSIC_STATUS");
        i.putExtra("playing", playing);
        i.putExtra("artist", track.getArtist());
        i.putExtra("track", track.getTitle());
        i.putExtra("album", track.getAlbum());
        i.putExtra("secs", track.getDuration() / 1000);
        context.sendBroadcast(i);
    }

}

