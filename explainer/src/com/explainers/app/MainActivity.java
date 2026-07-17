package com.explainers.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

  private static final int BG = 0xFF0F141A, CARD = 0xFF1A222C, ACCENT = 0xFF4FC3F7,
      TEXT = 0xFFECEFF4, MUTED = 0xFF93A3B3, ERR = 0xFFFF7A6E, OK = 0xFF9CDF7C;
  private static final int[] LEN_WORDS = {160, 320, 550};
  private static final String[] LEN_LABELS = {"≈ 1 minute", "≈ 2 minutes", "≈ 4 minutes"};

  private SharedPreferences prefs;
  private TtsEngine tts;
  private final List<Voice> voices = new ArrayList<>();

  private EditText topicEdit;
  private Spinner lenSpin, voiceSpin;
  private SeekBar speedBar;
  private TextView speedLabel, status;
  private Button genBtn;
  private LinearLayout libraryBox;
  private final List<Generator.Item> library = new ArrayList<>();

  // player bar
  private LinearLayout playerBar;
  private TextView pTitle, pTime;
  private SeekBar pSeek;
  private Button pToggle;
  private PlayerService player;
  private boolean bound, userSeeking, generating;
  private final Handler ui = new Handler(Looper.getMainLooper());

  private final ServiceConnection conn = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName n, IBinder b) {
      player = ((PlayerService.LocalBinder) b).get();
      bound = true;
    }
    @Override public void onServiceDisconnected(ComponentName n) { bound = false; player = null; }
  };

  private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    prefs = getSharedPreferences("explainers", MODE_PRIVATE);
    buildUi();
    loadLibrary();
    renderLibrary();
    tts = new TtsEngine(this, ok -> ui.post(() -> {
      if (ok) fillVoices();
      else setStatus("Speech engine unavailable on this device.", ERR);
    }));
    bindService(new Intent(this, PlayerService.class), conn, Context.BIND_AUTO_CREATE);
    ui.post(pollPlayer);
  }

  // ------------------------------------------------------------- UI skeleton
  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(16);
    content.setPadding(pad, pad, pad, pad);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    // header
    LinearLayout header = row(content, 0);
    TextView title = text(header, "🎧 Explainers", 24, TEXT, true);
    title.setLayoutParams(new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    Button settings = button(header, "⚙ API key", false);
    settings.setOnClickListener(v -> showSettings());
    text(content, "Turn any topic into a short, entertaining audio explainer — "
        + "written by Grok, narrated on your phone, saved as MP3.", 14, MUTED, false)
        .setPadding(0, dp(4), 0, dp(14));

    // ---- create card ----
    LinearLayout card = card(content);
    text(card, "TOPIC", 12, MUTED, true);
    topicEdit = new EditText(this);
    topicEdit.setHint("e.g. Why do cats purr?");
    topicEdit.setTextColor(TEXT);
    topicEdit.setHintTextColor(MUTED);
    topicEdit.setTextSize(16);
    topicEdit.setMaxLines(3);
    card.addView(topicEdit, mlp());

    text(card, "LENGTH", 12, MUTED, true).setPadding(0, dp(12), 0, 0);
    lenSpin = new Spinner(this);
    lenSpin.setAdapter(spinnerAdapter(LEN_LABELS));
    lenSpin.setSelection(prefs.getInt("len", 1));
    card.addView(lenSpin, mlp());

    text(card, "VOICE", 12, MUTED, true).setPadding(0, dp(12), 0, 0);
    LinearLayout vrow = row(card, 0);
    voiceSpin = new Spinner(this);
    LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    vrow.addView(voiceSpin, vlp);
    voiceSpin.setAdapter(spinnerAdapter(new String[]{"Loading voices…"}));
    Button preview = button(vrow, "▶ Preview", false);
    preview.setOnClickListener(v -> {
      if (tts == null || !tts.isReady()) return;
      tts.preview(selectedVoiceName(), rate(),
          "Hi! This is how your explainers will sound.");
    });

    text(card, "TALKING SPEED", 12, MUTED, true).setPadding(0, dp(12), 0, 0);
    speedLabel = text(card, "1.0×", 14, TEXT, false);
    speedBar = new SeekBar(this);
    speedBar.setMax(15); // 0.5x .. 2.0x in 0.1 steps
    speedBar.setProgress(prefs.getInt("speed", 5));
    card.addView(speedBar, mlp());
    speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int p, boolean u) { updateSpeedLabel(); }
      @Override public void onStartTrackingTouch(SeekBar s) {}
      @Override public void onStopTrackingTouch(SeekBar s) {}
    });
    updateSpeedLabel();

    genBtn = new Button(this);
    genBtn.setText("✨  Create explainer");
    genBtn.setTextSize(17);
    genBtn.setAllCaps(false);
    genBtn.setTextColor(0xFF06222E);
    GradientDrawable g = new GradientDrawable();
    g.setColor(ACCENT);
    g.setCornerRadius(dp(12));
    genBtn.setBackground(g);
    LinearLayout.LayoutParams glp = mlp();
    glp.topMargin = dp(16);
    card.addView(genBtn, glp);
    genBtn.setOnClickListener(v -> generate());

    status = text(card, "", 14, MUTED, false);
    status.setPadding(0, dp(10), 0, 0);

    // ---- library ----
    text(content, "LIBRARY", 12, MUTED, true).setPadding(0, dp(20), 0, dp(6));
    libraryBox = new LinearLayout(this);
    libraryBox.setOrientation(LinearLayout.VERTICAL);
    content.addView(libraryBox, mlp());

    // ---- player bar ----
    playerBar = new LinearLayout(this);
    playerBar.setOrientation(LinearLayout.VERTICAL);
    playerBar.setBackgroundColor(CARD);
    playerBar.setPadding(pad, dp(10), pad, dp(10));
    playerBar.setVisibility(View.GONE);
    LinearLayout prow = row(playerBar, 0);
    pToggle = button(prow, "⏸", false);
    pToggle.setOnClickListener(v -> { if (bound && player != null) player.toggle(); });
    LinearLayout mid = new LinearLayout(this);
    mid.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0,
        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    midLp.leftMargin = dp(10);
    prow.addView(mid, midLp);
    pTitle = text(mid, "", 15, TEXT, true);
    pTitle.setSingleLine(true);
    pTitle.setEllipsize(TextUtils.TruncateAt.END);
    pTime = text(mid, "", 12, MUTED, false);
    Button pStop = button(prow, "⏹", false);
    pStop.setOnClickListener(v -> {
      if (bound && player != null) player.stopPlayback();
      playerBar.setVisibility(View.GONE);
    });
    pSeek = new SeekBar(this);
    playerBar.addView(pSeek, mlp());
    pSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int p, boolean u) {}
      @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
      @Override public void onStopTrackingTouch(SeekBar s) {
        userSeeking = false;
        if (bound && player != null) player.seekTo(s.getProgress());
      }
    });
    root.addView(playerBar, mlp());

    setContentView(root);
  }

  // ------------------------------------------------------------- view helpers
  private LinearLayout.LayoutParams mlp() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }
  private LinearLayout row(LinearLayout parent, int topMargin) {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams lp = mlp();
    lp.topMargin = topMargin;
    parent.addView(r, lp);
    return r;
  }
  private LinearLayout card(LinearLayout parent) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    int p = dp(14);
    c.setPadding(p, p, p, p);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(CARD);
    bg.setCornerRadius(dp(14));
    c.setBackground(bg);
    parent.addView(c, mlp());
    return c;
  }
  private TextView text(LinearLayout parent, String s, int sizeSp, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(sizeSp);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    parent.addView(t, mlp());
    return t;
  }
  private Button button(LinearLayout parent, String label, boolean accent) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(accent ? 0xFF06222E : TEXT);
    parent.addView(b, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return b;
  }
  private ArrayAdapter<String> spinnerAdapter(String[] items) {
    ArrayAdapter<String> a = new ArrayAdapter<>(this,
        android.R.layout.simple_spinner_item, items);
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    return a;
  }
  private void setStatus(String msg, int color) {
    status.setText(msg);
    status.setTextColor(color);
  }

  // ------------------------------------------------------------- voices/speed
  private void fillVoices() {
    voices.clear();
    voices.addAll(tts.englishVoices());
    List<String> labels = new ArrayList<>();
    labels.add("System default voice");
    for (Voice v : voices) labels.add(TtsEngine.label(v));
    voiceSpin.setAdapter(spinnerAdapter(labels.toArray(new String[0])));
    String saved = prefs.getString("voice", null);
    if (saved != null)
      for (int i = 0; i < voices.size(); i++)
        if (voices.get(i).getName().equals(saved)) { voiceSpin.setSelection(i + 1); break; }
  }
  private String selectedVoiceName() {
    int i = voiceSpin.getSelectedItemPosition();
    return (i <= 0 || i > voices.size()) ? null : voices.get(i - 1).getName();
  }
  private float rate() { return 0.5f + speedBar.getProgress() * 0.1f; }
  private void updateSpeedLabel() {
    speedLabel.setText(String.format(Locale.US, "%.1f×", rate()));
  }

  // ------------------------------------------------------------- settings
  private void showSettings() {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    int p = dp(20);
    box.setPadding(p, dp(10), p, 0);
    TextView hint = new TextView(this);
    hint.setText("Paste your xAI API key (create one at console.x.ai). "
        + "It is stored only on this phone.");
    hint.setTextColor(MUTED);
    box.addView(hint);
    EditText key = new EditText(this);
    key.setHint("xai-…");
    key.setText(prefs.getString("key", ""));
    box.addView(key);
    EditText model = new EditText(this);
    model.setHint("model (default grok-3-mini)");
    model.setText(prefs.getString("model", "grok-3-mini"));
    box.addView(model);
    new AlertDialog.Builder(this)
        .setTitle("xAI API settings")
        .setView(box)
        .setPositiveButton("Save", (d, w) -> prefs.edit()
            .putString("key", key.getText().toString().trim())
            .putString("model", model.getText().toString().trim().isEmpty()
                ? "grok-3-mini" : model.getText().toString().trim())
            .apply())
        .setNegativeButton("Cancel", null)
        .show();
  }

  // ------------------------------------------------------------- generate
  private void generate() {
    if (generating) return;
    String key = prefs.getString("key", "");
    if (key.isEmpty()) {
      Toast.makeText(this, "First add your xAI API key", Toast.LENGTH_LONG).show();
      showSettings();
      return;
    }
    String topic = topicEdit.getText().toString().trim();
    if (topic.isEmpty()) {
      Toast.makeText(this, "Type a topic first", Toast.LENGTH_SHORT).show();
      return;
    }
    if (tts == null || !tts.isReady()) {
      Toast.makeText(this, "Speech engine is still starting — try again in a moment",
          Toast.LENGTH_SHORT).show();
      return;
    }
    prefs.edit()
        .putInt("len", lenSpin.getSelectedItemPosition())
        .putInt("speed", speedBar.getProgress())
        .putString("voice", selectedVoiceName() == null ? "" : selectedVoiceName())
        .apply();
    generating = true;
    genBtn.setEnabled(false);
    genBtn.setAlpha(0.5f);
    int words = LEN_WORDS[Math.max(0, Math.min(2, lenSpin.getSelectedItemPosition()))];
    Generator.run(this, tts, key, prefs.getString("model", "grok-3-mini"),
        topic, words, selectedVoiceName(), rate(), new Generator.Callback() {
          @Override public void onStatus(String msg) { setStatus(msg, MUTED); }
          @Override public void onDone(Generator.Item item) {
            generating = false;
            genBtn.setEnabled(true);
            genBtn.setAlpha(1f);
            setStatus("✅ Saved to Music/Explainers — playing now.", OK);
            library.add(0, item);
            saveLibrary();
            renderLibrary();
            play(item);
          }
          @Override public void onError(String msg) {
            generating = false;
            genBtn.setEnabled(true);
            genBtn.setAlpha(1f);
            setStatus("⚠️ " + msg, ERR);
          }
        });
  }

  // ------------------------------------------------------------- library
  private void loadLibrary() {
    library.clear();
    try {
      JSONArray arr = new JSONArray(prefs.getString("library", "[]"));
      for (int i = 0; i < arr.length(); i++)
        library.add(Generator.Item.fromJson(arr.getJSONObject(i)));
    } catch (Exception ignored) {}
  }
  private void saveLibrary() {
    JSONArray arr = new JSONArray();
    for (Generator.Item it : library) arr.put(it.toJson());
    prefs.edit().putString("library", arr.toString()).apply();
  }

  private void renderLibrary() {
    libraryBox.removeAllViews();
    if (library.isEmpty()) {
      text(libraryBox, "Nothing here yet — create your first explainer above!", 14, MUTED, false);
      return;
    }
    DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
    for (Generator.Item it : library) {
      LinearLayout rowBox = new LinearLayout(this);
      rowBox.setOrientation(LinearLayout.HORIZONTAL);
      rowBox.setGravity(Gravity.CENTER_VERTICAL);
      int p = dp(12);
      rowBox.setPadding(p, p, p, p);
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(CARD);
      bg.setCornerRadius(dp(12));
      rowBox.setBackground(bg);
      LinearLayout.LayoutParams lp = mlp();
      lp.bottomMargin = dp(8);
      libraryBox.addView(rowBox, lp);

      LinearLayout col = new LinearLayout(this);
      col.setOrientation(LinearLayout.VERTICAL);
      rowBox.addView(col, new LinearLayout.LayoutParams(0,
          ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      TextView t = text(col, it.title, 16, TEXT, true);
      t.setSingleLine(true);
      t.setEllipsize(TextUtils.TruncateAt.END);
      text(col, fmtMs(it.durationMs) + "  ·  " + df.format(new Date(it.created)),
          12, MUTED, false);
      TextView playIcon = new TextView(this);
      playIcon.setText("▶");
      playIcon.setTextSize(22);
      playIcon.setTextColor(ACCENT);
      playIcon.setPadding(dp(10), 0, dp(4), 0);
      rowBox.addView(playIcon);

      rowBox.setOnClickListener(v -> play(it));
      rowBox.setOnLongClickListener(v -> { itemMenu(it); return true; });
    }
  }

  private void itemMenu(Generator.Item it) {
    String[] opts = {"Share MP3", "Read script", "Delete"};
    new AlertDialog.Builder(this)
        .setTitle(it.title)
        .setItems(opts, (d, which) -> {
          if (which == 0) {
            Intent send = new Intent(Intent.ACTION_SEND)
                .setType("audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(it.uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share explainer"));
          } else if (which == 1) {
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setText(it.script == null || it.script.isEmpty() ? "(script not stored)" : it.script);
            tv.setPadding(dp(20), dp(10), dp(20), dp(10));
            tv.setTextIsSelectable(true);
            sv.addView(tv);
            new AlertDialog.Builder(this).setTitle(it.title).setView(sv)
                .setPositiveButton("Close", null).show();
          } else {
            try { getContentResolver().delete(Uri.parse(it.uri), null, null); }
            catch (Exception ignored) {}
            library.remove(it);
            saveLibrary();
            renderLibrary();
          }
        })
        .show();
  }

  // ------------------------------------------------------------- playback
  private void play(Generator.Item it) {
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }
    Intent i = new Intent(this, PlayerService.class)
        .setAction(PlayerService.ACTION_PLAY)
        .putExtra(PlayerService.EXTRA_URI, it.uri)
        .putExtra(PlayerService.EXTRA_TITLE, it.title);
    startForegroundService(i);
    playerBar.setVisibility(View.VISIBLE);
    pTitle.setText(it.title);
  }

  private final Runnable pollPlayer = new Runnable() {
    @Override public void run() {
      if (bound && player != null && player.isActive()) {
        playerBar.setVisibility(View.VISIBLE);
        pTitle.setText(player.getTitle());
        int dur = player.getDurationMs(), pos = player.getPositionMs();
        if (pSeek.getMax() != dur) pSeek.setMax(dur);
        if (!userSeeking) pSeek.setProgress(pos);
        pTime.setText(fmtMs(pos) + " / " + fmtMs(dur));
        pToggle.setText(player.isPlaying() ? "⏸" : "▶");
      } else if (playerBar.getVisibility() == View.VISIBLE && (player == null || !player.isActive())) {
        playerBar.setVisibility(View.GONE);
      }
      ui.postDelayed(this, 500);
    }
  };

  private static String fmtMs(long ms) {
    long s = ms / 1000;
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
  }

  @Override protected void onDestroy() {
    ui.removeCallbacks(pollPlayer);
    if (bound) unbindService(conn);
    if (tts != null) tts.shutdown();
    super.onDestroy();
  }
}
