aShuffler
=========

Basic, folder-shuffling media player for Android.

This media player was born out of my frustration with all other
Android players I tried. It is meant to be small, simple, and do
one thing and do it well: shuffle all the albums it can find on
the phone, and play them in their original track order.

That's achieved at the moment not by analyzing tags, but by simple
filesystem layout (folder with files = album) and file naming
(tracks are sorted alphabetically inside folders). This just
happens to match the way I manage my files.

Features
--------

This is a short list of what it does:

* Monitors the storage and updates the playlist as contents change.
* Responds to audio focus by pausing / resuming playback, so you
  can answer your phone.
* Pauses playback if headset is removed.
* Uses the Simple Last.fm Scrobbler API to send data to Last.fm.

Maybe Features
--------------

Things I haven't had time to look at yet.

* Equalizer.

Non-Features
------------

This is a list of what it doesn't do, and will probably never do
since I have no use for that.

* Collection browsing: I have a PC for that.
* Configuration: player does exactly what I want, no need to configure it.
* Lock screen, widgets: I have a headset to pause playback when I want to.
* Anything else that's not on the "features" or "maybe features" list:
  if I can't think of it, I probably don't need it.
* Picking specific albums or tracks to play (sort of assumes collection
  browsing), or anything other than album shuffle.

Bugs
----

Probably. If your files are not tagged properly you may see NPEs,
since I was pretty lazy and did not add proper checks. Android's
artwork support seems iffy. Last.fm scrobbling seems spotty too.

