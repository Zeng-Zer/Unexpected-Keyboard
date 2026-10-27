package juloo.keyboard2;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Build.VERSION;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import juloo.cdict.Cdict;
import juloo.keyboard2.typing.DictLetterModel;
import juloo.keyboard2.typing.GlideDecoder;
import juloo.keyboard2.typing.KeyCenters;
import juloo.keyboard2.typing.Lexicon;
import juloo.keyboard2.typing.TapModel;

public class Keyboard2View extends View
  implements View.OnTouchListener, Pointers.IPointerEventHandler
{
  private KeyboardData _keyboard;

  /** The key holding the shift key is used to set shift state from
      autocapitalisation. */
  private KeyboardData.Key _shift_key;

  /** Used to add fake pointers. */
  private KeyboardData.Key _compose_key;

  private Pointers _pointers;

  private Pointers.Modifiers _mods;

  private static int _currentWhat = 0;

  private Config _config;

  private float _keyWidth;
  private float _mainLabelSize;
  private float _subLabelSize;
  private float _marginRight;
  private float _marginLeft;
  private float _marginBottom;
  private int _insets_left = 0;
  private int _insets_right = 0;
  private int _insets_bottom = 0;

  private Theme _theme;
  private Theme.Computed _tc;

  private static RectF _tmpRect = new RectF();

  /** Letter keys of the current layout and their positions, used by
      [TapModel] and [GlideDecoder]. Computed in [onMeasure]. */
  private KeyCenters _key_centers = null;
  private KeyboardData.Key[] _letter_keys = new KeyboardData.Key[0];
  private GlideDecoder _glide_decoder = null;
  private DictLetterModel _letter_model = null;
  private Paint _glide_paint = null;
  private Path _glide_draw_path = new Path();

  /** A gesture becomes a glide after travelling this many key widths. Shorter
      swipes select the symbols in the corners of the keys. */
  static final float GLIDE_TRIGGER_KEYS = 1.3f;

  /** The last tap is learned by [TapModel] once the next key is pressed,
      unless that key is backspace: the tap was a typo. Index in
      [_key_centers] or [-1]. */
  private int _pending_tap = -1;
  private float _pending_tap_x;
  private float _pending_tap_y;

  /** Shared by all views, one per orientation. */
  private static TapModel _tap_model = null;
  private static boolean _tap_model_landscape = false;
  private static final TapModel NO_OFFSETS = new TapModel();

  enum Vertical
  {
    TOP,
    CENTER,
    BOTTOM
  }

  public Keyboard2View(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    _theme = new Theme(getContext(), attrs);
    _config = Config.globalConfig();
    _pointers = new Pointers(this, _config);
    refresh_navigation_bar(context);
    setOnTouchListener(this);
    int layout_id = (attrs == null) ? 0 :
      attrs.getAttributeResourceValue(null, "layout", 0);
    if (layout_id == 0)
      reset();
    else
      setKeyboard(KeyboardData.load(getResources(), layout_id));
  }

  private Window getParentWindow(Context context)
  {
    if (context instanceof InputMethodService)
      return ((InputMethodService)context).getWindow().getWindow();
    if (context instanceof ContextWrapper)
      return getParentWindow(((ContextWrapper)context).getBaseContext());
    return null;
  }

  public void refresh_navigation_bar(Context context)
  {
    if (VERSION.SDK_INT < 21)
      return;
    // The intermediate Window is a [Dialog].
    Window w = getParentWindow(context);
    w.setNavigationBarColor(_theme.colorNavBar);
    if (VERSION.SDK_INT < 26)
      return;
    int uiFlags = getSystemUiVisibility();
    if (_theme.isLightNavBar)
      uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    else
      uiFlags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
    setSystemUiVisibility(uiFlags);
  }

  public void setKeyboard(KeyboardData kw)
  {
    _keyboard = kw;
    // Start building the glide lexicon early, it takes a while.
    if (_config.glide_typing)
      Lexicon.get_async(_config.dictionaries);
    _shift_key = _keyboard.findKeyWithValue(KeyValue.SHIFT);
    _compose_key = _keyboard.findKeyWithValue(KeyValue.COMPOSE);
    KeyModifier.set_modmap(_keyboard.modmap);
    reset();
  }

  public void reset()
  {
    _mods = Pointers.Modifiers.EMPTY;
    _pointers.clear();
    _pending_tap = -1;
    save_tap_model();
    requestLayout();
    invalidate();
  }

  void set_fake_ptr_latched(KeyboardData.Key key, KeyValue kv, boolean latched,
      boolean lock)
  {
    if (_keyboard == null || key == null)
      return;
    _pointers.set_fake_pointer_state(key, kv, latched, lock);
  }

  /** Called by auto-capitalisation. */
  public void set_shift_state(boolean latched, boolean lock)
  {
    set_fake_ptr_latched(_shift_key, KeyValue.SHIFT, latched, lock);
  }

  /** Called from [KeyEventHandler]. */
  public void set_compose_pending(boolean pending)
  {
    set_fake_ptr_latched(_compose_key, KeyValue.COMPOSE, pending, false);
  }

  /** Called from [Keybard2.onUpdateSelection].  */
  public void set_selection_state(boolean selection_state)
  {
    if (_config.editor_config.selection_mode_enabled)
      set_fake_ptr_latched(KeyboardData.Key.EMPTY,
          KeyValue.SELECTION_MODE, selection_state, true);
  }

  public KeyValue modifyKey(KeyValue k, Pointers.Modifiers mods)
  {
    return KeyModifier.modify(k, mods);
  }

  public void onPointerDown(KeyValue k, boolean isSwipe)
  {
    updateFlags();
    _config.handler.key_down(k, isSwipe);
    invalidate();
    vibrate();
  }

  public void onPointerUp(KeyValue k, Pointers.Modifiers mods)
  {
    if (is_backspace(k))
      _pending_tap = -1;
    // [key_up] must be called before [updateFlags]. The latter might disable
    // flags.
    _config.handler.key_up(k, mods);
    updateFlags();
    invalidate();
  }

  public void onPointerHold(KeyValue k, Pointers.Modifiers mods)
  {
    if (is_backspace(k))
      _pending_tap = -1;
    _config.handler.key_up(k, mods);
    updateFlags();
  }

  public void onPointerFlagsChanged(boolean shouldVibrate)
  {
    updateFlags();
    invalidate();
    if (shouldVibrate)
      vibrate();
  }

  public void onTap(KeyboardData.Key key, float x, float y)
  {
    if (!_config.learn_touch_offsets)
      return;
    // Called before [onPointerUp], a typo must not be learned.
    if (is_backspace(key.keys[0]))
    {
      _pending_tap = -1;
      return;
    }
    if (_pending_tap >= 0)
    {
      TapModel m = tap_model();
      m.learn(_key_centers, _pending_tap, _pending_tap_x, _pending_tap_y);
      if (m.unsaved_samples() >= 50)
        save_tap_model();
    }
    _pending_tap = letter_key_index(key);
    _pending_tap_x = x;
    _pending_tap_y = y;
  }

  public boolean canGlide(KeyboardData.Key key)
  {
    return _config.glide_typing
      && _config.editor_config.should_show_candidates_view
      && letter_key_index(key) >= 0
      && glide_decoder() != null;
  }

  public void onGlideMove()
  {
    invalidate();
  }

  public void onGlideEnd(Pointers.GlidePath path, Pointers.Modifiers mods)
  {
    updateFlags();
    invalidate();
    GlideDecoder dec = glide_decoder();
    if (dec == null)
      return;
    dec.set_dict_bonus(_config.handler.language_bonus(false));
    GlideDecoder.Result r = dec.decode(path.xs, path.ys, path.length,
        juloo.keyboard2.suggestions.Suggestions.MAX_COUNT);
    if (r == null || r.words.length == 0)
      return;
    int shift_flags = _pointers.getKeyFlags(KeyValue.SHIFT);
    int caps = KeyEventHandler.CAPS_NONE;
    if (shift_flags != -1 && (shift_flags & Pointers.FLAG_P_LOCKED) != 0)
      caps = KeyEventHandler.CAPS_ALL;
    else if (mods.has(KeyValue.Modifier.SHIFT))
      caps = KeyEventHandler.CAPS_FIRST;
    _config.handler.glide_entered(r.words, caps);
  }

  /** The key a tap at [x, y] is meant for. [key] is the key under the
      finger. */
  private KeyboardData.Key predict_tap_key(KeyboardData.Key key, float x, float y)
  {
    int k = letter_key_index(key);
    if (k < 0)
      return key;
    boolean use_lm = _config.letter_aware_taps
      && _config.editor_config.should_show_candidates_view
      && _config.current_dictionary != null;
    if (!use_lm && !_config.learn_touch_offsets)
      return key;
    TapModel m = _config.learn_touch_offsets ? tap_model() : NO_OFFSETS;
    TapModel.LetterModel lm = null;
    String prefix = "";
    if (use_lm)
    {
      lm = letter_model(_config.dictionaries);
      prefix = _config.handler.word_before_cursor().toLowerCase();
    }
    return _letter_keys[m.predict(_key_centers, x, y, k, lm, prefix)];
  }

  private int letter_key_index(KeyboardData.Key key)
  {
    for (int i = 0; i < _letter_keys.length; i++)
      if (_letter_keys[i] == key)
        return i;
    return -1;
  }

  /** The lowercase letter typed by the key or [0]. */
  private static char letter_of_key(KeyboardData.Key key)
  {
    KeyValue kv = key.keys[0];
    if (kv == null || kv.getKind() != KeyValue.Kind.Char)
      return 0;
    char c = kv.getChar();
    return Character.isLetter(c) ? Character.toLowerCase(c) : 0;
  }

  private static boolean is_backspace(KeyValue k)
  {
    if (k == null)
      return false;
    switch (k.getKind())
    {
      case Editing: return k.getEditing() == KeyValue.Editing.BACKSPACE;
      case Keyevent: return k.getKeyevent() == KeyEvent.KEYCODE_DEL;
      default: return false;
    }
  }

  /** Must be called after [_keyWidth] and [_tc] are computed. Uses the same
      geometry as [getKeyAtPosition]. */
  private void compute_key_centers()
  {
    ArrayList<KeyboardData.Key> keys = new ArrayList<KeyboardData.Key>();
    ArrayList<float[]> pos = new ArrayList<float[]>();
    StringBuilder chars = new StringBuilder();
    float y = _config.marginTop;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      float top = y + row.shift * _tc.row_height;
      float h = row.height * _tc.row_height;
      float x = _marginLeft;
      for (KeyboardData.Key key : row.keys)
      {
        float left = x + key.shift * _keyWidth;
        float w = key.width * _keyWidth;
        char c = letter_of_key(key);
        if (c != 0)
        {
          keys.add(key);
          chars.append(c);
          pos.add(new float[]{ left + w / 2, top + h / 2, w, h });
        }
        x = left + w;
      }
      y = top + h;
    }
    int n = keys.size();
    float[] cx = new float[n], cy = new float[n], cw = new float[n], ch = new float[n];
    for (int i = 0; i < n; i++)
    {
      float[] p = pos.get(i);
      cx[i] = p[0]; cy[i] = p[1]; cw[i] = p[2]; ch[i] = p[3];
    }
    _letter_keys = keys.toArray(new KeyboardData.Key[n]);
    _key_centers = new KeyCenters(chars.toString().toCharArray(), cx, cy, cw, ch);
    _glide_decoder = null;
    _pending_tap = -1;
    _pointers.glide_trigger_px = GLIDE_TRIGGER_KEYS * _keyWidth;
  }

  /** Returns [null] while the lexicon is being built. */
  private GlideDecoder glide_decoder()
  {
    Lexicon lex = Lexicon.get_async(_config.dictionaries);
    if (lex == null || _key_centers == null || _key_centers.size() == 0)
      return null;
    if (_glide_decoder == null || !_glide_decoder.uses(_key_centers, lex))
      _glide_decoder = new GlideDecoder(_key_centers, lex);
    return _glide_decoder;
  }

  private DictLetterModel letter_model(Cdict[] dicts)
  {
    if (_letter_model == null || !_letter_model.uses(dicts))
      _letter_model = new DictLetterModel(dicts);
    return _letter_model;
  }

  private SharedPreferences tap_model_prefs()
  {
    return DirectBootAwarePreferences.get_protected_prefs(getContext(),
        "touch_model");
  }

  private static String tap_model_pref_key(boolean landscape)
  {
    return landscape ? "landscape" : "portrait";
  }

  private TapModel tap_model()
  {
    boolean landscape = _config.orientation_landscape;
    if (_tap_model == null || _tap_model_landscape != landscape)
    {
      save_tap_model();
      _tap_model = TapModel.deserialize(tap_model_prefs().getString(
            tap_model_pref_key(landscape), null));
      _tap_model_landscape = landscape;
    }
    return _tap_model;
  }

  private void save_tap_model()
  {
    if (_tap_model == null || _tap_model.unsaved_samples() == 0)
      return;
    tap_model_prefs().edit()
      .putString(tap_model_pref_key(_tap_model_landscape), _tap_model.serialize())
      .apply();
  }

  private void updateFlags()
  {
    _mods = _pointers.getModifiers();
    _config.handler.mods_changed(_mods);
  }

  @Override
  public boolean onTouch(View v, MotionEvent event)
  {
    int p;
    switch (event.getActionMasked())
    {
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_POINTER_UP:
        _pointers.onTouchUp(event.getPointerId(event.getActionIndex()));
        break;
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_POINTER_DOWN:
        p = event.getActionIndex();
        float tx = event.getX(p);
        float ty = event.getY(p);
        KeyboardData.Key key = getKeyAtPosition(tx, ty);
        if (key != null)
          _pointers.onTouchDown(tx, ty, event.getPointerId(p), key,
              predict_tap_key(key, tx, ty));
        break;
      case MotionEvent.ACTION_MOVE:
        for (p = 0; p < event.getPointerCount(); p++)
          _pointers.onTouchMove(event.getX(p), event.getY(p), event.getPointerId(p));
        break;
      case MotionEvent.ACTION_CANCEL:
        _pointers.onTouchCancel();
        break;
      default:
        return (false);
    }
    return (true);
  }

  private KeyboardData.Row getRowAtPosition(float ty)
  {
    float y = _config.marginTop;
    if (ty < y)
      return null;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += (row.shift + row.height) * _tc.row_height;
      if (ty < y)
        return row;
    }
    return null;
  }

  private KeyboardData.Key getKeyAtPosition(float tx, float ty)
  {
    KeyboardData.Row row = getRowAtPosition(ty);
    float x = _marginLeft;
    if (row == null || tx < x)
      return null;
    for (KeyboardData.Key key : row.keys)
    {
      float xLeft = x + key.shift * _keyWidth;
      float xRight = xLeft + key.width * _keyWidth;
      if (tx < xLeft)
        return null;
      if (tx < xRight)
        return key;
      x = xRight;
    }
    return null;
  }

  private void vibrate()
  {
    VibratorCompat.vibrate(this, _config);
  }

  @Override
  public void onMeasure(int wSpec, int hSpec)
  {
    DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
    int width = dm.widthPixels;
    _marginLeft = Math.max(_config.horizontal_margin, _insets_left);
    _marginRight = Math.max(_config.horizontal_margin, _insets_right);
    _marginBottom = _config.margin_bottom + _insets_bottom;
    _keyWidth = (width - _marginLeft - _marginRight) / _keyboard.keysWidth;
    _tc = new Theme.Computed(_theme, _config, _keyWidth, _keyboard);
    // Compute the size of labels based on the width or the height of keys. The
    // margin around keys is taken into account. Keys normal aspect ratio is
    // assumed to be 3/2 for a 10 columns layout. It's generally more, the
    // width computation is useful when the keyboard is unusually high.
    float labelBaseSize = Math.min(
        _tc.row_height - _tc.vertical_margin,
        (width / 10 - _tc.horizontal_margin) * 3/2
        ) * _config.characterSize;
    _mainLabelSize = labelBaseSize * _config.labelTextSize;
    _subLabelSize = labelBaseSize * _config.sublabelTextSize;
    int height =
      (int)(_tc.row_height * _keyboard.keysHeight
          + _config.marginTop + _marginBottom);
    setMeasuredDimension(width, height);
    compute_key_centers();
  }

  Rect _cached_exclusion_rect = new Rect();
  List<Rect> _cached_exclusion_rects = Arrays.asList(_cached_exclusion_rect);
  @Override
  public void onLayout(boolean changed, int left, int top, int right, int bottom)
  {
    if (!changed)
      return;
    // Since SDK 30, this is done automatically:
    // https://android.googlesource.com/platform/frameworks/base/+/android11-release/core/java/android/inputmethodservice/InputMethodService.java#852
    if (VERSION.SDK_INT == 29)
    {
      // Disable the back-gesture on the keyboard area
      _cached_exclusion_rect.set(
          left + (int)_marginLeft,
          top + (int)_config.marginTop,
          right - (int)_marginRight,
          bottom - (int)_marginBottom);
      setSystemGestureExclusionRects(_cached_exclusion_rects);
    }
  }

  @Override
  public WindowInsets onApplyWindowInsets(WindowInsets wi)
  {
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS is set in [Keyboard2#updateSoftInputWindowLayoutParams] for SDK_INT >= 35.
    if (VERSION.SDK_INT < 35)
      return wi;
    int insets_types =
      WindowInsets.Type.systemBars()
      | WindowInsets.Type.displayCutout();
    Insets insets = wi.getInsets(insets_types);
    _insets_left = insets.left;
    _insets_right = insets.right;
    _insets_bottom = insets.bottom;
    return WindowInsets.CONSUMED;
  }

  /** Horizontal and vertical position of the 9 indexes. */
  static final Paint.Align[] LABEL_POSITION_H = new Paint.Align[]{
    Paint.Align.CENTER, Paint.Align.LEFT, Paint.Align.RIGHT, Paint.Align.LEFT,
    Paint.Align.RIGHT, Paint.Align.LEFT, Paint.Align.RIGHT,
    Paint.Align.CENTER, Paint.Align.CENTER
  };

  static final Vertical[] LABEL_POSITION_V = new Vertical[]{
    Vertical.CENTER, Vertical.TOP, Vertical.TOP, Vertical.BOTTOM,
    Vertical.BOTTOM, Vertical.CENTER, Vertical.CENTER, Vertical.TOP,
    Vertical.BOTTOM
  };

  @Override
  protected void onDraw(Canvas canvas)
  {
    if (_tc.keyboard_background_paint != null)
    {
      canvas.drawRect(
              0,
              0,
              getWidth(),
              getHeight(),
              _tc.keyboard_background_paint);
    }

    float y = _tc.margin_top;
    for (KeyboardData.Row row : _keyboard.rows)
    {
      y += row.shift * _tc.row_height;
      float x = _marginLeft + _tc.margin_left;
      float keyH = row.height * _tc.row_height - _tc.vertical_margin;
      for (KeyboardData.Key k : row.keys)
      {
        x += k.shift * _keyWidth;
        float keyW = _keyWidth * k.width - _tc.horizontal_margin;
        boolean isKeyDown = _pointers.isKeyDown(k);
        Theme.Computed.Key tc_key;
        if (isKeyDown)
          tc_key = _tc.key_activated;
        else
          switch (k.role)
          {
            case Action: tc_key = _tc.key_action; break;
            case Space_bar: tc_key = _tc.key_space_bar; break;
            case Suggestion: tc_key = _tc.key_suggestion; break;
            default:
            case Normal: tc_key = _tc.key; break;
          }
        drawKeyFrame(canvas, x, y, keyW, keyH, tc_key);
        if (k.keys[0] != null)
          drawLabel(canvas, k.keys[0], keyW / 2f + x, y, keyH, isKeyDown, tc_key);
        for (int i = 1; i < 9; i++)
        {
          if (k.keys[i] != null)
            drawSubLabel(canvas, k.keys[i], x, y, keyW, keyH, i, isKeyDown, tc_key);
        }
        drawIndication(canvas, k, x, y, keyW, keyH, _tc);
        x += _keyWidth * k.width;
      }
      y += row.height * _tc.row_height;
    }
    Pointers.GlidePath glide = _pointers.getGlidePath();
    if (glide != null)
      drawGlidePath(canvas, glide);
  }

  /** Draw the trail of the glide gesture in progress. */
  void drawGlidePath(Canvas canvas, Pointers.GlidePath glide)
  {
    if (_glide_paint == null)
    {
      _glide_paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      _glide_paint.setStyle(Paint.Style.STROKE);
      _glide_paint.setStrokeCap(Paint.Cap.ROUND);
      _glide_paint.setStrokeJoin(Paint.Join.ROUND);
    }
    _glide_paint.setColor(_theme.activatedColor);
    _glide_paint.setAlpha(170);
    _glide_paint.setStrokeWidth(_keyWidth * 0.09f);
    Path path = _glide_draw_path;
    path.rewind();
    path.moveTo(glide.xs[0], glide.ys[0]);
    for (int i = 1; i < glide.length; i++)
      path.lineTo(glide.xs[i], glide.ys[i]);
    canvas.drawPath(path, _glide_paint);
  }

  @Override
  public void onDetachedFromWindow()
  {
    super.onDetachedFromWindow();
  }

  /** Draw borders and background of the key. */
  void drawKeyFrame(Canvas canvas, float x, float y, float keyW, float keyH,
      Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    float w = tc.border_width;
    float padding = w / 2.f;
    _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding);
    canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint);
    if (w > 0.f)
    {
      float overlap = r - r * 0.85f + w; // sin(45°)
      drawBorder(canvas, x, y, x + overlap, y + keyH, tc.border_left_paint, tc);
      drawBorder(canvas, x + keyW - overlap, y, x + keyW, y + keyH, tc.border_right_paint, tc);
      drawBorder(canvas, x, y, x + keyW, y + overlap, tc.border_top_paint, tc);
      drawBorder(canvas, x, y + keyH - overlap, x + keyW, y + keyH, tc.border_bottom_paint, tc);
    }
  }

  /** Clip to draw a border at a time. This allows to call [drawRoundRect]
      several time with the same parameters but a different Paint. */
  void drawBorder(Canvas canvas, float clipl, float clipt, float clipr,
      float clipb, Paint paint, Theme.Computed.Key tc)
  {
    float r = tc.border_radius;
    canvas.save();
    canvas.clipRect(clipl, clipt, clipr, clipb);
    canvas.drawRoundRect(_tmpRect, r, r, paint);
    canvas.restore();
  }

  private int labelColor(KeyValue k, boolean isKeyDown, boolean sublabel)
  {
    if (isKeyDown)
    {
      int flags = _pointers.getKeyFlags(k);
      if (flags != -1)
      {
        if ((flags & Pointers.FLAG_P_LOCKED) != 0)
          return _theme.lockedColor;
        return _theme.activatedColor;
      }
      return _theme.pressedColor;
    }
    if (k.hasFlagsAny(KeyValue.FLAG_SECONDARY | KeyValue.FLAG_GREYED))
    {
      if (k.hasFlagsAny(KeyValue.FLAG_GREYED))
        return _theme.greyedLabelColor;
      return _theme.secondaryLabelColor;
    }
    return sublabel ? _theme.subLabelColor : _theme.labelColor;
  }

  private void drawLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyH, boolean isKeyDown, Theme.Computed.Key tc)
  {
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, true);
    Paint p = tc.label_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(kv, isKeyDown, false), textSize);
    canvas.drawText(kv.getString(), x, (keyH - p.ascent() - p.descent()) / 2f + y, p);
  }

  private void drawSubLabel(Canvas canvas, KeyValue kv, float x, float y,
      float keyW, float keyH, int sub_index, boolean isKeyDown,
      Theme.Computed.Key tc)
  {
    Paint.Align a = LABEL_POSITION_H[sub_index];
    Vertical v = LABEL_POSITION_V[sub_index];
    kv = modifyKey(kv, _mods);
    if (kv == null)
      return;
    float textSize = scaleTextSize(kv, false);
    Paint p = tc.sublabel_paint(kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), labelColor(kv, isKeyDown, true), textSize, a);
    float subPadding = _config.keyPadding;
    if (v == Vertical.CENTER)
      y += (keyH - p.ascent() - p.descent()) / 2f;
    else
      y += (v == Vertical.TOP) ? subPadding - p.ascent() : keyH - subPadding - p.descent();
    if (a == Paint.Align.CENTER)
      x += keyW / 2f;
    else
      x += (a == Paint.Align.LEFT) ? subPadding : keyW - subPadding;
    String label = kv.getString();
    int label_len = label.length();
    // Limit the label of string keys to 3 characters
    if (label_len > 3 && kv.getKind() == KeyValue.Kind.String)
      label_len = 3;
    canvas.drawText(label, 0, label_len, x, y, p);
  }

  private void drawIndication(Canvas canvas, KeyboardData.Key k, float x,
      float y, float keyW, float keyH, Theme.Computed tc)
  {
    if (k.indication == null || k.indication.equals(""))
      return;
    Paint p = tc.indication_paint;
    p.setTextSize(_subLabelSize);
    canvas.drawText(k.indication, 0, k.indication.length(),
        x + keyW / 2f, (keyH - p.ascent() - p.descent()) * 4/5 + y, p);
  }

  private float scaleTextSize(KeyValue k, boolean main_label)
  {
    float smaller_font = k.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) ? 0.75f : 1.f;
    float label_size = main_label ? _mainLabelSize : _subLabelSize;
    return label_size * smaller_font;
  }
}
