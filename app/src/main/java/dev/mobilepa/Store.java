package dev.mobilepa;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class Store extends SQLiteOpenHelper {
  private static Store instance;
  private static String pendingDestination = "";

  static synchronized Store get(Context c) {
    if (instance == null) {
      String saved =
          c.getApplicationContext()
              .getSharedPreferences("mail", Context.MODE_PRIVATE)
              .getString("destination", "");
      pendingDestination = saved == null ? "" : saved;
      instance = new Store(c.getApplicationContext());
    }
    return instance;
  }

  private Store(Context c) {
    super(c, "mobilepa.db", null, 3);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, file TEXT,"
            + " mime TEXT, due INTEGER DEFAULT 0, alerted INTEGER DEFAULT 0, created INTEGER NOT"
            + " NULL)");
    db.execSQL(
        "CREATE TABLE collection(id INTEGER PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE"
            + " UNIQUE)");
    db.execSQL(
        "CREATE TABLE item(id INTEGER PRIMARY KEY, collection_id INTEGER NOT NULL REFERENCES"
            + " collection(id), title TEXT NOT NULL, body TEXT, file TEXT, mime TEXT, created"
            + " INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE rule(id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, phrases TEXT"
            + " NOT NULL, destination TEXT NOT NULL, enabled INTEGER DEFAULT 0,"
            + " last_checked INTEGER NOT NULL DEFAULT 0, last_result TEXT NOT NULL DEFAULT '',"
            + " kind TEXT NOT NULL DEFAULT 'phrase')");
    db.execSQL(
        "CREATE TABLE mail(id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT UNIQUE NOT NULL,"
            + " rule_id INTEGER, sender TEXT NOT NULL, body TEXT NOT NULL, phrase TEXT NOT NULL,"
            + " destination TEXT NOT NULL, received INTEGER NOT NULL, state TEXT NOT NULL, detail"
            + " TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT 'phrase')");
    db.execSQL("CREATE INDEX mail_state ON mail(state,id)");
    seedOtpRule(db);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if (from < 2) {
      db.execSQL("ALTER TABLE rule ADD COLUMN last_checked INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE rule ADD COLUMN last_result TEXT NOT NULL DEFAULT ''");
    }
    if (from < 3) {
      db.execSQL("ALTER TABLE rule ADD COLUMN kind TEXT NOT NULL DEFAULT 'phrase'");
      db.execSQL("ALTER TABLE mail ADD COLUMN kind TEXT NOT NULL DEFAULT 'phrase'");
      seedOtpRule(db);
    }
  }

  private void seedOtpRule(SQLiteDatabase db) {
    try (Cursor existing =
        db.rawQuery("SELECT id FROM rule WHERE kind=? LIMIT 1", new String[] {Language.OTP_KIND})) {
      if (existing.moveToFirst()) return;
    }
    String destination = defaultDestination(db);
    ContentValues values = new ContentValues();
    values.put("sender", "");
    values.put("phrases", Language.OTP_RULE_LABEL);
    values.put("destination", destination);
    values.put("enabled", destination.isEmpty() ? 0 : 1);
    values.put("last_checked", 0);
    values.put("last_result", "");
    values.put("kind", Language.OTP_KIND);
    db.insert("rule", null, values);
  }

  private String defaultDestination(SQLiteDatabase db) {
    if (!pendingDestination.isEmpty()) return pendingDestination;
    try (Cursor cursor =
        db.rawQuery(
            "SELECT destination FROM mail WHERE rule_id=0 AND destination!='' ORDER BY id DESC"
                + " LIMIT 1",
            null)) {
      if (cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
    }
    return "";
  }

  static final class Row extends HashMap<String, String> {
    String s(String key) {
      String value = get(key);
      return value == null ? "" : value;
    }

    long n(String key) {
      return s(key).isEmpty() ? 0 : Long.parseLong(s(key));
    }

    long id() {
      return n("id");
    }
  }

  List<Row> rows(String query, String... args) {
    List<Row> result = new ArrayList<>();
    try (Cursor cursor = getReadableDatabase().rawQuery(query, args)) {
      while (cursor.moveToNext()) {
        Row row = new Row();
        for (int i = 0; i < cursor.getColumnCount(); i++)
          row.put(cursor.getColumnName(i), cursor.getString(i));
        result.add(row);
      }
    }
    return result;
  }

  static ContentValues values(Object... pairs) {
    ContentValues v = new ContentValues();
    for (int i = 0; i < pairs.length; i += 2)
      v.put((String) pairs[i], String.valueOf(pairs[i + 1]));
    return v;
  }

  long add(String table, ContentValues values) {
    return getWritableDatabase().insertOrThrow(table, null, values);
  }

  void edit(String table, long id, ContentValues v) {
    getWritableDatabase().update(table, v, "id=?", new String[] {Long.toString(id)});
  }

  Row byId(String table, long id) {
    List<Row> result = rows("SELECT * FROM " + table + " WHERE id=?", Long.toString(id));
    return result.isEmpty() ? null : result.get(0);
  }

  void saveItem(String collection, ContentValues item) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.insertWithOnConflict(
          "collection", null, values("name", collection), SQLiteDatabase.CONFLICT_IGNORE);
      item.put(
          "collection_id", rows("SELECT id FROM collection WHERE name=?", collection).get(0).id());
      add("item", item);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }
}
