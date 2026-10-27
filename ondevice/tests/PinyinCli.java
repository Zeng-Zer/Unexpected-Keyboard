import juloo.keyboard2.pinyin.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Prints the candidates for pinyin inputs. args: pinyin.txt input... */
public class PinyinCli {
  public static void main(String[] a) throws Exception {
    long t = System.nanoTime();
    PinyinDict d = PinyinDict.load(new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8)));
    PinyinEngine e = new PinyinEngine(d);
    System.out.printf("loaded %d entries in %d ms%n", d.size(), (System.nanoTime() - t) / 1000000);
    for (int i = 1; i < a.length; i++) {
      t = System.nanoTime();
      List<PinyinEngine.Candidate> cs = e.candidates(a[i]);
      long ms = (System.nanoTime() - t) / 1000000;
      StringBuilder b = new StringBuilder();
      for (int j = 0; j < Math.min(10, cs.size()); j++) b.append(cs.get(j).word).append(cs.get(j).consumed < a[i].length() ? "(" + cs.get(j).consumed + ")" : "").append(' ');
      System.out.printf("%-22s %3dms %d cands: %s%n", a[i], ms, cs.size(), b);
    }
  }
}
