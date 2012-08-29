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
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.TextView;

import org.vanzin.ashuffler.PlayerControl.Command;

public class Main extends Activity
    implements PlayerListener, ServiceConnection
{
    private PlayerControl control;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Intent intent = new Intent(this, PlayerService.class);
        startService(intent);
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
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        Log.debug("Main::CONNECTED");
        control = (PlayerControl) service;
        control.addPlayerListener(this);
        if (control.getCurrentInfo() != null) {
            setCurrentTrack(control.getCurrentInfo());
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.debug("Main::DISCONNECTED");
        control = null;
    }

    @Override
    public void playbackStarted(final PlayerState state,
                                final TrackInfo info) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setCurrentTrack(info);
            }
        });
    }

    private void setCurrentTrack(TrackInfo info) {
        getTextView(R.id.title).setText(info.getTitle());
        getTextView(R.id.album).setText(info.getAlbum());
        getTextView(R.id.artist).setText(info.getArtist());
        getTextView(R.id.trackno).setText(
            String.format("%d.", info.getTrackNumber()));
    }

    private TextView getTextView(int id) {
        return (TextView) findViewById(id);
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
            control.runCommand(cmd);
        } else {
            Log.warn("No control.");
        }
    }

}
