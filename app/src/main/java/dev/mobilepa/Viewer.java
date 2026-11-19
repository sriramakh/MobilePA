package dev.mobilepa;

import android.app.AlertDialog;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.widget.*;
import java.io.*;

final class Viewer {
  static void show(MainActivity a, String file, String type, String title) {
    LinearLayout box = a.column();
    ImageView image = new ImageView(a);
    image.setAdjustViewBounds(true);
    int width = Math.min(1600, a.getResources().getDisplayMetrics().widthPixels);
    box.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
    if (type.equals("application/pdf")) {
      try {
        PdfRenderer pdf =
            new PdfRenderer(
                ParcelFileDescriptor.open(new File(file), ParcelFileDescriptor.MODE_READ_ONLY));
        LinearLayout controls = a.horizontal();
        int[] page = {0};
        TextView index = a.label("", 14, DeskUi.INK, true);
        Runnable draw =
            () -> {
              try (PdfRenderer.Page p = pdf.openPage(page[0])) {
                float scale = Math.min((float) width / p.getWidth(), 1800f / p.getHeight());
                Bitmap bitmap =
                    Bitmap.createBitmap(
                        Math.max(1, (int) (p.getWidth() * scale)),
                        Math.max(1, (int) (p.getHeight() * scale)),
                        Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(0xffffffff);
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                image.setImageBitmap(bitmap);
                index.setText((page[0] + 1) + " / " + pdf.getPageCount());
              }
            };
        a.flex(
            controls,
            a.action(
                "Previous",
                () -> {
                  if (page[0] > 0) {
                    page[0]--;
                    draw.run();
                  }
                },
                false));
        controls.addView(index);
        a.flex(
            controls,
            a.action(
                "Next",
                () -> {
                  if (page[0] + 1 < pdf.getPageCount()) {
                    page[0]++;
                    draw.run();
                  }
                },
                false));
        a.wide(box, controls);
        draw.run();
        display(a, title, box).setOnDismissListener(d -> pdf.close());
      } catch (Exception e) {
        a.info("Preview unavailable", "This PDF may be encrypted or damaged.");
      }
    } else {
      BitmapFactory.Options opt = new BitmapFactory.Options();
      opt.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file, opt);
      opt.inSampleSize = 1;
      while (Math.max(opt.outWidth, opt.outHeight) / opt.inSampleSize > 2048) opt.inSampleSize *= 2;
      opt.inJustDecodeBounds = false;
      Bitmap bitmap = BitmapFactory.decodeFile(file, opt);
      if (bitmap == null) {
        a.info("Preview unavailable", "This image format could not be opened.");
        return;
      }
      image.setImageBitmap(bitmap);
      display(a, title, box);
    }
  }

  private static AlertDialog display(MainActivity a, String title, LinearLayout box) {
    AlertDialog dialog =
        new AlertDialog.Builder(a)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Close", null)
            .show();
    dialog
        .getWindow()
        .setLayout(-1, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.85));
    return dialog;
  }
}
