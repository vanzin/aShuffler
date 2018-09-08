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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.session.MediaButtonReceiver;

/**
 * Service for playing back media.
 * <p>
 * This service handles the playback of the media, and dispatching
 * commands to the {@link PlayerControl} class. Intents sent to the
 * service that match a valid value in the {@link PlayerControl#Command}
 * class are delivered for asynchronous processing.
 * <p>
 * The {@link PlayerControl} instance can be retrieved by binding to
 * the service.
 */
public class PlayerService extends Service {

    private PlayerControl control;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        init();
        MediaButtonReceiver.handleIntent(control.getSession(), intent);
        control.processIntent(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        control.shutdown();
        control = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        init();
        return control;
    }

    private synchronized void init() {
        if (control == null) {
            control = new PlayerControl(this);
        }
    }

}

