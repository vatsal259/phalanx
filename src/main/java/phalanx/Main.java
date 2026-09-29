package phalanx;

public final class Main {
  public static void main(String[] args) throws Exception {
    String command = args.length == 0 ? "broker" : args[0];
    switch (command) {
      case "broker" -> Broker.start();
      case "host" -> NativeHost.start();
      case "agent" -> Agent.start();
      default -> System.err.println("Use broker, host, or agent.");
    }
  }

  private Main() {}
}
