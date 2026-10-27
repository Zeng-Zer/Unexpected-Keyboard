import juloo.cdict.Cdict;
import juloo.keyboard2.typing.*;
import java.nio.file.*;
public class LexProbe {
  public static void main(String[] a) throws Exception {
    Cdict d = null;
    for (Cdict x : Cdict.of_bytes(Files.readAllBytes(Paths.get(a[0])))) if (x.name.equals("main")) d = x;
    long t = System.nanoTime();
    Lexicon l = Lexicon.of_dict(d);
    System.out.println("lexicon " + l.size() + " words in " + (System.nanoTime() - t) / 1000000 + "ms");
    int[] hist = new int[16];
    for (int i = 0; i < l.size(); i++) hist[l.freq(i)]++;
    System.out.println(java.util.Arrays.toString(hist));
    for (int i = 0; i < 15; i++) System.out.print(l.word(i) + " ");
    System.out.println();
  }
}
