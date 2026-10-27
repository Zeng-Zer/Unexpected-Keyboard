package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.pinyin.PinyinDict;
import juloo.keyboard2.pinyin.PinyinEngine;

/** Pinyin mode: letters are composed in the editor (underlined) and converted
    to Chinese characters chosen in the candidates view. Space enters the first
    candidate, enter types the letters as they are. Choosing a candidate that
    covers only the beginning of the pinyin leaves the rest to convert. */
public final class PinyinComposer
{
  public interface Callback
  {
    InputConnection getCurrentInputConnection();
    /** Commit text, replacing the composing text. */
    void send_text(String text);
    /** Show the candidates, an empty list hides them. */
    void set_pinyin_candidates(List<String> words);
  }

  final Config _config;
  final Callback _callback;
  final Handler _handler;

  /** Pinyin not converted yet. */
  final StringBuilder _letters = new StringBuilder();
  /** Characters chosen from the beginning of the pinyin. */
  final StringBuilder _selected = new StringBuilder();
  final ArrayList<PinyinEngine.Candidate> _selected_cands =
    new ArrayList<PinyinEngine.Candidate>();
  /** The pinyin of each selected candidate, to undo a selection. */
  final ArrayList<String> _selected_letters = new ArrayList<String>();
  List<PinyinEngine.Candidate> _candidates =
    Collections.<PinyinEngine.Candidate>emptyList();
  boolean _double_quote_open = false;
  boolean _single_quote_open = false;

  public PinyinComposer(Config conf, Callback cb, Handler h)
  {
    _config = conf;
    _callback = cb;
    _handler = h;
  }

  /** Whether pinyin input applies to the current editor. Not in terminals or
      password fields. */
  public boolean active()
  {
    return _config.pinyin_mode
      && _config.editor_config.should_show_candidates_view;
  }

  public boolean composing()
  {
    return _letters.length() > 0 || _selected.length() > 0;
  }

  /** Returns [true] if the key was handled. Otherwise, the key must be handled
      as usual, the composing text has been committed if needed. */
  public boolean key_up(KeyValue key, Pointers.Modifiers mods)
  {
    if (!active())
      return false;
    if (mods.has(KeyValue.Modifier.CTRL) || mods.has(KeyValue.Modifier.ALT)
        || mods.has(KeyValue.Modifier.META))
    {
      commit_raw();
      return false;
    }
    switch (key.getKind())
    {
      case Char:
        return on_char(key.getChar());
      case Modifier:
      case Compose_pending:
        return false;
      case Editing:
        switch (key.getEditing())
        {
          case BACKSPACE: return backspace();
          case SPACE_BAR:
            if (!composing())
              return false;
            commit_first();
            return true;
          default:
            commit_raw();
            return false;
        }
      case Keyevent:
        switch (key.getKeyevent())
        {
          case KeyEvent.KEYCODE_DEL: return backspace();
          case KeyEvent.KEYCODE_ENTER:
            if (!composing())
              return false;
            commit_raw();
            return true;
          default:
            commit_raw();
            return false;
        }
      default:
        commit_all();
        return false;
    }
  }

  boolean on_char(char c)
  {
    if (c >= 'A' && c <= 'Z')
      c = Character.toLowerCase(c);
    if (c >= 'a' && c <= 'z')
    {
      _letters.append(c);
      update();
      return true;
    }
    if (c == '\'' && _letters.length() > 0)
    {
      if (_letters.charAt(_letters.length() - 1) != '\'')
        _letters.append(c);
      update();
      return true;
    }
    if (composing())
      commit_all();
    String fw = _config.pinyin_punctuation ? fullwidth(c) : null;
    if (fw == null)
      return false;
    _callback.send_text(fw);
    return true;
  }

  /** Full-width punctuation or [null] if [c] is not converted. */
  String fullwidth(char c)
  {
    switch (c)
    {
      case ',': return after_digit() ? null : "，";
      case '.': return after_digit() ? null : "。";
      case ':': return after_digit() ? null : "：";
      case '?': return "？";
      case '!': return "！";
      case ';': return "；";
      case '(': return "（";
      case ')': return "）";
      case '[': return "【";
      case ']': return "】";
      case '<': return "《";
      case '>': return "》";
      case '\\': return "、";
      case '~': return "～";
      case '^': return "……";
      case '$': return "￥";
      case '"':
        _double_quote_open = !_double_quote_open;
        return _double_quote_open ? "“" : "”";
      case '\'':
        _single_quote_open = !_single_quote_open;
        return _single_quote_open ? "‘" : "’";
      default: return null;
    }
  }

  /** Whether the character before the cursor is a digit, to keep "3.5". */
  boolean after_digit()
  {
    InputConnection ic = _callback.getCurrentInputConnection();
    CharSequence t = (ic == null) ? null : ic.getTextBeforeCursor(1, 0);
    return t != null && t.length() > 0 && Character.isDigit(t.charAt(0));
  }

  boolean backspace()
  {
    if (_letters.length() > 0)
    {
      _letters.deleteCharAt(_letters.length() - 1);
      if (!composing())
        reset(true);
      else
        update();
      return true;
    }
    if (_selected.length() > 0)
    {
      // Undo the last selection, its pinyin can be converted again.
      int last = _selected_cands.size() - 1;
      PinyinEngine.Candidate c = _selected_cands.remove(last);
      _selected.setLength(_selected.length() - c.word.length());
      _letters.insert(0, _selected_letters.remove(last));
      update();
      return true;
    }
    return false;
  }

