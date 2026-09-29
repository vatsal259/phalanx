package phalanx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Chrome talks to this process. It forwards messages to the broker and never prints secrets. */
public final class NativeHost {
  public static void start() throws Exception {
    Socket broker;
    try {
      broker = new Socket("127.0.0.1", Config.HOST_PORT);
    } catch (IOException exception) {
      System.err.println("Start the broker first.");
      return;
    }
    Thread toChrome = new Thread(() -> pumpToChrome(broker), "to-chrome");
    toChrome.setDaemon(true);
    toChrome.start();

    OutputStream toBroker = broker.getOutputStream();
    while (true) {
      String message = readFrame(System.in);
      if (message == null) {
        break;
      }
      toBroker.write(message.getBytes(StandardCharsets.UTF_8));
      toBroker.write('\n');
      toBroker.flush();
    }
  }

  private static void pumpToChrome(Socket broker) {
    try (BufferedReader lines = new BufferedReader(new InputStreamReader(broker.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = lines.readLine()) != null) {
        writeFrame(System.out, line);
      }
    } catch (IOException exception) {
      exception.printStackTrace();
    }
  }

  static void writeFrame(OutputStream out, String json) throws IOException {
    byte[] data = json.getBytes(StandardCharsets.UTF_8);
    ByteBuffer length = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(data.length);
    out.write(length.array());
    out.write(data);
    out.flush();
  }

  static String readFrame(InputStream in) throws IOException {
    byte[] lengthBytes = in.readNBytes(4);
    if (lengthBytes.length < 4) {
      return null;
    }
    int length = ByteBuffer.wrap(lengthBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
    if (length < 0 || length > 1_000_000) {
      throw new IOException("bad native message length");
    }
    byte[] data = in.readNBytes(length);
    if (data.length < length) {
      return null;
    }
    return new String(data, StandardCharsets.UTF_8);
  }

  private NativeHost() {}
}
