package com.explainers.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground media-playback service. Playback continues when the activity is
 * closed, the app is swiped away, or the screen is off (MediaPlayer holds a
 * partial wake lock while playing).
 */
public class PlayerService extends Service {

  public static final String ACTION_PLAY   = "com.explainers.app.PLAY";
  public static final String ACTION_TOGGLE = "com.explainers.app.TOGGLE";
  public static final String ACTION_STOP   = "com.explainers.app.STOP";
  public static final String EXTRA_URI = "uri";
  public static final String EXTRA_TITLE = "title";

  private static final String CHANNEL = "playback";
  private static final int NOTE_ID = 1;

  private MediaPlayer mp;
  private String title = "";
  private boolean prepared;
  private AudioManager audioManager;
  private AudioFocusRequest focusRequest;

  public class LocalBinder extends Binder {
    public PlayerService get() { return PlayerService.this; }
  }
  private final IBinder binder = new LocalBinder();

  @Override public IBinder onBind(Intent intent) { return binder; }

  @Override public void onCreate() {
    super.onCreate();
    audioManager = getSystemService(AudioManager.class);
    NotificationChannel ch =
        new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
    ch.setDescription("Audio explainer playback controls");
    getSystemService(NotificationManager.class).createNotificationChannel(ch);
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    String a = intent == null ? null : intent.getAction();
    if (ACTION_PLAY.equals(a)) {
      String u = intent.getStringExtra(EXTRA_URI);
      if (u != null) startPlayback(Uri.parse(u), intent.getStringExtra(EXTRA_TITLE));
    } else if (ACTION_TOGGLE.equals(a)) {
      toggle();
    } else if (ACTION_STOP.equals(a)) {
      stopPlayback();
    }
    return START_NOT_STICKY;
  }

  private void startPlayback(Uri uri, String t) {
    releasePlayer();
    title = t == null ? "Explainer" : t;
    prepared = false;
    mp = new MediaPlayer();
    mp.setAudioAttributes(new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
    mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK); // survive screen-off
    mp.setOnPreparedListener(p -> {
      prepared = true;
      if (requestFocus()) p.start();
      updateForeground();
    });
    mp.setOnCompletionListener(p -> { p.seekTo(0); updateForeground(); });
    mp.setOnErrorListener((p, what, extra) -> { stopPlayback(); return true; });
    goForeground(); // must happen promptly after startForegroundService, even on failure
    try {
      mp.setDataSource(this, uri);
      mp.prepareAsync();
    } catch (Exception e) {
      stopPlayback();
    }
  }

  public void toggle() {
    if (mp == null || !prepared) return;
    if (mp.isPlaying()) mp.pause();
    else if (requestFocus()) mp.start();
    updateForeground();
  }

  public void stopPlayback() {
    releasePlayer();
    abandonFocus();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  private void releasePlayer() {
    if (mp != null) {
      try { mp.stop(); } catch (Exception ignored) {}
      mp.release();
      mp = null;
      prepared = false;
    }
  }

  // ---- audio focus ----
  private boolean requestFocus() {
    if (focusRequest == null) {
      focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
          .setOnAudioFocusChangeListener(change -> {
            if (change != AudioManager.AUDIOFOCUS_GAIN && isPlaying()) {
              mp.pause();
              updateForeground();
            }
          })
          .build();
    }
    return audioManager.requestAudioFocus(focusRequest)
        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
  }

  private void abandonFocus() {
    if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
  }

  // ---- notification ----
  private void goForeground() {
    startForeground(NOTE_ID, buildNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
  }

  private void updateForeground() {
    if (mp == null) return;
    goForeground();
  }

  private PendingIntent svcIntent(String action) {
    Intent i = new Intent(this, PlayerService.class).setAction(action);
    return PendingIntent.getService(this, action.hashCode(), i,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification buildNote() {
    boolean playing = isPlaying();
    Intent open = new Intent(this, MainActivity.class)
        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_note))
        .setContentTitle(title)
        .setContentText(playing ? "Playing" : "Paused")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openPi)
        .setDeleteIntent(svcIntent(ACTION_STOP))
        .addAction(new Notification.Action.Builder(
            Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
            playing ? "Pause" : "Play", svcIntent(ACTION_TOGGLE)).build())
        .addAction(new Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stop),
            "Stop", svcIntent(ACTION_STOP)).build())
        .build();
  }

  // ---- state accessors for the activity ----
  public boolean isActive() { return mp != null && prepared; }
  public boolean isPlaying() {
    try { return mp != null && prepared && mp.isPlaying(); } catch (Exception e) { return false; }
  }
  public String getTitle() { return title; }
  public int getPositionMs() {
    try { return isActive() ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
  }
  public int getDurationMs() {
    try { return isActive() ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
  }
  public void seekTo(int ms) {
    if (isActive()) try { mp.seekTo(ms); } catch (Exception ignored) {}
  }

  @Override public void onDestroy() {
    releasePlayer();
    abandonFocus();
    super.onDestroy();
  }
}
