package dev.codescroll;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.NumberFormatter;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.ParseException;
import java.util.Objects;

public final class CodeScrollConfigurable implements Configurable {
    private JComponent component;
    private JCheckBox enabled;
    private JCheckBox leftAligned;
    private JCheckBox fit;
    private JCheckBox syntaxColors;
    private JCheckBox showMarkers;
    private JCheckBox showMethods;
    private JCheckBox showPreview;
    private JCheckBox scrollOnly;
    private JCheckBox showMain;
    private JCheckBox showDiff;
    private JCheckBox showPreviewEditors;
    private JCheckBox showConsole;
    private JCheckBox showUntyped;
    private JCheckBox hideScrollbar;
    private JCheckBox autoWidth;
    private JCheckBox lockWidth;
    private JSpinner width;
    private JSpinner pixelsPerLine;
    private JSpinner minLines;
    private JSpinner maxLines;
    private JSpinner debounceMs;
    private JTextField excludedExtensions;
    private JTextField markerWords;
    private JCheckBox fill;
    private JComboBox<CodeScrollSettings.RenderStyle> renderStyle;
    private JComboBox<CodeScrollSettings.ClickTiming> clickTiming;
    private JCheckBox clickByMousePosition;
    private JSpinner diffWidth;
    private JSpinner previewWidth;
    private JSpinner consoleWidth;
    private JSpinner untypedWidth;
    private JCheckBox hoverOnly;
    private JCheckBox showQuickHide;
    private JSpinner hoverDelayMs;
    private JCheckBox emptyConsole;
    private JCheckBox emptyUntyped;
    private JCheckBox outOfRangeEmpty;
    private JTextField viewportColor;
    private JTextField viewportBorderColor;
    private JSpinner viewportOpacity;
    private JSpinner viewportBorderOpacity;
    private JSpinner viewportBorderWidth;
    private JCheckBox showBookmarks;
    private JCheckBox showVcs;
    private JCheckBox showDiagnostics;
    private JCheckBox showSearch;
    private JCheckBox fullLineDiagnostics;
    private JCheckBox fullLineMarkup;
    private JTextArea methodNames;
    private JTextArea methodAnnotations;
    private JTextField markerRegex;
    private JSpinner markerFontSize;
    private JCheckBox showRenderedMarkdown;
    private JCheckBox previewWheel;

