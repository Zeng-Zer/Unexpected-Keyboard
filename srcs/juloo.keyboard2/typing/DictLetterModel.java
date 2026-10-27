package juloo.keyboard2.typing;

import juloo.cdict.Cdict;

/** How likely a letter is to come next in a word, from the dictionary: the
    total frequency of the most frequent words starting with [prefix + c].
    Letters that make the prefix of no word get the lowest score, but are not
    forbidden as the user might be typing a word that is not in the
    dictionary. */
public final class DictLetterModel implements TapModel.LetterModel
{
  /** Frequencies in the dictionary are in a logarithmic scale from 0 to 15. */
  static float BETA = 0.5f;
  static final int SUFFIXES = 8;

  final Cdict _dict;

  public DictLetterModel(Cdict dict)
  {
    _dict = dict;
  }

  public boolean uses(Cdict dict) { return _dict == dict; }

  @Override
  public float log_score(String prefix, char c)
  {
    Cdict.Result r = _dict.find(prefix + c);
    double mass = 1.0;
    for (int i : _dict.suffixes(r, SUFFIXES))
      mass += Math.exp(BETA * _dict.freq(i));
    return (float)Math.log(mass);
  }
}
