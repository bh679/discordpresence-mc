package games.brennan.discordpresence.discord;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal {@code multipart/form-data} body builder for Discord webhook uploads:
 * a {@code payload_json} part plus one or more file parts ({@code files[0]},
 * {@code files[1]}, …), as Discord requires when a message carries attachments
 * (e.g. an embed referencing an {@code attachment://} image, or plain file uploads).
 *
 * <p>The JDK {@link java.net.http.HttpClient} has no multipart support, so the
 * body is assembled by hand. Pure + deterministic given a fixed boundary, so it
 * is unit-testable.</p>
 */
final class MultipartBody {

    /** One file part: its form field name ({@code files[N]}), filename, MIME type, and bytes. */
    record FilePart(String fieldName, String filename, String mime, byte[] bytes) {}

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
        return jsonWithFiles(boundary, payloadJson, List.of(new FilePart(fileField, filename, "image/png", png)));
    }

    /**
     * Build a body with a {@code payload_json} part followed by N file parts (in order). Each
     * part's field name should be {@code files[0]}, {@code files[1]}, … and is referenced from the
     * payload (or simply shown by Discord as an attachment) by its {@code filename}.
     *
     * @param boundary    the multipart boundary (must not occur in the content)
     * @param payloadJson the {@code payload_json} value (the webhook message JSON)
     * @param files       the file parts, each carrying its field name, filename, MIME type, and bytes
     */
    static MultipartBody jsonWithFiles(String boundary, String payloadJson, List<FilePart> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"payload_json\"\r\n");
        writeAscii(out, "Content-Type: application/json\r\n\r\n");
        out.writeBytes(payloadJson.getBytes(StandardCharsets.UTF_8));
        writeAscii(out, "\r\n");
        for (FilePart f : files) {
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"" + f.fieldName()
                    + "\"; filename=\"" + f.filename() + "\"\r\n");
            writeAscii(out, "Content-Type: " + f.mime() + "\r\n\r\n");
            out.writeBytes(f.bytes());
            writeAscii(out, "\r\n");
        }
        writeAscii(out, "--" + boundary + "--\r\n");
        return new MultipartBody(boundary, out.toByteArray());
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
