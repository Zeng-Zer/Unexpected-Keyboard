package juloo.keyboard2.pinyin;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Words with their pinyin, sorted by syllables. The sorted array is used as
    a trie: the words starting with a sequence of syllables are a contiguous
    range, found by binary search. See [ondevice/gen_pinyin.py] for the file
    format. */
public final class PinyinDict
{
  /** Every syllable, sorted. Syllables are identified by their index. */
  public final String[] syllables;
  final HashMap<String, Integer> _syllable_ids;
  /** Syllables of entry [i] are [_syls[_syl_off[i].._syl_off[i+1]]]. */
  final char[] _syls;
  final int[] _syl_off;
  /** Entry [i] is the word [_words[_word_off[i].._word_off[i+1]]]. */
  final char[] _words;
  final int[] _word_off;
  /** Log of the weight of each entry. */
  final float[] _logw;

  PinyinDict(String[] syls, char[] s, int[] so, char[] w, int[] wo, float[] lw)
  {
    syllables = syls;
    _syllable_ids = new HashMap<String, Integer>();
    for (int i = 0; i < syls.length; i++)
      _syllable_ids.put(syls[i], i);
    _syls = s; _syl_off = so; _words = w; _word_off = wo; _logw = lw;
  }

  public int size() { return _logw.length; }

  public String word(int i)
  {
    return new String(_words, _word_off[i], _word_off[i + 1] - _word_off[i]);
  }

  public int length(int i) { return _syl_off[i + 1] - _syl_off[i]; }

  public int syllable(int i, int depth) { return _syls[_syl_off[i] + depth]; }

  public float log_weight(int i) { return _logw[i]; }

  /** Id of a syllable or [-1]. */
  public int syllable_id(String s)
  {
    Integer i = _syllable_ids.get(s);
    return (i == null) ? -1 : i;
  }

  /** Narrow the range [lo, hi), whose entries share [depth] syllables, to the
      entries whose next syllable is [syl]. Returns the range packed in a long
      ([lo << 32 | hi]), empty if [lo == hi]. */
  public long child_range(int lo, int hi, int depth, int syl)
  {
    int a = lower_bound(lo, hi, depth, syl);
    int b = lower_bound(a, hi, depth, syl + 1);
    return ((long)a << 32) | (b & 0xFFFFFFFFL);
  }

  /** First entry in [lo, hi) whose syllable at [depth] is >= [syl]. Entries
      that have exactly [depth] syllables sort first. */
  int lower_bound(int lo, int hi, int depth, int syl)
  {
    while (lo < hi)
    {
      int mid = (lo + hi) >>> 1;
      int s = (length(mid) > depth) ? syllable(mid, depth) : -1;
      if (s < syl)
        lo = mid + 1;
      else
        hi = mid;
    }
    return lo;
  }

  /** Read the format written by [gen_pinyin.py]. */
  public static PinyinDict load(BufferedReader r) throws IOException
  {
    String[] syls = r.readLine().trim().split(" ");
    HashMap<String, Integer> ids = new HashMap<String, Integer>();
    for (int i = 0; i < syls.length; i++)
      ids.put(syls[i], i);
    Builder b = new Builder();
    String line;
    while ((line = r.readLine()) != null)
    {
      int t1 = line.indexOf('\t');
      int t2 = line.indexOf('\t', t1 + 1);
      if (t1 < 0 || t2 < 0)
        continue;
      String[] ps = line.substring(0, t1).split(" ");
      char[] sid = new char[ps.length];
      boolean ok = true;
      for (int i = 0; i < ps.length; i++)
      {
        Integer id = ids.get(ps[i]);
        if (id == null) { ok = false; break; }
        sid[i] = (char)(int)id;
      }
      if (!ok)
        continue;
      b.add(sid, line.substring(t1 + 1, t2),
          (float)Math.log(1 + Long.parseLong(line.substring(t2 + 1))));
    }
    return b.build(syls);
  }

  /** Entries must be added in sorted order, or [sort] must be called. */
  public static final class Builder
  {
    StringBuilder syls = new StringBuilder();
    ArrayList<Integer> syl_off = new ArrayList<Integer>();
    StringBuilder words = new StringBuilder();
    ArrayList<Integer> word_off = new ArrayList<Integer>();
    ArrayList<Float> logw = new ArrayList<Float>();

    public void add(char[] syllable_ids, String word, float log_weight)
    {
      syl_off.add(syls.length());
      syls.append(syllable_ids);
      word_off.add(words.length());
      words.append(word);
      logw.add(log_weight);
    }

    public PinyinDict build(String[] syllables)
    {
      int n = logw.size();
      int[] so = new int[n + 1];
      int[] wo = new int[n + 1];
      float[] lw = new float[n];
      for (int i = 0; i < n; i++)
      {
        so[i] = syl_off.get(i);
        wo[i] = word_off.get(i);
        lw[i] = logw.get(i);
      }
      so[n] = syls.length();
      wo[n] = words.length();
      char[] s = new char[syls.length()];
      syls.getChars(0, s.length, s, 0);
      char[] w = new char[words.length()];
      words.getChars(0, w.length, w, 0);
      return new PinyinDict(syllables, s, so, w, wo, lw);
    }
  }

  /** Build a small dictionary from unsorted entries. */
  public static PinyinDict of_entries(String[] syllables, List<char[]> syls,
      List<String> words, List<Float> logw)
  {
    Integer[] order = new Integer[words.size()];
    for (int i = 0; i < order.length; i++)
      order[i] = i;
    final List<char[]> s = syls;
    Arrays.sort(order, new java.util.Comparator<Integer>() {
      public int compare(Integer a, Integer b)
      {
        char[] x = s.get(a), y = s.get(b);
        for (int i = 0; i < x.length && i < y.length; i++)
          if (x[i] != y[i])
            return x[i] - y[i];
        return x.length - y.length;
      }
    });
    Builder b = new Builder();
    for (int i : order)
      b.add(syls.get(i), words.get(i), logw.get(i));
    return b.build(syllables);
  }
}
