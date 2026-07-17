package com.explainers.app;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.GetAudio;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Parse;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

/**
 * Encodes 16-bit little-endian PCM to MP3 using jump3r (pure-Java LAME port).
 * This is a javax.sound-free rewrite of jump3r's LameEncoder so it runs on
 * Android. Pure JVM code — no Android imports — so it is testable on a desktop.
 */
public final class Mp3Encoder {

  /** Simple container for PCM audio read from a WAV file. */
  public static final class Pcm {
    public final byte[] data;      // 16-bit little-endian samples
    public final int channels;
    public final int sampleRate;
    public Pcm(byte[] data, int channels, int sampleRate) {
      this.data = data; this.channels = channels; this.sampleRate = sampleRate;
    }
    public long durationMs() {
      return (long) data.length * 1000L / (2L * channels * sampleRate);
    }
  }

  /** Parses a PCM WAV file (as produced by Android TextToSpeech). */
  public static Pcm readWav(File f) throws IOException {
    try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
      byte[] hdr = new byte[12];
      in.readFully(hdr);
      if (hdr[0] != 'R' || hdr[1] != 'I' || hdr[2] != 'F' || hdr[3] != 'F'
          || hdr[8] != 'W' || hdr[9] != 'A' || hdr[10] != 'V' || hdr[11] != 'E')
        throw new IOException("not a WAV file: " + f.getName());
      int channels = 0, sampleRate = 0, bits = 0;
      while (true) {
        byte[] ch = new byte[8];
        in.readFully(ch);
        String id = new String(ch, 0, 4, "US-ASCII");
        int size = (ch[4] & 0xff) | (ch[5] & 0xff) << 8 | (ch[6] & 0xff) << 16 | (ch[7] & 0xff) << 24;
        if (id.equals("fmt ")) {
          byte[] fmt = new byte[size];
          in.readFully(fmt);
          channels   = (fmt[2] & 0xff) | (fmt[3] & 0xff) << 8;
          sampleRate = (fmt[4] & 0xff) | (fmt[5] & 0xff) << 8 | (fmt[6] & 0xff) << 16 | (fmt[7] & 0xff) << 24;
          bits       = (fmt[14] & 0xff) | (fmt[15] & 0xff) << 8;
        } else if (id.equals("data")) {
          if (channels == 0) throw new IOException("WAV data before fmt chunk");
          if (bits != 16) throw new IOException("expected 16-bit WAV, got " + bits);
          // Android TTS sometimes writes size 0 / 0xFFFFFFFF and just streams:
          long remain = in.length() - in.getFilePointer();
          int len = (size <= 0 || size > remain) ? (int) remain : size;
          byte[] data = new byte[len];
          in.readFully(data);
          return new Pcm(data, channels, sampleRate);
        } else {
          in.seek(in.getFilePointer() + size + (size & 1));
        }
      }
    }
  }

  /** Encodes PCM to an MP3 file at the given constant bitrate (e.g. 128). */
  public static void encode(Pcm pcm, int bitrateKbps, File outFile) throws IOException {
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16)) {
      encode(pcm, bitrateKbps, out);
    }
  }

  public static void encode(Pcm pcm, int bitrateKbps, OutputStream out) throws IOException {
    // ---- module wiring, straight from jump3r's LameEncoder ----
    Lame lame = new Lame();
    GetAudio gaud = new GetAudio();
    GainAnalysis ga = new GainAnalysis();
    BitStream bs = new BitStream();
    Presets p = new Presets();
    QuantizePVT qupvt = new QuantizePVT();
    Quantize qu = new Quantize();
    VBRTag vbr = new VBRTag();
    Version ver = new Version();
    ID3Tag id3 = new ID3Tag();
    Reservoir rv = new Reservoir();
    Takehiro tak = new Takehiro();
    Parse parse = new Parse();
    MPGLib mpg = new MPGLib();
    Interface intf = new Interface();
    Common common = new Common();

    lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
    bs.setModules(ga, mpg, ver, vbr);
    id3.setModules(bs, ver);
    p.setModules(lame);
    qu.setModules(bs, rv, qupvt, tak);
    qupvt.setModules(tak, rv, lame.enc.psy);
    rv.setModules(bs);
    tak.setModules(qupvt);
    vbr.setModules(lame, bs, ver);
    gaud.setModules(parse, mpg);
    parse.setModules(ver, id3, p);
    mpg.setModules(intf, common);
    intf.setModules(vbr, common);

    LameGlobalFlags gfp = lame.lame_init();
    gfp.num_channels = pcm.channels;
    gfp.in_samplerate = pcm.sampleRate;
    gfp.mode = pcm.channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
    gfp.brate = bitrateKbps;
    gfp.quality = 5; // LAME "middle" quality — good and fast
    id3.id3tag_init(gfp);
    gfp.write_id3tag_automatic = false;
    gfp.findReplayGain = true;

    if (lame.lame_init_params(gfp) < 0)
      throw new IOException("LAME rejected parameters: " + pcm.channels + "ch " + pcm.sampleRate + "Hz");

    final int PCM_CHUNK = 2048 * 16; // bytes per encode call (as in LameEncoder)
    byte[] mp3buf = new byte[PCM_CHUNK / 2 + 1024];
    int[][] buffer = new int[2][];
    byte[] data = pcm.data;
    int bytesPerFrame = 2 * pcm.channels;

    for (int off = 0; off < data.length; off += PCM_CHUNK) {
      int len = Math.min(PCM_CHUNK, data.length - off);
      len -= len % bytesPerFrame;
      if (len <= 0) break;
      int samplesRead = len / 2;               // 16-bit samples incl. all channels
      int[] sampleBuffer = new int[samplesRead];
      for (int i = 0, si = 0; i < len; i += 2, si++) {
        sampleBuffer[si] = (data[off + i] & 0xff) << 16 | (data[off + i + 1] & 0xff) << 24;
      }
      int frames = samplesRead / pcm.channels;
      if (buffer[0] == null || buffer[0].length < frames) {
        buffer[0] = new int[frames];
        buffer[1] = new int[frames];
      }
      if (pcm.channels == 2) {
        for (int i = frames - 1, s = samplesRead; i >= 0; i--) {
          buffer[1][i] = sampleBuffer[--s];
          buffer[0][i] = sampleBuffer[--s];
        }
      } else {
        for (int i = 0; i < frames; i++) { buffer[0][i] = sampleBuffer[i]; buffer[1][i] = 0; }
      }
      int n = lame.lame_encode_buffer_int(gfp, buffer[0], buffer[1], frames, mp3buf, 0, mp3buf.length);
      if (n < 0) throw new IOException("LAME encode error " + n);
      out.write(mp3buf, 0, n);
    }
    int n = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.length);
    if (n > 0) out.write(mp3buf, 0, n);
    lame.lame_close(gfp);
  }

  /** Concatenates the PCM of several same-format WAV files. */
  public static Pcm concatWavs(java.util.List<File> wavs) throws IOException {
    Pcm first = null;
    java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
    for (File f : wavs) {
      Pcm w = readWav(f);
      if (first == null) first = w;
      else if (w.channels != first.channels || w.sampleRate != first.sampleRate)
        throw new IOException("WAV format mismatch between TTS chunks");
      all.write(w.data);
    }
    if (first == null) throw new IOException("no audio produced");
    return new Pcm(all.toByteArray(), first.channels, first.sampleRate);
  }

  private Mp3Encoder() {}
}
