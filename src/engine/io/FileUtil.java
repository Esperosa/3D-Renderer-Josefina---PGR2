package engine.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tady držím základní vstupně-výstupní souborové pomocníky bez externích knihoven.
 */
public final class FileUtil {

    private FileUtil() {}

    public static List<String> readLines(String filePath) {
        try {
            return Files.readAllLines(Path.of(filePath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read lines from " + filePath, e);
        }
    }

    public static byte[] readBytes(String filePath) {
        try {
            return Files.readAllBytes(Path.of(filePath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read bytes from " + filePath, e);
        }
    }

    public static void writeBytes(String filePath, byte[] data) {
        try {
            Files.write(Path.of(filePath), data);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write bytes to " + filePath, e);
        }
    }

    public static boolean exists(String filePath) {
        return Files.exists(Path.of(filePath));
    }

    public static String getExtension(String filePath) {
        String name = Path.of(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }
}
