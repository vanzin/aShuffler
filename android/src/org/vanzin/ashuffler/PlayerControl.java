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

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.provider.MediaStore.Audio.Albums;
import android.provider.MediaStore.Audio.AlbumColumns;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Main media player control class.
 * <p>
 * This class takes care of all the logic related to keeping the list
 * of things to be played, to responding to system events. It can be
 * retrieved by binding to PlayerService.
 * <p>
 * Interaction with the control is done by enqueueing commands by
 * calling {@link #runCommand(Command)}. Commands are handled
 * sequentially in a separate worker thread, as to not block the
 * caller.
 * <p>
 * Interesting behaviors include:
 *
 * <ul>
 *   <li>Loading track metadata.</li>
 *   <li>Monitoring storage state and checking when the monitored
 *   folders change.</li>
 *   <li>Responding to audio focus events so playback is played
 *   when incoming calls are coming (or other similar events).</li>
 *   <li>Scrobbling track info using the Scrobble Droid API.</li>
 *   <li>Sending events to interested listeners.</li>
 *   <li>Controlling service state based on the playback state.</li>
 * </ul>
 *
 * Notably, this class will keep the service running in the foreground
 * while playback is active (either playing or paused). This will cause
 * a notification to be active. When stopped, the service will be sent
 * to the background, and will only remain active in case there are
 * bound activities.
 * <p>
 * There are two state files kept by this class. One is the serialized
 * PlayerState, which contains the shuffled folders to be player. The
 * other is the serialized TrackInfo, which is the current track being
 * played.
 */
class PlayerControl extends Binder
    implements AudioManager.OnAudioFocusChangeListener,
               MediaPlayer.OnCompletionListener,
               Runnable {

    private static final int ONGOING_NOTIFICATION = 10001;

    private final PlayerService service;
    private final Thread worker;
    private final BlockingQueue<Command> commands;
    private final BroadcastReceiver bcastReceiver;
    private final AudioManager audioManager;
    private final ComponentName remoteControl;

    private boolean pausedByFocusLoss;
    private boolean serviceStarted;
    private PlayerState state;
    private MediaPlayer current;
    private TrackInfo currentInfo;
    private List<PlayerListener> listeners;

    /**
     * Initializes the player control.
     * <p>
     * Set up the storage monitor, and load all saved state.
     */
    public PlayerControl(PlayerService service) {
        this.service = service;
        this.listeners = new LinkedList<PlayerListener>();
        this.commands = new LinkedBlockingQueue<Command>();
        this.audioManager = (AudioManager)
            service.getSystemService(Context.AUDIO_SERVICE);
        this.remoteControl = new ComponentName(service.getPackageName(),
            RemoteControlMonitor.class.getName());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);

        this.bcastReceiver = new StorageMonitor();
        service.registerReceiver(bcastReceiver, filter);

        state = loadObject(PlayerState.class);
        if (state == null) {
            state = new PlayerState();
        }
        currentInfo = loadObject(TrackInfo.class);
        checkFolders();

        worker = new Thread(this);
        worker.start();
    }

    /**
     * Get the current MediaPlayer instance.
     * <p>
     * Note that this is not thread-safe.
     */
    public MediaPlayer getPlayer() {
        return current;
    }

    /**
     * Return the current player state.
     * <p>
     * The player state is not thread-safe. It's not recommended
     * to have other classes modify it.
     */
    public PlayerState getState() {
        return state;
    }

    /**
     * Get the current playing track's info.
     * <p>
     * As with other "getters", not thread-safe.
     */
    public TrackInfo getCurrentInfo() {
        if (state == null) {
            getState();
        }
        return currentInfo;
    }

    /**
     * Shutdown the player.
     * <p>
     * Stop the worker thread, stop playback, and save all state.
     */
    public void shutdown() {
        worker.interrupt();
        try {
            worker.join();
        } catch (InterruptedException ie) {
            Log.warn("Wait interrupted.");
        }
        stop(true);
        saveObject(state);
        saveObject(currentInfo);
        service.unregisterReceiver(bcastReceiver);
    }

    public void addPlayerListener(PlayerListener pl) {
        listeners.add(pl);
    }

    public void removePlayerListener(PlayerListener pl) {
        listeners.remove(pl);
    }

    /* Playback control. */

    /**
     * Commands available for enqueueing.
     */
    public static enum Command {
        CHECK_FOLDERS,
        NEXT_FOLDER,
        NEXT_TRACK,
        PREV_FOLDER,
        PREV_TRACK,
        PLAY_PAUSE,
        SET_AUDIO_FOCUS,
        STOP,
        STOP_AND_SAVE,
        UNSET_AUDIO_FOCUS,
    }

    /**
     * Enqueue a command for execution.
     * <p>
     * Returns immediately. Command is executed asynchronous and there
     * is no way to monitor completion, unless it causes some
     * side-effect (like starting playback, which fires an event).
     */
    public void runCommand(Command cmd) {
        Log.debug("RUN: %s", cmd.name());
        commands.offer(cmd);
    }

    /**
     * Returns whether media is playing.
     * <p>
     * Not thread-safe either.
     */
    public boolean isPlaying() {
        MediaPlayer mp = current;
        return mp != null && mp.isPlaying();
    }

    private void playPause() {
        if (current != null) {
            if (current.isPlaying()) {
                current.pause();
                fireTrackStateChange(PlayerListener.TrackState.PAUSE);
            } else {
                current.start();
                fireTrackStateChange(PlayerListener.TrackState.PLAY);
            }
            showNotification();
            pausedByFocusLoss = false;
        } else {
            startPlayback();
        }
    }

    private void stop(boolean mayStopService) {
        if (current != null) {
            try {
                current.stop();
            } finally {
                current.release();
                current = null;
            }
            state.setTrackPosition(0);
            scrobble(false);
        }
        service.stopForeground(true);
        if (mayStopService && serviceStarted) {
            service.stopSelf();
        }
        fireTrackStateChange(PlayerListener.TrackState.STOP);
        pausedByFocusLoss = false;
    }

    private void changeFolder(int delta) {
        loadFolder(delta);
        startPlayback();
    }

    private void changeTrack(int delta) {
        int next = state.getCurrentTrack() + delta;
        while (next < 0) {
            loadFolder(-1);
            next = state.getTracks().size() + next;
        }
        while (next >= state.getTracks().size()) {
            next -= state.getTracks().size();
            loadFolder(1);
        }

        if (next < 0) {
            next = state.getTracks().size() + next;
        }

        state.setCurrentTrack(next);
        state.setTrackPosition(0);
        startPlayback();
    }

    private void loadFolder(int delta) {
        int next = state.getCurrentFolder() + delta;
        if (next < 0) {
            next = state.getFolders().size() +
                (next % state.getFolders().size());
        }
        if (next >= state.getFolders().size()) {
            next = next % state.getFolders().size();
            Collections.shuffle(state.getFolders());

            String current = state.getFolders().get(state.getCurrentFolder());
            if (current.equals(state.getFolders().get(next))) {
                next = (next + 1) % state.getFolders().size();
            }
        }

        // Load the track list for the new album.
        state.setCurrentFolder(next);
        state.setCurrentTrack(0);
        state.setTracks(buildTrackList(state.getFolders().get(next)));
    }

    private void startPlayback() {
        Log.debug("startPlayback()");

        // Prepare the next track.
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(state.getTracks().get(state.getCurrentTrack()));
            mp.prepare();
            Log.debug("startPlayback(): prepared");
        } catch (IOException ioe) {
            Log.warn("Cannot load new track: %s", ioe.getMessage());
            mp.release();
            return;
        }

        // Stop the current track.
        Log.debug("startPlayback(): stopping");
        stop(false);

        // Start playback.
        Log.debug("startPlayback(): starting");
        mp.start();
        mp.setOnCompletionListener(this);
        current = mp;

        // Load track metadata.
        String track = state.getTracks().get(state.getCurrentTrack());
        MediaMetadataRetriever md = new MediaMetadataRetriever();
        try {
            md.setDataSource(track);
            TrackInfo tinfo = new TrackInfo(md, current.getDuration());
            if (currentInfo != null && tinfo.getAlbum().equals(currentInfo.getAlbum())) {
                tinfo.setArtwork(currentInfo.getArtwork());
            }
            currentInfo = tinfo;
        } finally {
            md.release();
        }

        // Load artwork for album.
        if (currentInfo.getArtwork() == null) {
            try {
                String criteria = String.format("%s = '%s' AND %s = '%s'",
                    AlbumColumns.ARTIST, currentInfo.getArtist(),
                    AlbumColumns.ALBUM, currentInfo.getAlbum());

                Cursor cursor = service.getContentResolver().query(
                    Albums.EXTERNAL_CONTENT_URI,
                    new String[] { AlbumColumns.ALBUM_ART },
                    criteria,
                    null,
                    null);
                if (cursor != null) {
                    cursor.moveToFirst();
                    currentInfo.setArtwork(cursor.getString(0));
                } else {
                    currentInfo.setArtwork(null);
                }
            } catch (Exception e) {
                Log.warn("Error querying artwork: %s: %s",
                    e.getClass().getName(), e.getMessage());
            }
        }

        // Put service in foreground.
        if (!serviceStarted) {
            Intent intent = new Intent(service, PlayerService.class);
            service.startService(intent);
            serviceStarted = true;
        }
        showNotification();

        Log.debug("startPlayback(): event");
        for (PlayerListener pl : listeners) {
            pl.playbackStarted(state, currentInfo);
        }
        fireTrackStateChange(PlayerListener.TrackState.PLAY);

        Log.debug("startPlayback(): seeking");
        if (state.getTrackPosition() > 0 &&
            state.getTrackPosition() < mp.getDuration()) {
            mp.seekTo(state.getTrackPosition());
        }
        pausedByFocusLoss = false;
        scrobble(true);
    }

    private void showNotification() {
        String state = current.isPlaying() ? "Playing" : "Paused";
        String msg = String.format("%s: %s - %s",
            state, currentInfo.getArtist(), currentInfo.getTitle());
        Notification notification = new Notification(
            R.drawable.ashuffler, msg, System.currentTimeMillis());

        Intent intent = new Intent(service, Main.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            service, 0, intent, 0);
        notification.setLatestEventInfo(service,
            service.getText(R.string.notification_title),
            msg, pendingIntent);
        service.startForeground(ONGOING_NOTIFICATION, notification);
    }

    private void fireTrackStateChange(PlayerListener.TrackState newState) {
        for (PlayerListener pl : listeners) {
            pl.trackStateChanged(state, newState);
        }
    }

    private void setAudioFocus(boolean focused) {
        if (focused) {
            if (pausedByFocusLoss) {
                if (current != null) {
                    Log.debug("Resuming on audio focus gain.");
                    current.start();
                    fireTrackStateChange(PlayerListener.TrackState.PLAY);
                }
                pausedByFocusLoss = false;
            }
        } else {
            if (current != null && current.isPlaying()) {
                Log.debug("Pausing on audio focus loss.");
                current.pause();
                fireTrackStateChange(PlayerListener.TrackState.PAUSE);
                pausedByFocusLoss = true;
            }
        }
    }

    /**
     * Scrobble Droid support.
     * <p>
     * See: http://code.google.com/p/scrobbledroid/wiki/DeveloperAPI
     *
     * @param playing Whether the current track is playing.
     */
    private void scrobble(boolean playing) {
        Intent i = new Intent("net.jjc1138.android.scrobbler.action.MUSIC_STATUS");
        i.putExtra("playing", playing);
        i.putExtra("artist", currentInfo.getArtist());
        i.putExtra("track", currentInfo.getTitle());
        i.putExtra("album", currentInfo.getAlbum());
        i.putExtra("secs", currentInfo.getDuration());
        service.sendBroadcast(i);
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        runCommand(Command.NEXT_TRACK);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            runCommand(Command.SET_AUDIO_FOCUS);
        } else {
            runCommand(Command.UNSET_AUDIO_FOCUS);
        }
    }

    @Override
    public void run() {
        // Make sure the state is loaded.
        getState();

        audioManager.requestAudioFocus(this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN);

        while (true) {
            try {
                Command cmd = commands.take();
                Log.debug("CMD: %s", cmd.name());
                switch (cmd) {
                case CHECK_FOLDERS:
                    checkFolders();
                    break;
                case NEXT_FOLDER:
                    changeFolder(1);
                    break;
                case NEXT_TRACK:
                    changeTrack(1);
                    break;
                case PREV_FOLDER:
                    changeFolder(-1);
                    break;
                case PREV_TRACK:
                    changeTrack(-1);
                    break;
                case PLAY_PAUSE:
                    playPause();
                    break;
                case SET_AUDIO_FOCUS:
                    setAudioFocus(true);
                    break;
                case STOP:
                    stop(true);
                    break;
                case STOP_AND_SAVE:
                    stopAndSave();
                    break;
                case UNSET_AUDIO_FOCUS:
                    setAudioFocus(false);
                    break;
                default:
                    Log.warn("Unknown command: " + cmd);
                }
            } catch (InterruptedException ie) {
                Thread.interrupted();
                break;
            }
        }
    }

    private void stopAndSave() {
        if (current == null) {
            return;
        }

        int pos = current.getCurrentPosition();
        stop(true);
        state.setTrackPosition(pos);
        saveObject(state);
        saveObject(currentInfo);
    }

    private void checkFolders() {
        String currentFolder = null;
        String currentTrack = null;
        int trackPosition = state.getTrackPosition();
        if (state.getFolders() != null) {
            currentFolder = state.getFolders().get(state.getCurrentFolder());
            if (state.getTracks() != null) {
                currentTrack = state.getTracks().get(state.getCurrentTrack());
            }
        }

        // Check to see if the storage timestamps have changed.
        File root = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC);
        if (foldersNeedReload(root)) {
            Log.info("Re-loading folder data.");
            List<String> folders = new LinkedList<String>();
            findChildFolders(root, folders);
            Collections.shuffle(folders);
            state.setFolders(folders);
            state.setLastModified(System.currentTimeMillis());
            state.setTracks(null);
            state.setCurrentTrack(0);
            state.setTrackPosition(0);
            if (folders.isEmpty()) {
                Log.warn("No playable folders found in root dir.");
                return;
            }
        }

        // Check if the previous current folder exists in the list,
        // set it as the current.
        boolean currentRestored = false;
        if (currentFolder != null) {
            for (int i = 0; i < state.getFolders().size(); i++) {
                if (state.getFolders().get(i).equals(currentFolder)) {
                    currentRestored = true;
                    state.setCurrentFolder(i);
                    break;
                }
            }
        }

        // Load the track list if needed.
        if (state.getTracks() == null || state.getTracks().isEmpty()) {
            String folder = state.getFolders().get(state.getCurrentFolder());
            List<String> tracks = buildTrackList(folder);
            state.setTracks(tracks);
            state.setCurrentTrack(0);
        }

        // If restoring the previously current folder, try to restore
        // the track too.
        if (currentTrack != null && currentRestored) {
            for (int i = 0; i < state.getTracks().size(); i++) {
                if (state.getTracks().get(i).equals(currentTrack)) {
                    state.setCurrentTrack(i);
                    state.setTrackPosition(trackPosition);
                }
            }
        }
    }

    private boolean foldersNeedReload(File root) {
        if (root.lastModified() > state.getLastModified()) {
            return true;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory() && foldersNeedReload(child)) {
                return true;
            }
        }
        return false;
    }

    private void findChildFolders(File folder, List<String> folders) {
        boolean added = false;
        File[] children = folder.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                findChildFolders(f, folders);
            } else if (!added) {
                folders.add(folder.getAbsolutePath());
                added = true;
            }
        }
    }

    private List<String> buildTrackList(String folder) {
        List<String> tracks = new LinkedList<String>();
        File[] children = new File(folder).listFiles();
        for (File f : children) {
            if (f.isFile()) {
                tracks.add(f.getAbsolutePath());
            }
        }
        Collections.sort(tracks);
        return tracks;
    }

    private <T extends Serializable> T loadObject(Class<T> klass) {
        String fileName = klass.getName();

        InputStream in = null;
        try {
            in = service.openFileInput(fileName);
            return (T) new ObjectInputStream(in).readObject();
        } catch (Exception e) {
            Log.info("Cannot load object from file %s: %s", fileName, e.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ioe) {
                    // Ignore.
                }
            }
        }
    }

    private void saveObject(Serializable object) {
        String fileName = object.getClass().getName();
        if (object == null) {
            service.deleteFile(fileName);
            return;
        }

        OutputStream out = null;
        try {
            out = service.openFileOutput(fileName, Context.MODE_PRIVATE);
            ObjectOutputStream oout = new ObjectOutputStream(out);
            oout.writeObject(object);
            oout.flush();
        } catch (Exception e) {
            Log.info("Cannot save object %s: %s", fileName, e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ioe) {
                    // Ignore.
                }
            }
        }
    }

    /**
     * Monitor for storage events.
     * <p>
     * Stops playback when the SD card is unmounted, and re-check
     * the folder list when it's mounted (but does not resume playback).
     */
    private class StorageMonitor extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_MEDIA_EJECT.equals(intent.getAction())) {
                runCommand(Command.STOP_AND_SAVE);
            } else if (intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction())) {
                runCommand(Command.CHECK_FOLDERS);
            }
        }

    }

}

