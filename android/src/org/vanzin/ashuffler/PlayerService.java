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

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public class PlayerService extends IntentService {

    private PlayerControl control;

    public PlayerService() {
        super("ShufflePlayerService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        init();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        control.shutdown();
        control = null;
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

