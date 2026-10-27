package juloo.keyboard2.typing;

import juloo.cdict.Cdict;

/** Guess which language is being typed when several dictionaries are active,
    from the last words before the cursor. Words that are in only one of the
    dictionaries vote for it, the most recent words count more. The result is
    a bonus for the words of each dictionary, in the unit of the
    dictionary frequencies (0 to 15). */
public final class LanguageContext
{
  /** Difference between the bonus of the language being typed and the other
      language, when the context is certain. A strong bonus makes switching
      language in the middle of a sentence harder. */
  static float MAX_SPREAD = 1.15f;
  static final int WORDS = 4;
  static final float[] RECENCY = { 1.f, 0.8f, 0.6f, 0.4f };
  /** Total votes needed for the bonus to be at its maximum. */
  static final float CONFIDENT_VOTES = 1.5f;

  /** Returns an element for each of [dicts]. All zeros if there's a single
      dictionary or no evidence. */
  public static float[] bonus(Cdict[] dicts, CharSequence text_before_cursor)
  {
    int n = dicts.length;
    float[] b = new float[n];
    if (n < 2 || text_before_cursor == null)
      return b;
    float[] votes = new float[n];
    float total = 0.f;
    String text = text_before_cursor.toString();
    int end = text.length();
    boolean[] found = new boolean[n];
    for (int w = 0; w < WORDS && end > 0;)
    {
      // Find the previous word.
      while (end > 0 && !is_word_char(text.charAt(end - 1)))
        end--;
      int start = end;
      while (start > 0 && is_word_char(text.charAt(start - 1)))
        start--;
      if (start == end)
        break;
      String word = text.substring(start, end);
      end = start;
      float recency = RECENCY[w++];
      int count = 0;
      for (int d = 0; d < n; d++)
      {
        found[d] = dicts[d].find(word).found
          || dicts[d].find(word.toLowerCase()).found;
        if (found[d])
          count++;
      }
      if (count == 0 || count == n)
        continue; // Not a vote but still counts as a word.
      for (int d = 0; d < n; d++)
        if (found[d])
          votes[d] += recency / count;
      total += recency;
    }
    if (total == 0.f)
      return b;
    float confidence = Math.min(1.f, total / CONFIDENT_VOTES);
    for (int d = 0; d < n; d++)
      b[d] = MAX_SPREAD * confidence * (votes[d] / total - 1.f / n);
    return b;
  }

  static boolean is_word_char(char c)
  {
    return Character.isLetter(c) || c == '\'' || c == '’';
  }
}
