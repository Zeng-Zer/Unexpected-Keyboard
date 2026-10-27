package juloo.keyboard2.typing;

import java.util.Arrays;

/** Position of the letter keys of the current layout, in pixels. Shared by
    [GlideDecoder] and [TapModel]. */
public final class KeyCenters
{
  /** Lowercase letter typed by each key. */
  public final char[] chars;
  public final float[] x;
  public final float[] y;
  public final float[] w;
  public final float[] h;
  /** Size of a typical key. Distances are expressed relative to it. */
  public final float key_w;
  public final float key_h;
  /** Key index of a lowercase letter, [-1] for letters not on the layout. */
  final int[] _index_of_char;

  public KeyCenters(char[] c, float[] x_, float[] y_, float[] w_, float[] h_)
  {
    chars = c; x = x_; y = y_; w = w_; h = h_;
    key_w = median(w_);
    key_h = median(h_);
    _index_of_char = new int[0x250]; // Latin scripts
    Arrays.fill(_index_of_char, -1);
    for (int i = 0; i < c.length; i++)
      if (c[i] < _index_of_char.length && _index_of_char[c[i]] == -1)
        _index_of_char[c[i]] = i;
  }

  public int size() { return chars.length; }

  public int index_of(char c)
  {
    return (c < _index_of_char.length) ? _index_of_char[c] : -1;
  }

  static float median(float[] a)
  {
    if (a.length == 0)
      return 1.f;
    float[] s = a.clone();
    Arrays.sort(s);
    return s[s.length / 2];
  }
}
