package dev.mobilepa;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

final class Attachments {
  static final ExecutorService IO = Executors.newSingleThreadExecutor();

  static final class Saved {
    final String name, file, mime;

    Saved(String name, String file, String mime) {
      this.name = name;
      this.file = file;
      this.mime = mime;
    }
  }

  static Saved copy(Context context, Uri uri) throws IOException {
    if (!"content".equals(uri.getScheme()))
      throw new IOException("Choose a file from the system picker.");
    ContentResolver resolver = context.getContentResolver();
    String name = null;
    try (Cursor c =
        resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
    }
    File dir = new File(context.getFilesDir(), "attachments");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Storage is unavailable.");
    File file = File.createTempFile("saved-", ".bin", dir);
    String mime;
    boolean complete = false;
    try (InputStream in = resolver.openInputStream(uri);
        OutputStream out = new FileOutputStream(file)) {
      if (in == null) throw new IOException("The source app could not open the file.");
      byte[] buf = new byte[32768];
      int count;
      long size = 0;
      while ((count = in.read(buf)) != -1) {
        size += count;
        if (size > 100L * 1024 * 1024) throw new IOException("Choose a file smaller than 100 MB.");
        out.write(buf, 0, count);
      }
      out.flush();
      mime = detectType(file);
      complete = true;
    } finally {
      if (!complete) file.delete();
    }
    if (name == null) name = mime.equals("application/pdf") ? "Document.pdf" : "Photo";
    return new Saved(name, file.getAbsolutePath(), mime);
  }

  private static String detectType(File file) throws IOException {
    try (DataInputStream stream = new DataInputStream(new FileInputStream(file))) {
      byte[] header = new byte[5];
      if (file.length() >= header.length) {
        stream.readFully(header);
        if (Arrays.equals(header, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
          return "application/pdf";
      }
    }
    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    android.graphics.BitmapFactory.decodeFile(file.getPath(), options);
    if (options.outWidth > 0 && options.outHeight > 0 && options.outMimeType != null)
      return options.outMimeType;
    throw new IOException("Choose an image or PDF.");
  }
}
