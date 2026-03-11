import engine.core.Engine;

/**
 * Tady spouštím celý 3D render engine.
 */
public class Main {

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            String arg0 = args[0];
            if ("--help".equalsIgnoreCase(arg0) || "-h".equalsIgnoreCase(arg0)) {
                System.out.println("3D-Render-Physics");
                System.out.println("Usage: java Main");
                System.out.println("No CLI parameters are currently required.");
                return;
            }
        }

        Engine engine = new Engine();
        engine.start();
    }
}
