/*
 * Copyright 2013-2018 Marcelo Vanzin
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

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Encapsulate Android's MediaPlayer and provides thread-safe
 * functionality around it.
 */
public class Player {

    private enum State {
        NEW,
        PREPARED,
        RELEASED;
    }

    private State state;
    private MediaPlayer next;
    private String nextTrack;
    private TrackInfo info;
    private TrackInfo nextInfo;
    private boolean isMetadataSet;

    private final List<PlayerListener> listeners;
    private final MediaPlayer current;
    private final String track;
    private final PlayerService service;
    private final MediaSessionCompat session;
    private final PlaybackStateCompat.Builder playbackState;

    public Player(PlayerService service,
        MediaSessionCompat session,
        String track,
        TrackInfo info,
        List<PlayerListener> listeners) throws IOException
    {
        this.service = service;
        this.session = session;
        this.playbackState = new PlaybackStateCompat.Builder();
        this.state = State.NEW;
        this.current = new MediaPlayer();
        this.current.setWakeMode(service, PowerManager.PARTIAL_WAKE_LOCK);
        this.current.setDataSource(track);
        this.track = track;
        this.listeners = listeners;
        this.info = info;
    }

    private Player(PlayerService service,
        MediaSessionCompat session,
        MediaPlayer player,
        String track,
        TrackInfo info,
        List<PlayerListener> listeners)
    {
        this.service = service;
        this.session = session;
        this.playbackState = new PlaybackStateCompat.Builder();
        this.current = player;
        this.track = track;
        this.info = info;
        this.listeners = listeners;
        this.state = State.PREPARED;
    }

    public synchronized boolean isValid() {
      return new File(track).isFile();
    }

    public synchronized void pause() {
        if (current.isPlaying()) {
            current.pause();
            updateSessionState(PlaybackStateCompat.STATE_PAUSED, getInfo().getElapsedTime());
            fireTrackStateChange(PlayerListener.TrackState.PAUSE);
        }
    }

    public synchronized void play(int startPos) {
        if (isPlaying()) {
            throw new IllegalStateException();
        }

        try {
            prepare();
        } catch (IOException ioe) {
            Log.warn("Error preparing playback: %s",
                ioe.getMessage());
        }

        if (startPos != 0) {
            current.seekTo(startPos);
        }
        current.start();
        updateSessionState(PlaybackStateCompat.STATE_PLAYING, startPos);
        fireTrackStateChange(PlayerListener.TrackState.PLAY);
    }

    public synchronized boolean playPause() {
        try {
            prepare();
        } catch (IOException ioe) {
            Log.warn("Error preparing playback: %s",
                ioe.getMessage());
            return false;
        }
        if (current.isPlaying()) {
            current.pause();
            updateSessionState(PlaybackStateCompat.STATE_PAUSED, getInfo().getElapsedTime());
            fireTrackStateChange(PlayerListener.TrackState.PAUSE);
            return false;
        } else {
            current.start();
            updateSessionState(PlaybackStateCompat.STATE_PLAYING, getInfo().getElapsedTime());
            fireTrackStateChange(PlayerListener.TrackState.PLAY);
            return true;
        }
    }

    public synchronized void stop() {
        if (isPlaying()) {
            current.stop();
            updateSessionState(PlaybackStateCompat.STATE_STOPPED, 0L);
            fireTrackStateChange(PlayerListener.TrackState.STOP);
        }
    }

    public synchronized TrackInfo save() {
        if (isPlaying()) {
            current.pause();
        }
        TrackInfo info = getInfo();
        current.stop();
        return info;
    }

    public synchronized void release() {
        current.release();
        if (next != null) {
            next.release();
        }
        state = State.RELEASED;
    }

    public synchronized Player complete() {
        current.release();
        state = State.RELEASED;
        fireTrackStateChange(PlayerListener.TrackState.COMPLETE);

        if (next != null) {
            Player nextPlayer = new Player(service, session, next, nextTrack,
                nextInfo, listeners);
            nextPlayer.play(0);
            return nextPlayer;
        }

        updateSessionState(PlaybackStateCompat.STATE_STOPPED, 0L);
        return null;
    }

    public synchronized void setNext(String track) throws IOException {
        MediaPlayer next = new MediaPlayer();
        next.setWakeMode(service, PowerManager.PARTIAL_WAKE_LOCK);
        next.setDataSource(track);
        next.prepare();
        nextInfo = loadInfo(track, next);
        //current.setNextMediaPlayer(next);
        this.next = next;
        this.nextTrack = track;
    }

    public synchronized TrackInfo getInfo() {
        if (info == null) {
            try {
                prepare();
            } catch (IOException ioe) {
                Log.warn("Exception preparing: %s", ioe.getMessage());
                return null;
            }
            info = loadInfo(track, current);
        }
        if (state == State.PREPARED) {
          info.setElapsedTime(current.getCurrentPosition());
        }
        return info;
    }

    public synchronized void setInfo(TrackInfo info) {
        this.info = info;
    }

    public synchronized void seekTo(int newPos) {
        current.seekTo(newPos);
    }

    public synchronized void setOnCompletionListener(
        MediaPlayer.OnCompletionListener listener)
    {
        current.setOnCompletionListener(listener);
    }

    public synchronized boolean isPlaying() {
        return state == State.PREPARED && current.isPlaying();
    }

    public String getTrack() {
        return track;
    }

    private void updateSessionState(int state, long position) {
        long actions = PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
            PlaybackStateCompat.ACTION_PLAY_PAUSE;
        switch (state) {
            case PlaybackStateCompat.STATE_PAUSED:
            case PlaybackStateCompat.STATE_PLAYING:
                actions |= PlaybackStateCompat.ACTION_STOP;
                break;
            default:
        }

        session.setPlaybackState(
            playbackState
                .setState(state, position, 1.0f)
                .setActions(actions)
                .build());

        if (!isMetadataSet) {
            TrackInfo info = getInfo();
            MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.getAlbum())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, info.getArtist())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, info.getDuration())
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, info.getTrackNumber());


            Bitmap artwork = info.getArtwork();
            if (artwork != null) {
                mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
            }

            session.setMetadata(mb.build());
            isMetadataSet = true;
        }
    }

    private void prepare() throws IOException {
        switch (state) {
        case RELEASED:
            throw new IllegalStateException();
        case PREPARED:
            return;
        }
        current.prepare();
        state = State.PREPARED;

        if (info == null) {
            info = loadInfo(track, current);
        }
    }

    private TrackInfo loadInfo(String track, MediaPlayer mp) {
        MediaMetadataRetriever md = new MediaMetadataRetriever();
        try {
            md.setDataSource(track);
            return new TrackInfo(track, md, mp.getDuration());
        } finally {
            md.release();
        }
    }


    private void fireTrackStateChange(PlayerListener.TrackState newState) {
        TrackInfo updated = getInfo();
        synchronized (listeners) {
            for (PlayerListener pl : listeners) {
                pl.trackStateChanged(updated, newState);
            }
        }
    }

}

