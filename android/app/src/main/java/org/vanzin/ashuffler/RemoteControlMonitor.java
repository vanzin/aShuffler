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
import android.view.KeyEvent;

/**
 * Monitor for media button events.
 * <p>
 * This receiver is installed to respond to MEDIA_BUTTON intents,
 * and responds to the "play" button in headsets to trigger the
 * play/pause action in PlayerControl.
 */
public class RemoteControlMonitor extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        KeyEvent event = (KeyEvent)intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (KeyEvent.KEYCODE_HEADSETHOOK == event.getKeyCode() &&
            KeyEvent.ACTION_UP == event.getAction()) {
            playPause(context);
        }
    }

    private void playPause(Context context) {
        Intent intent = new Intent(context, PlayerService.class);
        intent.setAction(PlayerControl.Command.PLAY_PAUSE.name());
        context.startService(intent);
    }

}

