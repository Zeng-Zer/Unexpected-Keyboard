package juloo.keyboard2.pinyin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Convert pinyin typed without tones to Chinese characters.

    The input is split into syllables in every possible way. A piece of the
    input can be a complete syllable, an initial standing for any syllable
    starting with it ("zg" for "zhong guo") or, at the end of the input, the
    beginning of a syllable that is still being typed. Words are looked up
    syllable by syllable in [PinyinDict].

    The candidates are, in order:
    - Words and sentences covering the whole input. Sentences are the best
      sequence of words according to their frequency.
    - Words covering the beginning of the input, longest first. Choosing one
      leaves the rest of the input to convert.

    Scores are log probabilities. Words the user chooses get a bonus and the
    phrases the user builds from several words are remembered. */
public final class PinyinEngine
{
  public static final class Candidate
  {
    public final String word;
    /** Number of chars of the input covered by this candidate. */
    public final int consumed;
    public final float score;
    final char[] syllables;

    Candidate(String w, int c, float s, char[] syls)
    { word = w; consumed = c; score = s; syllables = syls; }
  }

  static final String[] INITIALS = { "b", "p", "m", "f", "d", "t", "n", "l",
    "g", "k", "h", "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y",
    "w" };
  /** Penalty of an initial standing for a whole syllable. */
  static float P_ABBREVIATION = -2.5f;
  /** Penalty of the last syllable not being complete. */
  static float P_INCOMPLETE = -1.0f;
  /** Penalty of extending a complete last syllable ("xian" to "xiang"). */
  static float P_EXTEND = -4.0f;
  /** Bonus of the words chosen by the user, multiplied by the log of the
      number of times. */
  static float W_USER = 1.5f;
  /** Weight of learned phrases. */
  static final float USER_PHRASE_LOGW = 11.f;
  static final int MAX_VISITS = 30000;
  static final int MAX_SYLLABLES = 32;
  static final int MAX_FULL = 20;
  static final int MAX_CANDIDATES = 80;

  final PinyinDict _dict;
  /** Learned phrases, might be [null]. */
  PinyinDict _user_dict = null;
  final float _log_total;
  /** Syllables starting with a given string. */
  final HashMap<String, int[]> _prefixes = new HashMap<String, int[]>();
  final HashSet<String> _initials = new HashSet<String>();

  /** User data. */
  final HashMap<String, Integer> _word_counts = new HashMap<String, Integer>();
  final ArrayList<char[]> _phrase_syls = new ArrayList<char[]>();
  final ArrayList<String> _phrase_words = new ArrayList<String>();
  final ArrayList<Integer> _phrase_counts = new ArrayList<Integer>();
  boolean _user_data_changed = false;

  public PinyinEngine(PinyinDict dict)
  {
    _dict = dict;
    double total = 0;
    for (int i = 0; i < dict.size(); i++)
      total += Math.exp(dict.log_weight(i));
    _log_total = (float)Math.log(total);
    HashMap<String, ArrayList<Integer>> p = new HashMap<String, ArrayList<Integer>>();
    for (int id = 0; id < dict.syllables.length; id++)
    {
      String s = dict.syllables[id];
      for (int l = 1; l <= s.length(); l++)
      {
        String pre = s.substring(0, l);
        ArrayList<Integer> ids = p.get(pre);
        if (ids == null)
          p.put(pre, ids = new ArrayList<Integer>());
        ids.add(id);
      }
    }
    for (String pre : p.keySet())
    {
      ArrayList<Integer> ids = p.get(pre);
      int[] a = new int[ids.size()];
      for (int i = 0; i < a.length; i++)
        a[i] = ids.get(i);
      _prefixes.put(pre, a);
    }
    for (String i : INITIALS)
      _initials.add(i);
  }

  /** A piece of the input standing for one syllable. */
  static final class Edge
  {
    final int end; // Position after the piece and its separator
    final int[] ids;
    final float penalty;
    Edge(int e, int[] i, float p) { end = e; ids = i; penalty = p; }
  }

