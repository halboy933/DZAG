package ru.mobile.ytdownload;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Downloads two separate, direct HTTP streams and muxes them without re-encoding.
 * Supports MP4 video + M4A audio, and WEBM video + WEBM audio. No DRM bypass.
 * The caller MUST run download() off the main thread and keep app in the foreground.
 */
public final class MuxDownload {
    public interface Listener {
        void onProgress(String stage, int percent);
        void onLog(String line);
    }

    private MuxDownload() { }

    public static Uri download(Context context, String videoUrl, String audioUrl,
                               String fileName, boolean webm, Listener listener)
            throws Exception {
        String extension = webm ? ".webm" : ".mp4";
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new IOException("Invalid output extension");
        }
        File tempVideo = File.createTempFile("yt_vid_", extension, context.getCacheDir());
        File tempAudio = File.createTempFile("yt_aud_", webm ? ".webm" : ".m4a", context.getCacheDir());
        Uri pending = null;
        boolean success = false;
        try {
            listener.onLog("Загружаю видеодорожку: " + extension);
            streamToFile(videoUrl, tempVideo, "Скачивание видео", 0, 52, listener);
            listener.onLog("Загружаю аудиодорожку");
            streamToFile(audioUrl, tempAudio, "Скачивание звука", 52, 88, listener);

            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, webm ? "video/webm" : "video/mp4");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Кино на работе");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            pending = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (pending == null) throw new IOException("Не удалось создать файл в Загрузках");

            listener.onLog("Объединяю видео со звуком без потери качества");
            listener.onProgress("Объединение видео и звука", 89);
            try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(pending, "w")) {
                if (pfd == null) throw new IOException("Не удалось открыть выходной файл");
                mux(tempVideo, tempAudio, pfd, webm, listener);
            }

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(pending, ready, null, null);
            success = true;
            listener.onProgress("Готово! Видео со звуком сохранено", 100);
            listener.onLog("ГОТОВО: " + fileName);
            return pending;
        } finally {
            if (!success && pending != null) {
                try { context.getContentResolver().delete(pending, null, null); }
                catch (Exception ignored) { }
            }
            if (tempVideo.exists() && !tempVideo.delete()) listener.onLog("Не удалось очистить временное видео");
            if (tempAudio.exists() && !tempAudio.delete()) listener.onLog("Не удалось очистить временный звук");
        }
    }

    private static void streamToFile(String url, File destination, String stage,
                                     int fromPercent, int toPercent, Listener listener)
            throws IOException {
        if (!url.startsWith("https://")) throw new IOException("Допустимы только ссылки HTTPS");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", HttpExtractorDownloader.USER_AGENT);
        conn.setRequestProperty("Referer", "https://www.youtube.com/");
        conn.setRequestProperty("Accept-Encoding", "identity");
        long lastUpdate = 0;
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException(stage + ": HTTP " + code + " (попробуйте проверить ссылку заново)");
            long total = conn.getContentLengthLong();
            long bytes = 0;
            try (InputStream input = conn.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[128 * 1024];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    bytes += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 500) {
                        int percent = total > 0 ? fromPercent + (int) ((toPercent - fromPercent) * Math.min(1d, (double) bytes / total)) : fromPercent;
                        String size = String.format(Locale.getDefault(), "%.1f МБ", bytes / 1048576d);
                        listener.onProgress(stage + " · " + size, percent);
                        lastUpdate = now;
                    }
                }
            }
            if (bytes == 0) throw new IOException(stage + ": пустой файл");
            if (total > 0 && bytes < total) throw new IOException(stage + ": загрузка прервана (" + bytes + "/" + total + ")");
            listener.onProgress(stage + " завершено", toPercent);
        } finally {
            conn.disconnect();
        }
    }

    private static void mux(File video, File audio, ParcelFileDescriptor destination,
                            boolean webm, Listener listener) throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            videoExtractor.setDataSource(video.getAbsolutePath());
            audioExtractor.setDataSource(audio.getAbsolutePath());
            int videoSourceTrack = findTrack(videoExtractor, "video/");
            int audioSourceTrack = findTrack(audioExtractor, "audio/");
            if (videoSourceTrack < 0 || audioSourceTrack < 0)
                throw new IOException("В загруженных файлах отсутствует видео или аудио");

            muxer = new MediaMuxer(destination.getFileDescriptor(),
                    webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoSourceTrack));
            int audioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack));
            videoExtractor.selectTrack(videoSourceTrack);
            audioExtractor.selectTrack(audioSourceTrack);
            muxer.start();
            muxerStarted = true;

            ByteBuffer buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int samples = 0;
            while (true) {
                long videoTime = videoExtractor.getSampleTime();
                long audioTime = audioExtractor.getSampleTime();
                if (videoTime < 0 && audioTime < 0) break;
                boolean takeVideo = videoTime >= 0 && (audioTime < 0 || videoTime <= audioTime);
                MediaExtractor extractor = takeVideo ? videoExtractor : audioExtractor;
                int target = takeVideo ? videoTrack : audioTrack;
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) throw new IOException("Ошибка чтения медиапотока при объединении");
                info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
                muxer.writeSampleData(target, buffer, info);
                extractor.advance();
                samples++;
                if (samples % 120 == 0) listener.onProgress("Объединение потоков · " + samples + " кадров/пакетов", 94);
            }
            if (samples == 0) throw new IOException("Потоки оказались пустыми");
            muxer.stop();
            muxerStarted = false;
        } finally {
            if (muxer != null) {
                if (muxerStarted) { try { muxer.stop(); } catch (Exception ignored) { } }
                muxer.release();
            }
            videoExtractor.release();
            audioExtractor.release();
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }
}
