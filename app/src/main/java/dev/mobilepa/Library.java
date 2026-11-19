package dev.mobilepa;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

final class Library {
  final MainActivity a;
  final Store db;
  private AlertDialog dialog;
  private EditText body, group, title;
  private TextView dueButton, attachButton;
  private Uri attachment;
  private long due, memoryId;
  private String mode = "";
  private boolean busy;

  Library(MainActivity a) {
    this.a = a;
    db = a.db;
  }

  private static final String MATCH = "instr(lower(%s),lower(?))>0";

  private String match(String field) {
    return String.format(Locale.ROOT, MATCH, field);
  }

  void memoryPage() {
    a.layout("Memory", "Memory", "Notes and reminders on this phone.");
    a.wide(a.page, a.action("＋  New memory", () -> note(0, "", 0, null), true));
    a.section("Your memories");
    List<Store.Row> rows = db.rows("SELECT * FROM memory ORDER BY created DESC,id DESC");
    if (rows.isEmpty()) a.hint(a.page, "Save a detail you want to find later.");
    for (Store.Row r : rows) memoryCard(r);
  }

  private void memoryCard(Store.Row r) {
    String subtitle = r.n("due") > 0 ? "Reminder · " + a.when(r.n("due")) : a.when(r.n("created"));
    if (!r.s("file").isEmpty()) subtitle += " · Photo";
    a.card(a.page, r.s("body"), subtitle, r.n("due") > 0 ? "◷" : "≡", () -> detail(r.id()));
  }

  void detail(long id) {
    Store.Row r = db.byId("memory", id);
    if (r == null) return;
    LinearLayout box = a.column();
    a.pad(box, 20, 10);
    a.wide(box, a.label(r.s("body"), 18, DeskUi.INK, false));
    a.gap(box, 12);
    a.hint(
        box,
        r.n("due") > 0 ? "Reminder · " + a.when(r.n("due")) : "Saved " + a.when(r.n("created")));
    if (!r.s("file").isEmpty()) {
      a.gap(box, 12);
      a.wide(
          box,
          a.action(
              "Open photo", () -> Viewer.show(a, r.s("file"), r.s("mime"), "Memory photo"), false));
    }
    ScrollView scroll = new ScrollView(a);
    scroll.addView(box);
    new AlertDialog.Builder(a)
        .setTitle("Memory")
        .setView(scroll)
        .setPositiveButton("Edit", (d, w) -> note(id, r.s("body"), r.n("due"), null))
        .setNegativeButton("Close", null)
        .setNeutralButton("Delete", (d, w) -> confirmDelete("memory", r))
        .show();
  }

  private void confirmDelete(String table, Store.Row r) {
    new AlertDialog.Builder(a)
        .setTitle("Delete this " + (table.equals("memory") ? "memory" : "item") + "?")
        .setNegativeButton("Keep", null)
        .setPositiveButton(
            "Delete",
            (d, w) -> {
              db.getWritableDatabase().delete(table, "id=?", new String[] {r.s("id")});
              if (table.equals("memory")) {
                Reminders.schedule(a, r.id(), 0);
                a.getSystemService(NotificationManager.class).cancel((int) r.id());
              }
              if (!r.s("file").isEmpty()) new File(r.s("file")).delete();
              a.render();
            })
        .show();
  }

  void note(long id, String initial, long time, Uri photo) {
    memoryId = id;
    mode = "note";
    due = time;
    attachment = photo;
    busy = false;
    LinearLayout box = a.column();
    a.fieldLabel(box, "Note");
    body = a.field(initial, "What should I remember?", true);
    a.wide(box, body);
    a.gap(box, 12);
    attachButton = a.action("Choose photo", () -> pick(true), false);
    a.wide(box, attachButton);
    a.gap(box, 8);
    dueButton = a.action("", this::chooseTime, false);
    a.wide(box, dueButton);
    a.gap(box, 6);
    a.wide(
        box,
        a.action(
            "Clear reminder",
            () -> {
              due = 0;
              labels();
            },
            false));
    labels();
    dialog = a.form(id == 0 ? "New memory" : "Edit memory", box, "Save", this::saveNote);
    track();
  }

