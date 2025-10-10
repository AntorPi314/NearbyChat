package com.antor.nearbychat;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NotepadActivity extends Activity {

    private static final String PREFS_NAME = "NotepadPrefs";
    private static final String KEY_CONTENT = "notepad_content";
    private static final int AUTO_SAVE_DELAY = 2000;

    private EditText editNotepad;
    private TextView txtLineNumbers, txtStats, txtAutoSave, txtFindCount;
    private ScrollView editorScroll, lineNumberScroll;
    private LinearLayout findReplaceBar, replaceContainer;
    private EditText editFind, editReplace;
    private ImageView btnSave, btnUndo, btnRedo, btnFind, btnBack;
    private TextView btnShowReplace;
    private Button btnReplace, btnReplaceAll;

    private Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private Runnable autoSaveRunnable;
    private SharedPreferences prefs;

    private Stack<String> undoStack = new Stack<>();
    private Stack<String> redoStack = new Stack<>();
    private boolean isUndoRedo = false;
    private String lastSavedText = "";

    private List<Integer> findMatches = new ArrayList<>();
    private int currentMatchIndex = -1;
    private boolean isFindMode = false;

    private boolean isApplyingSyntax = false;
    private TextWatcher textWatcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notepad);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        initViews();
        loadContent();
        setupListeners();
        updateLineNumbers();
        updateStats();
    }

    private void initViews() {
        editNotepad = findViewById(R.id.editNotepad);
        txtLineNumbers = findViewById(R.id.txtLineNumbers);
        txtStats = findViewById(R.id.txtStats);
        txtAutoSave = findViewById(R.id.txtAutoSave);
        txtFindCount = findViewById(R.id.txtFindCount);
        editorScroll = findViewById(R.id.editorScroll);
        lineNumberScroll = findViewById(R.id.lineNumberScroll);
        findReplaceBar = findViewById(R.id.findReplaceBar);
        replaceContainer = findViewById(R.id.replaceContainer);
        editFind = findViewById(R.id.editFind);
        editReplace = findViewById(R.id.editReplace);
        btnSave = findViewById(R.id.btnSave);
        btnUndo = findViewById(R.id.btnUndo);
        btnRedo = findViewById(R.id.btnRedo);
        btnFind = findViewById(R.id.btnFind);
        btnBack = findViewById(R.id.btnBack);
        btnShowReplace = findViewById(R.id.btnShowReplace);
        btnReplace = findViewById(R.id.btnReplace);
        btnReplaceAll = findViewById(R.id.btnReplaceAll);
    }

    private void setupListeners() {
        editNotepad.setMovementMethod(LinkMovementMethod.getInstance());

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> {
            saveContent();
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show();
        });

        btnUndo.setOnClickListener(v -> undo());
        btnRedo.setOnClickListener(v -> redo());
        btnFind.setOnClickListener(v -> toggleFindMode());

        btnShowReplace.setOnClickListener(v -> {
            if (replaceContainer.getVisibility() == View.GONE) {
                replaceContainer.setVisibility(View.VISIBLE);
                btnShowReplace.setText("▲ Hide Replace");
            } else {
                replaceContainer.setVisibility(View.GONE);
                btnShowReplace.setText("▼ Show Replace");
            }
        });

        findViewById(R.id.btnCloseFindBar).setOnClickListener(v -> {
            findReplaceBar.setVisibility(View.GONE);
            isFindMode = false;
            clearHighlights();
            applySyntaxHighlighting();
        });

        findViewById(R.id.btnPrevMatch).setOnClickListener(v -> navigateMatch(false));
        findViewById(R.id.btnNextMatch).setOnClickListener(v -> navigateMatch(true));
        btnReplace.setOnClickListener(v -> replaceCurrentMatch());
        btnReplaceAll.setOnClickListener(v -> replaceAllMatches());

        textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!isUndoRedo && !isApplyingSyntax) {
                    String currentText = s.toString();
                    if (!currentText.equals(lastSavedText)) {
                        undoStack.push(lastSavedText);
                        redoStack.clear();
                        lastSavedText = currentText;
                        updateUndoRedoButtons();
                    }
                }
            }
            @Override
            public void afterTextChanged(Editable s) {
                if (isApplyingSyntax) return;

                updateLineNumbers();
                updateStats();
                scheduleAutoSave();

                if (isFindMode) {
                    performFind(editFind.getText().toString());
                } else {
                    applySyntaxHighlighting();
                }
            }
        };
        editNotepad.addTextChangedListener(textWatcher);

        editFind.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                performFind(s.toString());
            }
        });
        editorScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            lineNumberScroll.scrollTo(0, scrollY);
        });
    }

    private void loadContent() {
        String content = prefs.getString(KEY_CONTENT, "");
        editNotepad.setText(content);
        lastSavedText = content;
        if (!content.isEmpty()) {
            undoStack.push(content);
        }
        updateUndoRedoButtons();
        applySyntaxHighlighting();
    }

    private void saveContent() {
        String content = editNotepad.getText().toString();
        prefs.edit().putString(KEY_CONTENT, content).apply();
    }

    private void scheduleAutoSave() {
        if (autoSaveRunnable != null) {
            autoSaveHandler.removeCallbacks(autoSaveRunnable);
        }
        autoSaveRunnable = () -> {
            saveContent();
            showAutoSaveIndicator();
        };
        autoSaveHandler.postDelayed(autoSaveRunnable, AUTO_SAVE_DELAY);
    }

    private void showAutoSaveIndicator() {
        txtAutoSave.setVisibility(View.VISIBLE);
        txtAutoSave.setText("Auto-saved");
        txtAutoSave.setTextColor(Color.parseColor("#4CAF50"));

        autoSaveHandler.postDelayed(() -> {
            txtAutoSave.setVisibility(View.INVISIBLE);
        }, 2000);
    }

    private void updateLineNumbers() {
        String text = editNotepad.getText().toString();
        int lineCount = text.isEmpty() ? 1 : text.split("\n", -1).length;

        StringBuilder lineNumbers = new StringBuilder();
        for (int i = 1; i <= lineCount; i++) {
            lineNumbers.append(i);
            if (i < lineCount) {
                lineNumbers.append("\n");
            }
        }

        txtLineNumbers.setText(lineNumbers.toString());
    }

    private void updateStats() {
        String text = editNotepad.getText().toString();
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
        int chars = text.length();
        int words = text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length;

        txtStats.setText(String.format("Lines: %d | Words: %d | Chars: %d", lines, words, chars));
    }

    private void undo() {
        if (!undoStack.isEmpty()) {
            String currentText = editNotepad.getText().toString();
            redoStack.push(currentText);

            String previousText = undoStack.pop();
            isUndoRedo = true;
            editNotepad.setText(previousText);
            lastSavedText = previousText;
            isUndoRedo = false;

            updateUndoRedoButtons();
        }
    }

    private void redo() {
        if (!redoStack.isEmpty()) {
            String currentText = editNotepad.getText().toString();
            undoStack.push(currentText);

            String nextText = redoStack.pop();
            isUndoRedo = true;
            editNotepad.setText(nextText);
            lastSavedText = nextText;
            isUndoRedo = false;

            updateUndoRedoButtons();
        }
    }

    private void updateUndoRedoButtons() {
        btnUndo.setAlpha(undoStack.isEmpty() ? 0.3f : 1.0f);
        btnUndo.setEnabled(!undoStack.isEmpty());

        btnRedo.setAlpha(redoStack.isEmpty() ? 0.3f : 1.0f);
        btnRedo.setEnabled(!redoStack.isEmpty());
    }

    private void toggleFindMode() {
        if (isFindMode) {
            findReplaceBar.setVisibility(View.GONE);
            isFindMode = false;
            clearHighlights();
            applySyntaxHighlighting();
        } else {
            clearHighlights();
            findReplaceBar.setVisibility(View.VISIBLE);
            isFindMode = true;
            editFind.requestFocus();

            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(editFind, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private void performFind(String query) {
        clearHighlights();
        findMatches.clear();
        currentMatchIndex = -1;

        if (query.isEmpty()) {
            txtFindCount.setText("0/0");
            return;
        }

        Editable editable = editNotepad.getText();
        Pattern pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(editable);

        while (matcher.find()) {
            findMatches.add(matcher.start());
        }

        if (!findMatches.isEmpty()) {
            currentMatchIndex = 0;
            highlightMatches(query);
        }

        txtFindCount.setText((currentMatchIndex + 1) + "/" + findMatches.size());
        scrollToMatch(currentMatchIndex);
    }

    private void highlightMatches(String query) {
        if (query.isEmpty() || findMatches.isEmpty()) return;

        Editable editable = editNotepad.getText();

        for (int i = 0; i < findMatches.size(); i++) {
            int start = findMatches.get(i);
            int end = start + query.length();

            BackgroundColorSpan colorSpan;
            if (i == currentMatchIndex) {
                colorSpan = new BackgroundColorSpan(Color.parseColor("#FF9800"));
            } else {
                colorSpan = new BackgroundColorSpan(Color.parseColor("#FFEB3B"));
            }
            editable.setSpan(colorSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }


    private void navigateMatch(boolean forward) {
        if (findMatches.isEmpty()) return;

        clearHighlights();

        if (forward) {
            currentMatchIndex = (currentMatchIndex + 1) % findMatches.size();
        } else {
            currentMatchIndex = (currentMatchIndex - 1 + findMatches.size()) % findMatches.size();
        }

        highlightMatches(editFind.getText().toString());
        scrollToMatch(currentMatchIndex);
        txtFindCount.setText((currentMatchIndex + 1) + "/" + findMatches.size());
    }

    private void scrollToMatch(int matchIndex) {
        if (matchIndex >= 0 && matchIndex < findMatches.size()) {
            int position = findMatches.get(matchIndex);
            editNotepad.requestFocus();
            editNotepad.setSelection(position, position + editFind.getText().length());
        }
    }

    private void replaceCurrentMatch() {
        if (findMatches.isEmpty() || currentMatchIndex < 0) return;

        String findText = editFind.getText().toString();
        String replaceText = editReplace.getText().toString();

        isApplyingSyntax = true;
        Editable editable = editNotepad.getText();
        int start = findMatches.get(currentMatchIndex);
        int end = start + findText.length();

        editable.replace(start, end, replaceText);
        isApplyingSyntax = false;

        performFind(findText);
    }

    private void replaceAllMatches() {
        if (findMatches.isEmpty() || editFind.getText().toString().isEmpty()) return;

        isApplyingSyntax = true;
        String findText = editFind.getText().toString();
        String replaceText = editReplace.getText().toString();
        String currentText = editNotepad.getText().toString();

        Pattern pattern = Pattern.compile(Pattern.quote(findText), Pattern.CASE_INSENSITIVE);
        String newText = pattern.matcher(currentText).replaceAll(Matcher.quoteReplacement(replaceText));

        editNotepad.setText(newText);
        isApplyingSyntax = false;

        Toast.makeText(this, "Replaced " + findMatches.size() + " matches",
                Toast.LENGTH_SHORT).show();

        performFind("");
    }

    private void clearHighlights() {
        Editable editable = editNotepad.getText();
        BackgroundColorSpan[] spans = editable.getSpans(0, editable.length(), BackgroundColorSpan.class);
        for (BackgroundColorSpan span : spans) {
            editable.removeSpan(span);
        }
    }

    private void applySyntaxHighlighting() {
        if (isFindMode) return;

        isApplyingSyntax = true;
        int selectionStart = editNotepad.getSelectionStart();
        int selectionEnd = editNotepad.getSelectionEnd();

        Editable editable = editNotepad.getText();

        Object[] allSpans = editable.getSpans(0, editable.length(), Object.class);
        for (Object span : allSpans) {
            if (span instanceof ForegroundColorSpan || span instanceof BackgroundColorSpan || span instanceof StyleSpan || span instanceof URLSpan) {
                editable.removeSpan(span);
            }
        }
        Matcher linkMatcher = Patterns.WEB_URL.matcher(editable);
        while (linkMatcher.find()) {
            String url = linkMatcher.group(0);
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            editable.setSpan(new URLSpan(url),
                    linkMatcher.start(), linkMatcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            editable.setSpan(new ForegroundColorSpan(Color.parseColor("#0D80E0")),
                    linkMatcher.start(), linkMatcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher emailMatcher = Patterns.EMAIL_ADDRESS.matcher(editable);
        while (emailMatcher.find()) {
            editable.setSpan(new ForegroundColorSpan(Color.parseColor("#009688")),
                    emailMatcher.start(), emailMatcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Pattern numberPattern = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
        Matcher numberMatcher = numberPattern.matcher(editable);
        while(numberMatcher.find()){
            editable.setSpan(new ForegroundColorSpan(Color.parseColor("#FF5722")),
                    numberMatcher.start(), numberMatcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Pattern headerPattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Matcher headerMatcher = headerPattern.matcher(editable);
        while (headerMatcher.find()) {
            editable.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                    headerMatcher.start(), headerMatcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            editable.setSpan(new ForegroundColorSpan(Color.parseColor("#E91E63")),
                    headerMatcher.start(), headerMatcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        isApplyingSyntax = false;
        editNotepad.setSelection(selectionStart, selectionEnd);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveContent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (autoSaveHandler != null) {
            autoSaveHandler.removeCallbacksAndMessages(null);
        }
        saveContent();
    }
}