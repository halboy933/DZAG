package ru.mobile.ytdownload;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** HTTP implementation for NewPipeExtractor: keeps request method, headers and body. */
public final class HttpExtractorDownloader extends Downloader {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36";

    @Override
    public Response execute(Request req) throws IOException {
        String target = req.url();
        if (!target.startsWith("https://")) {
            throw new IOException("Only HTTPS requests are allowed");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        try {
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod(req.httpMethod());
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "identity");
            Map<String, List<String>> headers = req.headers();
            if (headers != null) {
                for (Map.Entry<String, List<String>> item : headers.entrySet()) {
                    if (item.getKey() == null || item.getValue() == null) continue;
                    for (String value : item.getValue()) {
                        if (value != null) conn.addRequestProperty(item.getKey(), value);
                    }
                }
            }
            byte[] requestBody = req.dataToSend();
            if (requestBody != null && requestBody.length > 0) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(requestBody);
                }
            }
            int code = conn.getResponseCode();
            String responseBody = "";
            if (!"HEAD".equalsIgnoreCase(req.httpMethod())) {
                InputStream input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (input != null) {
                    try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[16 * 1024];
                        int n;
                        // Extractor expects text/JSON rather than video content.
                        while ((n = in.read(buffer)) != -1) {
                            if (out.size() + n > 15 * 1024 * 1024) {
                                throw new IOException("Response too large for metadata fetch");
                            }
                            out.write(buffer, 0, n);
                        }
                        responseBody = out.toString(StandardCharsets.UTF_8.name());
                    }
                }
            }
            String latestUrl = conn.getURL().toString();
            return new Response(code, conn.getResponseMessage(), conn.getHeaderFields(), responseBody, latestUrl);
        } finally {
            conn.disconnect();
        }
    }
}