  void chooseTime() {
    Calendar c = Calendar.getInstance();
    if (due > 0) c.setTimeInMillis(due);
    else c.add(Calendar.HOUR_OF_DAY, 1);
    new DatePickerDialog(
            a,
            (v, y, m, d) ->
                new TimePickerDialog(
                        a,
                        (v2, h, min) -> {
                          c.set(y, m, d, h, min, 0);
                          c.set(Calendar.MILLISECOND, 0);
                          due = c.getTimeInMillis();
                          labels();
                        },
                        c.get(Calendar.HOUR_OF_DAY),
                        c.get(Calendar.MINUTE),
                        android.text.format.DateFormat.is24HourFormat(a))
                    .show(),
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH),
            c.get(Calendar.DAY_OF_MONTH))
        .show();
  }

  private void labels() {
    if (mode.equals("note")) {
      dueButton.setText(due == 0 ? "Set reminder" : "Reminder · " + a.when(due));
      Store.Row old = memoryId == 0 ? null : db.byId("memory", memoryId);
      attachButton.setText(
          attachment != null
              ? "Photo selected · Change"
              : old != null && !old.s("file").isEmpty() ? "Change photo" : "Choose photo");
    } else if (attachButton != null)
      attachButton.setText(attachment == null ? "Choose image or PDF" : "File selected · Change");
  }

  private void saveNote() {
    String note = body.getText().toString().trim();
    if (note.isEmpty()) {
      body.setError("Enter a note");
      return;
    }
    long time = due, id = memoryId;
    Store.Row old = id == 0 ? null : db.byId("memory", id);
    if (time != 0 && time <= System.currentTimeMillis() && (old == null || old.n("due") != time)) {
      a.toast("Choose a future time.");
      return;
    }
    saveWithAttachment(
        saved -> {
          ContentValues values = Store.values("body", note, "due", time);
          if (old == null || old.n("due") != time) values.put("alerted", 0);
          if (saved != null) {
            values.put("file", saved.file);
            values.put("mime", saved.mime);
          }
          long key = id;
          if (id == 0) {
            values.put("created", System.currentTimeMillis());
            key = db.add("memory", values);
          } else db.edit("memory", id, values);
          if (saved != null && old != null && !old.s("file").isEmpty())
            new File(old.s("file")).delete();
          Reminders.schedule(a, key, time);
          dialog.dismiss();
          a.navigate("Memory");
          a.toast(time > 0 ? "Memory and reminder saved" : "Memory saved");
          if (time > 0
              && Build.VERSION.SDK_INT >= 33
              && !a.allowed(Manifest.permission.POST_NOTIFICATIONS))
            a.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 31);
        });
  }

  void savedPage() {
    Store.Row c = a.collectionId == 0 ? null : db.byId("collection", a.collectionId);
    a.layout(
        "Saved",
        c == null ? "Collections" : c.s("name"),
        c == null ? "Links and files you’ve kept together." : "Saved collection");
    a.wide(a.page, a.action("＋  Add an item", () -> importItem(null, "", ""), true));
    if (c == null) {
      a.section("Your collections");
      List<Store.Row> groups =
          db.rows(
              "SELECT collection.*,count(item.id) AS total FROM collection LEFT JOIN item ON"
                  + " collection.id=item.collection_id GROUP BY collection.id ORDER BY name");
      if (groups.isEmpty())
        a.hint(
            a.page, "Add your first item here, or choose MobilePA from another app’s Share menu.");
      for (Store.Row g : groups) groupCard(g);
    } else {
      a.section("Items");
      for (Store.Row r :
          db.rows(
              "SELECT * FROM item WHERE collection_id=? ORDER BY created DESC,id DESC", c.s("id")))
        itemCard(r);
      a.wide(a.page, a.action("All collections", () -> a.navigate("Saved"), false));
    }
  }

  private void groupCard(Store.Row r) {
    a.card(
        a.page,
        r.s("name"),
        r.s("total") + " saved",
        "▤",
        () -> {
          a.tab = "Saved";
          a.collectionId = r.id();
          a.render();
        });
  }

  private static boolean isLink(String body) {
    Uri uri = Uri.parse(body.trim());
    return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        && uri.getHost() != null
        && !body.contains(" ");
  }

  private void itemCard(Store.Row r) {
    String availability =
        r.s("file").isEmpty() && isLink(r.s("body")) ? "Link only" : "Available offline";
    a.card(
        a.page,
        r.s("title"),
        availability,
        r.s("file").isEmpty() ? "≡" : "▤",
        () -> item(r, availability));
  }

  private void item(Store.Row r, String availability) {
    AlertDialog.Builder b =
        new AlertDialog.Builder(a)
            .setTitle(r.s("title"))
            .setMessage(availability + (r.s("body").isEmpty() ? "" : "\n\n" + r.s("body")))
            .setNegativeButton("Close", null)
            .setNeutralButton("Delete", (d, w) -> confirmDelete("item", r));
    if (!r.s("file").isEmpty())
      b.setPositiveButton(
          "Open file", (d, w) -> Viewer.show(a, r.s("file"), r.s("mime"), r.s("title")));
    else if (isLink(r.s("body")))
      b.setPositiveButton(
          "Open link",
          (d, w) -> {
            try {
              a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.s("body"))));
            } catch (ActivityNotFoundException e) {
              a.toast("No browser available.");
            }
          });
    b.show();
  }

  int results(String term) {
    int count = 0;
    for (Store.Row r :
        db.rows("SELECT * FROM memory WHERE " + match("body") + " ORDER BY created DESC", term)) {
      memoryCard(r);
      count++;
    }
    for (Store.Row r :
        db.rows(
            "SELECT collection.*,count(item.id) AS total FROM collection LEFT JOIN item ON"
                + " item.collection_id=collection.id WHERE "
                + match("name")
                + " GROUP BY collection.id",
            term)) {
      groupCard(r);
      count++;
    }
    for (Store.Row r :
        db.rows(
            "SELECT * FROM item WHERE "
                + match("title")
                + " OR "
                + match("body")
                + " ORDER BY created DESC",
            term,
            term)) {
      itemCard(r);
      count++;
    }
    return count;
  }

  void importItem(Uri uri, String text, String subject) {
    mode = "item";
    attachment = uri;
    busy = false;
    LinearLayout box = a.column();
    a.fieldLabel(box, "Collection");
    Store.Row current = a.collectionId == 0 ? null : db.byId("collection", a.collectionId);
    AutoCompleteTextView groups = new AutoCompleteTextView(a);
    List<String> names = new ArrayList<>();
    for (Store.Row r : db.rows("SELECT name FROM collection ORDER BY name")) names.add(r.s("name"));
    groups.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_dropdown_item_1line, names));
    groups.setSingleLine();
    groups.setThreshold(1);
    groups.setHint("Choose or type a name");
    if (current != null) groups.setText(current.s("name"), false);
    groups.setOnClickListener(
        v -> {
          if (!names.isEmpty()) groups.showDropDown();
        });
    group = groups;
    a.wide(box, groups);
    a.fieldLabel(box, "Title");
    title = a.field(subject == null ? "" : subject, "Optional label", false);
    a.wide(box, title);
    a.fieldLabel(box, "Link or text");
    body = a.field(text, "Paste a link or write a note", true);
    a.wide(box, body);
    a.gap(box, 10);
    attachButton = a.action("", () -> pick(false), false);
    a.wide(box, attachButton);
    labels();
    dialog = a.form("Save to collection", box, "Save", this::saveItem);
    track();
  }

  private void saveItem() {
    String collection = group.getText().toString().trim(),
        text = body.getText().toString().trim(),
        name = title.getText().toString().trim();
    if (collection.isEmpty()) {
      group.setError("Enter a collection name");
      return;
    }
    if (text.isEmpty() && attachment == null) {
      body.setError("Add some text or choose a file");
      return;
    }
    saveWithAttachment(
        saved -> {
          String auto = saved != null ? saved.name : text.substring(0, Math.min(80, text.length()));
          ContentValues v =
              Store.values(
                  "title",
                  name.isEmpty() ? auto : name,
                  "body",
                  text,
                  "created",
                  System.currentTimeMillis());
          if (saved != null) {
            v.put("file", saved.file);
            v.put("mime", saved.mime);
          }
          db.saveItem(collection, v);
          dialog.dismiss();
          a.tab = "Saved";
          a.collectionId =
              db.rows("SELECT id FROM collection WHERE name=?", collection).get(0).id();
          a.render();
          a.toast("Saved to " + collection);
        });
  }

  private void pick(boolean photo) {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(photo ? "image/*" : "*/*");
    if (!photo) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "application/pdf"});
    a.startActivityForResult(i, photo ? 41 : 42);
  }

  void picked(int req, Uri uri) {
    if (dialog == null || !dialog.isShowing()) return;
    attachment = uri;
    labels();
  }

  private void track() {
    dialog.setOnDismissListener(
        d -> {
          mode = "";
        });
  }

  private interface Save {
    void done(Attachments.Saved saved);
  }

  private void saveWithAttachment(Save save) {
    if (busy) return;
    if (attachment == null) {
      save.done(null);
      return;
    }
    busy = true;
    dialog.setCancelable(false);
    dialog.getButton(-1).setEnabled(false);
    dialog.getButton(-2).setEnabled(false);
    dialog.getButton(-1).setText("Saving…");
    Uri uri = attachment;
    Attachments.IO.execute(
        () -> {
          try {
            Attachments.Saved saved = Attachments.copy(a, uri);
            a.runOnUiThread(
                () -> {
                  if (a.isDestroyed() || a.isFinishing()) {
                    new File(saved.file).delete();
                    return;
                  }
                  try {
                    save.done(saved);
                  } catch (RuntimeException ex) {
                    new File(saved.file).delete();
                    failed("Could not save the item.");
                  }
                });
          } catch (Exception ex) {
            a.runOnUiThread(
                () ->
                    failed(
                        "Could not copy the file. "
                            + (ex instanceof IOException
                                ? ex.getMessage()
                                : "Choose it again from Files.")));
          }
        });
  }

  private void failed(String message) {
    if (a.isDestroyed() || a.isFinishing()) return;
    busy = false;
    dialog.setCancelable(true);
    dialog.getButton(-1).setText("Save");
    dialog.getButton(-1).setEnabled(true);
    dialog.getButton(-2).setEnabled(true);
    a.info("File unavailable", message);
  }

  void persist(Bundle b) {
    if (mode.isEmpty() || dialog == null || !dialog.isShowing()) return;
    b.putString("editor", mode);
    b.putString("body", body.getText().toString());
    b.putString("uri", attachment == null ? "" : attachment.toString());
    b.putLong("time", due);
    b.putLong("memoryId", memoryId);
    if (mode.equals("item")) {
      b.putString("group", group.getText().toString());
      b.putString("title", title.getText().toString());
    }
  }

  void restore(Bundle b) {
    String m = b.getString("editor", ""), value = b.getString("uri", "");
    Uri u = value.isEmpty() ? null : Uri.parse(value);
    if (m.equals("note"))
      note(b.getLong("memoryId"), b.getString("body", ""), b.getLong("time"), u);
    if (m.equals("item")) {
      importItem(u, b.getString("body", ""), b.getString("title", ""));
      group.setText(b.getString("group", ""));
    }
  }
}
