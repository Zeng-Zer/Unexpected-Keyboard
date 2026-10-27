import juloo.cdict.Cdict;
import java.nio.file.*;
public class DictProbe {
  public static void main(String[] a) throws Exception {
    Cdict[] ds = Cdict.of_bytes(Files.readAllBytes(Paths.get(a[0])));
    for (Cdict d : ds) System.out.println("dict " + d.name);
    Cdict d = ds[0];
    for (Cdict x : ds) if (x.name.equals("main")) d = x;
    for (int i : new int[]{0,1,2,1000,50000}) System.out.println(i + " " + d.word(i) + " f=" + d.freq(i));
    // find size by galloping
    int lo = 0, hi = 1;
    String prev = d.word(0);
    for (int i = 1; i < 2000000; i++) { String w = d.word(i); if (w.compareTo(prev) <= 0) { System.out.println("end at " + i + " last=" + prev + " next=" + w); break; } prev = w; }
    Cdict.Result r = d.find("hel");
    System.out.println("hel found=" + r.found + " idx=" + r.original_index);
    for (int i : d.suffixes(r, 8)) System.out.print(d.word(i) + "(" + d.freq(i) + ") ");
    System.out.println();
    r = d.find("helk"); System.out.println("helk prefix? " + (d.suffixes(r,1).length));
  }
}
