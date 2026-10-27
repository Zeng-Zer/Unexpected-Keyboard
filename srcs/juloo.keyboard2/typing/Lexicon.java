package juloo.keyboard2.typing;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import juloo.cdict.Cdict;

/** Compact list of the words of a dictionary, as used by [GlideDecoder].
    Each word is stored in its "glide form": lowercase, without diacritics,
    apostrophes or hyphens. "Don't" becomes "dont". Words are grouped by their
    first letter and sorted by decreasing frequency. */
public final class Lexicon
{
  public final Cdict dict;

  /** Glide forms of all the words, concatenated. */
  final char[] _chars;
  /** Word [i] is [_chars[_offsets[i].._offsets[i+1]]]. */
  final int[] _offsets;
  final byte[] _freq;
  /** See [FLAG_*]. */
  final byte[] _flags;
  /** Index of the word in [dict]. */
  final int[] _dict_index;
  /** Word indexes grouped by the first letter of their glide form. */
  final HashMap<Character, int[]> _by_first_letter;

  public static final int FLAG_CAPITALIZED = 1;
  public static final int FLAG_APOSTROPHE = 2;

  static Cdict _async_dict = null;
  static Lexicon _async_result = null;

  /** Returns the lexicon of [dict] if it's ready. Otherwise, start building
      it in the background and return [null]. Only the lexicon of the last
      requested dictionary is kept. */
  public static synchronized Lexicon get_async(final Cdict dict)
  {
    if (dict == null)
      return null;
    if (dict == _async_dict)
      return _async_result; // [null] while building
    _async_dict = dict;
    _async_result = null;
    Thread t = new Thread() {
      public void run()
      {
        Lexicon lex;
        try { lex = of_dict(dict); }
        catch (Throwable e) { return; } // Out of memory, glide is disabled
        synchronized (Lexicon.class)
        {
          if (_async_dict == dict)
            _async_result = lex;
        }
      }
    };
    t.setPriority(Thread.MIN_PRIORITY);
    t.start();
    return null;
  }

  /** Expensive: enumerates the whole dictionary. Call from a background
      thread. */
  public static Lexicon of_dict(Cdict dict)
  {
    StringBuilder chars = new StringBuilder(1 << 21);
    ArrayList<int[]> entries = new ArrayList<int[]>(1 << 18);
    String prev = null;
    // Indexes are in alphabetical order. The end of the dictionary is reached
    // when the returned words stop increasing.
    for (int i = 0;; i++)
    {
      String w = dict.word(i);
      if (prev != null && w.compareTo(prev) <= 0)
        break;
      prev = w;
      String g = glide_form(w);
      if (g == null || g.length() < 2)
        continue;
      int flags = 0;
      if (Character.isUpperCase(w.charAt(0)))
        flags |= FLAG_CAPITALIZED;
      if (w.indexOf('\'') >= 0 || w.indexOf('\u2019') >= 0)
        flags |= FLAG_APOSTROPHE;
      entries.add(new int[]{ chars.length(), g.length(), dict.freq(i), i, flags });
      chars.append(g);
    }
    return new Lexicon(dict, chars, entries);
  }

  Lexicon(Cdict d, StringBuilder chars, ArrayList<int[]> entries)
  {
    dict = d;
    int n = entries.size();
    _chars = new char[chars.length()];
    chars.getChars(0, chars.length(), _chars, 0);
    _offsets = new int[n + 1];
    _freq = new byte[n];
    _dict_index = new int[n];
    _flags = new byte[n];
    // Sort by decreasing frequency, then group by first letter.
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++)
      order[i] = i;
    final ArrayList<int[]> e = entries;
    Arrays.sort(order, new Comparator<Integer>() {
      public int compare(Integer a, Integer b)
      { return e.get(b)[2] - e.get(a)[2]; }
    });
    // Rewrite [_chars] in sorted order so that words of a group are close in
    // memory.
    int off = 0;
    HashMap<Character, ArrayList<Integer>> groups =
      new HashMap<Character, ArrayList<Integer>>();
    for (int i = 0; i < n; i++)
    {
      int[] en = e.get(order[i]);
      _offsets[i] = off;
      chars.getChars(en[0], en[0] + en[1], _chars, off);
      off += en[1];
      _freq[i] = (byte)en[2];
      _dict_index[i] = en[3];
      _flags[i] = (byte)en[4];
      char first = _chars[_offsets[i]];
      ArrayList<Integer> g = groups.get(first);
      if (g == null)
      {
        g = new ArrayList<Integer>();
        groups.put(first, g);
      }
      g.add(i);
    }
    _offsets[n] = off;
    _by_first_letter = new HashMap<Character, int[]>();
    for (Character c : groups.keySet())
    {
      ArrayList<Integer> g = groups.get(c);
      int[] a = new int[g.size()];
      for (int i = 0; i < a.length; i++)
        a[i] = g.get(i);
      _by_first_letter.put(c, a);
    }
  }

  public int size() { return _freq.length; }

  /** The word as it appears in the dictionary, with case and diacritics. */
  public String word(int i) { return dict.word(_dict_index[i]); }

  public int freq(int i) { return _freq[i]; }

  public int flags(int i) { return _flags[i]; }

  /** Words whose glide form starts with [c], most frequent first. Might be
      [null]. */
  int[] starting_with(char c) { return _by_first_letter.get(c); }

  /** Returns [null] if the word can't be glided (digits, symbols). */
  static String glide_form(String w)
  {
    String n = Normalizer.normalize(w, Normalizer.Form.NFD);
    StringBuilder b = new StringBuilder(n.length());
    for (int i = 0; i < n.length(); i++)
    {
      char c = n.charAt(i);
      if (Character.isLetter(c))
        b.append(Character.toLowerCase(c));
      else if (c == '\'' || c == '-' || c == '’'
          || Character.getType(c) == Character.NON_SPACING_MARK)
        continue;
      else
        return null;
    }
    return b.toString();
  }
}
