package probe;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.io.File;
public class Probe extends Instrumentation {
  public void onCreate(Bundle b) {
    super.onCreate(b);
    start();
  }
  Object bridge, storage;
  Class<?> c, sc;
  StringBuilder report = new StringBuilder();
  Object call(String name, Class<?>[] types, Object... args) throws Exception {
    return c.getMethod(name, types).invoke(bridge, args);
  }
  String prepare(Uri uri) throws Exception {
    return (String) sc.getMethod("prepare", android.content.Context.class, String.class)
        .invoke(storage, getTargetContext(), uri.toString());
  }
  long cacheBytes(File f) {
    if (!f.isDirectory())
      return f.length();
    long n = 0;
    File[] fs = f.listFiles();
    if (fs != null)
      for (File x : fs) n += cacheBytes(x);
    return n;
  }
  public void onStart() {
    Bundle out = new Bundle();
    long session = 0;
    boolean passed = false;
    try {
      Class<?> factory = getTargetContext().getClassLoader().loadClass(
          "com.sbro.emucoreh.core.SaveStatePreviewKt");
      android.graphics.Bitmap bitmap =
          (android.graphics.Bitmap) factory
              .getMethod("createSaveStatePreviewBitmap", int.class, int.class)
              .invoke(null, 3, 1);
      bitmap.copyPixelsFromBuffer(
          java.nio.ByteBuffer.wrap(new byte[] {100, 50, 25, 0, 40, 80, 120, 16, 120, 90, 60, 32}));
      java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png);
      byte[] bytes = png.toByteArray();
      android.graphics.Bitmap decoded =
          android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      if (decoded.hasAlpha() || decoded.getPixel(0, 0) != android.graphics.Color.rgb(100, 50, 25)
          || decoded.getPixel(1, 0) != android.graphics.Color.rgb(40, 80, 120)
          || decoded.getPixel(2, 0) != android.graphics.Color.rgb(120, 90, 60))
        throw new Exception("PNG alpha/RGB regression");
      bitmap.recycle();
      decoded.recycle();
      report.append("Production preview bitmap: alpha 0/16/32 -> opaque PNG with exact RGB PASS\n");
      c = getTargetContext().getClassLoader().loadClass("com.sbro.emucoreh.core.NativeCoreBridge");
      Class<?> u = Class.forName("sun.misc.Unsafe");
      java.lang.reflect.Field uf = u.getDeclaredField("theUnsafe");
      uf.setAccessible(true);
      bridge = u.getMethod("allocateInstance", Class.class).invoke(uf.get(null), c);
      sc = getTargetContext().getClassLoader().loadClass("com.sbro.emucoreh.core.PspStorageBridge");
      storage = sc.getField("INSTANCE").get(null);
      File root = new File(getTargetContext().getFilesDir(), "ppsspp");
      call("nativeInit", new Class[] {String.class, String.class, String.class},
          new File(root, "system").toString(), new File(root, "save").toString(),
          new File(root, "assets").toString());
      call("nativeSetOption", new Class[] {String.class, String.class}, "ppsspp_backend", "opengl");
      session = (Long) call("createSession", new Class[] {});
      java.lang.reflect.Method read = c.getMethod("readGameAsset", String.class, int.class);
      Uri fixtureTree =
          DocumentsContract.buildTreeDocumentUri("com.sbro.emucoreh.probe.files", "root");
      getTargetContext().getContentResolver().call(
          Uri.parse("content://com.sbro.emucoreh.probe.files"), "grant", null, null);
      getTargetContext().getContentResolver().takePersistableUriPermission(fixtureTree, 1);
      long before = cacheBytes(getTargetContext().getCacheDir());
      int count = 0;
      for (String image : new String[] {"cube.iso", "cube.cso", "cube.chd", "cube-zlib.chd",
               "cube-lzma.chd", "cube-zstd.chd"}) {
        String path = prepare(
            DocumentsContract.buildDocumentUriUsingTree(fixtureTree, "root/formats/" + image));
        byte[] data = (byte[]) read.invoke(bridge, path, 0);
        if (data == null)
          throw new Exception("No VFS SFO: " + image);
        report.append("SAF SFO PASS ").append(image).append("\n");
        count++;
      }
      Uri demo =
          DocumentsContract.buildDocumentUriUsingTree(fixtureTree, "root/cavestory/EBOOT.PBP");
      String mapped = prepare(demo);
      if (mapped == null)
        throw new Exception("No homebrew mapping");
      String parent = mapped.substring(0, mapped.lastIndexOf('/'));
      String[] names = (String[]) sc.getMethod("list", String.class).invoke(null, parent);
      if (names == null || names.length != 2)
        throw new Exception("No homebrew siblings");
      int fd = (Integer) sc.getMethod("open", String.class).invoke(null, parent + "/DATA.CSZ");
      if (fd < 0)
        throw new Exception("Case-insensitive sibling open failed");
      android.os.ParcelFileDescriptor.adoptFd(fd).close();
      if (sc.getMethod("stat", String.class).invoke(null, parent + "/../../../../etc/passwd")
          != null)
        throw new Exception("Traversal escaped tree");
      report.append("Homebrew sibling access + traversal checks PASS\n");
      int initialReads =
          getTargetContext()
              .getContentResolver()
              .call(Uri.parse("content://com.sbro.emucoreh.probe.files"), "report", null, null)
              .getInt("dataReads");
      android.os.HandlerThread thread = new android.os.HandlerThread("probe-images");
      thread.start();
      android.media.ImageReader images = android.media.ImageReader.newInstance(480, 272, 1, 3);
      java.util.concurrent.atomic.AtomicInteger frames =
          new java.util.concurrent.atomic.AtomicInteger();
      images.setOnImageAvailableListener(reader -> {
        android.media.Image image = reader.acquireLatestImage();
        if (image != null) {
          frames.incrementAndGet();
          image.close();
        }
      }, new android.os.Handler(thread.getLooper()));
      call("destroySession", new Class[] {long.class}, session);
      session = 0;
      for (String image :
          new String[] {"cavestory/EBOOT.PBP", "locoroco/EBOOT.PBP", "formats/cube.cso",
              "formats/cube-zlib.chd", "formats/cube-lzma.chd", "formats/cube-zstd.chd"}) {
        mapped = prepare(DocumentsContract.buildDocumentUriUsingTree(fixtureTree, "root/" + image));
        session = (Long) call("createSession", new Class[] {});
        call("setSurface", new Class[] {long.class, android.view.Surface.class, int.class}, session,
            images.getSurface(), 2);
        int loaded =
            (Integer) call("loadDisc", new Class[] {long.class, String.class}, session, mapped);
        if (loaded != 0)
          throw new Exception(image + " load failed " + loaded);
        int start = frames.get();
        for (int i = 0; i < 240; i++) {
          call("runFrame", new Class[] {long.class}, session);
          Thread.sleep(16);
        }
        if (frames.get() - start < 60)
          throw new Exception("Insufficient rendered frames: " + (frames.get() - start));
        report.append("SAF BOOT ")
            .append(image)
            .append(" rendered=")
            .append(frames.get() - start)
            .append("\n");
        call("destroySession", new Class[] {long.class}, session);
        session = 0;
      }
      int reads =
          getTargetContext()
              .getContentResolver()
              .call(Uri.parse("content://com.sbro.emucoreh.probe.files"), "report", null, null)
              .getInt("dataReads");
      if (reads <= initialReads)
        throw new Exception("Homebrew did not read data.csz");
      report.append("data.csz provider opens=").append(reads).append("\n");
      images.close();
      thread.quitSafely();
      long after = cacheBytes(getTargetContext().getCacheDir());
      report.append("Cache before=").append(before).append(" after=").append(after).append("\n");
      for (String folder : new String[] {"swanstation-cue", "swanstation-disc", "psp-achievements"})
        if (new File(getTargetContext().getCacheDir(), folder).exists())
          throw new Exception("Legacy image cache created: " + folder);
      report.append("CACHE FILES ")
          .append(java.util.Arrays.toString(getTargetContext().getCacheDir().list()))
          .append("\n");
      out.putString(
          "stream", report.toString() + "Verified " + count + " SAF images\nSTORAGE_PROBE_PASS\n");
      passed = true;
    } catch (Throwable e) {
      java.io.StringWriter sw = new java.io.StringWriter();
      e.printStackTrace(new java.io.PrintWriter(sw));
      out.putString("stream", report.toString() + sw.toString());
    } finally {
      if (session != 0)
        try {
          call("destroySession", new Class[] {long.class}, session);
        } catch (Exception ignored) {
        }
    }
    finish(passed ? -1 : 0, out);
  }
}
