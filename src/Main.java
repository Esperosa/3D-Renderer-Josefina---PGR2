import engine.core.Engine;
import engine.core.PackageSmokeVerifier;

/**
 *tuto spouštím celý 3D render engine.
 */
public class Main {

    public static void main(String[] args) {
        boolean launchFullscreen = false;
        int explicitRenderWidth = 0;
        int explicitRenderHeight = 0;
        if (args != null && args.length > 0) {
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                    System.out.println("3D-Render-Physics");
                    System.out.println("Usage: java Main");
                    System.out.println("Optional CLI parameters:");
                    System.out.println("  --help           Show this help.");
                    System.out.println("  --package-smoke  Verify packaged runtime assets without launching the UI.");
                    System.out.println("  --fullscreen     Launch the normal editor UI in fullscreen mode.");
                    System.out.println("  --internal-render=WxH  Force explicit internal preview render target.");
                    return;
                }
                if ("--package-smoke".equalsIgnoreCase(arg)) {
                    PackageSmokeVerifier.run();
                    return;
                }
                if ("--fullscreen".equalsIgnoreCase(arg)) {
                    launchFullscreen = true;
                    continue;
                }
                if (arg.regionMatches(true, 0, "--internal-render=", 0, "--internal-render=".length())) {
                    int[] parsed = parseResolution(arg.substring("--internal-render=".length()));
                    if (parsed != null) {
                        explicitRenderWidth = parsed[0];
                        explicitRenderHeight = parsed[1];
                    }
                }
            }
        }

        Engine engine = new Engine();
        engine.setLaunchFullscreen(launchFullscreen);
        engine.setExplicitPreviewRenderResolution(explicitRenderWidth, explicitRenderHeight);
        engine.start();
    }

    private static int[] parseResolution(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim().toLowerCase();
        int split = normalized.indexOf('x');
        if (split <= 0 || split >= normalized.length() - 1) {
            return null;
        }
        try {
            int width = Integer.parseInt(normalized.substring(0, split).trim());
            int height = Integer.parseInt(normalized.substring(split + 1).trim());
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new int[]{width, height};
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}