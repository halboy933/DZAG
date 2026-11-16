package ru.mobile.ytdownload;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight standalone Android front end for NewPipeExtractor.
 * v1 downloads ONLY ready-made video+audio progressive streams, or audio-only streams.
 * It never claims a silent video-only stream is complete.
 */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(13, 19, 33);
    private static final int PANEL = Color.rgb(25, 36, 56);
    private static final int TEXT = Color.rgb(244, 247, 253);
    private static final int SUBTEXT = Color.rgb(165, 182, 205);
    private static final int BLUE = Color.rgb(77, 174, 252);
    private static final int HOT_PINK = Color.rgb(255, 71, 194);
    private static final int NEON_YELLOW = Color.rgb(255, 236, 105);


    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final ArrayList<Choice> choices = new ArrayList<>();
    private EditText linkField;
    private TextView titleView, progressText, logView, savePathText;
    private Spinner qualitySpinner;
    private ProgressBar progress;
    private Button analyzeButton, downloadButton;
    private String videoTitle = "";
    private long lastDownloadId = -1;
    private boolean analyzing;
    private final Runnable progressPoll = new Runnable() {
        @Override public void run() {
            if (lastDownloadId >= 0) pollDownload(lastDownloadId);
            ui.postDelayed(this, 1000);
        }
    };

    private static final class Choice {
        final Stream stream;
        final String label;
        final String extension;
        final String mime;
        final boolean audio;
        final Stream companionAudio;

        Choice(Stream stream, String label, String extension, String mime, boolean audio) {
            this(stream, label, extension, mime, audio, null);
        }
        Choice(Stream stream, String label, String extension, String mime, boolean audio, Stream companionAudio) {
            this.stream = stream;
            this.label = label;
            this.extension = extension;
            this.mime = mime;
            this.audio = audio;
            this.companionAudio = companionAudio;
        }
        @Override public String toString() { return label; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(0);
        NewPipe.init(new HttpExtractorDownloader());
        buildInterface();
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        lastDownloadId = prefs.getLong("download_id", -1);
        String shared = sharedLink(getIntent());
        if (shared != null) linkField.setText(shared);
        ui.post(progressPoll);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String shared = sharedLink(intent);
        if (shared != null) linkField.setText(shared);
    }

    private String sharedLink(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group() : null;
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        TextView appTitle = text("ЮТ ЗАГРУЗЧИК · V3", 18, TEXT, true);
        appTitle.setLetterSpacing(0.15f);
        root.addView(appTitle);
        TextView subtitle = text("Твой карманный киносеанс \u25b6", 13, SUBTEXT, false);
        addMargin(root, subtitle, 0, 4, 0, 18);

        // Main header, deliberately placed ABOVE the URL field.
        // Gradient type and shadow draw directly on Android TextView (no image asset or network needed).
        LinearLayout neonCard = new LinearLayout(this);
        neonCard.setOrientation(LinearLayout.VERTICAL);
        neonCard.setGravity(Gravity.CENTER);
        neonCard.setPadding(dp(16), dp(21), dp(16), dp(18));
        GradientDrawable neonBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(63, 23, 106), Color.rgb(34, 27, 92), Color.rgb(12, 69, 92)});
        neonBackground.setCornerRadius(dp(20));
        neonBackground.setStroke(dp(2), HOT_PINK);
        neonCard.setBackground(neonBackground);

        TextView mini = text("\u2728 ПЛАН НА СЕГОДНЯ \u2728", 12, Color.rgb(159, 246, 255), true);
        mini.setGravity(Gravity.CENTER);
        mini.setLetterSpacing(0.12f);
        neonCard.addView(mini);

        TextView neonHeadline = text("ВОТ ЧТО СЕГОДНЯ\nБУДУ СМОТРЕТЬ\nНА РАБОТЕ))", 24, NEON_YELLOW, true);
        neonHeadline.setGravity(Gravity.CENTER);
        neonHeadline.setLetterSpacing(0.025f);
        neonHeadline.setIncludeFontPadding(true);
        neonHeadline.setLineSpacing(dp(1), 1f);
        neonHeadline.setShadowLayer(dp(8), 0, 0, HOT_PINK);
        neonHeadline.getPaint().setShader(new LinearGradient(
                0, 0, dp(290), dp(104),
                new int[]{NEON_YELLOW, Color.rgb(255, 140, 205), Color.rgb(88, 239, 255)},
                null, Shader.TileMode.CLAMP));
        LinearLayout.LayoutParams headlineParams = new LinearLayout.LayoutParams(-1, -2);
        headlineParams.topMargin = dp(9);
        neonCard.addView(neonHeadline, headlineParams);

        TextView emojiLine = text("\ud83c\udf7f  Найдём что-нибудь интересное  \ud83c\udf7f", 12, Color.rgb(216, 223, 255), false);
        emojiLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emojiParams = new LinearLayout.LayoutParams(-1, -2);
        emojiParams.topMargin = dp(10);
        neonCard.addView(emojiLine, emojiParams);
        addMargin(root, neonCard, 0, 0, 0, 22);

        TextView inputCaption = text("ССЫЛКА НА ВИДЕО", 12, SUBTEXT, true);
        addMargin(root, inputCaption, 0, 0, 0, 8);
        linkField = new EditText(this);
        linkField.setSingleLine(false);
        linkField.setMinLines(2);
        linkField.setMaxLines(3);
        linkField.setTextSize(15);
        linkField.setTextColor(TEXT);
        linkField.setHintTextColor(SUBTEXT);
        linkField.setHint("https://youtu.be/...");
        linkField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        linkField.setPadding(dp(14), dp(10), dp(14), dp(10));
        linkField.setBackground(round(PANEL, 12));
        root.addView(linkField, new LinearLayout.LayoutParams(-1, dp(74)));

        LinearLayout urlActions = new LinearLayout(this);
        urlActions.setOrientation(LinearLayout.HORIZONTAL);
        addMargin(root, urlActions, 0, 10, 0, 0);
        Button paste = button("Вставить", PANEL);
        urlActions.addView(paste, new LinearLayout.LayoutParams(0, dp(49), 1));
        analyzeButton = button("Проверить ссылку", BLUE);
        LinearLayout.LayoutParams analyzeParams = new LinearLayout.LayoutParams(0, dp(49), 2);
        analyzeParams.leftMargin = dp(9);
        urlActions.addView(analyzeButton, analyzeParams);
        paste.setOnClickListener(v -> pasteLink());
        analyzeButton.setOnClickListener(v -> analyzeLink());

        titleView = text("Название и качество появятся после проверки ссылки", 15, SUBTEXT, false);
        titleView.setPadding(dp(15), dp(15), dp(15), dp(15));
        titleView.setBackground(round(PANEL, 12));
        addMargin(root, titleView, 0, 22, 0, 17);

        TextView qualityCaption = text("ФОРМАТ / КАЧЕСТВО", 12, SUBTEXT, true);
        addMargin(root, qualityCaption, 0, 0, 0, 8);
        qualitySpinner = new Spinner(this);
        qualitySpinner.setPadding(dp(8), dp(5), dp(8), dp(5));
        qualitySpinner.setBackground(round(PANEL, 12));
        root.addView(qualitySpinner, new LinearLayout.LayoutParams(-1, dp(58)));
        updateChoices();

        downloadButton = button("Скачать", BLUE);
        downloadButton.setEnabled(false);
        addMargin(root, downloadButton, 0, 20, 0, 14);
        downloadButton.setOnClickListener(v -> startDownload());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        progressText = text("Загрузок пока нет", 13, SUBTEXT, false);
        addMargin(root, progressText, 0, 8, 0, 0);
        savePathText = text("Папка: Загрузки / ЮТ Загрузчик", 13, SUBTEXT, false);
        addMargin(root, savePathText, 0, 8, 0, 15);

        TextView tip = text("Важно: выбирайте вариант «видео со звуком». Отдельные дорожки MP4 или WEBM объединяются автоматически. Во время загрузки держите приложение открытым. Если выбран «Только звук», видео не сохранится.", 13, SUBTEXT, false);
        tip.setPadding(dp(14), dp(14), dp(14), dp(14));
        tip.setBackground(round(PANEL, 12));
        addMargin(root, tip, 0, 5, 0, 23);

        TextView logHeader = text("ЖУРНАЛ", 12, SUBTEXT, true);
        root.addView(logHeader);
        logView = text("Журнал готов. Ссылку можно отправить из YouTube через «Поделиться».\n", 12, SUBTEXT, false);
        logView.setTextIsSelectable(true);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setMinHeight(dp(90));
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackground(round(PANEL, 12));
        addMargin(root, logView, 0, 8, 0, 9);
        Button copyLog = button("Копировать журнал", PANEL);
        root.addView(copyLog, new LinearLayout.LayoutParams(-1, dp(46)));
        copyLog.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Журнал", logView.getText()));
            toast("Журнал скопирован");
        });
    }

    private void pasteLink() {
        ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb != null && cb.hasPrimaryClip()) {
            ClipData clip = cb.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence data = clip.getItemAt(0).coerceToText(this);
                linkField.setText(data);
                if (data != null) linkField.setSelection(linkField.length());
            }
        }
    }

    private void analyzeLink() {
        if (analyzing) return;
        final String link = extractAndValidateUrl(linkField.getText().toString());
        if (link == null) {
            toast("Вставьте ссылку на YouTube");
            return;
        }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(linkField.getWindowToken(), 0);
        analyzing = true;
        analyzeButton.setEnabled(false);
        downloadButton.setEnabled(false);
        titleView.setText("Проверяю ссылку и доступные дорожки…");
        choices.clear();
        updateChoices();
        log("Проверка: " + link);
        workers.execute(() -> {
            try {
                StreamInfo info = StreamInfo.getInfo(link);
                ArrayList<Choice> fetched = new ArrayList<>();
                List<VideoStream> videoStreams = info.getVideoStreams();
                if (videoStreams != null) {
                    for (VideoStream stream : videoStreams) {
                        if (!stream.isUrl() || stream.isVideoOnly()
                                || stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
                        MediaFormat format = stream.getFormat();
                        String ext = format == null ? "mp4" : format.getSuffix();
                        String mime = format == null ? "video/mp4" : format.getMimeType();
                        fetched.add(new Choice(stream,
                                "Видео со звуком · " + stream.getResolution() + " · " + ext.toUpperCase(Locale.ROOT),
                                ext, mime, false));
                    }
                    fetched.sort((a, b) -> Integer.compare(((VideoStream) b.stream).getHeight(),
                            ((VideoStream) a.stream).getHeight()));
                }
                List<AudioStream> audioStreams = info.getAudioStreams();
                if (audioStreams != null) {
                    ArrayList<Choice> audioChoices = new ArrayList<>();
                    for (AudioStream stream : audioStreams) {
                        if (!stream.isUrl() || stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
                        MediaFormat format = stream.getFormat();
                        String ext = format == null ? "m4a" : format.getSuffix();
                        String mime = format == null ? "audio/mp4" : format.getMimeType();
                        String lang = stream.getAudioLocale() == null ? "" : " · " + stream.getAudioLocale().toLanguageTag();
                        int bitrate = stream.getAverageBitrate();
                        String rate = bitrate > 0 ? " · " + bitrate + " кбит/с" : "";
                        audioChoices.add(new Choice(stream,
                                "Только звук · " + ext.toUpperCase(Locale.ROOT) + rate + lang,
                                ext, mime, true));
                    }
                    audioChoices.sort((a, b) -> Integer.compare(
                            ((AudioStream) b.stream).getAverageBitrate(),
                            ((AudioStream) a.stream).getAverageBitrate()));
                    fetched.addAll(audioChoices);
                }
                // YouTube often returns DASH video-only streams and separate audio streams.
                // Only offer combinations Android MediaMuxer can join without transcoding.
                List<VideoStream> videoOnly = info.getVideoOnlyStreams();
                if (videoOnly != null && audioStreams != null) {
                    AudioStream mp4Audio = bestAudio(audioStreams, "m4a");
                    AudioStream webmAudio = bestAudio(audioStreams, "webm");
                    ArrayList<Choice> joined = new ArrayList<>();
                    for (VideoStream video : videoOnly) {
                        if (!video.isUrl() || video.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
                        MediaFormat fmt = video.getFormat();
                        if (fmt == null) continue;
                        String suffix = fmt.getSuffix().toLowerCase(Locale.ROOT);
                        if (!"mp4".equals(suffix) && !"webm".equals(suffix)) continue;
                        AudioStream audio = "mp4".equals(suffix) ? mp4Audio : webmAudio;
                        if (audio == null) continue;
                        joined.add(new Choice(video,
                                "Видео со звуком · " + video.getResolution() + " · " + suffix.toUpperCase(Locale.ROOT) + " · объединение",
                                suffix, "mp4".equals(suffix) ? "video/mp4" : "video/webm", false, audio));
                    }
                    joined.sort((a, b) -> {
                        // Prefer MP4 over WebM, then higher resolutions.
                        int fp = Integer.compare("mp4".equals(b.extension) ? 1 : 0, "mp4".equals(a.extension) ? 1 : 0);
                        return fp != 0 ? fp : Integer.compare(((VideoStream) b.stream).getHeight(), ((VideoStream) a.stream).getHeight());
                    });
                    fetched.addAll(0, joined);
                }
                String title = info.getName();
                runOnUiThread(() -> {
                    analyzing = false;
                    analyzeButton.setEnabled(true);
                    videoTitle = title == null ? "Видео YouTube" : title;
                    titleView.setText(videoTitle + "\nНайдено вариантов: " + fetched.size());
                    choices.clear();
                    choices.addAll(fetched);
                    updateChoices();
                    downloadButton.setEnabled(!choices.isEmpty());
                    log("Найдено вариантов: " + fetched.size());
                    if (fetched.isEmpty()) log("Варианты прямой загрузки не обнаружены. Этот ролик может требовать объединения дорожек или подтверждения доступа.");
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    analyzing = false;
                    analyzeButton.setEnabled(true);
                    titleView.setText("Ошибка при проверке видео. Скопируйте журнал.");
                    log("ОШИБКА: " + ex.getClass().getSimpleName() + ": " + safe(ex.getMessage()));
                });
            }
        });
    }

    private static AudioStream bestAudio(List<AudioStream> streams, String ext) {
        AudioStream selected = null;
        int best = Integer.MIN_VALUE;
        for (AudioStream stream : streams) {
            if (!stream.isUrl() || stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            MediaFormat format = stream.getFormat();
            if (format == null || !ext.equalsIgnoreCase(format.getSuffix())) continue;
            boolean isRussian = stream.getAudioLocale() != null && "ru".equals(stream.getAudioLocale().getLanguage());
            int score = stream.getAverageBitrate() + (isRussian ? 1000000 : 0);
            if (score > best) { selected = stream; best = score; }
        }
        return selected;
    }

    private void startCombinedDownload(Choice choice) {
        String videoUrl = choice.stream.getContent();
        String audioUrl = choice.companionAudio.getContent();
        if (!videoUrl.startsWith("https://") || !audioUrl.startsWith("https://")) {
            log("ОШИБКА: видео и звук должны иметь ссылки HTTPS");
            return;
        }
        final String filename = makeFileName(videoTitle, choice.extension);
        final boolean webm = "webm".equals(choice.extension);
        downloadButton.setEnabled(false);
        analyzeButton.setEnabled(false);
        progress.setProgress(0);
        progressText.setText("Начинаю скачивание видео со звуком…");
        savePathText.setText("Папка: Загрузки / Кино на работе / " + filename);
        log("Видео со звуком: " + choice.label + " · " + filename);
        workers.execute(() -> {
            try {
                Uri saved = MuxDownload.download(getApplicationContext(), videoUrl, audioUrl,
                        filename, webm, new MuxDownload.Listener() {
                            @Override public void onProgress(String stage, int percent) {
                                runOnUiThread(() -> {
                                    progress.setProgress(percent);
                                    progressText.setText(percent + "% · " + stage);
                                });
                            }
                            @Override public void onLog(String message) {
                                runOnUiThread(() -> log(message));
                            }
                        });
                runOnUiThread(() -> {
                    progress.setProgress(100);
                    progressText.setText("Готово! Сохранено: " + filename);
                    log("Файл доступен в системной папке Загрузки · " + saved);
                    downloadButton.setEnabled(true);
                    analyzeButton.setEnabled(true);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    progressText.setText("Ошибка загрузки видео. Скопируйте журнал.");
                    log("ОШИБКА объединения: " + ex.getClass().getSimpleName() + ": " + safe(ex.getMessage()));
                    downloadButton.setEnabled(true);
                    analyzeButton.setEnabled(true);
                });
            }
        });
    }

    private void startDownload() {
        int index = qualitySpinner.getSelectedItemPosition();
        if (index < 0 || index >= choices.size()) return;
        Choice selected = choices.get(index);
        if (selected.companionAudio != null) { startCombinedDownload(selected); return; }
        if (!selected.stream.isUrl()) { log("ОШИБКА: выбранный формат не является прямым URL"); return; }
        String sourceUrl = selected.stream.getContent();
        if (!sourceUrl.startsWith("https://")) { log("ОШИБКА: поток не HTTPS"); return; }
        String file = makeFileName(videoTitle, selected.extension);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(sourceUrl));
        request.setTitle(file);
        request.setDescription("ЮТ Загрузчик · YouTube");
        request.setMimeType(selected.mime);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.addRequestHeader("User-Agent", HttpExtractorDownloader.USER_AGENT);
        request.addRequestHeader("Referer", "https://www.youtube.com/");
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ЮТ Загрузчик/" + file);
        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            lastDownloadId = manager.enqueue(request);
            getPreferences(MODE_PRIVATE).edit().putLong("download_id", lastDownloadId).apply();
            progress.setProgress(0);
            progressText.setText("Загрузка начата…");
            log("Скачивание: " + file + " · ID " + lastDownloadId);
            savePathText.setText("Сохранение: Загрузки / ЮТ Загрузчик / " + file);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        } catch (Exception ex) {
            log("ОШИБКА запуска загрузки: " + ex.getClass().getSimpleName() + ": " + safe(ex.getMessage()));
            toast("Не удалось начать загрузку");
        }
    }

    private void pollDownload(long id) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (android.database.Cursor cur = manager.query(query)) {
            if (cur == null || !cur.moveToFirst()) return;
            int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            long current = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if (total > 0) {
                int percentage = (int) Math.max(0, Math.min(100, current * 100.0 / total));
                progress.setProgress(percentage);
                progressText.setText(percentage + "% · " + humanBytes(current) + " / " + humanBytes(total));
            } else if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                progressText.setText("Загрузка: " + humanBytes(current));
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                progress.setProgress(100);
                progressText.setText("Готово! Файл сохранён в «Загрузки».");
                log("ГОТОВО: загрузка " + id);
                lastDownloadId = -1;
                getPreferences(MODE_PRIVATE).edit().remove("download_id").apply();
            } else if (status == DownloadManager.STATUS_FAILED) {
                int reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                progressText.setText("Ошибка скачивания (код " + reason + ")");
                log("ОШИБКА загрузки · код: " + reason + ". Для ошибок 403/404 проверьте ссылку заново.");
                lastDownloadId = -1;
                getPreferences(MODE_PRIVATE).edit().remove("download_id").apply();
            } else if (status == DownloadManager.STATUS_PAUSED) {
                progressText.setText("Загрузка приостановлена системой");
            }
        } catch (Exception ex) {
            log("Ошибка статуса загрузки: " + safe(ex.getMessage()));
            lastDownloadId = -1;
        }
    }

    private static String extractAndValidateUrl(String text) {
        Matcher m = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE).matcher(text.trim());
        if (!m.find()) return null;
        String candidate = m.group().replaceAll("[),.;]+$", "");
        try {
            Uri uri = Uri.parse(candidate);
            String host = uri.getHost();
            if (host == null) return null;
            String h = host.toLowerCase(Locale.ROOT);
            if (!(h.equals("youtube.com") || h.endsWith(".youtube.com") || h.equals("youtu.be")
                    || h.equals("youtube-nocookie.com") || h.endsWith(".youtube-nocookie.com"))) return null;
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            return candidate;
        } catch (Exception ex) { return null; }
    }

    private static String makeFileName(String raw, String ext) {
        String base = raw.replaceAll("[^\\p{L}\\p{N} ._()-]", "_")
                .replaceAll("[. ]+$", "").trim();
        if (base.isEmpty()) base = "YouTube";
        // Android filename byte count is limited. Conservative char count for Cyrillic.
        if (base.length() > 65) base = base.substring(0, 65);
        return base + "_" + System.currentTimeMillis() / 1000 + "." + ext;
    }

    private void updateChoices() {
        ArrayList<String> labels = new ArrayList<>();
        for (Choice item : choices) labels.add(item.label);
        if (labels.isEmpty()) labels.add("Сначала проверьте ссылку");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
    }

    private void log(String message) {
        if (logView == null) return;
        String old = logView.getText().toString();
        String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date());
        String next = old + "[" + time + "] " + message + "\n";
        if (next.length() > 14000) next = next.substring(next.length() - 12000);
        logView.setText(next);
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
    }
    private static String safe(String s) { return s == null ? "Без описания" : s; }
    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f КБ", n / 1024.0);
        return String.format(Locale.getDefault(), "%.1f МБ", n / (1024.0 * 1024.0));
    }
    private int dp(float f) { return Math.round(f * getResources().getDisplayMetrics().density); }
    private static GradientDrawable round(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius * 3);
        return bg;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private Button button(String label, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setBackground(round(color, 12));
        return b;
    }
    private void addMargin(LinearLayout root, View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        root.addView(v, p);
    }
    @Override protected void onDestroy() {
        ui.removeCallbacks(progressPoll);
        workers.shutdownNow();
        super.onDestroy();
    }
}
