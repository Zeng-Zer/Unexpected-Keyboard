package juloo.keyboard2.typing;

/** Recognize the word drawn by a glide gesture.

    Candidate words are the words whose first and last letters are near the
    beginning and the end of the gesture. Each candidate is compared to the
    gesture with two measures:
    - Alignment: the gesture must pass near every letter of the word, in order.
    - Location: the gesture and the polyline joining the centers of the keys
      of the word, both resampled to [N] points, must be close to each other
      point by point. This penalizes words that skip a turn of the gesture.
    These are combined with the frequency of the word.

    Coordinates are converted to "key units", where a key is a 1x1 square. */
public final class GlideDecoder
{
  public static final class Result
  {
    public final String[] words;
    public final float[] scores;
    Result(String[] w, float[] s) { words = w; scores = s; }
  }

  /** Number of points the gesture and templates are resampled to. */
  static final int N = 48;
  /** Max distance between the gesture's ends and the keys of the first and
      last letters. */
  static float END_RADIUS = 1.1f;
  /** Weight of the alignment and location costs and of the frequency. */
  static float W_ALIGN = 2.2f;
  static float W_LOC = 1.6f;
  static float W_FREQ = 0.7f;
  /** Penalty for capitalized words and words with an apostrophe, they are
      usually less likely than the plain word with the same glide form. */
  static float CAPITALIZED_PENALTY = 0.6f;
  static float APOSTROPHE_PENALTY = 0.6f;
  /** Candidates whose partial alignment cost exceed this are dropped. */
  static float PRUNE_LETTER_DIST2 = 1.8f;

  final KeyCenters _keys;
  final Lexicon _lex;
  /** Key centers in key units. */
  final float[] _kx;
  final float[] _ky;

  public GlideDecoder(KeyCenters keys, Lexicon lex)
  {
    _keys = keys;
    _lex = lex;
    int n = keys.size();
    _kx = new float[n];
    _ky = new float[n];
    for (int i = 0; i < n; i++)
    {
      _kx[i] = keys.x[i] / keys.key_w;
      _ky[i] = keys.y[i] / keys.key_h;
    }
  }

  public boolean uses(KeyCenters keys, Lexicon lex)
  {
    return _keys == keys && _lex == lex;
  }

  // Scratch buffers, the decoder is not reentrant.
  final float[] _px = new float[N];
  final float[] _py = new float[N];
  final float[] _tx = new float[N];
  final float[] _ty = new float[N];
  final int[] _tmpl = new int[64];
  final float[] _tmpl_x = new float[64];
  final float[] _tmpl_y = new float[64];
  final float[] _dp = new float[N];
  final float[] _dp_next = new float[N];

  /** [xs] and [ys] are the [n] points of the gesture in pixels. Returns up to
      [count] words, best first. */
  public Result decode(float[] xs, float[] ys, int n, int count)
  {
    if (n < 2)
      return null;
    float[] gx = new float[n];
    float[] gy = new float[n];
    for (int i = 0; i < n; i++)
    {
      gx[i] = xs[i] / _keys.key_w;
      gy[i] = ys[i] / _keys.key_h;
    }
    if (resample(gx, gy, n, _px, _py) < 0.5f)
      return null; // Too short to be a glide.
    boolean[] end_keys = keys_near(_px[N - 1], _py[N - 1]);
    String[] best_w = new String[count];
    int[] best_i = new int[count];
    float[] best_s = new float[count];
    int found = 0;
    boolean[] start_keys = keys_near(_px[0], _py[0]);
    for (int k = 0; k < start_keys.length; k++)
    {
      if (!start_keys[k])
        continue;
      int[] group = _lex.starting_with(_keys.chars[k]);
      if (group == null)
        continue;
      for (int wi : group)
      {
        int m = template_of_word(wi, end_keys);
        if (m < 2)
          continue;
        float s = score(m, _lex.freq(wi));
        if (Float.isNaN(s))
          continue;
        int flags = _lex.flags(wi);
        if ((flags & Lexicon.FLAG_CAPITALIZED) != 0)
          s -= CAPITALIZED_PENALTY;
        if ((flags & Lexicon.FLAG_APOSTROPHE) != 0)
          s -= APOSTROPHE_PENALTY;
        // Insert into the sorted top [count]
        if (found == count && s <= best_s[count - 1])
          continue;
        int j = (found < count) ? found++ : count - 1;
        while (j > 0 && best_s[j - 1] < s)
        {
          best_s[j] = best_s[j - 1];
          best_i[j] = best_i[j - 1];
          j--;
        }
        best_s[j] = s;
        best_i[j] = wi;
      }
    }
    // Several words can have the same glide form ("its" and "it's"), keep
    // them all but remove case duplicates.
    String[] words = new String[found];
    float[] scores = new float[found];
    int r = 0;
    for (int i = 0; i < found; i++)
    {
      String w = _lex.word(best_i[i]);
      boolean dup = false;
      for (int j = 0; j < r; j++)
        if (words[j].equalsIgnoreCase(w))
          dup = true;
      if (dup)
        continue;
      words[r] = w;
      scores[r] = best_s[i];
      r++;
    }
    return new Result(java.util.Arrays.copyOf(words, r),
        java.util.Arrays.copyOf(scores, r));
  }

