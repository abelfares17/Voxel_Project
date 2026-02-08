package engine.util;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.lwjgl.opengl.GL11.*;

/**
 * Utility class for capturing screenshots from the OpenGL framebuffer.
 * Screenshots are saved to the "captures" folder with timestamped filenames.
 */
public class ScreenCapture {
    private static final Path CAPTURES_DIR = Path.of("captures");
    private static final DateTimeFormatter FILENAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /**
     * Capture the current OpenGL framebuffer and save it as a PNG file.
     *
     * @param width  Framebuffer width in pixels
     * @param height Framebuffer height in pixels
     * @return Path to the saved screenshot, or null if capture failed
     */
    public static Path capture(int width, int height) {
        try {
            // Ensure captures directory exists
            Files.createDirectories(CAPTURES_DIR);

            // Generate filename with timestamp
            String timestamp = LocalDateTime.now().format(FILENAME_FORMAT);
            Path outputPath = CAPTURES_DIR.resolve("screenshot_" + timestamp + ".png");

            // Handle duplicate filenames (multiple screenshots in same second)
            int counter = 1;
            while (Files.exists(outputPath)) {
                outputPath = CAPTURES_DIR.resolve("screenshot_" + timestamp + "_" + counter + ".png");
                counter++;
            }

            // Read pixels from OpenGL framebuffer
            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

            // Convert to BufferedImage (OpenGL has origin at bottom-left, flip vertically)
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // Read from bottom-up (OpenGL) and write top-down (image)
                    int srcIndex = ((height - 1 - y) * width + x) * 4;
                    int r = buffer.get(srcIndex) & 0xFF;
                    int g = buffer.get(srcIndex + 1) & 0xFF;
                    int b = buffer.get(srcIndex + 2) & 0xFF;
                    int a = buffer.get(srcIndex + 3) & 0xFF;
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    image.setRGB(x, y, argb);
                }
            }

            // Save as PNG
            ImageIO.write(image, "PNG", outputPath.toFile());
            System.out.println("Screenshot saved: " + outputPath.toAbsolutePath());
            return outputPath;

        } catch (IOException e) {
            System.err.println("Failed to save screenshot: " + e.getMessage());
            return null;
        }
    }
}
