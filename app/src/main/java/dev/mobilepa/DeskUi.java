package dev.mobilepa;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

abstract class DeskUi extends Activity {
  static final int PAPER = 0xfff7f8f4,
      INK = 0xff152d29,
      MUTED = 0xff52665f,
      TEAL = 0xff005c4b,
      LINE = 0xffd9e2d9,
      WHITE = 0xffffffff,
      MINT = 0xffdeefdf,
      DARK = 0xff173d35;
  LinearLayout root, page, nav;

  abstract void navigate(String tab);

  int dp(float n) {
    return Math.round(getResources().getDisplayMetrics().density * n);
  }

  GradientDrawable shape(int color, int radius, boolean edge) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (edge) d.setStroke(dp(1), LINE);
    return d;
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout horizontal() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  void pad(View v, int x, int y) {
    v.setPadding(dp(x), dp(y), dp(x), dp(y));
  }

  TextView label(String s, int size, int color, boolean bold) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    v.setIncludeFontPadding(false);
    return v;
  }

  void gap(LinearLayout l, int n) {
    Space v = new Space(this);
    l.addView(v, 1, dp(n));
  }

  void wide(LinearLayout l, View v) {
    l.addView(v, new LinearLayout.LayoutParams(-1, -2));
  }

  void flex(LinearLayout l, View v) {
    l.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
  }

  TextView action(String s, Runnable run, boolean primary) {
    TextView t = label(s, 15, primary ? WHITE : TEAL, true);
    pad(t, 14, 10);
    t.setMinHeight(dp(44));
    t.setGravity(Gravity.CENTER);
    t.setBackground(shape(primary ? TEAL : MINT, 12, false));
    click(t, run);
    return t;
  }

  void click(View v, Runnable run) {
    v.setOnClickListener(w -> run.run());
    v.setFocusable(true);
  }

  void card(LinearLayout parent, String title, String sub, String symbol, Runnable run) {
    LinearLayout row = horizontal();
    row.setBackground(shape(WHITE, 15, true));
    pad(row, 14, 15);
    TextView badge = label(symbol, 20, TEAL, true);
    badge.setGravity(Gravity.CENTER);
    badge.setBackground(shape(MINT, 10, false));
    row.addView(badge, dp(40), dp(40));
    LinearLayout text = column();
    text.setPadding(dp(12), 0, dp(6), 0);
    TextView heading = label(title, 16, INK, true);
    heading.setMaxLines(3);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    wide(text, heading);
    if (!sub.isEmpty()) {
      gap(text, 5);
      wide(text, label(sub, 13, MUTED, false));
    }
    flex(row, text);
    row.addView(label("›", 22, MUTED, false));
    click(row, run);
    wide(parent, row);
    gap(parent, 8);
  }

  void section(String text) {
    gap(page, 16);
    wide(page, label(text, 20, INK, true));
    gap(page, 10);
  }

  void hint(LinearLayout box, String text) {
    TextView t = label(text, 14, MUTED, false);
    t.setLineSpacing(dp(2), 1);
    wide(box, t);
  }

  void fieldLabel(LinearLayout box, String s) {
    gap(box, 12);
    wide(box, label(s, 14, INK, true));
    gap(box, 6);
  }

  EditText field(String value, String placeholder, boolean multiline) {
    EditText e = new EditText(this);
    e.setTextSize(16);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setText(value);
    e.setHint(placeholder);
    e.setSingleLine(!multiline);
    e.setMinLines(multiline ? 2 : 1);
    e.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    pad(e, 12, 10);
    e.setBackground(shape(WHITE, 10, true));
    return e;
  }

  void layout(String tab, String title, String subtitle) {
    root = column();
    root.setBackgroundColor(PAPER);
    root.setFocusableInTouchMode(true);
    root.setOnApplyWindowInsetsListener(
        (v, in) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets b =
                in.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
          } else
            v.setPadding(
                in.getSystemWindowInsetLeft(),
                in.getSystemWindowInsetTop(),
                in.getSystemWindowInsetRight(),
                in.getSystemWindowInsetBottom());
          return in.consumeSystemWindowInsets();
        });
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
    } else
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                  | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    setContentView(root);
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow()
          .getInsetsController()
          .setSystemBarsAppearance(
              WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
              WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
    LinearLayout header = horizontal();
    pad(header, 20, 12);
    ImageView icon = new ImageView(this);
    icon.setImageResource(dev.mobilepa.R.drawable.assistant_mark);
    header.addView(icon, dp(33), dp(33));
    TextView name = label("MobilePA", 20, INK, true);
    name.setPadding(dp(9), 0, 0, 0);
    flex(header, name);
    TextView date =
        label(
            new java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault())
                .format(new java.util.Date()),
            13,
            MUTED,
            false);
    header.addView(date);
    wide(root, header);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    page = column();
    pad(page, 20, 12);
    scroll.addView(page);
    wide(page, label(title, 32, INK, true));
    if (!subtitle.isEmpty()) {
      gap(page, 5);
      hint(page, subtitle);
    }
    gap(page, 18);
    nav = horizontal();
    nav.setBackgroundColor(WHITE);
    pad(nav, 5, 4);
    String[] names = {"Desk", "Memory", "Saved", "Rules"};
    String[] glyphs = {"⌂", "◷", "▤", "⇄"};
    for (int i = 0; i < names.length; i++) {
      String nameTab = names[i];
      boolean active = tab.equals(nameTab);
      LinearLayout n = column();
      n.setGravity(Gravity.CENTER);
      pad(n, 2, 5);
      if (active) n.setBackground(shape(MINT, 13, false));
      TextView symbol = label(glyphs[i], 22, active ? TEAL : MUTED, true);
      symbol.setGravity(Gravity.CENTER);
      n.addView(symbol);
      gap(n, 2);
      TextView caption = label(nameTab, 12, active ? TEAL : MUTED, true);
      caption.setGravity(Gravity.CENTER);
      n.addView(caption);
      click(n, () -> navigate(nameTab));
      n.setContentDescription(nameTab);
      flex(nav, n);
    }
    wide(root, nav);
  }

  void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  void info(String title, String text) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(text)
        .setPositiveButton("OK", null)
        .show();
  }

  AlertDialog form(String title, LinearLayout box, String button, Runnable save) {
    ScrollView scroll = new ScrollView(this);
    pad(box, 20, 8);
    box.setFocusableInTouchMode(true);
    scroll.addView(box);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(button, null)
            .create();
    d.setOnShowListener(x -> d.getButton(-1).setOnClickListener(y -> save.run()));
    d.show();
    return d;
  }

  String when(long millis) {
    return java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(new java.util.Date(millis));
  }
}
