package dev.mobilepa;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class Store extends SQLiteOpenHelper {
  private static Store instance;

  static synchronized Store get(Context c) {
    if (instance == null) instance = new Store(c.getApplicationContext());
    return instance;
  }

  private Store(Context c) {
    super(c, "mobilepa.db", null, 2);
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
            + " last_checked INTEGER NOT NULL DEFAULT 0, last_result TEXT NOT NULL DEFAULT '')");
    db.execSQL(
        "CREATE TABLE mail(id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT UNIQUE NOT NULL,"
            + " rule_id INTEGER, sender TEXT NOT NULL, body TEXT NOT NULL, phrase TEXT NOT NULL,"
            + " destination TEXT NOT NULL, received INTEGER NOT NULL, state TEXT NOT NULL, detail"
            + " TEXT NOT NULL DEFAULT '')");
    db.execSQL("CREATE INDEX mail_state ON mail(state,id)");
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    if (from == 1 && to == 2) {
      db.execSQL("ALTER TABLE rule ADD COLUMN last_checked INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE rule ADD COLUMN last_result TEXT NOT NULL DEFAULT ''");
    } else {
      throw new IllegalStateException("Unknown database version");
    }
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
