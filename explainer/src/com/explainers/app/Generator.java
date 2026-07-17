package com.explainers.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Orchestrates: xAI script -> TTS narration -> MP3 encode -> export to Music/Explainers. */
public final class Generator {

  public interface Callback {
    void onStatus(String msg);
    void onDone(Item item);
    void onError(String msg);
  }

  /** One saved explainer in the library. */
  public static final class Item {
    public String title;
    public String uri;        // MediaStore content:// uri of the MP3
    public long durationMs;
    public long created;
    public String script;

    public JSONObject toJson() {
      try {
        return new JSONObject().put("title", title).put("uri", uri)
            .put("durationMs", durationMs).put("created", created).put("script", script);
      } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static Item fromJson(JSONObject j) {
      Item it = new Item();
      it.title = j.optString("title");
      it.uri = j.optString("uri");
      it.durationMs = j.optLong("durationMs");
      it.created = j.optLong("created");
      it.script = j.optString("script");
      return it;
    }
  }

  public static void run(Context ctx, TtsEngine tts, String apiKey, String model,
                         String topic, int words, String voiceName, float rate, Callback cb) {
    final Handler ui = new Handler(Looper.getMainLooper());
    final Context app = ctx.getApplicationContext();
    new Thread(() -> {
      try {
        ui.post(() -> cb.onStatus("✍️ Asking Grok to write the script…"));
        String script = XaiApi.generateScript(apiKey, model, topic, words);
        if (script.length() < 40) throw new Exception("The model returned an unusable script");

        ui.post(() -> cb.onStatus("🗣 Recording the narration…"));
        File dir = new File(app.getCacheDir(), "tts");
        dir.mkdirs();
        List<File> wavs = tts.synthesize(script, voiceName, rate, dir);

        ui.post(() -> cb.onStatus("🎛 Encoding MP3…"));
        Mp3Encoder.Pcm pcm = Mp3Encoder.concatWavs(wavs);
        File tmp = new File(app.getCacheDir(), "explainer-tmp.mp3");
        Mp3Encoder.encode(pcm, 128, tmp);
        for (File w : wavs) w.delete();

        ui.post(() -> cb.onStatus("💾 Saving to Music/Explainers…"));
        Uri uri = exportToMediaStore(app, tmp, topic);
        tmp.delete();

        Item it = new Item();
        it.title = topic;
        it.uri = uri.toString();
        it.durationMs = pcm.durationMs();
        it.created = System.currentTimeMillis();
        it.script = script;
        ui.post(() -> cb.onDone(it));
      } catch (Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        ui.post(() -> cb.onError(msg));
      }
    }, "generator").start();
  }

  private static Uri exportToMediaStore(Context ctx, File mp3, String topic) throws Exception {
    String base = topic.replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '_');
    if (base.isEmpty()) base = "explainer";
    if (base.length() > 48) base = base.substring(0, 48);

    ContentValues v = new ContentValues();
    v.put(MediaStore.Audio.Media.DISPLAY_NAME, base + ".mp3");
    v.put(MediaStore.Audio.Media.TITLE, topic);
    v.put(MediaStore.Audio.Media.ARTIST, "Explainers");
    v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
    v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Explainers");
    v.put(MediaStore.Audio.Media.IS_PENDING, 1);
    Uri uri = ctx.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
    if (uri == null) throw new Exception("Could not create the MP3 in MediaStore");
    try (InputStream in = new FileInputStream(mp3);
         OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
    v.clear();
    v.put(MediaStore.Audio.Media.IS_PENDING, 0);
    ctx.getContentResolver().update(uri, v, null, null);
    return uri;
  }

  private Generator() {}
}