    @Override
    public @NotNull String getDisplayName() {
        return "Code Scroll";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (component != null) {
            return component;
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        addSection(form, "Appearance");
        enabled = addCheckbox(form, "Enable minimap", "Show the Code Scroll minimap globally.");
        leftAligned = addCheckbox(form, "Align left", "Place the minimap on the left side of the editor.");
        fit = addCheckbox(form, "Fit to editor height", "Scale the document to fit the available minimap height.");
        syntaxColors = addCheckbox(form, "Syntax colors", "Use syntax highlighting colors in the minimap.");
        fill = addCheckbox(form, "Fill editor height", "Fill available height even for short documents.");
        renderStyle = new JComboBox<>(CodeScrollSettings.RenderStyle.values());
        addRow(form, labeledControl("Render style", renderStyle), "CLEAN uses compact strokes; ACCURATE uses glyph shapes.");
        hideScrollbar = addCheckbox(form, "Hide editor scrollbar", "Hide the editor's normal vertical scrollbar.");
        autoWidth = addCheckbox(form, "Automatic width", "Reduce minimap width in narrow and split editors.");
        lockWidth = addCheckbox(form, "Lock width", "Keep the minimap width fixed instead of allowing resizing.");
        width = addSpinner(form, "Main width (px)", 110, 40, 300, "Main editor minimap width: 40 to 300 pixels.");
        diffWidth = addSpinner(form, "Diff width (px)", 60, 40, 300, "Diff editor width: 40 to 300 pixels.");
        previewWidth = addSpinner(form, "Preview width (px)", 70, 40, 300, "Preview editor width: 40 to 300 pixels.");
        consoleWidth = addSpinner(form, "Console width (px)", 60, 40, 300, "Console width: 40 to 300 pixels.");
        untypedWidth = addSpinner(form, "Untyped width (px)", 60, 40, 300, "Untyped editor width: 40 to 300 pixels.");
        pixelsPerLine = addSpinner(form, "Pixels per line", 2, 1, 8,
                "Line height when not fitting the document: 1 to 8 pixels.");
        addSection(form, "Navigation and Preview");
        showPreview = addCheckbox(form, "Show hover preview", "Preview source code when hovering over the minimap.");
        scrollOnly = addCheckbox(form, "Scroll only", "Navigate by scrolling without moving the text caret.");
        clickTiming = new JComboBox<>(CodeScrollSettings.ClickTiming.values());
        addRow(form, labeledControl("Click timing", clickTiming), "Navigate on mouse DOWN, mouse UP, or NONE.");
        clickByMousePosition = addCheckbox(form, "Click by mouse position", "Center navigation on the clicked source position.");
        hoverOnly = addCheckbox(form, "Show only on hover", "Reveal the minimap while the editor edge is hovered.");
        showQuickHide = addCheckbox(form, "Show quick hide", "Offer a quick-hide control on the minimap.");
        hoverDelayMs = addSpinner(form, "Hover delay (ms)", 250, 0, 2_000, "Preview/reveal delay: 0 to 2000 milliseconds.");
        previewWheel = addCheckbox(form, "Preview mouse wheel", "Allow scrolling inside the hover preview.");
        showRenderedMarkdown = addCheckbox(form, "Rendered Markdown", "Show a minimap for rendered Markdown previews.");
        addSection(form, "Editor Visibility");
        showMain = addCheckbox(form, "Main editors", "Show the minimap in regular file editors.");
        showDiff = addCheckbox(form, "Diff editors", "Show the minimap in diff views.");
        showPreviewEditors = addCheckbox(form, "Preview editors", "Show the minimap in preview editor views.");
        showConsole = addCheckbox(form, "Console editors", "Show the minimap in console output views.");
        showUntyped = addCheckbox(form, "Untyped editors", "Show the minimap in editors without a recognized file type.");
        emptyConsole = addCheckbox(form, "Empty console minimap", "Keep the console minimap empty instead of drawing text.");
        emptyUntyped = addCheckbox(form, "Empty untyped minimap", "Keep the untyped minimap empty instead of drawing text.");
        outOfRangeEmpty = addCheckbox(form, "Empty outside line limits", "Keep an empty minimap rather than hiding it outside line limits.");
        minLines = addSpinner(form, "Minimum lines", 0, 0, 1_000_000,
                "Hide the minimap for documents below this line count; 0 disables the lower limit.");
        maxLines = addSpinner(form, "Maximum lines", 1_000_000, 0, 1_000_000,
                "Hide the minimap above this line count; must be at least the minimum.");
        minLines.addChangeListener(event -> {
            SpinnerNumberModel maximumModel = (SpinnerNumberModel) maxLines.getModel();
            int minimum = number(minLines);
            if (number(maxLines) < minimum) {
                maxLines.setValue(minimum);
            }
            maximumModel.setMinimum(minimum);
        });
        debounceMs = addSpinner(form, "Update delay (ms)", 120, 50, 2_000,
                "Delay after edits before updating the minimap: 50 to 2000 milliseconds.");
        excludedExtensions = addTextField(form, "Excluded extensions",
                "Comma-separated file extensions without dots, for example ipynb,log.");
        addSection(form, "Viewport");
        viewportColor = addTextField(form, "Viewport color", "Six hexadecimal RGB digits; an optional # prefix is accepted.");
        viewportBorderColor = addTextField(form, "Viewport border color", "Six hexadecimal RGB digits; an optional # prefix is accepted.");
        viewportOpacity = addSpinner(form, "Viewport opacity", 35, 0, 255, "Alpha: 0 transparent to 255 opaque.");
        viewportBorderOpacity = addSpinner(form, "Border opacity", 110, 0, 255, "Alpha: 0 transparent to 255 opaque.");
        viewportBorderWidth = addSpinner(form, "Border width (px)", 1, 0, 8, "Border thickness: 0 to 8 pixels.");
        addSection(form, "Markers and Highlights");
        showMarkers = addCheckbox(form, "Show markers", "Highlight occurrences of the configured comment markers.");
        showMethods = addCheckbox(form, "Show declarations", "Show method and class locations in the minimap.");
        showBookmarks = addCheckbox(form, "Show bookmarks", "Show editor bookmarks.");
        showVcs = addCheckbox(form, "Show VCS changes", "Show version-control change markers.");
        showDiagnostics = addCheckbox(form, "Show diagnostics", "Show errors and warnings.");
        showSearch = addCheckbox(form, "Show search results", "Show search matches.");
        fullLineDiagnostics = addCheckbox(form, "Full-line diagnostics", "Extend diagnostic highlights across the minimap line.");
        fullLineMarkup = addCheckbox(form, "Full-line markup", "Extend other editor markup across the minimap line.");
        markerWords = addTextField(form, "Marker words",
                "Comma-separated words to highlight, for example TODO,FIXME,MARK,region.");
        methodNames = addTextArea(form, "Method names", "Comma/newline-separated filters; * matches any text, ? one character. Empty matches no methods.");
        methodAnnotations = addTextArea(form, "Method annotations", "Comma/newline-separated annotation filters, with optional @; empty allows all annotations.");
        markerRegex = addTextField(form, "Comment marker regex", "Literals, classes, anchors, ordinary escapes; one quantifier maximum. No groups, alternation, lookaround or backreferences. Max 256 characters; scans 512 per line.");
        markerFontSize = addSpinner(form, "Marker font size", 10, 6, 36, "Marker label size: 6 to 36 points.");

        JPanel container = new JPanel(new BorderLayout());
        container.add(form, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(container);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        component = scrollPane;
        reset();
        return component;
    }

    private static JCheckBox addCheckbox(JPanel form, String title, String explanation) {
        JCheckBox checkbox = new JCheckBox(title);
        addRow(form, checkbox, explanation);
        return checkbox;
    }

    private static void addSection(JPanel form, String title) {
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
        heading.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0));
        addRow(form, heading, "");
    }

