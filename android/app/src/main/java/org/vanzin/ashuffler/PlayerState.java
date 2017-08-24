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

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

/**
 * The player state.
 * <p>
 * This class holds information about the known folders in the
 * phone's Music directory and the current folder / track being
 * played.
 */
class PlayerState implements Serializable {

    public static final long serialVersionUID = 3999248501740180351L;

    private int currentFolder;
    private List<String> folders = new LinkedList<String>();

    private int currentTrack;
    private List<String> tracks = new LinkedList<String>();

    public int getCurrentFolder() {
        return currentFolder < folders.size() ? currentFolder : -1;
    }

    public void setCurrentFolder(int currentFolder) {
        this.currentFolder = currentFolder;
    }

    public List<String> getFolders() {
        return folders;
    }

    public void setFolders(List<String> folders) {
        this.folders = folders;
    }

    public int getCurrentTrack() {
        return currentTrack < tracks.size() ? currentTrack : -1;
    }

    public void setCurrentTrack(int currentTrack) {
        this.currentTrack = currentTrack;
    }

    public List<String> getTracks() {
        return tracks;
    }

    public void setTracks(List<String> tracks) {
        this.tracks = tracks;
    }

    public String currentFolder() {
      int idx = getCurrentFolder();
      return idx >= 0 ? folders.get(idx) : null;
    }

    public String currentTrack() {
      int idx = getCurrentTrack();
      return idx >= 0 ? tracks.get(idx) : null;
    }

}

