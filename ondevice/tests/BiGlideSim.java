import juloo.cdict.Cdict;
import juloo.keyboard2.typing.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;

/** Glide accuracy for English and French words with both dictionaries merged.
    args: en.dict fr.dict */
public class BiGlideSim {
  static Cdict main(String path) throws Exception {
    for (Cdict x : Cdict.of_bytes(Files.readAllBytes(Paths.get(path)))) if (x.name.equals("main")) return x;
    return null;
  }
  static String glide(String w) {
    return Normalizer.normalize(w, Normalizer.Form.NFD).replaceAll("\\p{M}|['’-]", "").toLowerCase();
  }
  static List<String> words(Lexicon lex, int n, long seed) {
    List<String> t = new ArrayList<>();
    for (int i = 0; i < lex.size() && t.size() < 5000; i++) {
      String w = lex.word(i);
      if (lex.freq(i) >= 11 && w.length() >= 3 && w.equals(w.toLowerCase()) && glide(w).matches("[a-z]+")) t.add(w);
    }
    Collections.shuffle(t, new Random(seed));
    return t.subList(0, n);
  }
  static void eval(String name, GlideDecoder dec, KeyCenters kc, List<String> test) {
    GlideSim.rnd = new Random(3);
    int t1 = 0, t3 = 0; long t = System.nanoTime(); List<String> fails = new ArrayList<>();
    for (String w : test) {
      float[][] g = GlideSim.gestureGlide(kc, glide(w), 0.25f);
      GlideDecoder.Result r = dec.decode(g[0], g[1], g[0].length, 5);
      boolean ok1 = false, ok3 = false;
      if (r != null) for (int i = 0; i < Math.min(3, r.words.length); i++) if (r.words[i].equals(w)) { ok3 = true; ok1 = i == 0; }
      if (ok1) t1++; if (ok3) t3++;
      if (!ok1 && fails.size() < 12) fails.add(w + "->" + (r == null ? "null" : String.join(",", Arrays.copyOf(r.words, Math.min(3, r.words.length)))));
    }
    System.out.printf("%s: top1=%.1f%% top3=%.1f%% (%.1f ms/word)%n  %s%n", name, 100.0*t1/test.size(), 100.0*t3/test.size(), (System.nanoTime()-t)/1e6/test.size(), String.join("  ", fails));
  }
  public static void main(String[] a) throws Exception {
    Cdict en = main(a[0]), fr = main(a[1]);
    KeyCenters kc = GlideSim.layout();
    Lexicon len = Lexicon.of_dict(en), lfr = Lexicon.of_dict(fr);
    List<String> te = words(len, 400, 1), tf = words(lfr, 400, 2);
    long t = System.nanoTime();
    Lexicon both = Lexicon.of_dicts(new Cdict[]{ en, fr });
    System.out.printf("merged lexicon: %d words in %d ms%n", both.size(), (System.nanoTime() - t) / 1000000);
    eval("EN only, English words", new GlideDecoder(kc, len), kc, te);
    eval("EN+FR,   English words", new GlideDecoder(kc, both), kc, te);
    eval("FR only, French words ", new GlideDecoder(kc, lfr), kc, tf);
    eval("EN+FR,   French words ", new GlideDecoder(kc, both), kc, tf);
    Cdict[] ds = { en, fr };
    float[] ben = LanguageContext.bonus(ds, "I was thinking that we could go to the");
    float[] bfr = LanguageContext.bonus(ds, "je pense qu'on pourrait aller voir la");
    float[] bmix = LanguageContext.bonus(ds, "je pense qu'on pourrait check the");
    System.out.println("bonus en=" + Arrays.toString(ben) + " fr=" + Arrays.toString(bfr) + " mixed=" + Arrays.toString(bmix));
    GlideDecoder de = new GlideDecoder(kc, both); de.set_dict_bonus(ben);
    GlideDecoder df = new GlideDecoder(kc, both); df.set_dict_bonus(bfr);
    eval("EN context, English words", de, kc, te);
    eval("FR context, French words ", df, kc, tf);
    eval("FR context, English words", df, kc, te);
    eval("EN context, French words ", de, kc, tf);
  }
}
