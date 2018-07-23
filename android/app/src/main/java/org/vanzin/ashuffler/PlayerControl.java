/*
 * Copyright 2012-2018 Marcelo Vanzin
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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main media player control class.
 * <p>
 * This class takes care of all the logic related to keeping the list
 * of things to be played, to responding to system events. It can be
 * retrieved by binding to PlayerService.
 * <p>
 * Interaction with the control is done by enqueueing commands by
 * calling {@link #runCommand(Command, String...)}. Commands are handled
 * sequentially in a separate worker thread, as to not block the
 * caller.
 * <p>
 * Interesting behaviors include:
 *
 * <ul>
 *   <li>Loading track metadata.</li>
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
               MediaPlayer.OnCompletionListener {

    private static final int ONGOING_NOTIFICATION = 10001;
    private static final String CMD_ARGS = "cmd_args";
    private static final String NOTIFICATION_CHAN_ID = "ashuffler-play-notification";

    private final PlayerService service;
    private final MediaSessionCompat session;
    private final ScheduledExecutorService executor;
    private final BroadcastReceiver headsetReceiver;
    private final BroadcastReceiver shutdownReceiver;
    private final AudioManager audioManager;
    private final AtomicReference<Player> current;
    private final PendingIntent pendingIntent;
    private final NotificationManager notificationMgr;
    private final StorageManager storage;

    private boolean pausedByFocusLoss;
    private boolean registeredFocusListener;
    private PlayerState state;
    private List<PlayerListener> listeners;
    private Future<?> stopTask;

    /**
     * Initializes the player control.
     * <p>
     * Set up the storage monitor, and load all saved state.
     */
    public PlayerControl(PlayerService service) {
        this.service = service;
        this.session = new MediaSessionCompat(service, "aShuffler");
        this.listeners = new LinkedList<PlayerListener>();
        this.audioManager = (AudioManager)
            service.getSystemService(Context.AUDIO_SERVICE);
        this.current = new AtomicReference<Player>();

        // Set up the notification channel for Oreo.
        this.notificationMgr = service.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHAN_ID,
                "aShuffler", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("aShuffler");
            channel.enableLights(false);
            channel.enableVibration(false);
            notificationMgr.createNotificationChannel(channel);
        }

        // Set up the media session.
        Intent intent = new Intent(service, Main.class);
        this.pendingIntent = PendingIntent.getActivity(service, 0, intent, 0);
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(pendingIntent);
        session.setPlaybackState(new PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .build());
        session.setCallback(new MediaCallback());
        session.setMediaButtonReceiver(pendingIntent);
        session.setActive(true);

        // Instantiate the headset broadcast receiver.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        this.headsetReceiver = new HeadsetMonitor();
        service.registerReceiver(headsetReceiver, filter);

        // Instantiate the shutdown broadcast receiver.
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        this.shutdownReceiver = new ShutdownMonitor();
        service.registerReceiver(shutdownReceiver, filter);

        // Initialize default listeners.
        addPlayerListener(new Scrobbler(service));
        addPlayerListener(new NotificationUpdater());

        state = loadObject(PlayerState.class);
        if (state == null) {
            state = new PlayerState();
        }
        checkFolders();

        executor = Executors.newSingleThreadScheduledExecutor();
        storage = service.getSystemService(StorageManager.class);
    }

    /**
     * Get the current playing track's info.
     * <p>
     * As with other "getters", not thread-safe.
     */
    public TrackInfo getCurrentInfo() {
        Player p = current.get();
        if (p != null) {
            return p.getInfo();
        }

        return loadObject(TrackInfo.class);
    }

    public int getElapsedTime() {
        TrackInfo info = getCurrentInfo();
        return info != null ? info.getElapsedTime() : 0;
    }

    /**
     * Shutdown the player.
     * <p>
     * Stop the executor, stop playback, and save all state.
     */
    public void shutdown() {
        audioManager.abandonAudioFocus(this);
        session.setActive(false);
        session.release();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                Log.warn("Failed to shut down executor.");
            }
        } catch (InterruptedException ie) {
            Log.warn("Interrupted while waiting for termination.");
        }

        if (current.get() != null) {
            stopAndSave();
        }
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

    public MediaSessionCompat getSession() {
        return session;
    }

    private void pause() {
        Player player = current.get();
        if (player != null && player.isPlaying()) {
            player.pause();
            setupStopTask();
        }
    }

    private void setupStopTask() {
        if (stopTask != null) {
            Log.warn("Stop task already scheduled!");
            stopTask.cancel(false);
        }

        Intent intent = new Intent();
        intent.setAction(Command.STOP_AND_SAVE.name());
        stopTask = executor.schedule(new IntentTask(intent),
            30, TimeUnit.SECONDS);
    }

    private void clearStopTask() {
        if (stopTask == null) {
            Log.warn("Clearing non-existant stop task.");
            return;
        }
        stopTask.cancel(false);
        stopTask = null;
    }

    /* Playback control. */

    /**
     * Commands available for enqueueing.
     */
    public static enum Command {
        NEXT_FOLDER,
        NEXT_TRACK,
        PLAY,
        PAUSE,
        PREV_FOLDER,
        PREV_TRACK,
        PLAY_PAUSE,
        SEEK,
        SET_AUDIO_FOCUS,
        STOP,
        STOP_AND_SAVE,
        UNSET_AUDIO_FOCUS,
        FINISH_CURRENT;

        private final String cmd;

        Command(String cmd) {
            this.cmd = cmd;
        }

        Command() {
            this(null);
        }

        public static Command fromAction(String action) {
            for (Command c : values()) {
                String cmd = c.cmd != null ? c.cmd : c.name();
                if (cmd.equals(action)) {
                    return c;
                }
            }
            throw new IllegalArgumentException("action not found: " + action);
        }

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
        executor.submit(new IntentTask(intent));
    }

    /**
     * Returns whether media is playing.
     * <p>
     * Not thread-safe either.
     */
    public boolean isPlaying() {
        Player player = current.get();
        return player != null && player.isPlaying();
    }

    private void playPause() {
        if (!hasTracks()) {
            checkFolders();
            if (!hasTracks()) {
                Log.warn("No music found to play.");
                return;
            }
        }

        Player player = current.get();
        if (player != null) {
            if (!player.isValid()) {
              releasePlayer();
              startPlayback();
            } else if (!player.playPause()) {
                setupStopTask();
            } else {
                clearStopTask();
            }
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
        Player player = current.get();
        if (player == null) {
            return;
        }

        int newPos = player.getInfo().getDuration() * percent / 100;
        player.seekTo(newPos);
    }

    private void stop(boolean mayStopService) {
        Player player = current.getAndSet(null);
        TrackInfo info = null;
        if (player != null) {
            player.stop();
            info = player.getInfo();
            player.release();
            current.set(null);
        }

        service.stopForeground(true);
        if (mayStopService) {
            session.release();
            service.stopSelf();
        }

        saveObject(state, PlayerState.class);
        saveObject(info, TrackInfo.class);
        pausedByFocusLoss = false;
        if (registeredFocusListener) {
            audioManager.abandonAudioFocus(this);
            registeredFocusListener = false;
        }
    }

    private void changeFolder(int delta) {
        loadFolder(delta);
        stop(false);
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
        stop(false);
        startPlayback();
    }

    private void loadFolder(int delta) {
        // Check storage for changes, just in case.
        checkFolders();

        if (state.getFolders().isEmpty()) {
            return;
        }

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
        if (!registeredFocusListener) {
            audioManager.requestAudioFocus(this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
            registeredFocusListener = true;
        }
        String track = state.getTracks().get(state.getCurrentTrack());
        if (!new File(track).isFile()) {
            checkFolders();
            track = state.getTracks().get(state.getCurrentTrack());
        }

        Player player;
        try {
            player = new Player(service, session, track, null, listeners);
        } catch (IOException ioe) {
            Log.warn("Error loading player: %s", ioe.getMessage());
            return;
        }

        TrackInfo info = loadObject(TrackInfo.class);
        if (info != null && track.equals(info.getPath())) {
            player.setInfo(info);
        } else {
            saveObject(null, TrackInfo.class);
            info = null;
        }

        int startPos = 0;
        if (info != null &&
            info.getElapsedTime() > 0 &&
            info.getElapsedTime() < player.getInfo().getDuration()) {
            startPos = info.getElapsedTime();
        }

        player.setOnCompletionListener(this);
        player.play(startPos);
        current.set(player);

        pausedByFocusLoss = false;
        saveObject(state, PlayerState.class);
        saveObject(player.getInfo(), TrackInfo.class);
        setNextTrack(player);
    }

    private void setAudioFocus(boolean focused) {
        Player player = current.get();
        if (focused) {
            if (pausedByFocusLoss) {
                if (player != null) {
                    player.playPause();
                }
                pausedByFocusLoss = false;
            }
        } else {
            if (player != null && player.isPlaying()) {
                player.pause();
                pausedByFocusLoss = true;
            }
        }
    }

    private void setNextTrack(Player player) {
        int nextIdx = state.getCurrentTrack() + 1;
        if (nextIdx >= state.getTracks().size()) {
            return;
        }

        try {
            String nextPath = state.getTracks().get(nextIdx);
            player.setNext(nextPath);
        } catch (IOException ioe) {
            Log.warn("Failed to initialize next track: %s",
                ioe.getMessage());
        }
    }

    private void finishCurrentTrack() {
        Player player = current.getAndSet(null);
        Player next = player.complete();
        if (next != null) {
            state.setCurrentTrack(state.getCurrentTrack() + 1);
            next.setOnCompletionListener(this);
            setNextTrack(next);
            current.set(next);
        } else {
            changeTrack(1);
        }
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        runCommand(Command.FINISH_CURRENT);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (!session.isActive()) {
            return;
        }
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            runCommand(Command.SET_AUDIO_FOCUS);
        } else {
            runCommand(Command.UNSET_AUDIO_FOCUS);
        }
    }

    private void processIntent(Intent intent) {
        Command cmd;
        try {
            cmd = Command.fromAction(intent.getAction());
        } catch (IllegalArgumentException iae) {
            Log.warn("Unknown command: %s", intent.getAction());
            return;
        }

        String[] args = intent.getStringArrayExtra(CMD_ARGS);
        switch (cmd) {
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
        case PLAY:
            if (!isPlaying()) {
                playPause();
            }
            break;
        case PAUSE:
            pause();
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
        case UNSET_AUDIO_FOCUS:
            setAudioFocus(false);
            break;
        case FINISH_CURRENT:
            finishCurrentTrack();
            break;
        default:
            Log.warn("Unknown command: " + cmd);
        }
    }

    private void stopAndSave() {
        Player player = current.get();
        TrackInfo info = null;
        if (player != null) {
            info = player.save();
            releasePlayer();
            current.set(null);
        }
        saveObject(info, TrackInfo.class);
        saveObject(state, PlayerState.class);

        if (stopTask != null) {
            clearStopTask();
        }
    }

    private void checkFolders() {
        File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        Set<String> folders = new HashSet<String>();
        findChildFolders(root, folders);

        String currentFolder = state.currentFolder();
        String currentTrack = state.currentTrack();

        // Look at the current known folders, and keep the existing
        // ones in the current order. We'll shuffle just the added
        // ones at the end of the current list.
        for (Iterator<String> it = state.getFolders().iterator();
            it.hasNext(); ) {
            String folder = it.next();
            if (!folders.remove(folder)) {
                it.remove();
            }
        }

        List<String> newFolders = new ArrayList<String>(folders);
        Collections.shuffle(newFolders);
        state.getFolders().addAll(newFolders);

        if (state.getFolders().isEmpty()) {
            Log.warn("No playable folders found in root dir.");
            return;
        }

        // Find the current folder in the new list, and the current
        // track, and update the indices.
        boolean found = false;
        int idx = 0;
        for (Iterator<String> it = state.getFolders().iterator();
            it.hasNext(); ) {
            if (it.next().equals(currentFolder)) {
                found = true;
                break;
            }
            idx++;
        }

        if (found) {
            state.setCurrentFolder(idx);
            List<String> tracks = buildTrackList(
                state.getFolders().get(idx));
            state.setTracks(tracks);

            found = false;
            idx = 0;
            for (Iterator<String> it = tracks.iterator();
                it.hasNext(); ) {
                if (it.next().equals(currentTrack)) {
                    found = true;
                    break;
                }
                idx++;
            }

            if (found) {
              state.setCurrentTrack(idx);
            }
        } else {
            state.setCurrentFolder(0);
            state.setCurrentTrack(0);
            loadFolder(0);
        }
    }

    private void findChildFolders(File folder, Collection<String> folders) {
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
        } catch (FileNotFoundException fnf) {
            return null;
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

    private void saveObject(Serializable object, Class<?> klass) {
        if (object != null && !object.getClass().equals(klass)) {
            throw new IllegalArgumentException();
        }

        String fileName = klass.getName();
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

    private void releasePlayer() {
      Player p = current.getAndSet(null);
      if (p != null) {
        p.release();
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
            Command action = null;
            switch (intent.getAction()) {
                case Intent.ACTION_HEADSET_PLUG:
                    int hsState = intent.getIntExtra("state", 0);
                    if (hsState == 0) {
                        action = Command.PAUSE;
                    }
                    break;

                case BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED:
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    int pState = ba.getProfileConnectionState(BluetoothProfile.A2DP);
                    if (pState != BluetoothProfile.STATE_CONNECTED) {
                        action = Command.PAUSE;
                    }
                    break;

                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int baState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.STATE_OFF);
                    if (baState != BluetoothAdapter.STATE_ON) {
                        action = Command.PAUSE;
                    }
                    break;

                default:
                    /* no op */
            }

            if (action != null) {
                runCommand(action);
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

    /**
     * Submittable task for processing intents.
     */
    private class IntentTask implements Runnable {

        private final Intent intent;

        IntentTask(Intent intent) {
            this.intent = intent;
        }

        @Override
        public void run() {
            try {
                processIntent(intent);
            } catch (Exception e) {
              Log.error(e, "Error processing intent.");
            }
        }

    }

    private class MediaCallback extends MediaSessionCompat.Callback {

        @Override
        public boolean onMediaButtonEvent(Intent event) {
            KeyEvent ke = (KeyEvent) event.getParcelableExtra(Intent.EXTRA_KEY_EVENT);

            if (ke.getAction() != KeyEvent.ACTION_UP || ke.isCanceled()) {
                return true;
            }

            Log.debug("GOT MEDIA EVENT: %s", event.getAction());
            Log.debug(" code: %d  / %d", ke.getKeyCode(), ke.getAction());

            switch (ke.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    // The media session seems to get confused about things, so treat all these
                    // as the same action.
                    runCommand(Command.PLAY_PAUSE);
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    runCommand(Command.STOP);
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    runCommand(Command.PREV_TRACK);
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    runCommand(Command.NEXT_TRACK);
                    break;
                default:
                    return super.onMediaButtonEvent(event);
            }
            return true;
        }

    }

    private class NotificationUpdater implements PlayerListener {

        private final NotificationCompat.Action playAction;
        private final NotificationCompat.Action pauseAction;
        private final NotificationCompat.Action nextAction;

        NotificationUpdater() {
            this.playAction = createAction(R.drawable.play, "Play", Command.PLAY_PAUSE.name());
            this.pauseAction = createAction(R.drawable.pause, "Pause", Command.PLAY_PAUSE.name());
            this.nextAction = createAction(R.drawable.next_track, "Next",
                Command.NEXT_TRACK.name());
        }

        private NotificationCompat.Action createAction(int icon, String label, String command) {
            Intent intent = new Intent(service, PlayerService.class);
            intent.setAction(command);
            return new NotificationCompat.Action(icon, label,
                PendingIntent.getService(service, 1, intent, 0));
        }

        @Override
        public void trackStateChanged(TrackInfo track, TrackState trackState) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(service);
            switch (trackState) {
                case PLAY:
                    builder.addAction(pauseAction);
                    break;
                case PAUSE:
                    builder.addAction(playAction);
                    break;
                default:
                    return;
            }

            builder.addAction(nextAction)
                .setContentTitle(track.getTitle())
                .setContentText(track.getAlbum())
                .setSubText(track.getArtist())
                .setSmallIcon(R.drawable.ashuffler)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setChannelId(NOTIFICATION_CHAN_ID)
                .setColorized(false);

            MediaStyle style = new MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1);
            builder.setStyle(style);

            if (track.getArtwork() != null) {
                Bitmap artwork = BitmapFactory.decodeFile(track.getArtwork());
                builder.setLargeIcon(artwork);
            }

            service.startForeground(ONGOING_NOTIFICATION, builder.build());
        }

    }

 }
