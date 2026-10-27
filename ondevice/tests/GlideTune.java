import juloo.cdict.Cdict;
import juloo.keyboard2.typing.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

public class GlideTune {
  static void set(String f, float v) throws Exception {
    Field fl = GlideDecoder.class.getDeclaredField(f); fl.setAccessible(true); fl.setFloat(null, v);
  }
  static float[] eval(GlideDecoder dec, KeyCenters kc, List<String> words, float noise) {
    GlideSim.rnd = new Random(7);
    int t1 = 0, t3 = 0;
    for (String w : words) {
      float[][] g = GlideSim.gesture(kc, w, noise);
      GlideDecoder.Result r = dec.decode(g[0], g[1], g[0].length, 5);
      if (r == null) continue;
      for (int i = 0; i < Math.min(3, r.words.length); i++) if (r.words[i].equals(w)) { if (i == 0) t1++; t3++; break; }
    }
    return new float[]{ 100f * t1 / words.size(), 100f * t3 / words.size() };
  }
  public static void main(String[] a) throws Exception {
    Cdict d = null;
    for (Cdict x : Cdict.of_bytes(Files.readAllBytes(Paths.get(a[0])))) if (x.name.equals("main")) d = x;
    Lexicon lex = Lexicon.of_dict(d);
    KeyCenters kc = GlideSim.layout();
    GlideDecoder dec = new GlideDecoder(kc, lex);
    List<String> test = new ArrayList<>();
    for (int i = 0; i < lex.size() && test.size() < 6000; i++) {
      String w = lex.word(i);
      if (lex.freq(i) >= 11 && w.matches("[a-z]{3,}")) test.add(w);
    }
    Collections.shuffle(test, new Random(Integer.parseInt(a[1])));
    test = test.subList(0, 350);
    String[] names = a[2].split(",");
    float[][] vals = new float[names.length][];
    for (int i = 0; i < names.length; i++) { String[] vs = a[3 + i].split(","); vals[i] = new float[vs.length]; for (int j = 0; j < vs.length; j++) vals[i][j] = Float.parseFloat(vs[j]); }
    int total = 1; for (float[] v : vals) total *= v.length;
    for (int c = 0; c < total; c++) {
      int r = c; StringBuilder desc = new StringBuilder();
      for (int i = 0; i < names.length; i++) { float v = vals[i][r % vals[i].length]; r /= vals[i].length; set(names[i], v); desc.append(names[i]).append('=').append(v).append(' '); }
      float[] e1 = eval(dec, kc, test, 0.25f), e2 = eval(dec, kc, test, 0.35f);
      System.out.printf("%s| n.25 top1=%.1f top3=%.1f | n.35 top1=%.1f top3=%.1f | avg1=%.1f%n", desc, e1[0], e1[1], e2[0], e2[1], (e1[0]+e2[0])/2);
    }
  }
}