  /** [input] is made of lowercase letters and ['] separators. */
  public List<Candidate> candidates(String input)
  {
    int n = input.length();
    ArrayList<Candidate> result = new ArrayList<Candidate>();
    if (n == 0)
      return result;
    ArrayList<Edge>[] edges = edges(input);
    HashMap<String, Candidate> full = new HashMap<String, Candidate>();
    HashMap<String, Candidate> partial = new HashMap<String, Candidate>();
    Collector c = new Collector(n, full, partial);
    search(_dict, edges, 0, c);
    if (_user_dict != null)
      search(_user_dict, edges, 0, c);
    Candidate s = sentence(edges, n);
    if (s != null)
      put_best(full, s);
    ArrayList<Candidate> fl = new ArrayList<Candidate>(full.values());
    Collections.sort(fl, BY_SCORE);
    ArrayList<Candidate> pl = new ArrayList<Candidate>(partial.values());
    Collections.sort(pl, BY_CONSUMED_THEN_SCORE);
    HashSet<String> seen = new HashSet<String>();
    for (int i = 0; i < fl.size() && i < MAX_FULL; i++)
      if (seen.add(fl.get(i).word))
        result.add(fl.get(i));
    for (int i = 0; i < pl.size() && result.size() < MAX_CANDIDATES; i++)
      if (seen.add(pl.get(i).word))
        result.add(pl.get(i));
    return result;
  }

  ArrayList<Edge>[] edges(String input)
  {
    int n = input.length();
    @SuppressWarnings("unchecked")
    ArrayList<Edge>[] edges = new ArrayList[n + 1];
    for (int p = 0; p <= n; p++)
      edges[p] = new ArrayList<Edge>();
    for (int p = 0; p < n; p++)
    {
      if (input.charAt(p) == '\'')
      {
        // A separator at the start or after another separator, skip it.
        edges[p].add(new Edge(p + 1, null, 0.f));
        continue;
      }
      for (int l = 1; l <= 6 && p + l <= n; l++)
      {
        if (input.charAt(p + l - 1) == '\'')
          break;
        String s = input.substring(p, p + l);
        int[] ids = _prefixes.get(s);
        if (ids == null)
          break; // Longer pieces won't match either
        int end = p + l;
        if (end < n && input.charAt(end) == '\'')
          end++;
        int complete = _dict.syllable_id(s);
        if (complete >= 0)
          edges[p].add(new Edge(end, new int[]{ complete }, 0.f));
        int[] others = without(ids, complete);
        if (others.length == 0)
          continue;
        if (end == n)
          edges[p].add(new Edge(end, others,
                (complete >= 0) ? P_EXTEND : P_INCOMPLETE));
        // An initial is an abbreviation only if the next letter doesn't
        // continue the syllable: "nh" but not the "h" of "nihao".
        else if (_initials.contains(s) && (input.charAt(p + l) == '\''
              || !_prefixes.containsKey(s + input.charAt(p + l))))
          edges[p].add(new Edge(end, others, P_ABBREVIATION));
      }
    }
    return edges;
  }

  static int[] without(int[] ids, int x)
  {
    if (x < 0)
      return ids;
    int[] r = new int[ids.length - 1];
    int j = 0;
    for (int id : ids)
      if (id != x && j < r.length)
        r[j++] = id;
    return r;
  }

  /** Receive the words found by [search]. */
  interface Found
  {
    void found(PinyinDict d, int entry, int end, float penalty, char[] path,
        int depth);
  }

  int _visits;

  void search(PinyinDict d, ArrayList<Edge>[] edges, int start, Found out)
  {
    _visits = 0;
    dfs(d, edges, start, 0, d.size(), 0, 0.f, out, new char[MAX_SYLLABLES]);
  }

  void dfs(PinyinDict d, ArrayList<Edge>[] edges, int pos, int lo, int hi,
      int depth, float penalty, Found out, char[] path)
  {
    if (++_visits > MAX_VISITS)
      return;
    // Entries that have exactly [depth] syllables come first in the range.
    if (depth > 0)
      for (int i = lo; i < hi && d.length(i) == depth; i++)
        out.found(d, i, pos, penalty, path, depth);
    if (pos >= edges.length - 1 || depth >= path.length)
      return;
    for (Edge e : edges[pos])
    {
      if (e.ids == null)
      {
        dfs(d, edges, e.end, lo, hi, depth, penalty, out, path);
        continue;
      }
      for (int id : e.ids)
      {
        long r = d.child_range(lo, hi, depth, id);
        int clo = (int)(r >>> 32), chi = (int)r;
        if (clo >= chi)
          continue;
        path[depth] = (char)id;
        dfs(d, edges, e.end, clo, chi, depth + 1, penalty + e.penalty, out,
            path);
      }
    }
  }

