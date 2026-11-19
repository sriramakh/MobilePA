package dev.mobilepa;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import java.time.*;
import java.util.*;

public final class MainActivity extends DeskUi {
  Store db;
  Library library;
  Rules rules;
  String tab = "Desk", query = "";
  long collectionId = 0;
  private EditText command;
  private final Handler refreshHandler = new Handler(Looper.getMainLooper());
  private final Runnable updateMail =
      new Runnable() {
        public void run() {
          if (tab.equals("Rules") && rules != null) rules.refreshStatus();
          refreshHandler.postDelayed(this, 3000);
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
    }
    db = Store.get(this);
    library = new Library(this);
    rules = new Rules(this);
    if (state != null) {
      tab = state.getString("tab", "Desk");
      query = state.getString("query", "");
      collectionId = state.getLong("collection");
      rules.restore(state);
    }
    render();
    if (state == null) incoming(getIntent());
    else library.restore(state);
    DeliveryJob.enqueue(this);
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("tab", tab);
    b.putString("query", query);
    b.putLong("collection", collectionId);
    library.persist(b);
    rules.persist(b);
  }

  @Override
  protected void onResume() {
    super.onResume();
    refreshHandler.post(updateMail);
  }

  @Override
  protected void onPause() {
    refreshHandler.removeCallbacks(updateMail);
    super.onPause();
  }

  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    incoming(i);
  }

  private void incoming(Intent i) {
    if (Intent.ACTION_SEND.equals(i.getAction())) {
      Object stream = i.getParcelableExtra(Intent.EXTRA_STREAM);
      CharSequence text = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
      if (stream instanceof Uri || text != null)
        library.importItem(
            stream instanceof Uri ? (Uri) stream : null,
            text == null ? "" : text.toString(),
            i.getStringExtra(Intent.EXTRA_SUBJECT));
    } else if (i.hasExtra("memory")) library.detail(i.getLongExtra("memory", -1));
  }

  void navigate(String name) {
    tab = name;
    query = "";
    collectionId = 0;
    render();
  }

  void render() {
    switch (tab) {
      case "Search":
        search(query);
        break;
      case "Memory":
        library.memoryPage();
        break;
      case "Saved":
        library.savedPage();
        break;
      case "Rules":
        rules.page();
        break;
      default:
        desk();
    }
  }

  void desk() {
    layout("Desk", "Your desk", "What would you like to handle?");
    LinearLayout composer = column();
    composer.setBackground(shape(DARK, 20, false));
    pad(composer, 18, 17);
    wide(composer, label("Ask MobilePA", 18, WHITE, true));
    gap(composer, 12);
    command = field("", "Remember, remind me, or find…", false);
    command.setImeOptions(EditorInfo.IME_ACTION_GO);
    wide(composer, command);
    gap(composer, 10);
    TextView go = action("Run command   →", this::submit, false);
    wide(composer, go);
    wide(page, composer);
    command.setOnEditorActionListener(
        (v, id, e) -> {
          if (id == EditorInfo.IME_ACTION_GO) {
            submit();
            return true;
          }
          return false;
        });
    section("Quick actions");
    card(
        page,
        "Save a memory",
        "Keep a note, photo, or reminder.",
        "+",
        () -> library.note(0, "", 0, null));
    card(
        page,
        "Add to a collection",
        "Save a link, image, or PDF.",
        "▤",
        () -> library.importItem(null, "", ""));
    card(
        page,
        "Set up forwarding",
        "Send matching SMS to your email.",
        "⇄",
        () -> rules.editor(0, "", ""));
    List<Store.Row> next =
        db.rows("SELECT * FROM memory WHERE due>0 AND alerted=0 ORDER BY due LIMIT 3");
    if (!next.isEmpty()) {
      section("Reminders");
      for (Store.Row r : next)
        card(page, r.s("body"), when(r.n("due")), "◷", () -> library.detail(r.id()));
    }
  }

  void submit() {
    String value = command.getText().toString().trim();
    if (value.isEmpty()) {
      command.setError("Type a command");
      return;
    }
    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(command.getWindowToken(), 0);
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("remind me")) {
      Language.Reminder parsed = Language.reminder(value, LocalDateTime.now());
      long due =
          parsed.time == null
              ? 0
              : parsed.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
      library.note(0, parsed.body, due, null);
      if (due == 0) library.chooseTime();
    } else if (lower.startsWith("remember ") || lower.startsWith("note ")) {
      library.note(0, value.replaceFirst("(?i)^(remember(?: that)?|note)\\s+", ""), 0, null);
    } else if (lower.startsWith("email ") || lower.startsWith("forward ")) {
      List<String> phrases = Language.quoted(value);
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile(
                  "(?i)\\b(?:from|when)\\s+(.+?)\\s+(?:texts?|says?|containing|contains|with)\\b")
              .matcher(value);
      rules.editor(
          0, m.find() ? m.group(1).replaceFirst("(?i)^my ", "") : "", String.join(", ", phrases));
    } else if (lower.matches("^(show|find|search|where|what|when)\\b.*")) {
      String term = Language.searchTerm(value);
      if (term.isEmpty()) {
        toast("Type something to find.");
        return;
      }
      search(term);
    } else
      info(
          "Try a command",
          "Remember I parked on B2, pillar 17.\n\n"
              + "Remind me tomorrow at 6 PM to take the parking pass.\n\n"
              + "Where did I park?\n\n"
              + "Show my Jaipur plans.\n\n"
              + "Forward texts from POWER containing “bill generated”.");
  }

  void search(String term) {
    tab = "Search";
    query = term;
    layout("Desk", "Search results", "Results on this phone for “" + term + "”");
    int count = library.results(term);
    if (count == 0) hint(page, "No matching memories or saved items.");
    wide(page, action("Back to desk", () -> navigate("Desk"), false));
  }

  @Override
  public void onBackPressed() {
    goBack();
  }

  private void goBack() {
    if (tab.equals("Saved") && collectionId != 0) {
      collectionId = 0;
      render();
    } else if (!tab.equals("Desk")) navigate("Desk");
    else finish();
  }

  boolean allowed(String permission) {
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void onRequestPermissionsResult(int req, String[] p, int[] grants) {
    super.onRequestPermissionsResult(req, p, grants);
    if (req == 31) {
      if (allowed(Manifest.permission.POST_NOTIFICATIONS)) Reminders.restore(this);
      else toast("Reminder saved. Enable notifications to receive alerts.");
    } else rules.permission(req);
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == 41 || req == 42) {
      if (res == RESULT_OK && data != null && data.getData() != null)
        library.picked(req, data.getData());
    } else if (req == 51) rules.authorized(res, data);
  }
}
