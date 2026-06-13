package games.brennan.discordpresence.discord;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal {@code multipart/form-data} body builder for Discord webhook uploads:
 * a {@code payload_json} part plus one file part ({@code files[0]}), as Discord
 * requires when an embed references an {@code attachment://} image.
 *
 * <p>The JDK {@link java.net.http.HttpClient} has no multipart support, so the
 * body is assembled by hand. Pure + deterministic given a fixed boundary, so it
 * is unit-testable.</p>
 */
final class MultipartBody {

    private final String boundary;
    private final byte[] body;

    private MultipartBody(String boundary, byte[] body) {
        this.boundary = boundary;
        this.body = body;
    }

    /** The {@code Content-Type} header value (carries the boundary). */
    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /** The assembled request body. */
    byte[] body() {
        return body;
    }

    /**
     * Build a body with a {@code payload_json} part and a single PNG file part.
     *
     * @param boundary    the multipart boundary (must not occur in the content)
     * @param payloadJson the {@code payload_json} value (the webhook message JSON)
     * @param fileField   the file part's form field name (Discord expects {@code files[0]})
     * @param filename    the attachment filename (referenced as {@code attachment://<filename>})
     * @param png         the PNG bytes
     */
    static MultipartBody jsonWithPng(String boundary, String payloadJson,
                                     String fileField, String filename, byte[] png) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"payload_json\"\r\n");
        writeAscii(out, "Content-Type: application/json\r\n\r\n");
        out.writeBytes(payloadJson.getBytes(StandardCharsets.UTF_8));
        writeAscii(out, "\r\n");
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"" + fileField
                + "\"; filename=\"" + filename + "\"\r\n");
        writeAscii(out, "Content-Type: image/png\r\n\r\n");
        out.writeBytes(png);
        writeAscii(out, "\r\n");
        writeAscii(out, "--" + boundary + "--\r\n");
        return new MultipartBody(boundary, out.toByteArray());
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