  /** The user tapped a candidate in the candidates view. */
  public void select(int i)
  {
    if (i < 0 || i >= _candidates.size())
      return;
    PinyinEngine.Candidate c = _candidates.get(i);
    _selected_letters.add(_letters.substring(0, c.consumed));
    _letters.delete(0, c.consumed);
    _selected.append(c.word);
    _selected_cands.add(c);
    PinyinEngine e = engine();
    if (e != null)
      e.learn_word(c);
    if (only_separators(_letters))
      finish();
    else
      update();
  }

  void commit_first()
  {
    if (_candidates.isEmpty())
      commit_raw();
    else
      select(0);
  }

  /** Convert all the pinyin with the first candidates. */
  void commit_all()
  {
    for (int guard = 0; composing() && guard < 64; guard++)
    {
      if (_candidates.isEmpty())
      {
        commit_raw();
        return;
      }
      select(0);
    }
  }

  /** Commit the chosen characters and the remaining pinyin as is. */
  public void commit_raw()
  {
    if (!composing())
      return;
    String text = _selected.toString() + _letters.toString();
    reset(false);
    _callback.send_text(text);
  }

  /** Every syllable has been converted. */
  void finish()
  {
    String text = _selected.toString();
    PinyinEngine e = engine();
    if (e != null && _selected_cands.size() > 1)
      e.learn_phrase(text, PinyinEngine.concat_syllables(_selected_cands));
    reset(false);
    _callback.send_text(text);
  }

  /** Forget the composition. [clear_editor] removes the composing text. */
  public void reset(boolean clear_editor)
  {
    boolean was_composing = composing();
    _letters.setLength(0);
    _selected.setLength(0);
    _selected_cands.clear();
    _selected_letters.clear();
    _candidates = Collections.<PinyinEngine.Candidate>emptyList();
    if (was_composing && clear_editor)
    {
      InputConnection ic = _callback.getCurrentInputConnection();
      if (ic != null)
        ic.setComposingText("", 1);
    }
    _callback.set_pinyin_candidates(Collections.<String>emptyList());
  }

  /** The selection or the composing region ([-1] when there's none) changed
      in the editor. */
  public void selection_updated(int sel_start, int sel_end, int comp_start,
      int comp_end)
  {
    if (!composing())
      return;
    if (comp_start < 0)
      reset(false); // The editor dropped the composing text.
    else if (sel_start != comp_end || sel_end != comp_end)
    {
      // The user moved the cursor away, leave the pinyin as it is.
      InputConnection ic = _callback.getCurrentInputConnection();
      if (ic != null)
        ic.finishComposingText();
      reset(false);
    }
  }

  void update()
  {
    PinyinEngine e = engine();
    String input = _letters.toString();
    _candidates = (e == null || only_separators(_letters)) ?
      Collections.<PinyinEngine.Candidate>emptyList() : e.candidates(input);
    InputConnection ic = _callback.getCurrentInputConnection();
    if (ic != null)
      ic.setComposingText(_selected.toString() + input, 1);
    ArrayList<String> words = new ArrayList<String>(_candidates.size());
    for (PinyinEngine.Candidate c : _candidates)
      words.add(c.word);
    _callback.set_pinyin_candidates(words);
  }

  static boolean only_separators(CharSequence s)
  {
    for (int i = 0; i < s.length(); i++)
      if (s.charAt(i) != '\'')
        return false;
    return true;
  }

  /** Engine, shared and loaded in the background. */

  static PinyinEngine _engine = null;
  static boolean _engine_loading = false;
  static SharedPreferences _user_prefs = null;

  /** [null] while loading. */
  PinyinEngine engine()
  {
    return _engine;
  }

  /** Start loading the engine if needed. Calls [update] when it's ready. */
  public void load_engine(final Context ctx)
  {
    synchronized (PinyinComposer.class)
    {
      if (_engine != null || _engine_loading)
        return;
      _engine_loading = true;
    }
    final Context app = ctx.getApplicationContext();
    _user_prefs = DirectBootAwarePreferences.get_protected_prefs(app, "pinyin");
    Thread t = new Thread() {
      public void run()
      {
        PinyinEngine e = null;
        try
        {
          BufferedReader r = new BufferedReader(new InputStreamReader(
                app.getAssets().open("pinyin.txt"), "UTF-8"), 1 << 16);
          e = new PinyinEngine(PinyinDict.load(r));
          r.close();
          e.load_user_data(_user_prefs.getString("user_data", null));
        }
        catch (Throwable ex)
        {
          android.util.Log.e("juloo.keyboard2",
              "Failed to load the pinyin dictionary", ex);
        }
        final PinyinEngine loaded = e;
        _handler.post(new Runnable() {
          public void run()
          {
            _engine = loaded;
            _engine_loading = false;
            if (composing())
              update();
          }
        });
      }
    };
    t.setPriority(Thread.MIN_PRIORITY);
    t.start();
  }

  /** Save what the engine learned. */
  public void save_user_data()
  {
    if (_engine != null && _user_prefs != null && _engine.user_data_changed())
      _user_prefs.edit()
        .putString("user_data", _engine.serialize_user_data()).apply();
  }
}
