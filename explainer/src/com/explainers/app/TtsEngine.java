package com.explainers.app;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wraps Android TextToSpeech: voice listing, previews, and blocking file synthesis. */
public final class TtsEngine {

  public interface ReadyCallback { void onReady(boolean ok); }

  private final TextToSpeech tts;
  private volatile boolean ready;

  public TtsEngine(Context ctx, ReadyCallback cb) {
    tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
      ready = status == TextToSpeech.SUCCESS;
      cb.onReady(ready);
    });
  }

  public boolean isReady() { return ready; }

  /** Installed English voices that work offline, sorted by label. */
  public List<Voice> englishVoices() {
    List<Voice> out = new ArrayList<>();
    if (!ready) return out;
    Set<Voice> vs;
    try { vs = tts.getVoices(); } catch (Exception e) { return out; }
    if (vs == null) return out;
    for (Voice v : vs) {
      Locale l = v.getLocale();
      if (l != null && "en".equals(l.getLanguage()) && !v.isNetworkConnectionRequired())
        out.add(v);
    }
    Collections.sort(out, (a, b) -> label(a).compareToIgnoreCase(label(b)));
    return out;
  }

  /** Human-friendly name for a TTS voice. */
  public static String label(Voice v) {
    Locale l = v.getLocale();
    String region = l == null ? "" : l.getDisplayCountry(Locale.ENGLISH);
    String head = region == null || region.isEmpty() ? "English" : "English (" + region + ")";
    String tail = v.getName()
        .replaceAll("(?i)^[a-z]{2,3}[-_][a-z]{2,3}[-_]?", "")
        .replaceAll("(?i)[-_]?(local|language|network)", "")
        .replace('_', ' ').replace('-', ' ').trim();
    return tail.isEmpty() ? head : head + " · " + tail;
  }

  private void applyVoice(String voiceName) {
    if (voiceName == null || !ready) return;
    try {
      for (Voice v : tts.getVoices())
        if (v.getName().equals(voiceName)) { tts.setVoice(v); return; }
    } catch (Exception ignored) {}
  }

  /** Speaks a short sample out loud (voice/speed preview). */
  public void preview(String voiceName, float rate, String text) {
    if (!ready) return;
    applyVoice(voiceName);
    tts.setSpeechRate(rate);
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview");
  }

  /**
   * Blocking (call from a worker thread): synthesizes the text into one or more
   * WAV files in {@code dir}, splitting on sentence boundaries when the text
   * exceeds the engine's per-utterance limit.
   */
  public List<File> synthesize(String text, String voiceName, float rate, File dir) throws Exception {
    if (!ready) throw new IllegalStateException("Speech engine is not ready yet");
    applyVoice(voiceName);
    tts.setSpeechRate(rate);
    int max = Math.min(TextToSpeech.getMaxSpeechInputLength() - 100, 3500);
    List<String> chunks = chunk(text, max);
    List<File> files = new ArrayList<>();
    int i = 0;
    for (String part : chunks) {
      File f = new File(dir, "tts-" + (i++) + ".wav");
      if (f.exists() && !f.delete()) throw new Exception("cannot overwrite " + f);
      final String id = "utt-" + System.nanoTime();
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicBoolean ok = new AtomicBoolean(false);
      tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        @Override public void onStart(String s) {}
        @Override public void onDone(String s) { if (id.equals(s)) { ok.set(true); latch.countDown(); } }
        @Override public void onError(String s) { if (id.equals(s)) latch.countDown(); }
        @Override public void onError(String s, int code) { if (id.equals(s)) latch.countDown(); }
      });
      if (tts.synthesizeToFile(part, new Bundle(), f, id) != TextToSpeech.SUCCESS)
        throw new Exception("Speech engine refused to synthesize");
      if (!latch.await(180, TimeUnit.SECONDS))
        throw new Exception("Speech synthesis timed out");
      if (!ok.get() || !f.exists() || f.length() < 100)
        throw new Exception("Speech synthesis failed — try a different voice");
      files.add(f);
    }
    return files;
  }

  /** Splits text into chunks of at most {@code max} chars, preferring sentence ends. */
  static List<String> chunk(String text, int max) {
    List<String> out = new ArrayList<>();
    String t = text.trim();
    while (t.length() > max) {
      int cut = -1;
      for (String sep : new String[]{". ", "! ", "? ", "\n", ", ", " "}) {
        int idx = t.lastIndexOf(sep, max);
        if (idx > max / 3) { cut = idx + sep.length(); break; }
      }
      if (cut <= 0) cut = max;
      out.add(t.substring(0, cut).trim());
      t = t.substring(cut).trim();
    }
    if (!t.isEmpty()) out.add(t);
    return out;
  }

  public void shutdown() { try { tts.shutdown(); } catch (Exception ignored) {} }
}