  /** Keys whose center is within [END_RADIUS] of the point. The nearest key
      is always included. */
  boolean[] keys_near(float x, float y)
  {
    int n = _kx.length;
    boolean[] r = new boolean[n];
    int nearest = -1;
    float nearest_d = Float.MAX_VALUE;
    for (int i = 0; i < n; i++)
    {
      float d = dist2(x, y, _kx[i], _ky[i]);
      if (d < END_RADIUS * END_RADIUS)
        r[i] = true;
      if (d < nearest_d)
      {
        nearest_d = d;
        nearest = i;
      }
    }
    if (nearest >= 0)
      r[nearest] = true;
    return r;
  }

  /** Write the key sequence of word [wi] into [_tmpl], removing repeated
      keys. Returns its length or [-1] if the word can't be typed on the
      layout or doesn't end on one of [end_keys]. */
  int template_of_word(int wi, boolean[] end_keys)
  {
    int start = _lex._offsets[wi];
    int end = _lex._offsets[wi + 1];
    int last = _keys.index_of(_lex._chars[end - 1]);
    if (last < 0 || !end_keys[last] || end - start > _tmpl.length)
      return -1;
    int m = 0;
    int prev = -1;
    for (int i = start; i < end; i++)
    {
      int k = _keys.index_of(_lex._chars[i]);
      if (k < 0)
        return -1;
      if (k != prev)
        _tmpl[m++] = k;
      prev = k;
    }
    return m;
  }

  /** Score of the template [_tmpl[0..m]] against the gesture. Higher is
      better. Returns NaN if the word is not a candidate. */
  float score(int m, int freq)
  {
    // Alignment: dynamic programming over (letter, gesture point) with the
    // first letter at the first point and the last letter at the last point.
    // [_dp[j]] is the best cost for letters [0..i] with letter [i] at point
    // [j]. Letters can't go back in the gesture.
    float[] dp = _dp;
    float[] next = _dp_next;
    int k0 = _tmpl[0];
    float inf = Float.MAX_VALUE / 4;
    for (int j = 0; j < N; j++)
      dp[j] = inf;
    dp[0] = dist2(_px[0], _py[0], _kx[k0], _ky[k0]);
    for (int i = 1; i < m; i++)
    {
      int k = _tmpl[i];
      float kx = _kx[k], ky = _ky[k];
      float run_min = inf;
      float row_min = inf;
      for (int j = 0; j < N; j++)
      {
        // Two letters can be matched to the same point (sharp turns).
        if (dp[j] < run_min)
          run_min = dp[j];
        float c = run_min + dist2(_px[j], _py[j], kx, ky);
        next[j] = c;
        if (c < row_min)
          row_min = c;
      }
      if (row_min > PRUNE_LETTER_DIST2 * (i + 1))
        return Float.NaN;
      float[] t = dp; dp = next; next = t;
    }
    float align = dp[N - 1] / m;
    // Location
    float tlen = resample_template(m);
    float loc = 0.f;
    for (int j = 0; j < N; j++)
      loc += dist2(_px[j], _py[j], _tx[j], _ty[j]);
    loc /= N;
    return -(W_ALIGN * align + W_LOC * loc) + W_FREQ * freq;
  }

  float resample_template(int m)
  {
    float[] x = _tmpl_x;
    float[] y = _tmpl_y;
    for (int i = 0; i < m; i++)
    {
      x[i] = _kx[_tmpl[i]];
      y[i] = _ky[_tmpl[i]];
    }
    return resample(x, y, m, _tx, _ty);
  }

  /** Resample the polyline to [N] points at equal distances along the path.
      Returns its length. */
  static float resample(float[] x, float[] y, int n, float[] ox, float[] oy)
  {
    float total = 0.f;
    for (int i = 1; i < n; i++)
      total += (float)Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
    if (total <= 0.f)
    {
      for (int j = 0; j < N; j++) { ox[j] = x[0]; oy[j] = y[0]; }
      return 0.f;
    }
    float step = total / (N - 1);
    ox[0] = x[0]; oy[0] = y[0];
    int j = 1;
    float acc = 0.f; // Distance travelled up to point [i - 1]
    for (int i = 1; i < n && j < N; i++)
    {
      float seg = (float)Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
      while (j < N && acc + seg >= j * step)
      {
        float t = (seg == 0.f) ? 0.f : (j * step - acc) / seg;
        ox[j] = x[i - 1] + (x[i] - x[i - 1]) * t;
        oy[j] = y[i - 1] + (y[i] - y[i - 1]) * t;
        j++;
      }
      acc += seg;
    }
    for (; j < N; j++) { ox[j] = x[n - 1]; oy[j] = y[n - 1]; }
    return total;
  }

  static float dist2(float ax, float ay, float bx, float by)
  {
    float dx = ax - bx, dy = ay - by;
    return dx * dx + dy * dy;
  }
}
