package com.example.reports.gotenberg;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An open PDF response body. The caller owns it and must close it, which releases the HTTP
 * connection. {@code contentLength} is -1 when the server did not send one.
 */
public record PdfStream(InputStream body, long contentLength, Closeable onClose) implements Closeable {

    public long transferTo(OutputStream out) throws IOException {
        try (this) {
            return body.transferTo(out);
        }
    }

    public long saveTo(Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            return transferTo(out);
        }
    }

    @Override
    public void close() throws IOException {
        try (onClose) {
            body.close();
        }
    }
}
