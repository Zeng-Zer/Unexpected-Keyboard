package juloo.keyboard2.typing;

import java.util.Arrays;
import juloo.cdict.Cdict;

/** How likely a letter is to come next in a word, from the dictionaries:
    the total frequency of the most frequent words starting with [prefix + c].
    With several dictionaries, a letter is likely if it is in any language.
    Letters that make the prefix of no word get the lowest score, but are not
    forbidden as the user might be typing a word that is not in the
    dictionary. */
public final class DictLetterModel implements TapModel.LetterModel
{
  /** Frequencies in the dictionary are in a logarithmic scale from 0 to 15. */
  static float BETA = 0.5f;
  static final int SUFFIXES = 8;

  final Cdict[] _dicts;

  public DictLetterModel(Cdict[] dicts)
  {
    _dicts = dicts;
  }

  public boolean uses(Cdict[] dicts) { return Arrays.equals(_dicts, dicts); }

  @Override
  public float log_score(String prefix, char c)
  {
    String q = prefix + c;
    double mass = 1.0;
    for (Cdict dict : _dicts)
    {
      Cdict.Result r = dict.find(q);
      for (int i : dict.suffixes(r, SUFFIXES))
        mass += Math.exp(BETA * dict.freq(i));
    }
    return (float)Math.log(mass);
  }
}
