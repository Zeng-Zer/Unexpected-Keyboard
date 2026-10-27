package juloo.keyboard2.dict;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.inputmethodservice.InputMethodService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import juloo.keyboard2.*;

public final class DictionarySwitcher
{
  InputMethodService _ims;
  Dictionaries _dicts;
  SupportedDictionaries _sd;
  Callback _callback;

  public DictionarySwitcher(InputMethodService ims, Dictionaries dicts,
      Callback callback)
  {
    _ims = ims;
    _dicts = dicts;
    _sd = SupportedDictionaries.get(ims.getResources());
    _callback = callback;
  }

  /** Several languages can be checked to type in all of them at once.
      [current] is the current selection, might be [null]. */
  public void choose(String current)
  {
    final List<String> dict_names = new ArrayList<String>(_dicts.get_installed());
    Collections.sort(dict_names);
    // Keep the order of the current selection, the first dictionary is the
    // main one.
    final List<String> selected = new ArrayList<String>();
    if (current != null)
      for (String n : Dictionaries.split_names(current))
        if (dict_names.contains(n))
          selected.add(n);
    String[] labels = new String[dict_names.size()];
    boolean[] checked = new boolean[dict_names.size()];
    for (int i = 0; i < labels.length; i++)
    {
      labels[i] = _sd.get_display_name(dict_names.get(i));
      checked[i] = selected.contains(dict_names.get(i));
    }
    AlertDialog dialog = new AlertDialog.Builder(_ims)
      .setTitle(R.string.dictionary_switcher_title)
      .setMultiChoiceItems(labels, checked,
        new DialogInterface.OnMultiChoiceClickListener(){
          public void onClick(DialogInterface _d, int which, boolean is_checked)
          {
            String n = dict_names.get(which);
            selected.remove(n);
            if (is_checked)
              selected.add(n);
          }
        })
      .setPositiveButton(android.R.string.ok,
        new DialogInterface.OnClickListener(){
          public void onClick(DialogInterface _d, int _which)
          {
            if (selected.size() > 0)
              _callback.on_change_dictionary(Dictionaries.join_names(selected));
          }
        })
      .setNeutralButton(R.string.launcher_button_dictionaries,
        new DialogInterface.OnClickListener(){
          public void onClick(DialogInterface _d, int _which)
          {
            _callback.launch_dictionaries_activity();
          }
        })
      .setNegativeButton(android.R.string.cancel, null)
      .create();
    Utils.show_dialog_on_ime(dialog, _ims);
  }

  public interface Callback
  {
    /** Called when the user switches dictionary. */
    public void on_change_dictionary(String dict_name);
    /** Open the dictionaries activity */
    public void launch_dictionaries_activity();
  }
}
