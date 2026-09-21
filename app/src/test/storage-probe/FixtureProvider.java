package probe;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
public class FixtureProvider extends DocumentsProvider {
  static final String[] FILES = {"cavestory/EBOOT.PBP", "cavestory/data.csz", "locoroco/EBOOT.PBP",
      "formats/cube.iso", "formats/cube.cso", "formats/cube.chd", "formats/cube-zlib.chd",
      "formats/cube-lzma.chd", "formats/cube-zstd.chd"};
  public boolean onCreate() {
    try {
      for (String path : FILES) {
        File f = new File(getContext().getFilesDir(), "fixture-source/" + path);
        f.getParentFile().mkdirs();
        if (!f.exists())
          try (InputStream in = getContext().getAssets().open(path);
              OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
          }
      }
      return true;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  public Bundle call(String method, String arg, Bundle extras) {
    if ("grant".equals(method)) {
      getContext().grantUriPermission(getContext().getPackageName(),
          DocumentsContract.buildTreeDocumentUri("com.sbro.emucoreh.probe.files", "root"),
          1 | 64 | 128);
      return new Bundle();
    }
    if ("report".equals(method)) {
      Bundle b = new Bundle();
      b.putInt("dataReads", getContext().getSharedPreferences("probe", 0).getInt("dataReads", 0));
      return b;
    }
    return super.call(method, arg, extras);
  }
  String[] columns(String[] p) {
    return p != null ? p
                     : new String[] {"document_id", "_display_name", "mime_type", "flags", "_size",
                           "last_modified"};
  }
  void row(MatrixCursor c, String id) {
    String name = id.equals("root") ? "Validation" : id.substring(id.lastIndexOf('/') + 1);
    boolean dir = id.equals("root") || id.split("/").length == 2;
    File file = new File(
        getContext().getFilesDir(), "fixture-source/" + id.substring(Math.min(5, id.length())));
    MatrixCursor.RowBuilder r = c.newRow();
    for (String col : c.getColumnNames()) {
      Object v = null;
      switch (col) {
        case "document_id":
          v = id;
          break;
        case "_display_name":
          v = name;
          break;
        case "mime_type":
          v = dir ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
          break;
        case "flags":
          v = 0;
          break;
        case "_size":
          v = dir ? 0 : file.length();
          break;
        case "last_modified":
          v = 1720000000000L;
          break;
      }
      r.add(col, v);
    }
  }
  public Cursor queryRoots(String[] p) {
    return new MatrixCursor(p != null ? p : new String[] {"root_id"});
  }
  public Cursor queryDocument(String id, String[] p) {
    MatrixCursor c = new MatrixCursor(columns(p));
    row(c, id);
    return c;
  }
  public Cursor queryChildDocuments(String id, String[] p, String sort) {
    MatrixCursor c = new MatrixCursor(columns(p));
    if (id.equals("root")) {
      for (String dir : new String[] {"cavestory", "locoroco", "formats"}) row(c, "root/" + dir);
    } else
      for (String path : FILES)
        if (("root/" + path).startsWith(id + "/"))
          row(c, "root/" + path);
    return c;
  }
  public boolean isChildDocument(String parent, String child) {
    return child.startsWith(parent + "/");
  }
  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    String path = id.substring(5);
    boolean valid = false;
    for (String f : FILES)
      if (f.equals(path))
        valid = true;
    if (!valid || !mode.equals("r"))
      throw new FileNotFoundException();
    if (path.equals("cavestory/data.csz")) {
      android.content.SharedPreferences p = getContext().getSharedPreferences("probe", 0);
      p.edit().putInt("dataReads", p.getInt("dataReads", 0) + 1).commit();
    }
    return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), "fixture-source/" + path),
        ParcelFileDescriptor.MODE_READ_ONLY);
  }
}
