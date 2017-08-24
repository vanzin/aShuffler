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

/**
 * Listener interface for PlayerControl events.
 *
 * @see PlayerControl
 */
public interface PlayerListener {

    public static enum TrackState {
        COMPLETE,
        PLAY,
        PAUSE,
        STOP,
    }

    /**
     * Called when the playback state of the current track changes.
     *
     * @param track The affected track info.
     * @param trackState The new playback state of the track.
     */
    void trackStateChanged(TrackInfo track,
                           TrackState trackState);

}

