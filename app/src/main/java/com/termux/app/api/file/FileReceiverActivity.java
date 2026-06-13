package com.termux.app.api.file;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Patterns;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.net.uri.UriScheme;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class FileReceiverActivity extends AppCompatActivity {

    static final String TERMUX_RECEIVEDIR = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads";
    static final String EDITOR_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor";
    static final String URL_OPENER_PROGRAM = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener";

    /** Fallback basename used when no valid name can be derived for a received file or content uri. */
    static final String DEFAULT_RECEIVED_FILE_BASENAME = "received_file";

    /** Fallback basename used when no valid name can be derived for received shared text. */
    static final String DEFAULT_RECEIVED_TEXT_FILE_BASENAME = "received_text";

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     */
    boolean mFinishOnDismissNameDialog = true;

    private static final String API_TAG = TermuxConstants.TERMUX_APP_NAME + "FileReceiver";

    private static final String LOG_TAG = "FileReceiverActivity";

    static boolean isSharedTextAnUrl(String sharedText) {
        if (sharedText == null || sharedText.isEmpty()) return false;

        return Patterns.WEB_URL.matcher(sharedText).matches()
            || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText);
    }

    @Override
    protected void onResume() {
        super.onResume();

        final Intent intent = getIntent();
        final String action = intent.getAction();
        final String type = intent.getType();
        final String scheme = intent.getScheme();

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        final String sharedTitle = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null);

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle);
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText);
                } else {
                    String subject = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null);
                    if (subject == null) subject = sharedTitle;
                    promptNameAndSave(new ByteArrayInputStream(sharedText.getBytes(StandardCharsets.UTF_8)),
                        getReceivedTextFileName(subject));
                }
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.");
            }
        } else {
            Uri dataUri = intent.getData();

            if (dataUri == null) {
                showErrorDialogAndQuit("Data uri not passed.");
                return;
            }

            if (UriScheme.SCHEME_CONTENT.equals(scheme)) {
                handleContentUri(dataUri, sharedTitle);
            } else if (UriScheme.SCHEME_FILE.equals(scheme)) {
                Logger.logVerbose(LOG_TAG, "uri: \"" + dataUri + "\", path: \"" + dataUri.getPath() + "\", fragment: \"" + dataUri.getFragment() + "\"");

                // Get full path including fragment (anything after last "#")
                String path = UriUtils.getUriFilePathWithFragment(dataUri);
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit("File path from data uri is null, empty or invalid.");
                    return;
                }

                File file = new File(path);
                try {
                    FileInputStream in = new FileInputStream(file);
                    promptNameAndSave(in, getReceivedFileName(file.getName()));
                } catch (FileNotFoundException e) {
                    showErrorDialogAndQuit("Cannot open file: " + e.getMessage() + ".");
                }
            } else {
                showErrorDialogAndQuit("Unable to receive any file or URL.");
            }
        }
    }

    void showErrorDialogAndQuit(String message) {
        mFinishOnDismissNameDialog = false;
        MessageDialogUtils.showMessage(this,
            API_TAG, message,
            null, (dialog, which) -> finish(),
            null, null,
            dialog -> finish());
    }

    void handleContentUri(@NonNull final Uri uri, String subjectFromIntent) {
        try {
            Logger.logVerbose(LOG_TAG, "uri: \"" + uri + "\", path: \"" + uri.getPath() + "\", fragment: \"" + uri.getFragment() + "\"");

            String displayName = null;

            String[] projection = new String[]{OpenableColumns.DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(uri, projection, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    final int fileNameColumnId = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0) displayName = c.getString(fileNameColumnId);
                }
            }

            // Unify the file name source: prefer the provider display name, then the intent title,
            // then the uri basename, falling back to a default. getReceivedFileName() always returns
            // a sanitized, path-separator-free basename so the file stays inside the downloads dir.
            String attachmentFileName = getReceivedFileName(displayName, subjectFromIntent,
                UriUtils.getUriFileBasename(uri, true));

            InputStream in = getContentResolver().openInputStream(uri);
            promptNameAndSave(in, attachmentFileName);
        } catch (Exception e) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=" + uri + ") failed", e);
        }
    }

    void promptNameAndSave(final InputStream in, final String attachmentFileName) {
        TextInputDialogUtils.textInput(this, R.string.title_file_received, attachmentFileName,
            R.string.action_file_received_edit, text -> {
                File outFile = saveStreamWithName(in, text);
                if (outFile == null) return;

                final File editorProgramFile = new File(EDITOR_PROGRAM);
                if (!editorProgramFile.isFile()) {
                    showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/termux-file-editor\n\n"
                        + "Create this file as a script or a symlink - it will be called with the received file as only argument.");
                    return;
                }

                // Do this for the user if necessary:
                //noinspection ResultOfMethodCallIgnored
                editorProgramFile.setExecutable(true);

                final Uri scriptUri = UriUtils.getFileUri(EDITOR_PROGRAM);

                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
                executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{outFile.getAbsolutePath()});
                startService(executeIntent);
                finish();
            },
            R.string.action_file_received_open_directory, text -> {
                File outFile = saveStreamWithName(in, text);
                if (outFile == null) return;

                Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE);
                executeIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, outFile.getParentFile().getAbsolutePath());
                executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
                startService(executeIntent);
                finish();
            },
            android.R.string.cancel, text -> finish(), dialog -> {
                if (mFinishOnDismissNameDialog) finish();
            });
    }

    public File saveStreamWithName(InputStream in, String attachmentFileName) {
        try {
            return saveStreamToFile(in, new File(TERMUX_RECEIVEDIR), attachmentFileName);
        } catch (IOException e) {
            showErrorDialogAndQuit("Error saving file:\n\n" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "Error saving file", e);
            return null;
        }
    }

    /**
     * Sanitize an arbitrary file name candidate into a safe basename usable inside
     * {@link #TERMUX_RECEIVEDIR}.
     *
     * The candidate may come from untrusted sources such as {@link OpenableColumns#DISPLAY_NAME},
     * {@link Intent#EXTRA_SUBJECT}, {@link Intent#EXTRA_TITLE}, a {@link Uri} basename or the name
     * typed by the user. Any of these may contain path separators (which would otherwise let the
     * file escape the downloads directory) or be empty/whitespace. Only the last path segment is
     * kept and surrounding whitespace is trimmed.
     *
     * @param name The raw file name candidate.
     * @return Returns a non-empty basename, or {@code null} if the candidate cannot yield a valid one.
     */
    static String sanitizeFileName(String name) {
        if (name == null) return null;
        // Keep only the last path segment so that a name like "a/b/c.txt" or "../c.txt" cannot escape
        // the target directory. Both "/" and "\" are treated as separators since display names may
        // originate from providers using either convention.
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String basename = (lastSeparator == -1) ? name : name.substring(lastSeparator + 1);
        basename = basename.trim();
        if (basename.isEmpty() || ".".equals(basename) || "..".equals(basename)) return null;
        return basename;
    }

    /**
     * Resolve the file name to use for received shared text, ensuring it ends with a {@code .txt}
     * extension when the subject has none.
     *
     * @param subject The {@link Intent#EXTRA_SUBJECT} or {@link Intent#EXTRA_TITLE} value, if any.
     * @return Returns a sanitized, non-empty file name.
     */
    static String getReceivedTextFileName(String subject) {
        String fileName = sanitizeFileName(subject);
        if (fileName == null) fileName = DEFAULT_RECEIVED_TEXT_FILE_BASENAME;
        if (fileName.indexOf('.') < 0) fileName += ".txt";
        return fileName;
    }

    /**
     * Resolve the file name to use for a received file or content uri by picking the first candidate
     * that sanitizes to a valid basename, falling back to {@link #DEFAULT_RECEIVED_FILE_BASENAME}.
     *
     * @param candidates The ordered file name candidates.
     * @return Returns a sanitized, non-empty file name.
     */
    static String getReceivedFileName(String... candidates) {
        for (String candidate : candidates) {
            String fileName = sanitizeFileName(candidate);
            if (fileName != null) return fileName;
        }
        return DEFAULT_RECEIVED_FILE_BASENAME;
    }

    /**
     * Save {@code in} into {@code outputDir} using a sanitized {@code rawFileName}, never overwriting
     * an existing file. If a file with the resolved name already exists, a counter suffix like
     * " (1)", " (2)", ... is inserted before the extension until a free name is found.
     *
     * @param in The {@link InputStream} to read the content from.
     * @param outputDir The directory to save the file in.
     * @param rawFileName The (possibly unsafe) file name to use.
     * @return Returns the {@link File} that was written.
     * @throws IOException if the name is invalid or the file cannot be created or written.
     */
    static File saveStreamToFile(InputStream in, File outputDir, String rawFileName) throws IOException {
        String fileName = sanitizeFileName(rawFileName);
        if (fileName == null)
            throw new IOException("File name cannot be null or empty");

        if (!outputDir.isDirectory() && !outputDir.mkdirs())
            throw new IOException("Cannot create directory: " + outputDir.getAbsolutePath());

        File outFile = getUniqueFile(outputDir, fileName);
        try (FileOutputStream f = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int readBytes;
            while ((readBytes = in.read(buffer)) > 0) {
                f.write(buffer, 0, readBytes);
            }
        }
        return outFile;
    }

    /**
     * Atomically reserve a file that does not yet exist in {@code dir} for {@code fileName}. If
     * {@code fileName} is already taken, a " (n)" counter is inserted before the extension. The
     * returned file is created (empty) via {@link File#createNewFile()} so that the existence check
     * and reservation are not subject to a time-of-check/time-of-use race.
     *
     * @param dir The directory to create the file in.
     * @param fileName The desired (already sanitized) file name.
     * @return Returns the newly created, conflict-free {@link File}.
     * @throws IOException if no unique file could be created.
     */
    static File getUniqueFile(File dir, String fileName) throws IOException {
        File file = new File(dir, fileName);
        if (file.createNewFile()) return file;

        String basename = FileUtils.getFileBasenameWithoutExtension(fileName);
        String extension;
        if (basename == null || basename.isEmpty()) {
            // Names like "noext" or dotfiles like ".bashrc": keep the whole name and append the
            // counter at the end.
            basename = fileName;
            extension = "";
        } else {
            // Everything after the basename, i.e. the dot and extension (e.g. ".pdf").
            extension = fileName.substring(basename.length());
        }

        for (int counter = 1; counter <= 1000; counter++) {
            file = new File(dir, basename + " (" + counter + ")" + extension);
            if (file.createNewFile()) return file;
        }

        // Extremely unlikely fallback to guarantee progress and uniqueness.
        file = new File(dir, basename + "-" + System.currentTimeMillis() + extension);
        if (file.createNewFile()) return file;

        throw new IOException("Could not create a unique file for: " + fileName);
    }

    void handleUrlAndFinish(final String url) {
        final File urlOpenerProgramFile = new File(URL_OPENER_PROGRAM);
        if (!urlOpenerProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n$HOME/bin/termux-url-opener\n\n"
                + "Create this file as a script or a symlink - it will be called with the shared URL as the first argument.");
            return;
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true);

        final Uri urlOpenerProgramUri = UriUtils.getFileUri(URL_OPENER_PROGRAM);

        Intent executeIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri);
        executeIntent.setClass(FileReceiverActivity.this, TermuxService.class);
        executeIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{url});
        startService(executeIntent);
        finish();
    }

    /**
     * Update {@link TERMUX_APP#FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_SHARE_RECEIVER} value and
     * {@link TERMUX_APP#FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME} component state depending on
     * {@link TermuxPropertyConstants#KEY_DISABLE_FILE_VIEW_RECEIVER} value.
     */
    public static void updateFileReceiverActivityComponentsState(@NonNull Context context) {
        new Thread() {
            @Override
            public void run() {
                TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();

                String errmsg;
                boolean state;

                state = !properties.isFileShareReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_SHARE_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

                state = !properties.isFileViewReceiverDisabled();
                Logger.logVerbose(LOG_TAG, "Setting " + TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME + " component state to " + state);
                errmsg = PackageUtils.setComponentState(context,TermuxConstants.TERMUX_PACKAGE_NAME,
                    TERMUX_APP.FILE_VIEW_RECEIVER_ACTIVITY_CLASS_NAME,
                    state, null, false, false);
                if (errmsg != null)
                    Logger.logError(LOG_TAG, errmsg);

            }
        }.start();
    }

}
