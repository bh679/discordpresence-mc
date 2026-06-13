package games.brennan.discordpresence.discord;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes a row/grid of Minecraft item icons into one PNG, off-thread, for the
 * death-report attachment. Each {@link IconSpec} becomes a slot (dark background);
 * non-empty slots fetch the item's icon from the configured CDN and draw it, with
 * a stack-count badge when {@code > 1}. Missing/modded icons (404 / decode failure)
 * render as an empty slot — best-effort, the embed still posts.
 *
 * <p>Uses headless {@code java.awt}/{@code javax.imageio} (safe on a dedicated
 * server — {@link games.brennan.discordpresence.DiscordPresence} forces
 * {@code java.awt.headless=true} at construction). Runs on the shared
 * {@link DiscordHttp#EXECUTOR}; the icon fetches here are synchronous by design.</p>
 */
final class DeathImageComposer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CELL = 64;        // icon cell size (the CDN serves 64px icons)
    private static final int PAD = 6;          // gap around / between cells
    private static final int MAX_COLUMNS = 9;  // hotbar width
    private static final Color SLOT = new Color(139, 139, 139, 160);
    private static final Color SLOT_BORDER = new Color(40, 40, 40, 200);

    private DeathImageComposer() {}

    /** One slot: an item icon URL ({@code null} = empty slot) and the stack count. */
    record IconSpec(String url, int count) {}

    /** Grid geometry for {@code count} cells. Pure, so it is unit-testable. */
    record Dim(int columns, int rows, int width, int height) {}

    static Dim gridDimensions(int count, int maxColumns, int cell, int pad) {
        if (count <= 0) {
            return new Dim(0, 0, 0, 0);
        }
        int columns = Math.min(count, maxColumns);
        int rows = (count + columns - 1) / columns;
        int width = pad + columns * (cell + pad);
        int height = pad + rows * (cell + pad);
        return new Dim(columns, rows, width, height);
    }

    /**
     * Compose the icons into PNG bytes, or {@code null} when there is nothing to
     * draw (no non-empty slots) or PNG encoding fails.
     */
    static byte[] compose(List<IconSpec> icons) {
        if (icons == null || icons.isEmpty()) {
            return null;
        }
        boolean anyIcon = icons.stream().anyMatch(s -> s.url() != null && !s.url().isBlank());
        if (!anyIcon) {
            return null; // all-empty slots → no point attaching an image
        }

        Dim dim = gridDimensions(icons.size(), MAX_COLUMNS, CELL, PAD);
        BufferedImage img = new BufferedImage(dim.width(), dim.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            Map<String, BufferedImage> cache = new HashMap<>();
            for (int i = 0; i < icons.size(); i++) {
                int col = i % dim.columns();
                int row = i / dim.columns();
                int x = PAD + col * (CELL + PAD);
                int y = PAD + row * (CELL + PAD);
                drawSlot(g, x, y);

                IconSpec spec = icons.get(i);
                if (spec.url() == null || spec.url().isBlank()) {
                    continue;
                }
                BufferedImage icon = cache.computeIfAbsent(spec.url(), DeathImageComposer::fetch);
                if (icon != null) {
                    g.drawImage(icon, x, y, CELL, CELL, null);
                }
                if (spec.count() > 1) {
                    drawCount(g, x, y, spec.count());
                }
            }
        } finally {
            g.dispose();
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(img, "png", baos)) {
                return null;
            }
            return baos.toByteArray();
        } catch (Exception e) {
            LOGGER.warn("Death report image PNG encode failed: {}", e.toString());
            return null;
        }
    }

    private static void drawSlot(Graphics2D g, int x, int y) {
        g.setColor(SLOT);
        g.fillRoundRect(x, y, CELL, CELL, 8, 8);
        g.setColor(SLOT_BORDER);
        g.drawRoundRect(x, y, CELL - 1, CELL - 1, 8, 8);
    }

    private static void drawCount(Graphics2D g, int x, int y, int count) {
        String s = Integer.toString(count);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        int tw = g.getFontMetrics().stringWidth(s);
        int tx = x + CELL - tw - 4;
        int ty = y + CELL - 4;
        g.setColor(new Color(0, 0, 0, 200));
        g.drawString(s, tx + 1, ty + 1);
        g.setColor(Color.WHITE);
        g.drawString(s, tx, ty);
    }

    /** Fetch + decode one icon, or {@code null} on any failure (best-effort). */
    private static BufferedImage fetch(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "DiscordPresence-Mod")
                    .timeout(DiscordHttp.TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = DiscordHttp.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LOGGER.debug("Death report icon fetch failed for {}: {}", url, e.toString());
            return null;
        }
    }
}