    private JSpinner[] spinners() {
        return new JSpinner[]{width, diffWidth, previewWidth, consoleWidth, untypedWidth,
                pixelsPerLine, minLines, maxLines, debounceMs, hoverDelayMs,
                viewportOpacity, viewportBorderOpacity, viewportBorderWidth, markerFontSize};
    }

    private static JSpinner addSpinner(JPanel form, String title, int value, int minimum,
                                       int maximum, String explanation) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, 1));
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0");
        spinner.setEditor(editor);
        editor.getTextField().setColumns(8);
        NumberFormatter formatter = (NumberFormatter) editor.getTextField().getFormatter();
        formatter.setValueClass(Integer.class);
        formatter.setCommitsOnValidEdit(true);
        addRow(form, labeledControl(title, spinner), explanation);
        return spinner;
    }

    private static JTextField addTextField(JPanel form, String title, String explanation) {
        JTextField field = new JTextField(24);
        addRow(form, labeledControl(title, field), explanation);
        return field;
    }

    private static JTextArea addTextArea(JPanel form, String title, String explanation) {
        JTextArea field = new JTextArea(3, 24);
        field.setToolTipText(explanation);
        addRow(form, labeledControl(title, new JScrollPane(field)), explanation);
        return field;
    }

    private static JPanel labeledControl(String title, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel(title);
        label.setLabelFor(control);
        row.add(label, BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    private static void addRow(JPanel form, JComponent control, String explanation) {
        int row = form.getComponentCount() / 2;
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(4, 0, 4, 16);
        control.setToolTipText(explanation);
        form.add(control, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.insets = new Insets(4, 0, 4, 0);
        form.add(new JLabel(explanation), constraints);
    }

    @Override
    public boolean isModified() {
        if (component == null) {
            return false;
        }
        CodeScrollSettings.State saved = CodeScrollSettings.getInstance().getState();
        CodeScrollSettings.State current = readForm();
        return current.enabled != saved.enabled
                || current.leftAligned != saved.leftAligned
                || current.fit != saved.fit
                || current.syntaxColors != saved.syntaxColors
                || current.showMarkers != saved.showMarkers
                || current.showMethods != saved.showMethods
                || current.showPreview != saved.showPreview
                || current.scrollOnly != saved.scrollOnly
                || current.showMain != saved.showMain
                || current.showDiff != saved.showDiff
                || current.showPreviewEditors != saved.showPreviewEditors
                || current.showConsole != saved.showConsole
                || current.showUntyped != saved.showUntyped
                || current.hideScrollbar != saved.hideScrollbar
                || current.autoWidth != saved.autoWidth
                || current.lockWidth != saved.lockWidth
                || current.width != saved.width
                || current.pixelsPerLine != saved.pixelsPerLine
                || current.minLines != saved.minLines
                || current.maxLines != saved.maxLines
                || current.debounceMs != saved.debounceMs
                || !Objects.equals(current.excludedExtensions, saved.excludedExtensions)
                || !Objects.equals(current.markerWords, saved.markerWords)
                || current.fill != saved.fill
                || current.renderStyle != saved.renderStyle
                || current.clickTiming != saved.clickTiming
                || current.clickByMousePosition != saved.clickByMousePosition
                || current.diffWidth != saved.diffWidth
                || current.previewWidth != saved.previewWidth
                || current.consoleWidth != saved.consoleWidth
                || current.untypedWidth != saved.untypedWidth
                || current.hoverOnly != saved.hoverOnly
                || current.showQuickHide != saved.showQuickHide
                || current.hoverDelayMs != saved.hoverDelayMs
                || current.emptyConsole != saved.emptyConsole
                || current.emptyUntyped != saved.emptyUntyped
                || current.outOfRangeEmpty != saved.outOfRangeEmpty
                || !Objects.equals(current.viewportColor, saved.viewportColor)
                || !Objects.equals(current.viewportBorderColor, saved.viewportBorderColor)
                || current.viewportOpacity != saved.viewportOpacity
                || current.viewportBorderOpacity != saved.viewportBorderOpacity
                || current.viewportBorderWidth != saved.viewportBorderWidth
                || current.showBookmarks != saved.showBookmarks
                || current.showVcs != saved.showVcs
                || current.showDiagnostics != saved.showDiagnostics
                || current.showSearch != saved.showSearch
                || current.fullLineDiagnostics != saved.fullLineDiagnostics
                || current.fullLineMarkup != saved.fullLineMarkup
                || !Objects.equals(current.methodNames, saved.methodNames)
                || !Objects.equals(current.methodAnnotations, saved.methodAnnotations)
                || !Objects.equals(current.markerRegex, saved.markerRegex)
                || current.markerFontSize != saved.markerFontSize
                || current.showRenderedMarkdown != saved.showRenderedMarkdown
                || current.previewWheel != saved.previewWheel
                || java.util.Arrays.stream(spinners()).anyMatch(CodeScrollConfigurable::hasUncommittedNumber);
    }

    private static boolean hasUncommittedNumber(JSpinner spinner) {
        JTextField field = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField();
        return !field.getText().equals(Integer.toString(number(spinner)));
    }

    @Override
    public void apply() throws ConfigurationException {
        if (component == null) {
            return;
        }
        try {
            for (JSpinner spinner : spinners()) {
                String text = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().getText();
                int value = Integer.parseInt(text);
                SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
                if (value < ((Number) model.getMinimum()).intValue()
                        || value > ((Number) model.getMaximum()).intValue()) {
                    throw new IllegalArgumentException("Number is outside its allowed range.");
                }
            }
            for (JSpinner spinner : spinners()) spinner.commitEdit();
        } catch (ParseException | IllegalArgumentException exception) {
            throw new ConfigurationException("Enter whole numbers within the displayed ranges.");
        }
        CodeScrollSettings.State state = readForm();
        if (state.maxLines < state.minLines) {
            throw new ConfigurationException("Maximum lines must be at least minimum lines.");
        }
        if (!state.viewportColor.trim().matches("#?[0-9a-fA-F]{6}")
                || !state.viewportBorderColor.trim().matches("#?[0-9a-fA-F]{6}")) {
            throw new ConfigurationException("Enter viewport colors as six hexadecimal RGB digits.");
        }
        try {
            MarkerScanner.compileMarkerRegex(state.markerRegex);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("Invalid comment marker regex: " + exception.getMessage());
        }
        state.validate();
        CodeScrollSettings.getInstance().loadState(state);
        CodeScrollManager.getInstance().refreshAll();
        reset();
    }

    private CodeScrollSettings.State readForm() {
        CodeScrollSettings.State state = new CodeScrollSettings.State();
        state.enabled = enabled.isSelected();
        state.leftAligned = leftAligned.isSelected();
        state.fit = fit.isSelected();
        state.syntaxColors = syntaxColors.isSelected();
        state.showMarkers = showMarkers.isSelected();
        state.showMethods = showMethods.isSelected();
        state.showPreview = showPreview.isSelected();
        state.scrollOnly = scrollOnly.isSelected();
        state.showMain = showMain.isSelected();
        state.showDiff = showDiff.isSelected();
        state.showPreviewEditors = showPreviewEditors.isSelected();
        state.showConsole = showConsole.isSelected();
        state.showUntyped = showUntyped.isSelected();
        state.hideScrollbar = hideScrollbar.isSelected();
        state.autoWidth = autoWidth.isSelected();
        state.lockWidth = lockWidth.isSelected();
        state.width = number(width);
        state.pixelsPerLine = number(pixelsPerLine);
        state.minLines = number(minLines);
        state.maxLines = number(maxLines);
        state.debounceMs = number(debounceMs);
        state.excludedExtensions = excludedExtensions.getText();
        state.markerWords = markerWords.getText();
        state.fill = fill.isSelected();
        state.renderStyle = (CodeScrollSettings.RenderStyle) renderStyle.getSelectedItem();
        state.clickTiming = (CodeScrollSettings.ClickTiming) clickTiming.getSelectedItem();
        state.clickByMousePosition = clickByMousePosition.isSelected();
        state.diffWidth = number(diffWidth);
        state.previewWidth = number(previewWidth);
        state.consoleWidth = number(consoleWidth);
        state.untypedWidth = number(untypedWidth);
        state.hoverOnly = hoverOnly.isSelected();
        state.showQuickHide = showQuickHide.isSelected();
        state.hoverDelayMs = number(hoverDelayMs);
        state.emptyConsole = emptyConsole.isSelected();
        state.emptyUntyped = emptyUntyped.isSelected();
        state.outOfRangeEmpty = outOfRangeEmpty.isSelected();
        state.viewportColor = viewportColor.getText();
        state.viewportBorderColor = viewportBorderColor.getText();
        state.viewportOpacity = number(viewportOpacity);
        state.viewportBorderOpacity = number(viewportBorderOpacity);
        state.viewportBorderWidth = number(viewportBorderWidth);
        state.showBookmarks = showBookmarks.isSelected();
        state.showVcs = showVcs.isSelected();
        state.showDiagnostics = showDiagnostics.isSelected();
        state.showSearch = showSearch.isSelected();
        state.fullLineDiagnostics = fullLineDiagnostics.isSelected();
        state.fullLineMarkup = fullLineMarkup.isSelected();
        state.methodNames = methodNames.getText();
        state.methodAnnotations = methodAnnotations.getText();
        state.markerRegex = markerRegex.getText();
        state.markerFontSize = number(markerFontSize);
        state.showRenderedMarkdown = showRenderedMarkdown.isSelected();
        state.previewWheel = previewWheel.isSelected();
        return state;
    }

    private static int number(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    @Override
    public void reset() {
        if (component == null) {
            return;
        }
        CodeScrollSettings.State state = CodeScrollSettings.getInstance().getState();
        enabled.setSelected(state.enabled);
        leftAligned.setSelected(state.leftAligned);
        fit.setSelected(state.fit);
        syntaxColors.setSelected(state.syntaxColors);
        showMarkers.setSelected(state.showMarkers);
        showMethods.setSelected(state.showMethods);
        showPreview.setSelected(state.showPreview);
        scrollOnly.setSelected(state.scrollOnly);
        showMain.setSelected(state.showMain);
        showDiff.setSelected(state.showDiff);
        showPreviewEditors.setSelected(state.showPreviewEditors);
        showConsole.setSelected(state.showConsole);
        showUntyped.setSelected(state.showUntyped);
        hideScrollbar.setSelected(state.hideScrollbar);
        autoWidth.setSelected(state.autoWidth);
        lockWidth.setSelected(state.lockWidth);
        width.setValue(state.width);
        pixelsPerLine.setValue(state.pixelsPerLine);
        minLines.setValue(state.minLines);
        maxLines.setValue(state.maxLines);
        debounceMs.setValue(state.debounceMs);
        excludedExtensions.setText(state.excludedExtensions);
        markerWords.setText(state.markerWords);
        fill.setSelected(state.fill);
        renderStyle.setSelectedItem(state.renderStyle);
        clickTiming.setSelectedItem(state.clickTiming);
        clickByMousePosition.setSelected(state.clickByMousePosition);
        diffWidth.setValue(state.diffWidth);
        previewWidth.setValue(state.previewWidth);
        consoleWidth.setValue(state.consoleWidth);
        untypedWidth.setValue(state.untypedWidth);
        hoverOnly.setSelected(state.hoverOnly);
        showQuickHide.setSelected(state.showQuickHide);
        hoverDelayMs.setValue(state.hoverDelayMs);
        emptyConsole.setSelected(state.emptyConsole);
        emptyUntyped.setSelected(state.emptyUntyped);
        outOfRangeEmpty.setSelected(state.outOfRangeEmpty);
        viewportColor.setText(state.viewportColor);
        viewportBorderColor.setText(state.viewportBorderColor);
        viewportOpacity.setValue(state.viewportOpacity);
        viewportBorderOpacity.setValue(state.viewportBorderOpacity);
        viewportBorderWidth.setValue(state.viewportBorderWidth);
        showBookmarks.setSelected(state.showBookmarks);
        showVcs.setSelected(state.showVcs);
        showDiagnostics.setSelected(state.showDiagnostics);
        showSearch.setSelected(state.showSearch);
        fullLineDiagnostics.setSelected(state.fullLineDiagnostics);
        fullLineMarkup.setSelected(state.fullLineMarkup);
        methodNames.setText(state.methodNames);
        methodAnnotations.setText(state.methodAnnotations);
        markerRegex.setText(state.markerRegex);
        markerFontSize.setValue(state.markerFontSize);
        showRenderedMarkdown.setSelected(state.showRenderedMarkdown);
        previewWheel.setSelected(state.previewWheel);
        // Setting an unchanged model value does not clear invalid text in its editor.
        for (JSpinner spinner : spinners()) {
            ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().setValue(spinner.getValue());
        }
    }

    @Override
    public void disposeUIResources() {
        component = null;
        enabled = null;
        leftAligned = null;
        fit = null;
        syntaxColors = null;
        showMarkers = null;
        showMethods = null;
        showPreview = null;
        scrollOnly = null;
        showMain = null;
        showDiff = null;
        showPreviewEditors = null;
        showConsole = null;
        showUntyped = null;
        hideScrollbar = null;
        autoWidth = null;
        lockWidth = null;
        width = null;
        pixelsPerLine = null;
        minLines = null;
        maxLines = null;
        debounceMs = null;
        excludedExtensions = null;
        markerWords = null;
        fill = null;
        renderStyle = null;
        clickTiming = null;
        clickByMousePosition = null;
        diffWidth = null;
        previewWidth = null;
        consoleWidth = null;
        untypedWidth = null;
        hoverOnly = null;
        showQuickHide = null;
        hoverDelayMs = null;
        emptyConsole = null;
        emptyUntyped = null;
        outOfRangeEmpty = null;
        viewportColor = null;
        viewportBorderColor = null;
        viewportOpacity = null;
        viewportBorderOpacity = null;
        viewportBorderWidth = null;
        showBookmarks = null;
        showVcs = null;
        showDiagnostics = null;
        showSearch = null;
        fullLineDiagnostics = null;
        fullLineMarkup = null;
        methodNames = null;
        methodAnnotations = null;
        markerRegex = null;
        markerFontSize = null;
        showRenderedMarkdown = null;
        previewWheel = null;
    }
}
