package juloo.keyboard2.suggestions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.dict.Dictionaries;
import juloo.keyboard2.Config;
import juloo.keyboard2.ComposeKey;
import juloo.keyboard2.ComposeKeyData;

/** Keep track of the word being typed and provide suggestions for
    [CandidatesView]. */
public final class Suggestions
{
  Callback _callback;
  Config _config;
  boolean _enabled;

  /** Current suggestions. The best suggestion is at index [0]. */
  public String[] suggestions = new String[MAX_COUNT];
  /** Number of suggestions at the beginning of the [suggestions] array that
      are not [null]. */
  public int count = 0;
  public String emoji_suggestion = null;
  /** Frequency bonus for each of [_config.dictionaries], favoring the
      language being typed. Might be [null]. */
  public float[] dict_bonus = null;
  /** Number of suggestions in [suggestions]. */
  public static final int MAX_COUNT = 3;

  public Suggestions(Callback c, Config conf)
  {
    _callback = c;
    _config = conf;
  }

  public void started()
  {
    _enabled = _config.editor_config.should_show_candidates_view;
    clear();
  }

  public void currently_typed_word(String word)
  {
    if (!_enabled || _config.pinyin_mode)
      return;
    if (word.length() < 2 || _config.dictionaries.length == 0)
      clear();
    else
      query_suggestions(word);
    _callback.set_suggestions(this);
  }

  /** Show the candidates of a glide gesture. [words[0]] is the word that
      was entered. */
  public void glide_candidates(String[] words)
  {
    if (!_enabled)
      return;
    clear();
    count = Math.min(words.length, MAX_COUNT);
    for (int i = 0; i < count; i++)
      suggestions[i] = words[i];
    _callback.set_suggestions(this);
  }

  void clear()
  {
    count = 0;
    for (int i = 0; i < MAX_COUNT; i++)
      suggestions[i] = null;
    emoji_suggestion = null;
  }

  int query_suggestions(String word)
  {
    boolean first_char_upper = Character.isUpperCase(word.charAt(0));
    word = apply_substitutions(word);
    // With several dictionaries, the results of each kind are merged and
    // sorted by frequency.
    ArrayList<Candidate> exact = new ArrayList<Candidate>();
    ArrayList<Candidate> suffixes = new ArrayList<Candidate>();
    ArrayList<Candidate> dist = new ArrayList<Candidate>();
    Cdict[] dicts = _config.dictionaries;
    for (int di = 0; di < dicts.length; di++)
    {
      Cdict dict = dicts[di];
      float bonus = (dict_bonus != null && dict_bonus.length == dicts.length)
        ? dict_bonus[di] : 0.f;
      Cdict.Result r = dict.find(word);
      if (r.found)
        exact.add(new Candidate(dict, r.index, bonus));
      for (int s : dict.suffixes(r, MAX_COUNT))
        suffixes.add(new Candidate(dict, s, bonus));
      // Disable distance search for small words
      if (word.length() >= 3 && !(r.found && MAX_COUNT <= 2))
        for (int d : dict.distance(word, 1, MAX_COUNT))
          dist.add(new Candidate(dict, d, bonus));
    }
    Collections.sort(exact);
    Collections.sort(suffixes);
    Collections.sort(dist);
    int i = 0;
    for (Candidate c : exact)
      i = add_suggestion(c.word(), i);
    for (int j = 0; j < MAX_COUNT * 2 && i < MAX_COUNT; j++)
    {
      if (suffixes.size() > j)
        i = add_suggestion(suffixes.get(j).word(), i);
      if (dist.size() > j)
        i = add_suggestion(dist.get(j).word(), i);
    }
    if (first_char_upper)
      capitalize_results(suggestions, i);
    emoji_suggestion = query_emoji(word); // word with substitutions applied
    count = i;
    return i;
  }

  /** Add [w] at position [i] unless it's already suggested or there's no
      more room. Returns the next position. */
  int add_suggestion(String w, int i)
  {
    if (i >= MAX_COUNT)
      return i;
    for (int j = 0; j < i; j++)
      if (suggestions[j].equals(w))
        return i;
    suggestions[i] = w;
    return i + 1;
  }

  /** A word in one of the dictionaries, most frequent first. */
  static final class Candidate implements Comparable<Candidate>
  {
    final Cdict dict;
    final int index;
    final float score;

    Candidate(Cdict d, int i, float bonus)
    { dict = d; index = i; score = d.freq(i) + bonus; }

    String word() { return dict.word(index); }

    public int compareTo(Candidate o) { return Float.compare(o.score, score); }
  }

  static void capitalize_results(String[] s, int count)
  {
    for (int i = 0; i < count; i++)
      s[i] = s[i].substring(0, 1).toUpperCase() + s[i].substring(1);
  }

  String query_emoji(String word)
  {
    Cdict dict = _config.emoji_dictionary;
    // Disable emoji suggestion for short words
    if (dict == null || word.length() < 3)
      return null;
    Cdict.Result r = dict.find(word);
    if (r.found)
      return dict.word(r.index);
    int[] s = dict.suffixes(r, 1);
    if (s.length > 0)
      return dict.word(s[0]);
    return null;
  }

  /** Apply the same substitutions that were used when building the
      dictionaries to find word aliases. This catches missing diacritics for
      example. */
  String apply_substitutions(String w)
  {
    StringBuilder b = new StringBuilder(w);
    int len = w.length();
    for (int i = 0; i < len; i++)
    {
      char r =
        ComposeKey.transform_char(ComposeKeyData.substitutions, b.charAt(i));
      if (r != 0) b.setCharAt(i, r);
    }
    return b.toString();
  }

  static final int[] NO_RESULTS = new int[0];

  public static interface Callback
  {
    public void set_suggestions(Suggestions suggestions);
  }
}
