import juloo.cdict.Cdict;
import juloo.keyboard2.typing.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Simulates sloppy tapping with a systematic offset; compares error rates. */
public class TapSim {
  static void set(Class<?> cl, String f, float v) throws Exception {
    Field fl = cl.getDeclaredField(f); fl.setAccessible(true); fl.setFloat(null, v);
  }
  static int geometric(KeyCenters kc, float x, float y) {
    for (int k = 0; k < kc.size(); k++)
      if (Math.abs(x - kc.x[k]) <= kc.w[k] / 2 && Math.abs(y - kc.y[k]) <= kc.h[k] / 2) return k;
    return -1;
  }
  /** Returns char error rate (%) for: mode 0 = geometric, 1 = bias only, 2 = bias + LM. */
  static float run(KeyCenters kc, List<String> words, TapModel.LetterModel lm, int mode, float sigma, float offx, float offy) {
    Random rnd = new Random(11);
    TapModel m = new TapModel();
    int errors = 0, total = 0;
    for (String w : words) {
      StringBuilder typed = new StringBuilder();
      for (char c : w.toCharArray()) {
        int k = kc.index_of(c);
        float x = kc.x[k] + ((float)rnd.nextGaussian() * sigma + offx) * kc.w[k];
        float y = kc.y[k] + ((float)rnd.nextGaussian() * sigma + offy) * kc.h[k];
        int g = geometric(kc, x, y);
        int p = g;
        if (mode > 0 && g >= 0) p = m.predict(kc, x, y, g, mode == 2 ? lm : null, typed.toString());
        if (p != k) errors++;
        else if (mode > 0) m.learn(kc, k, x, y);
        typed.append(p >= 0 ? kc.chars[p] : '?');
        total++;
      }
    }
    return 100f * errors / total;
  }
  public static void main(String[] a) throws Exception {
    Cdict d = null;
    for (Cdict x : Cdict.of_bytes(Files.readAllBytes(Paths.get(a[0])))) if (x.name.equals("main")) d = x;
    Lexicon lex = Lexicon.of_dict(d);
    KeyCenters kc = GlideSim.layout();
    TapModel.LetterModel lm = new DictLetterModel(new Cdict[]{ d });
    // Word stream drawn from frequent words, weighted towards the most frequent
    List<String> pool = new ArrayList<>();
    for (int i = 0; i < lex.size() && pool.size() < 3000; i++) { String w = lex.word(i); if (lex.freq(i) >= 11 && w.matches("[a-z]+")) pool.add(w); }
    Random r = new Random(5); List<String> words = new ArrayList<>();
    for (int i = 0; i < 3000; i++) words.add(pool.get((int)(Math.pow(r.nextDouble(), 2) * pool.size())));
    for (int i = 1; i + 1 < a.length; i += 2) {
      Class<?> cl = a[i].startsWith("BETA") ? DictLetterModel.class : TapModel.class;
      set(cl, a[i], Float.parseFloat(a[i + 1]));
    }
    for (float sigma : new float[]{ 0.25f, 0.33f }) {
      for (float[] off : new float[][]{ {0f, 0f}, {0.12f, 0.2f} }) {
        System.out.printf("sigma=%.2f offset=(%.2f,%.2f): plain %.2f%%  bias %.2f%%  bias+letters %.2f%%%n", sigma, off[0], off[1],
          run(kc, words, lm, 0, sigma, off[0], off[1]), run(kc, words, lm, 1, sigma, off[0], off[1]), run(kc, words, lm, 2, sigma, off[0], off[1]));
      }
    }
  }
}
