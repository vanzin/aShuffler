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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
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

class PlayerControl extends Binder
    implements MediaPlayer.OnCompletionListener, Runnable {

    private final Context context;
    private final Thread worker;
    private final BlockingQueue<Command> commands;
    private final BroadcastReceiver bcastReceiver;

    private PlayerState state;
    private MediaPlayer current;
    private TrackInfo currentInfo;
    private List<PlayerListener> listeners;

    public PlayerControl(Context context) {
        this.context = context;
        this.listeners = new LinkedList<PlayerListener>();
        this.commands = new LinkedBlockingQueue<Command>();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);

        this.bcastReceiver = new StorageMonitor();
        context.registerReceiver(bcastReceiver, filter);

        state = loadObject(PlayerState.class);
        if (state == null) {
            state = new PlayerState();
        }
        currentInfo = loadObject(TrackInfo.class);
        checkFolders();

        worker = new Thread(this);
        worker.start();
    }

    public MediaPlayer getPlayer() {
        return current;
    }

    public PlayerState getState() {
        return state;
    }

    public TrackInfo getCurrentInfo() {
        if (state == null) {
            getState();
        }
        return currentInfo;
    }

    public void shutdown() {
        worker.interrupt();
        try {
            worker.join();
        } catch (InterruptedException ie) {
            Log.warn("Wait interrupted.");
        }
        stop();
        saveObject(state);
        saveObject(currentInfo);
        context.unregisterReceiver(bcastReceiver);
    }

    public void addPlayerListener(PlayerListener pl) {
        listeners.add(pl);
    }

    public void removePlayerListener(PlayerListener pl) {
        listeners.remove(pl);
    }

    /* Playback control. */

    public static enum Command {
        CHECK_FOLDERS,
        NEXT_FOLDER,
        NEXT_TRACK,
        PREV_FOLDER,
        PREV_TRACK,
        PLAY_PAUSE,
        STOP,
        STOP_AND_SAVE,
    }

    public void runCommand(Command cmd) {
        Log.debug("RUN: %s", cmd.name());
        commands.offer(cmd);
    }

    private void playPause() {
        if (current != null) {
            if (current.isPlaying()) {
                current.pause();
            } else {
                current.start();
            }
        } else {
            startPlayback();
        }
    }

    private void stop() {
        if (current != null) {
            try {
                current.stop();
            } finally {
                current.release();
                current = null;
            }
            state.setTrackPosition(0);
        }
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
        stop();

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
            TrackInfo tinfo = new TrackInfo(md);
            currentInfo = tinfo;
        } finally {
            md.release();
        }

        Log.debug("startPlayback(): event");
        for (PlayerListener pl : listeners) {
            pl.playbackStarted(state, currentInfo);
        }

        Log.debug("startPlayback(): seeking");
        if (state.getTrackPosition() > 0 &&
            state.getTrackPosition() < mp.getDuration()) {
            mp.seekTo(state.getTrackPosition());
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        runCommand(Command.NEXT_TRACK);
    }

    @Override
    public void run() {
        // Make sure the state is loaded.
        getState();

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
                case STOP:
                    stop();
                    break;
                case STOP_AND_SAVE:
                    stopAndSave();
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
        stop();
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
            in = context.openFileInput(fileName);
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
            context.deleteFile(fileName);
            return;
        }

        OutputStream out = null;
        try {
            out = context.openFileOutput(fileName, Context.MODE_PRIVATE);
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

