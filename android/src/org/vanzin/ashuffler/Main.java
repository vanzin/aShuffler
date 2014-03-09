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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.net.Uri;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

import org.vanzin.ashuffler.PlayerControl.Command;

/**
 * Main activity class.
 * <p>
 * Shows the main UI, binds to PlayerService, and listens to playback
 * events.
 */
public class Main extends Activity
    implements PlayerListener, ServiceConnection,
                 SeekBar.OnSeekBarChangeListener {

    private volatile PlayerControl control;
    private String currentArtwork;

    private Timer progressTimer;
    private TimerTask progressTask;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        ((SeekBar)findViewById(R.id.seekbar))
            .setOnSeekBarChangeListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, PlayerService.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (control != null) {
            control.removePlayerListener(this);
        }
        unbindService(this);
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
            progressTask = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {

    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        control = (PlayerControl) service;
        control.addPlayerListener(this);
        runOnUiThread(new Runnable() {
            public void run() {
                if (control.getCurrentInfo() != null) {
                    setCurrentTrack(control.getCurrentInfo());
                }

                boolean isPlaying = control.isPlaying();
                updatePlayControls(isPlaying);
                updateTimes(control.getCurrentInfo() != null ?
                    control.getCurrentInfo().getElapsedTime() : 0);
                if (isPlaying) {
                    setupProgressTimer();
                }
            }
        });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        control = null;
        currentArtwork = null;
    }

    @Override
    public void trackStateChanged(final TrackInfo track,
                                  final TrackState trackState) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (trackState == TrackState.PLAY) {
                    setCurrentTrack(track);
                }
                updatePlayControls(trackState == TrackState.PLAY);
            }
        });

        if (trackState == TrackState.PLAY) {
            setupProgressTimer();
        } else {
            if (progressTask != null) {
                progressTask.cancel();
                progressTask = null;
            }
            updateTimesTask();
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar,
                                  int progress,
                                  boolean fromUser) {
        if (!fromUser || control == null) {
            return;
        }
        control.runCommand(Command.SEEK, String.valueOf(progress));
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // No op.
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        // No op.
    }

    private void setCurrentTrack(TrackInfo info) {
        getTextView(R.id.title).setText(info.getTitle());
        getTextView(R.id.album).setText(info.getAlbum());
        getTextView(R.id.artist).setText(info.getArtist());
        getTextView(R.id.trackno).setText(
            String.format("%d.", info.getTrackNumber()));

        ImageView cover = (ImageView) findViewById(R.id.cover);
        if (control.isStorageAvailable() && info.getArtwork() != null) {
            if (!info.getArtwork().equals(currentArtwork)) {
                Uri uri = Uri.fromFile(new File(info.getArtwork()));
                cover.setImageURI(uri);
            } else if (!new File(info.getArtwork()).exists()) {
                cover.setImageResource(R.drawable.nocover);
            }
        } else {
            cover.setImageResource(R.drawable.nocover);
        }
        updateTimes(control.getElapsedTime());
    }

    private void updateTimes(int elapsed) {
        if (control == null) {
            return;
        }
        TrackInfo current = control.getCurrentInfo();
        if (current == null) {
            return;
        }
        int position = elapsed / 1000;
        int progress;
        int remaining;
        if (position >= current.getDuration()) {
            position = current.getDuration();
            progress = 100;
            remaining = 0;
        } else {
            progress = position * 100 * 1000 / current.getDuration();
            remaining = current.getDuration() / 1000 - position;
        }

        getTextView(R.id.elapsed).setText(secondsToStr(position));
        getTextView(R.id.remaining).setText("-" + secondsToStr(remaining));
        ((SeekBar)findViewById(R.id.seekbar)).setProgress(progress);
    }

    private void updateTimesTask() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateTimes(control.getElapsedTime());
            }
        });
    }

    private void updatePlayControls(boolean isPlaying) {
        ImageButton playBtn = (ImageButton) findViewById(R.id.play_btn);
        if (isPlaying) {
            playBtn.setImageResource(R.drawable.pause);
        } else {
            playBtn.setImageResource(R.drawable.play);
        }
        ((SeekBar)findViewById(R.id.seekbar)).setEnabled(isPlaying);
    }

    private TextView getTextView(int id) {
        return (TextView) findViewById(id);
    }

    private String secondsToStr(int seconds) {
        int hours = seconds / 3600;
        int min = (seconds % 3600) / 60;
        int secs = (seconds % 60);

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, min, secs);
        } else {
            return String.format("%02d:%02d", min, secs);
        }
    }

    private synchronized void setupProgressTimer() {
        if (progressTimer == null) {
            progressTimer = new Timer();
        }
        if (progressTask == null) {
            progressTask = new TimerTask() {
                @Override
                public void run() {
                    updateTimesTask();
                }
            };
            progressTimer.schedule(progressTask, 1000, 1000);
        }
    }

    /* Playback controls. */

    public void prevAlbum(View view) {
        runCommand(Command.PREV_FOLDER);
    }

    public void prevTrack(View view) {
        runCommand(Command.PREV_TRACK);
    }

    public void playPause(View view) {
        runCommand(Command.PLAY_PAUSE);
    }

    public void stop(View view) {
        runCommand(Command.STOP);
    }

    public void nextTrack(View view) {
        runCommand(Command.NEXT_TRACK);
    }

    public void nextAlbum(View view) {
        runCommand(Command.NEXT_FOLDER);
    }

    private void runCommand(Command cmd) {
        if (control != null) {
            if (!control.isStorageAvailable()) {
                Toast toast = Toast.makeText(
                    getApplicationContext(),
                    R.string.not_available,
                    Toast.LENGTH_SHORT);
                toast.show();
            } else {
                control.runCommand(cmd);
            }
        } else {
            Log.warn("No control.");
        }
    }

}