  float word_score(PinyinDict d, int entry, float penalty)
  {
    float s = d.log_weight(entry) - _log_total + penalty;
    Integer count = _word_counts.get(d.word(entry));
    if (count != null)
      s += W_USER * (float)Math.log(1 + count);
    return s;
  }

  final class Collector implements Found
  {
    final int n;
    final HashMap<String, Candidate> full;
    final HashMap<String, Candidate> partial;

    Collector(int n_, HashMap<String, Candidate> f, HashMap<String, Candidate> p)
    { n = n_; full = f; partial = p; }

    public void found(PinyinDict d, int entry, int end, float penalty,
        char[] path, int depth)
    {
      Candidate c = new Candidate(d.word(entry), end,
          word_score(d, entry, penalty), copy(path, depth));
      put_best((end == n) ? full : partial, c);
    }
  }

  static void put_best(HashMap<String, Candidate> m, Candidate c)
  {
    Candidate prev = m.get(c.word);
    if (prev == null || prev.consumed < c.consumed
        || (prev.consumed == c.consumed && prev.score < c.score))
      m.put(c.word, c);
  }

  static char[] copy(char[] a, int len)
  {
    char[] r = new char[len];
    System.arraycopy(a, 0, r, 0, len);
    return r;
  }

  /** The most likely sequence of at least two words covering the whole
      input, or [null]. */
  Candidate sentence(ArrayList<Edge>[] edges, int n)
  {
    final float[] best = new float[n + 1];
    final Candidate[] back = new Candidate[n + 1];
    final int[] back_pos = new int[n + 1];
    java.util.Arrays.fill(best, Float.NEGATIVE_INFINITY);
    best[0] = 0.f;
    for (int p = 0; p < n; p++)
    {
      if (best[p] == Float.NEGATIVE_INFINITY)
        continue;
      // Best word for each end position, starting at [p].
      final Candidate[] words = new Candidate[n + 1];
      Found f = new Found() {
        public void found(PinyinDict d, int entry, int end, float penalty,
            char[] path, int depth)
        {
          float s = word_score(d, entry, penalty);
          if (words[end] == null || words[end].score < s)
            words[end] = new Candidate(d.word(entry), end, s, copy(path, depth));
        }
      };
      search(_dict, edges, p, f);
      if (_user_dict != null)
        search(_user_dict, edges, p, f);
      for (int e = p + 1; e <= n; e++)
      {
        if (words[e] == null)
          continue;
        float s = best[p] + words[e].score;
        if (s > best[e])
        {
          best[e] = s;
          back[e] = words[e];
          back_pos[e] = p;
        }
      }
    }
    if (back[n] == null || back_pos[n] == 0)
      return null; // No sentence or a single word
    ArrayList<Candidate> ws = new ArrayList<Candidate>();
    for (int e = n; e > 0; e = back_pos[e])
      ws.add(0, back[e]);
    StringBuilder w = new StringBuilder();
    int len = 0;
    for (Candidate c : ws)
    {
      w.append(c.word);
      len += c.syllables.length;
    }
    char[] syls = new char[len];
    int i = 0;
    for (Candidate c : ws)
      for (char s : c.syllables)
        syls[i++] = s;
    return new Candidate(w.toString(), n, best[n], syls);
  }

  static final Comparator<Candidate> BY_SCORE = new Comparator<Candidate>() {
    public int compare(Candidate a, Candidate b)
    { return Float.compare(b.score, a.score); }
  };

  static final Comparator<Candidate> BY_CONSUMED_THEN_SCORE =
    new Comparator<Candidate>() {
      public int compare(Candidate a, Candidate b)
      {
        if (a.consumed != b.consumed)
          return b.consumed - a.consumed;
        return Float.compare(b.score, a.score);
      }
    };

