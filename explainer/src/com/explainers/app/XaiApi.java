package com.explainers.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal client for the xAI (Grok) chat completions API. */
public final class XaiApi {

  static String ENDPOINT = "https://api.x.ai/v1/chat/completions"; // overridable in tests

  public static String generateScript(String apiKey, String model, String topic, int words)
      throws Exception {
    String system =
        "You write scripts for short audio explainers that are read aloud by a text-to-speech voice. " +
        "Style: warm, conversational English that is genuinely entertaining. Open with a hook, " +
        "weave in a surprising fact or a playful comparison, explain clearly for a curious general " +
        "listener, and end with a satisfying takeaway. Output ONLY the words to be spoken: plain " +
        "prose paragraphs. No title, no headings, no lists, no markdown, no emojis, no stage " +
        "directions, no sound effects, no host name. Length: about " + words + " words.";

    JSONObject body = new JSONObject()
        .put("model", model)
        .put("temperature", 0.7)
        .put("messages", new JSONArray()
            .put(new JSONObject().put("role", "system").put("content", system))
            .put(new JSONObject().put("role", "user").put("content", "Topic: " + topic)));

    HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
    try {
      c.setConnectTimeout(20000);
      c.setReadTimeout(180000);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
      try (OutputStream out = c.getOutputStream()) {
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
      }
      int code = c.getResponseCode();
      InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
      String resp = readAll(is);
      if (code != 200) throw new Exception("xAI API error " + code + ": " + extractError(resp));
      JSONObject json = new JSONObject(resp);
      String content = json.getJSONArray("choices").getJSONObject(0)
          .getJSONObject("message").getString("content");
      return cleanScript(content);
    } finally {
      c.disconnect();
    }
  }

  private static String readAll(InputStream is) throws Exception {
    if (is == null) return "";
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = is.read(b)) > 0) buf.write(b, 0, n);
    is.close();
    return buf.toString("UTF-8");
  }

  private static String extractError(String resp) {
    try {
      JSONObject j = new JSONObject(resp);
      if (j.has("error")) {
        Object e = j.get("error");
        if (e instanceof JSONObject) return ((JSONObject) e).optString("message", e.toString());
        return e.toString();
      }
      if (j.has("msg")) return j.getString("msg");
    } catch (Exception ignored) {}
    return resp.length() > 300 ? resp.substring(0, 300) : resp;
  }

  /** Strips anything a model might add that should not be spoken aloud. */
  static String cleanScript(String s) {
    s = s.replaceAll("(?m)^#+\\s*", "")          // markdown headings
         .replaceAll("\\*\\*?|__|`", "")          // bold/italic/code markers
         .replaceAll("(?m)^\\s*[-*•]\\s+", "")   // list bullets
         .replaceAll("\\[[^\\]]*\\]\\([^)]*\\)", "") // links
         .trim();
    return s;
  }

  private XaiApi() {}
}
