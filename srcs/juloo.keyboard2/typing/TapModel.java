package juloo.keyboard2.typing;

import java.util.Locale;

/** Decide which letter key a tap was meant for.

    Each letter key is modeled as a Gaussian centered on the key, shifted by the
    offset learned from the user's taps. The likelihood of the touch is
    combined with how likely each letter is to come next in the word being
    typed, according to the dictionary. A touch near the edge of two keys goes
    to the letter that makes sense, a touch near the center of a key stays on
    that key. Only letter keys are considered.

    Distances are in key units: 1 is the width or height of the key. */
public final class TapModel
{
  /** Spread of the touches around the intended key center. */
  static float SIGMA = 0.36f;
  /** Weight of the language model. */
  static float W_LM = 0.7f;
  /** Bonus of the key under the finger, flips need clear evidence. */
  static float GEOMETRIC_BONUS = 0.3f;
  /** Only the taps that were not corrected are learned. These are biased
      toward the center of the key, which makes the average underestimate the
      user's offset. */
  static float OFFSET_GAIN = 1.6f;
  /** Maximum learned offset. */
  static final float MAX_OFFSET = 0.4f;
  /** Number of samples after which the per-key offset is fully trusted. */
  static final float PRIOR_SAMPLES = 15.f;
  /** Running averages forget older samples beyond this count, so the model
      adapts when the way the phone is held changes. */
  static final int MAX_SAMPLES = 150;

  public interface LetterModel
  {
    /** Score of typing the letter [c] after [prefix], in log scale. */
    float log_score(String prefix, char c);
  }

  /** Learned offsets, indexed by lowercase letter. */
  final float[] _ox = new float[128];
  final float[] _oy = new float[128];
  final int[] _n = new int[128];
  float _gx = 0.f, _gy = 0.f;
  int _gn = 0;
  /** Number of samples learned since the last [serialize]. */
  int _unsaved = 0;

  public TapModel() {}

  /** Index of the key the tap at [x, y] (pixels) is meant for. [geometric] is
      the letter key under the finger. [lm] might be [null] to only use the
      learned offsets. */
  public int predict(KeyCenters kc, float x, float y, int geometric,
      LetterModel lm, String prefix)
  {
    int best = geometric;
    float best_s = Float.NEGATIVE_INFINITY;
    for (int k = 0; k < kc.size(); k++)
    {
      float dx = (x - kc.x[k]) / kc.w[k];
      float dy = (y - kc.y[k]) / kc.h[k];
      // Only direct neighbors of the touched point are candidates.
      if (k != geometric && (Math.abs(dx) > 1.1f || Math.abs(dy) > 1.1f))
        continue;
      char c = kc.chars[k];
      dx -= offset_x(c);
      dy -= offset_y(c);
      float s = -(dx * dx + dy * dy) / (2 * SIGMA * SIGMA);
      if (k == geometric)
        s += GEOMETRIC_BONUS;
      if (lm != null)
        s += W_LM * lm.log_score(prefix, c);
      if (s > best_s)
      {
        best_s = s;
        best = k;
      }
    }
    return best;
  }

  /** Record that the tap at [x, y] was meant for key [k]. */
  public void learn(KeyCenters kc, int k, float x, float y)
  {
    char c = kc.chars[k];
    if (c >= 128)
      return;
    float dx = clamp((x - kc.x[k]) / kc.w[k]);
    float dy = clamp((y - kc.y[k]) / kc.h[k]);
    _gn = Math.min(_gn + 1, MAX_SAMPLES);
    _gx += (dx - _gx) / _gn;
    _gy += (dy - _gy) / _gn;
    int n = _n[c] = Math.min(_n[c] + 1, MAX_SAMPLES);
    _ox[c] += (dx - _ox[c]) / n;
    _oy[c] += (dy - _oy[c]) / n;
    _unsaved++;
  }

  /** Per-key offset, pulled toward the global offset while the key has few
      samples. The global offset is itself pulled toward 0. */
  float offset_x(char c)
  {
    if (c >= 128) return 0.f;
    float g = _gx * _gn / (_gn + PRIOR_SAMPLES);
    return clamp(OFFSET_GAIN * (_ox[c] * _n[c] + g * PRIOR_SAMPLES) / (_n[c] + PRIOR_SAMPLES));
  }

  float offset_y(char c)
  {
    if (c >= 128) return 0.f;
    float g = _gy * _gn / (_gn + PRIOR_SAMPLES);
    return clamp(OFFSET_GAIN * (_oy[c] * _n[c] + g * PRIOR_SAMPLES) / (_n[c] + PRIOR_SAMPLES));
  }

  public int unsaved_samples() { return _unsaved; }

  public void reset()
  {
    for (int c = 0; c < 128; c++)
    {
      _ox[c] = 0.f; _oy[c] = 0.f; _n[c] = 0;
    }
    _gx = 0.f; _gy = 0.f; _gn = 0;
    _unsaved = 0;
  }

  /** Text form for storing in the preferences:
      "*:gx,gy,gn;a:ox,oy,n;b:...", where "*" is the global offset. */
  public String serialize()
  {
    StringBuilder b = new StringBuilder();
    b.append(String.format(Locale.ROOT, "*:%.4f,%.4f,%d", _gx, _gy, _gn));
    for (int c = 0; c < 128; c++)
      if (_n[c] > 0)
        b.append(String.format(Locale.ROOT, ";%c:%.4f,%.4f,%d", (char)c,
              _ox[c], _oy[c], _n[c]));
    _unsaved = 0;
    return b.toString();
  }

  public static TapModel deserialize(String s)
  {
    TapModel m = new TapModel();
    if (s == null)
      return m;
    try
    {
      for (String entry : s.split(";"))
      {
        int colon = entry.lastIndexOf(':');
        if (colon != 1)
          continue;
        String[] v = entry.substring(2).split(",");
        float ox = clamp(Float.parseFloat(v[0]));
        float oy = clamp(Float.parseFloat(v[1]));
        int n = Math.max(0, Math.min(MAX_SAMPLES, Integer.parseInt(v[2])));
        char c = entry.charAt(0);
        if (c == '*')
        {
          m._gx = ox; m._gy = oy; m._gn = n;
        }
        else if (c < 128)
        {
          m._ox[c] = ox; m._oy[c] = oy; m._n[c] = n;
        }
      }
    }
    catch (RuntimeException e)
    {
      return new TapModel(); // Corrupted, start over.
    }
    return m;
  }

  static float clamp(float v)
  {
    return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, v));
  }
}
