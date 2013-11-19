/*
 * Copyright 2012-2013 Marcelo Vanzin
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
import java.util.concurrent.atomic.AtomicReference;

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
    private static final String CMD_ARGS = "cmd_args";

    private final PlayerService service;
    private final Thread worker;
    private final BlockingQueue<Intent> commands;
    private final BroadcastReceiver storageReceiver;
    private final BroadcastReceiver headsetReceiver;
    private final BroadcastReceiver shutdownReceiver;
    private final AudioManager audioManager;
    private final ComponentName remoteControl;
    private final AtomicReference<MediaPlayer> current;

    private boolean pausedByFocusLoss;
    private boolean serviceStarted;
    private PlayerState state;
    private TrackInfo currentInfo;
    private List<PlayerListener> listeners;
    private MediaPlayer nextPlayer;
    private String nextTrack;

    /**
     * Initializes the player control.
     * <p>
     * Set up the storage monitor, and load all saved state.
     */
    public PlayerControl(PlayerService service) {
        this.service = service;
        this.listeners = new LinkedList<PlayerListener>();
        this.commands = new LinkedBlockingQueue<Intent>();
        this.audioManager = (AudioManager)
            service.getSystemService(Context.AUDIO_SERVICE);
        this.remoteControl = new ComponentName(service.getPackageName(),
            RemoteControlMonitor.class.getName());
        this.current = new AtomicReference<MediaPlayer>();

        // Instantiate the storage broadcast receiver.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        this.storageReceiver = new StorageMonitor();
        service.registerReceiver(storageReceiver, filter);

        // Instantiate the headset broadcast receiver.
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        this.headsetReceiver = new HeadsetMonitor();
        service.registerReceiver(headsetReceiver, filter);

        // Instantiate the shutdown broadcast receiver.
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        this.shutdownReceiver = new ShutdownMonitor();
        service.registerReceiver(shutdownReceiver, filter);

        // Instantiate the scrobbler.
        addPlayerListener(new Scrobbler(service));

        state = loadObject(PlayerState.class);
        if (state == null) {
            state = new PlayerState();
        }
        currentInfo = loadObject(TrackInfo.class);
        checkFolders(false);

        worker = new Thread(this);
        worker.start();
    }

    /**
     * Get the current MediaPlayer instance.
     * <p>
     * Note that this is not thread-safe.
     */
    public MediaPlayer getPlayer() {
        return current.get();
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
     * Get the elapsed time of the current track.
     * <p>
     * Also not very thread-safe.
     *
     * @return Current track position in milliseconds, or 0 if no
     *         track is playing.
     */
    public int getElapsedTime() {
        MediaPlayer mp = current.get();
        if (mp != null) {
            return mp.getCurrentPosition();
        }
        return 0;
    }

    /**
     * @return Whether the SD card is currently mounted.
     */
    public boolean isStorageAvailable() {
        return Environment.MEDIA_MOUNTED.equals(
            Environment.getExternalStorageState());
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
        service.unregisterReceiver(storageReceiver);
        service.unregisterReceiver(headsetReceiver);
        service.unregisterReceiver(shutdownReceiver);
    }

    public void addPlayerListener(PlayerListener pl) {
        synchronized (listeners) {
            listeners.add(pl);
        }
    }

    public void removePlayerListener(PlayerListener pl) {
        synchronized (listeners) {
            listeners.remove(pl);
        }
    }

    /* Playback control. */

    /**
     * Commands available for enqueueing.
     */
    public static enum Command {
        CONTINUE,
        NEXT_FOLDER,
        NEXT_TRACK,
        PAUSE,
        PREV_FOLDER,
        PREV_TRACK,
        PLAY_PAUSE,
        SEEK,
        SET_AUDIO_FOCUS,
        STOP,
        STOP_AND_SAVE,
        STORAGE_MOUNTED,
        UNSET_AUDIO_FOCUS,
    }

    /**
     * Enqueue a command for execution.
     * <p>
     * Returns immediately. Command is executed asynchronous and there
     * is no way to monitor completion, unless it causes some
     * side-effect (like starting playback, which fires an event).
     */
    public void runCommand(Command cmd, String... args) {
        Intent intent = new Intent();
        intent.setAction(cmd.name());
        intent.putExtra(CMD_ARGS, args);
        runIntent(intent);
    }

    /**
     * Raw command interface. Try not to use it.
     */
    public void runIntent(Intent intent) {
        commands.offer(intent);
    }

    /**
     * Returns whether media is playing.
     * <p>
     * Not thread-safe either.
     */
    public boolean isPlaying() {
        MediaPlayer mp = current.get();
        return mp != null && mp.isPlaying();
    }

    private void pause() {
        MediaPlayer mp = current.get();
        if (mp != null && mp.isPlaying()) {
            mp.pause();
            fireTrackStateChange(PlayerListener.TrackState.PAUSE);
            showNotification();
        }
    }

    private void playPause() {
        if (!hasTracks()) {
            checkFolders(true);
            if (!hasTracks()) {
                Log.warn("No music found to play.");
                return;
            }
        }

        MediaPlayer mp = current.get();
        if (mp != null) {
            if (mp.isPlaying()) {
                mp.pause();
                fireTrackStateChange(PlayerListener.TrackState.PAUSE);
            } else {
                mp.start();
                fireTrackStateChange(PlayerListener.TrackState.PLAY);
            }
            showNotification();
            pausedByFocusLoss = false;
        } else {
            startPlayback();
        }
    }

    private void seek(String[] args) {
        if (args == null || args.length != 1) {
            Log.warn("Invalid arguments to seek().");
            return;
        }

        int percent;
        try {
            percent = Integer.parseInt(args[0]);
        } catch (NumberFormatException nfe) {
            Log.warn("Invalid integer argument: %s", args[0]);
            return;
        }

        if (percent < 0 || percent > 100) {
            Log.warn("Invalid seek() argument: %d", percent);
            return;
        }

        // Finally, do something.
        MediaPlayer mp = current.get();
        if (mp == null) {
            return;
        }

        int newPos = currentInfo.getDuration() * percent / 100;
        mp.seekTo(newPos);
    }

    private void stop(boolean mayStopService) {
        MediaPlayer mp = current.get();
        current.set(null);
        if (mp != null) {
            fireTrackStateChange(PlayerListener.TrackState.STOP);
            try {
                mp.stop();
            } finally {
                mp.release();
            }
            state.setTrackPosition(0);
        }
        service.stopForeground(true);
        if (mayStopService && serviceStarted) {
            service.stopSelf();
        }
        saveObject(state);
        saveObject(currentInfo);
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
        String track = state.getTracks().get(state.getCurrentTrack());
        if (!new File(track).isFile()) {
            checkFolders(true);
            state.setCurrentTrack(0);
            state.setCurrentFolder(0);
            track = state.getTracks().get(state.getCurrentTrack());
        }

        // Prepare the next track.
        MediaPlayer mp;
        if (track.equals(nextTrack)) {
            mp = nextPlayer;
        } else {
            if (nextPlayer != null) {
                nextPlayer.release();
            }
            mp = preparePlayer(track);
            if (mp == null) {
                return;
            }
        }
        nextPlayer = null;

        // Stop the current track.
        stop(false);

        // Start playback.
        if (state.getTrackPosition() > 0 &&
            state.getTrackPosition() < mp.getDuration()) {
            mp.seekTo(state.getTrackPosition());
        }
        mp.start();
        current.set(mp);

        // Load track metadata.
        MediaMetadataRetriever md = new MediaMetadataRetriever();
        try {
            md.setDataSource(track);
            TrackInfo tinfo = new TrackInfo(md, mp.getDuration());
            if (currentInfo != null &&
                state.getCurrentTrack() > 0 &&
                tinfo.getAlbum().equals(currentInfo.getAlbum())) {
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

        pausedByFocusLoss = false;
        fireTrackStateChange(PlayerListener.TrackState.PLAY);
        updateTrackInfo();
        setUpNextPlayer();
    }

    private void showNotification() {
        String state = current.get().isPlaying() ? "Playing" : "Paused";
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
        synchronized (listeners) {
            for (PlayerListener pl : listeners) {
                pl.trackStateChanged(state, currentInfo, newState);
            }
        }
    }

    private void setAudioFocus(boolean focused) {
        MediaPlayer mp = current.get();
        if (focused) {
            if (pausedByFocusLoss) {
                if (mp != null) {
                    mp.start();
                    fireTrackStateChange(PlayerListener.TrackState.PLAY);
                }
                pausedByFocusLoss = false;
            }
        } else {
            if (mp != null && mp.isPlaying()) {
                mp.pause();
                fireTrackStateChange(PlayerListener.TrackState.PAUSE);
                pausedByFocusLoss = true;
            }
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        fireTrackStateChange(PlayerListener.TrackState.COMPLETE);
        current.set(null);
        mp.release();
        runCommand(Command.CONTINUE);
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

        if (!pausedByFocusLoss && isStorageAvailable()) {
            audioManager.requestAudioFocus(this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        }

        while (true) {
            try {
                Intent intent = commands.take();
                Command cmd;
                try {
                    cmd = Command.valueOf(intent.getAction());
                } catch (IllegalArgumentException iae) {
                    Log.warn("Unknown command: %s", intent.getAction());
                    continue;
                }

                // If storage is not available, we only allow
                // "STORAGE_MOUNTED" to go through, so that we avoid
                // trying to change player state or access the
                // underlying files in that state.
                if (!isStorageAvailable() && cmd != Command.STORAGE_MOUNTED) {
                    return;
                }

                String[] args = intent.getStringArrayExtra(CMD_ARGS);
                switch (cmd) {
                case CONTINUE:
                    continueToNextTrack();
                    break;
                case NEXT_FOLDER:
                    changeFolder(1);
                    break;
                case NEXT_TRACK:
                    changeTrack(1);
                    break;
                case PAUSE:
                    pause();
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
                case SEEK:
                    seek(args);
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
                case STORAGE_MOUNTED:
                    checkFolders(false);
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
        TrackInfo trackInfo = currentInfo;
        if (current.get() != null) {
            int pos = current.get().getCurrentPosition();
            stop(true);
            state.setTrackPosition(pos);
            saveObject(currentInfo);
        }
        saveObject(state);
        saveObject(trackInfo);
    }

    private void checkFolders(boolean force) {
        String currentFolder = null;
        String currentTrack = null;
        int trackPosition = state.getTrackPosition();
        if (hasTracks()) {
            currentFolder = state.getFolders().get(state.getCurrentFolder());
            if (state.getTracks() != null) {
                currentTrack = state.getTracks().get(state.getCurrentTrack());
            }
        }

        // Check to see if the storage timestamps have changed.
        File root = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MUSIC);
        if (force || foldersNeedReload(root)) {
            Log.info("Re-loading data from %s.", root.getAbsolutePath());
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
        // set it as the first in the new list.
        boolean currentRestored = false;
        if (currentFolder != null) {
            for (int i = 0; i < state.getFolders().size(); i++) {
                if (state.getFolders().get(i).equals(currentFolder)) {
                    state.getFolders().remove(i);
                    state.getFolders().add(0, currentFolder);
                    break;
                }
            }
        }

        // Load the track list if needed.
        if (!hasTracks()) {
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
        if (object == null) {
            return;
        }

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

    private boolean hasTracks() {
        return state != null && state.getTracks() != null &&
            !state.getTracks().isEmpty();
    }

    private void continueToNextTrack() {
        if (nextPlayer == null) {
            changeTrack(1);
            return;
        }
        state.setCurrentTrack(state.getCurrentTrack() + 1);
        current.set(nextPlayer);
        nextPlayer = null;
        updateTrackInfo();
        fireTrackStateChange(PlayerListener.TrackState.PLAY);
        setUpNextPlayer();
    }

    private void setUpNextPlayer() {
        int next = state.getCurrentTrack() + 1;
        if (next >= state.getTracks().size()) {
            return;
        }

        String nextPath = state.getTracks().get(next);
        MediaPlayer nextPlayer = preparePlayer(nextPath);
        if (nextPlayer == null) {
            return;
        }

        if (current.get() == null) {
            Log.warn("ShouldNotReachHere(NoCurrentPlayer)");
            return;
        }

        current.get().setNextMediaPlayer(nextPlayer);
        this.nextPlayer = nextPlayer;
        this.nextTrack = nextPath;
    }

    private void updateTrackInfo() {
        String track = state.getTracks().get(state.getCurrentTrack());
        MediaPlayer mp = current.get();

        // Load track metadata.
        MediaMetadataRetriever md = new MediaMetadataRetriever();
        try {
            md.setDataSource(track);
            TrackInfo tinfo = new TrackInfo(md, mp.getDuration());
            if (currentInfo != null &&
                state.getCurrentTrack() > 0 &&
                tinfo.getAlbum().equals(currentInfo.getAlbum())) {
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

        showNotification();
        saveObject(state);
        saveObject(currentInfo);
    }

    private MediaPlayer preparePlayer(String track) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(track);
            mp.prepare();
            mp.setOnCompletionListener(this);
            return mp;
        } catch (IOException ioe) {
            Log.warn("Cannot load new track: %s", ioe.getMessage());
            mp.release();
            return null;
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
            System.err.printf("ASHUFFLER: storage intent = %s\n", intent.getAction());
            Log.warn("ASHUFFLER: storage intent = %s", intent.getAction());
            if (Intent.ACTION_MEDIA_EJECT.equals(intent.getAction())) {
                runCommand(Command.STOP_AND_SAVE);
            } else if (intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction())) {
                runCommand(Command.STORAGE_MOUNTED);
            }
        }

    }

    /**
     * Monitor for headset events.
     * <p>
     * Pause playback if the headset is unplugged.
     */
    private class HeadsetMonitor extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", 0);
            if (state == 0) {
                runCommand(Command.PAUSE);
            }
        }

    }

    /**
     * Monitor for shutdown events.
     * <p>
     * Saves the player state and stops the service when shutting
     * down.
     */
    private class ShutdownMonitor extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            runCommand(Command.STOP_AND_SAVE);
        }

    }

 }

