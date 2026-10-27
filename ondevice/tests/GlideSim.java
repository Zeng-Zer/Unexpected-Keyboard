import juloo.cdict.Cdict;
import juloo.keyboard2.typing.*;
import java.nio.file.*;
import java.util.*;

/** Simulates noisy glide gestures on the user's layout and measures accuracy. */
public class GlideSim {
  static final float KW = 100, KH = 140;
  public static KeyCenters layout() {
    String[] rows = { "qwertyuiop", "asdfghjkl", "zxcvbnm" };
    float[] off = { 1.0f, 1.0f, 1.5f };
    List<Character> c = new ArrayList<>(); List<Float> x = new ArrayList<>(), y = new ArrayList<>();
    for (int r = 0; r < 3; r++)
      for (int i = 0; i < rows[r].length(); i++) {
        c.add(rows[r].charAt(i)); x.add((off[r] + i + 0.5f) * KW); y.add((0.75f + r + 0.5f) * KH);
      }
    int n = c.size(); char[] cs = new char[n]; float[] xs = new float[n], ys = new float[n], w = new float[n], h = new float[n];
    for (int i = 0; i < n; i++) { cs[i] = c.get(i); xs[i] = x.get(i); ys[i] = y.get(i); w[i] = KW; h[i] = KH; }
    return new KeyCenters(cs, xs, ys, w, h);
  }

  static Random rnd = new Random(42);

  /** Gesture for [word]: through noisy key centers, smoothed (corner cutting). */
  static float[][] gesture(KeyCenters kc, String word, float noise) {
    List<float[]> pts = new ArrayList<>();
    float[] prev = null;
    for (char ch : word.toCharArray()) {
      int k = kc.index_of(ch);
      float[] p = { kc.x[k] + (float)rnd.nextGaussian() * noise * KW, kc.y[k] + (float)rnd.nextGaussian() * noise * KH };
      if (prev != null) {
        float d = (float)Math.hypot(p[0] - prev[0], p[1] - prev[1]);
        int steps = Math.max(1, (int)(d / 12));
        for (int s = 1; s <= steps; s++) { float t = s / (float)steps; pts.add(new float[]{ prev[0] + (p[0] - prev[0]) * t, prev[1] + (p[1] - prev[1]) * t }); }
      } else pts.add(p);
      prev = p;
    }
    // moving average smoothing
    int n = pts.size(), win = 4;
    float[] xs = new float[n], ys = new float[n];
    for (int i = 0; i < n; i++) {
      float sx = 0, sy = 0; int c = 0;
      for (int j = Math.max(0, i - win); j <= Math.min(n - 1, i + win); j++) { sx += pts.get(j)[0]; sy += pts.get(j)[1]; c++; }
      xs[i] = sx / c + (float)rnd.nextGaussian() * 2; ys[i] = sy / c + (float)rnd.nextGaussian() * 2;
    }
    return new float[][]{ xs, ys };
  }

  public static void main(String[] a) throws Exception {
    Cdict d = null;
    for (Cdict x : Cdict.of_bytes(Files.readAllBytes(Paths.get(a[0])))) if (x.name.equals("main")) d = x;
    float noise = a.length > 1 ? Float.parseFloat(a[1]) : 0.25f;
    Lexicon lex = Lexicon.of_dict(d);
    KeyCenters kc = layout();
    GlideDecoder dec = new GlideDecoder(kc, lex);
    // Test words: frequent lowercase words of 2+ distinct-key letters, sampled
    List<String> test = new ArrayList<>();
    for (int i = 0; i < lex.size() && test.size() < 4000; i++) {
      String w = lex.word(i);
      if (lex.freq(i) >= 11 && w.matches("[a-z]{2,}") && w.chars().distinct().count() >= 2) test.add(w);
    }
    Collections.shuffle(test, new Random(1));
    test = test.subList(0, Math.min(600, test.size()));
    int top1 = 0, top3 = 0; long t = System.nanoTime();
    List<String> fails = new ArrayList<>();
    for (String w : test) {
      float[][] g = gesture(kc, w, noise);
      GlideDecoder.Result r = dec.decode(g[0], g[1], g[0].length, 5);
      if (r != null && r.words.length > 0 && r.words[0].equals(w)) top1++;
      else if (fails.size() < 25) fails.add(w + "->" + (r == null ? "null" : String.join(",", Arrays.copyOf(r.words, Math.min(3, r.words.length)))));
      if (r != null) for (int i = 0; i < Math.min(3, r.words.length); i++) if (r.words[i].equals(w)) { top3++; break; }
    }
    long ms = (System.nanoTime() - t) / 1000000;
    System.out.printf("noise=%.2f top1=%.1f%% top3=%.1f%% (%d words, %.1f ms/word)%n", noise, 100.0*top1/test.size(), 100.0*top3/test.size(), test.size(), ms/(double)test.size());
    System.out.println(String.join("  ", fails));
  }
}