  /** User data */

  /** The user chose [c]. */
  public void learn_word(Candidate c)
  {
    Integer n = _word_counts.get(c.word);
    _word_counts.put(c.word, (n == null) ? 1 : n + 1);
    _user_data_changed = true;
  }

  /** The user typed [word], made of several candidates. Remember it as a
      phrase if it's not already a word. */
  public void learn_phrase(String word, char[] syllables)
  {
    if (word.length() < 2 || word.length() > 8 || syllables.length > 8
        || contains(_dict, word, syllables))
      return;
    for (int i = 0; i < _phrase_words.size(); i++)
      if (_phrase_words.get(i).equals(word))
      {
        _phrase_counts.set(i, _phrase_counts.get(i) + 1);
        rebuild_user_dict();
        return;
      }
    _phrase_words.add(word);
    _phrase_syls.add(syllables);
    _phrase_counts.add(1);
    rebuild_user_dict();
  }

  static boolean contains(PinyinDict d, String word, char[] syls)
  {
    int lo = 0, hi = d.size();
    for (int depth = 0; depth < syls.length && lo < hi; depth++)
    {
      long r = d.child_range(lo, hi, depth, syls[depth]);
      lo = (int)(r >>> 32);
      hi = (int)r;
    }
    for (int i = lo; i < hi && d.length(i) == syls.length; i++)
      if (d.word(i).equals(word))
        return true;
    return false;
  }

  void rebuild_user_dict()
  {
    ArrayList<Float> w = new ArrayList<Float>();
    for (int c : _phrase_counts)
      w.add(USER_PHRASE_LOGW + (float)Math.log(c));
    _user_dict = _phrase_words.isEmpty() ? null :
      PinyinDict.of_entries(_dict.syllables, _phrase_syls, _phrase_words, w);
    _user_data_changed = true;
  }

  public boolean user_data_changed() { return _user_data_changed; }

  /** Lines "w<TAB>word<TAB>count" and
      "p<TAB>word<TAB>syllable syllable...<TAB>count". */
  public String serialize_user_data()
  {
    StringBuilder b = new StringBuilder();
    for (String w : _word_counts.keySet())
      b.append("w\t").append(w).append('\t').append(_word_counts.get(w))
        .append('\n');
    for (int i = 0; i < _phrase_words.size(); i++)
    {
      b.append("p\t").append(_phrase_words.get(i)).append('\t');
      char[] s = _phrase_syls.get(i);
      for (int j = 0; j < s.length; j++)
      {
        if (j > 0)
          b.append(' ');
        b.append(_dict.syllables[s[j]]);
      }
      b.append('\t').append(_phrase_counts.get(i)).append('\n');
    }
    _user_data_changed = false;
    return b.toString();
  }

  public void load_user_data(String data)
  {
    if (data == null)
      return;
    for (String line : data.split("\n"))
    {
      String[] f = line.split("\t");
      try
      {
        if (f.length == 3 && f[0].equals("w"))
          _word_counts.put(f[1], Integer.parseInt(f[2]));
        else if (f.length == 4 && f[0].equals("p"))
        {
          String[] ss = f[2].split(" ");
          char[] syls = new char[ss.length];
          boolean ok = true;
          for (int i = 0; i < ss.length; i++)
          {
            int id = _dict.syllable_id(ss[i]);
            if (id < 0) ok = false;
            else syls[i] = (char)id;
          }
          if (!ok)
            continue;
          _phrase_words.add(f[1]);
          _phrase_syls.add(syls);
          _phrase_counts.add(Integer.parseInt(f[3]));
        }
      }
      catch (NumberFormatException e) {}
    }
    rebuild_user_dict();
    _user_data_changed = false;
  }

  /** Concatenate the syllables of several candidates. */
  public static char[] concat_syllables(List<Candidate> cs)
  {
    int len = 0;
    for (Candidate c : cs)
      len += c.syllables.length;
    char[] r = new char[len];
    int i = 0;
    for (Candidate c : cs)
      for (char s : c.syllables)
        r[i++] = s;
    return r;
  }
}
