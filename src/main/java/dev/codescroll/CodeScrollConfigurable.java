package dev.codescroll;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.ColorPanel;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.NumberFormatter;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class CodeScrollConfigurable implements SearchableConfigurable {
    @Override
    public @NotNull String getId() {
        return "dev.codescroll.settings";
    }

    /**
     * A color that follows the editor color scheme until "Custom" is selected; the picker then
     * stores an explicit RRGGBB value. While not custom, the picker previews the scheme color.
     */
    private static final class ColorChoice {
        final JBCheckBox custom = new JBCheckBox("Custom");
        final ColorPanel picker = new ColorPanel();
        final Function<EditorColorsScheme, Color> schemeColor;
        final JPanel component = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));

        ColorChoice(Function<EditorColorsScheme, Color> schemeColor) {
            this.schemeColor = schemeColor;
            component.setOpaque(false);
            component.add(picker);
            component.add(Box.createHorizontalStrut(JBUI.scale(8)));
            component.add(custom);
            custom.addActionListener(event -> {
                if (!custom.isSelected()) picker.setSelectedColor(schemeColor());
                picker.setEnabled(custom.isSelected());
            });
        }

        private Color schemeColor() {
            return schemeColor.apply(EditorColorsManager.getInstance().getGlobalScheme());
        }

        void reset(String hex) {
            String normalized = CodeScrollSettings.normalizeColor(hex);
            custom.setSelected(!normalized.isEmpty());
            picker.setSelectedColor(normalized.isEmpty() ? schemeColor() : new Color(Integer.parseInt(normalized, 16)));
            picker.setEnabled(custom.isSelected());
        }

        String value() {
            return custom.isSelected() ? CodeScrollSettings.hex(picker.getSelectedColor()) : "";
        }
    }

    private JComponent component;
    private final List<JSpinner> spinners = new ArrayList<>();
    private JBCheckBox enabled;
    private JBCheckBox leftAligned;
    private JBCheckBox fit;
    private JBCheckBox fill;
    private JBCheckBox syntaxColors;
    private JComboBox<CodeScrollSettings.RenderStyle> renderStyle;
    private JSpinner textIntensity;
    private JBCheckBox hideScrollbar;
    private JBCheckBox autoWidth;
    private JBCheckBox lockWidth;
    private JSpinner width;
    private JSpinner diffWidth;
    private JSpinner previewWidth;
    private JSpinner consoleWidth;
    private JSpinner untypedWidth;
    private JSpinner pixelsPerLine;
    private JBCheckBox showPreview;
    private JBCheckBox scrollOnly;
    private JComboBox<CodeScrollSettings.ClickTiming> clickTiming;
    private JBCheckBox clickByMousePosition;
    private JBCheckBox hoverOnly;
    private JBCheckBox showQuickHide;
    private JSpinner hoverDelayMs;
    private JBCheckBox previewWheel;
    private JBCheckBox previewLineNumbers;
    private JBCheckBox showRenderedMarkdown;
    private JBCheckBox markdownSyncSource;
    private JBCheckBox showMain;
    private JBCheckBox showDiff;
    private JBCheckBox showPreviewEditors;
    private JBCheckBox showConsole;
    private JBCheckBox showUntyped;
    private JBCheckBox emptyConsole;
    private JBCheckBox emptyUntyped;
    private JBCheckBox outOfRangeEmpty;
    private JSpinner minLines;
    private JSpinner maxLines;
    private JSpinner debounceMs;
    private JBTextField excludedExtensions;
    private ColorChoice viewportColor;
    private ColorChoice viewportBorderColor;
    private ColorChoice caretLineColor;
    private ColorChoice selectionColor;
    private ColorChoice markerLabelColor;
    private ColorChoice declarationLabelColor;
    private JSpinner viewportOpacity;
    private JSpinner viewportBorderOpacity;
    private JSpinner viewportBorderWidth;
    private JBCheckBox showMarkers;
    private JBCheckBox showMethods;
    private JBCheckBox showBookmarks;
    private JBCheckBox showVcs;
    private JBCheckBox showDiagnostics;
    private JBCheckBox showErrors;
    private JBCheckBox showWarnings;
    private JBCheckBox showSearch;
    private JBCheckBox fullLineDiagnostics;
    private JBCheckBox fullLineMarkup;
    private JBTextField markerWords;
    private JBTextArea methodNames;
    private JBTextArea methodAnnotations;
    private JBTextField markerRegex;
    private JSpinner markerFontSize;

    @Override
    public @NotNull String getDisplayName() {
        return "Code Scroll";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (component != null) return component;
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(JBUI.Borders.empty(4, 0, 16, 0));

        section(form, "Appearance");
        enabled = checkbox(form, "Enable minimap", "Show the Code Scroll minimap globally.");
        leftAligned = checkbox(form, "Align left", "Place the minimap on the left side of the editor.");
        fit = checkbox(form, "Fit to editor height", "Scale the document to fit the available minimap height.");
        fill = checkbox(form, "Fill editor height", "With Fit, stretch short documents to the full height.");
        syntaxColors = checkbox(form, "Syntax colors", "Use the editor's syntax highlighting colors in the minimap.");
        renderStyle = new JComboBox<>(CodeScrollSettings.RenderStyle.values());
        row(form, "Render style:", renderStyle, "CLEAN draws solid strokes with a light row between lines; ACCURATE also shows capitals, ascenders and descenders.");
        textIntensity = spinner(form, "Text intensity (%):", 20, 100,
                "How strongly minimap text stands out from the editor background, 20 to 100.");
        pixelsPerLine = spinner(form, "Pixels per line:", 1, 8, "Line height when not fitting the document, 1 to 8.");
        hideScrollbar = checkbox(form, "Hide editor scrollbar", "Hide the editor's own vertical scrollbar while a minimap is shown.");

        section(form, "Width");
        autoWidth = checkbox(form, "Automatic width", "Reduce the minimap width in narrow and split editors.");
        lockWidth = checkbox(form, "Lock width", "Disable resizing by dragging the minimap edge.");
        width = spinner(form, "Main editors (px):", 40, 300, "Width in regular file editors, 40 to 300.");
        diffWidth = spinner(form, "Diff editors (px):", 40, 300, "Width in diff and merge views, 40 to 300.");
        previewWidth = spinner(form, "Preview editors (px):", 40, 300, "Width in preview editors, 40 to 300.");
        consoleWidth = spinner(form, "Consoles (px):", 40, 300, "Width in console output, 40 to 300.");
        untypedWidth = spinner(form, "Untyped editors (px):", 40, 300, "Width in editors without a file type, 40 to 300.");

        section(form, "Colors");
        viewportColor = color(form, "Viewport:", scheme -> key(scheme, EditorColors.SELECTION_BACKGROUND_COLOR),
                "Fill of the visible-area frame. Follows the scheme's selection background unless Custom.");
        viewportOpacity = spinner(form, "Viewport opacity:", 0, 255, "Alpha of the viewport fill: 0 transparent, 255 opaque.");
        viewportBorderColor = color(form, "Viewport border:", scheme -> key(scheme, EditorColors.SELECTION_BACKGROUND_COLOR),
                "Border of the visible-area frame. Follows the scheme's selection background unless Custom.");
        viewportBorderOpacity = spinner(form, "Border opacity:", 0, 255, "Alpha of the viewport border: 0 transparent, 255 opaque.");
        viewportBorderWidth = spinner(form, "Border width (px):", 0, 8, "Thickness of the viewport border; 0 hides it.");
        caretLineColor = color(form, "Caret line:", scheme -> key(scheme, EditorColors.CARET_COLOR),
                "Line marking the caret position. Follows the scheme's caret color unless Custom.");
        selectionColor = color(form, "Selection:", scheme -> key(scheme, EditorColors.SELECTION_BACKGROUND_COLOR),
                "Band marking selected lines. Follows the scheme's selection background unless Custom.");
        markerLabelColor = color(form, "Marker labels:", scheme -> foreground(scheme, CodeInsightColors.TODO_DEFAULT_ATTRIBUTES),
                "TODO, region and custom marker labels. Follows the scheme's TODO color unless Custom.");
        declarationLabelColor = color(form, "Declaration labels:",
                scheme -> foreground(scheme, DefaultLanguageHighlighterColors.FUNCTION_DECLARATION),
                "Method and class labels. Follows the scheme's function declaration color unless Custom.");

        section(form, "Navigation and Preview");
        clickTiming = new JComboBox<>(CodeScrollSettings.ClickTiming.values());
        row(form, "Click timing:", clickTiming, "Navigate on mouse DOWN or UP; NONE allows only dragging the viewport.");
        clickByMousePosition = checkbox(form, "Click by mouse position", "Place the caret at the clicked column, not just the clicked line.");
        scrollOnly = checkbox(form, "Scroll only", "Scroll the editor on click without moving the caret or selection.");
        showPreview = checkbox(form, "Show hover preview", "Show the code under the pointer in a popup while hovering.");
        previewLineNumbers = checkbox(form, "Line numbers in preview", "Show the document's line numbers next to the hover preview.");
        previewWheel = checkbox(form, "Scroll preview with mouse wheel", "While the preview is open, the wheel moves the preview instead of the editor.");
        hoverDelayMs = spinner(form, "Hover delay (ms):", 0, 2_000, "Delay before the preview or a hover-only minimap appears.");
        hoverOnly = checkbox(form, "Show only on hover", "Keep the minimap collapsed to a narrow strip until the pointer rests on it.");
        showQuickHide = checkbox(form, "Show quick hide button", "Show a × button that hides the minimap in the current editor.");
        showRenderedMarkdown = checkbox(form, "Rendered Markdown minimap", "Show a minimap inside the rendered Markdown preview.");
        markdownSyncSource = checkbox(form, "Sync source with Markdown minimap", "Scrolling with the preview minimap also scrolls the Markdown source editor.");

        section(form, "Editors");
        showMain = checkbox(form, "Main editors", "Show the minimap in regular file editors.");
        showDiff = checkbox(form, "Diff editors", "Show the minimap in diff and merge views.");
        showPreviewEditors = checkbox(form, "Preview editors", "Show the minimap in preview editors.");
        showConsole = checkbox(form, "Consoles", "Show the minimap in console output.");
        emptyConsole = checkbox(form, "Keep console minimap empty", "Draw only the viewport in consoles, without text.");
        showUntyped = checkbox(form, "Untyped editors", "Show the minimap in editors without a recognized file type.");
        emptyUntyped = checkbox(form, "Keep untyped minimap empty", "Draw only the viewport in untyped editors, without text.");
        minLines = spinner(form, "Minimum lines:", 0, 1_000_000, "Hide the minimap for shorter documents; 0 disables the limit.");
        maxLines = spinner(form, "Maximum lines:", 0, 1_000_000, "Hide the minimap for longer documents; at least the minimum.");
        minLines.addChangeListener(event -> {
            int minimum = number(minLines);
            if (number(maxLines) < minimum) maxLines.setValue(minimum);
            ((SpinnerNumberModel) maxLines.getModel()).setMinimum(minimum);
        });
        outOfRangeEmpty = checkbox(form, "Keep an empty minimap outside the line limits", "Show an empty, still navigable minimap instead of hiding it.");
        excludedExtensions = textField(form, "Excluded extensions:", "Comma-separated extensions without dots, for example ipynb,log.");
        debounceMs = spinner(form, "Update delay (ms):", 50, 2_000, "Delay after edits before the minimap is redrawn.");

        section(form, "Markers and Highlights");
        showDiagnostics = checkbox(form, "Errors and warnings", "Mark diagnostics on the far edge of the minimap. Also in the minimap context menu and View | Toggle Code Scroll Diagnostics.");
        showErrors = checkbox(form, "Errors", "Show error marks.");
        showWarnings = checkbox(form, "Warnings", "Show warning, weak warning and other inspection marks.");
        showDiagnostics.addActionListener(event -> {
            showErrors.setEnabled(showDiagnostics.isSelected());
            showWarnings.setEnabled(showDiagnostics.isSelected());
        });
        fullLineDiagnostics = checkbox(form, "Full-line diagnostics", "Draw diagnostics across the whole minimap line.");
        showVcs = checkbox(form, "VCS changes", "Mark added, changed and deleted lines on the editor-facing edge.");
        showBookmarks = checkbox(form, "Bookmarks", "Mark bookmarked lines.");
        showSearch = checkbox(form, "Search results and usages", "Highlight find results and highlighted usages.");
        fullLineMarkup = checkbox(form, "Full-line other markup", "Draw remaining editor highlights across the whole line.");
        showMarkers = checkbox(form, "Comment markers", "Label comments that contain the marker words or regex below.");
        markerWords = textField(form, "Marker words:", "Comma-separated words, for example TODO,FIXME,MARK,region.");
        markerRegex = textField(form, "Marker regex:",
                "Literals, classes, anchors and simple escapes, one quantifier at most; no groups or alternation. Up to 256 characters.");
        showMethods = checkbox(form, "Declarations", "Label method and class declarations.");
        methodNames = textArea(form, "Method names:", "Methods to label, comma- or newline-separated; * matches any text, ? one character. Empty labels no methods (classes and types are always labelled).");
        methodAnnotations = textArea(form, "Method annotations:", "Comma- or newline-separated annotation filters, @ optional. Empty allows all methods.");
        markerFontSize = spinner(form, "Label font size:", 6, 36, "Size of marker and declaration labels, 6 to 36.");

        GridBagConstraints filler = cell(nextRow(form), 0, 2, 0, 0);
        filler.weighty = 1;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        form.add(spacer, filler);
        JBScrollPane scrollPane = new JBScrollPane(form);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        component = scrollPane;
        reset();
        return component;
    }

    private static Color key(EditorColorsScheme scheme, ColorKey key) {
        Color color = scheme.getColor(key);
        return color != null ? color : scheme.getDefaultForeground();
    }

    private static Color foreground(EditorColorsScheme scheme, TextAttributesKey key) {
        var attributes = scheme.getAttributes(key);
        return attributes != null && attributes.getForegroundColor() != null
                ? attributes.getForegroundColor() : scheme.getDefaultForeground();
    }

    private static int nextRow(JPanel form) {
        Integer row = (Integer) form.getClientProperty(CodeScrollConfigurable.class);
        int value = row == null ? 0 : row;
        form.putClientProperty(CodeScrollConfigurable.class, value + 1);
        return value;
    }

    private static GridBagConstraints cell(int row, int column, int width, int top, int left) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = width;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = JBUI.insets(top, left, 0, 0);
        return constraints;
    }

    private static void section(JPanel form, String title) {
        GridBagConstraints constraints = cell(nextRow(form), 0, 2, 14, 0);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        form.add(new TitledSeparator(title), constraints);
    }

    /** The explanation sits under its control as a small, secondary-colored comment line. */
    private static void comment(JPanel form, int column, int left, String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(JBFont.small());
        form.add(label, cell(nextRow(form), column, 2 - column, 2, left));
    }

    private static JBCheckBox checkbox(JPanel form, String title, String comment) {
        JBCheckBox checkbox = new JBCheckBox(title);
        form.add(checkbox, cell(nextRow(form), 0, 2, 8, 16));
        comment(form, 0, 16 + UIUtil.getCheckBoxTextHorizontalOffset(checkbox), comment);
        return checkbox;
    }

    private static void row(JPanel form, String label, JComponent control, String comment) {
        JBLabel title = new JBLabel(label);
        title.setLabelFor(control);
        GridBagConstraints labelCell = cell(nextRow(form), 0, 1, 8, 16);
        form.add(title, labelCell);
        GridBagConstraints controlCell = (GridBagConstraints) labelCell.clone();
        controlCell.gridx = 1;
        controlCell.insets = JBUI.insets(8, 8, 0, 0);
        controlCell.weightx = 1;
        form.add(control, controlCell);
        comment(form, 1, 8, comment);
    }

    private JSpinner spinner(JPanel form, String label, int minimum, int maximum, String comment) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(minimum, minimum, maximum, 1));
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0");
        spinner.setEditor(editor);
        editor.getTextField().setColumns(7);
        NumberFormatter formatter = (NumberFormatter) editor.getTextField().getFormatter();
        formatter.setValueClass(Integer.class);
        formatter.setCommitsOnValidEdit(true);
        row(form, label, spinner, comment);
        spinners.add(spinner);
        return spinner;
    }

    private static JBTextField textField(JPanel form, String label, String comment) {
        JBTextField field = new JBTextField(28);
        row(form, label, field, comment);
        return field;
    }

    private static JBTextArea textArea(JPanel form, String label, String comment) {
        JBTextArea area = new JBTextArea(3, 28);
        row(form, label, new JBScrollPane(area), comment);
        return area;
    }

    private static ColorChoice color(JPanel form, String label, Function<EditorColorsScheme, Color> schemeColor,
                                     String comment) {
        ColorChoice choice = new ColorChoice(schemeColor);
        row(form, label, choice.component, comment);
        return choice;
    }

    @Override
    public boolean isModified() {
        if (component == null) return false;
        if (spinners.stream().anyMatch(CodeScrollConfigurable::hasUncommittedNumber)) return true;
        return !sameState(readForm(), CodeScrollSettings.getInstance().getState());
    }

    /** Compares every persisted field, so new settings cannot be forgotten in isModified. */
    static boolean sameState(CodeScrollSettings.State first, CodeScrollSettings.State second) {
        try {
            for (Field field : CodeScrollSettings.State.class.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!Objects.equals(field.get(first), field.get(second))) return false;
            }
            return true;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean hasUncommittedNumber(JSpinner spinner) {
        JTextField field = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField();
        return !field.getText().equals(Integer.toString(number(spinner)));
    }

    @Override
    public void apply() throws ConfigurationException {
        if (component == null) return;
        try {
            for (JSpinner spinner : spinners) {
                int value = Integer.parseInt(((JSpinner.NumberEditor) spinner.getEditor()).getTextField().getText());
                SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
                if (value < ((Number) model.getMinimum()).intValue() || value > ((Number) model.getMaximum()).intValue()) {
                    throw new IllegalArgumentException("Number is outside its allowed range.");
                }
            }
            for (JSpinner spinner : spinners) spinner.commitEdit();
        } catch (ParseException | IllegalArgumentException exception) {
            throw new ConfigurationException("Enter whole numbers within the described ranges.");
        }
        CodeScrollSettings.State state = readForm();
        if (state.maxLines < state.minLines) {
            throw new ConfigurationException("Maximum lines must be at least minimum lines.");
        }
        try {
            MarkerScanner.compileMarkerRegex(state.markerRegex);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException("Invalid marker regex: " + exception.getMessage());
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
        state.fill = fill.isSelected();
        state.syntaxColors = syntaxColors.isSelected();
        state.renderStyle = (CodeScrollSettings.RenderStyle) renderStyle.getSelectedItem();
        state.textIntensity = number(textIntensity);
        state.pixelsPerLine = number(pixelsPerLine);
        state.hideScrollbar = hideScrollbar.isSelected();
        state.autoWidth = autoWidth.isSelected();
        state.lockWidth = lockWidth.isSelected();
        state.width = number(width);
        state.diffWidth = number(diffWidth);
        state.previewWidth = number(previewWidth);
        state.consoleWidth = number(consoleWidth);
        state.untypedWidth = number(untypedWidth);
        state.viewportColor = viewportColor.value();
        state.viewportOpacity = number(viewportOpacity);
        state.viewportBorderColor = viewportBorderColor.value();
        state.viewportBorderOpacity = number(viewportBorderOpacity);
        state.viewportBorderWidth = number(viewportBorderWidth);
        state.caretLineColor = caretLineColor.value();
        state.selectionColor = selectionColor.value();
        state.markerLabelColor = markerLabelColor.value();
        state.declarationLabelColor = declarationLabelColor.value();
        state.clickTiming = (CodeScrollSettings.ClickTiming) clickTiming.getSelectedItem();
        state.clickByMousePosition = clickByMousePosition.isSelected();
        state.scrollOnly = scrollOnly.isSelected();
        state.showPreview = showPreview.isSelected();
        state.previewWheel = previewWheel.isSelected();
        state.previewLineNumbers = previewLineNumbers.isSelected();
        state.hoverDelayMs = number(hoverDelayMs);
        state.hoverOnly = hoverOnly.isSelected();
        state.showQuickHide = showQuickHide.isSelected();
        state.showRenderedMarkdown = showRenderedMarkdown.isSelected();
        state.markdownSyncSource = markdownSyncSource.isSelected();
        state.showMain = showMain.isSelected();
        state.showDiff = showDiff.isSelected();
        state.showPreviewEditors = showPreviewEditors.isSelected();
        state.showConsole = showConsole.isSelected();
        state.emptyConsole = emptyConsole.isSelected();
        state.showUntyped = showUntyped.isSelected();
        state.emptyUntyped = emptyUntyped.isSelected();
        state.minLines = number(minLines);
        state.maxLines = number(maxLines);
        state.outOfRangeEmpty = outOfRangeEmpty.isSelected();
        state.excludedExtensions = excludedExtensions.getText();
        state.debounceMs = number(debounceMs);
        state.showDiagnostics = showDiagnostics.isSelected();
        state.showErrors = showErrors.isSelected();
        state.showWarnings = showWarnings.isSelected();
        state.fullLineDiagnostics = fullLineDiagnostics.isSelected();
        state.showVcs = showVcs.isSelected();
        state.showBookmarks = showBookmarks.isSelected();
        state.showSearch = showSearch.isSelected();
        state.fullLineMarkup = fullLineMarkup.isSelected();
        state.showMarkers = showMarkers.isSelected();
        state.markerWords = markerWords.getText();
        state.markerRegex = markerRegex.getText();
        state.showMethods = showMethods.isSelected();
        state.methodNames = methodNames.getText();
        state.methodAnnotations = methodAnnotations.getText();
        state.markerFontSize = number(markerFontSize);
        return state;
    }

    private static int number(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    @Override
    public void reset() {
        if (component == null) return;
        CodeScrollSettings.State state = CodeScrollSettings.getInstance().getState();
        enabled.setSelected(state.enabled);
        leftAligned.setSelected(state.leftAligned);
        fit.setSelected(state.fit);
        fill.setSelected(state.fill);
        syntaxColors.setSelected(state.syntaxColors);
        renderStyle.setSelectedItem(state.renderStyle);
        textIntensity.setValue(state.textIntensity);
        pixelsPerLine.setValue(state.pixelsPerLine);
        hideScrollbar.setSelected(state.hideScrollbar);
        autoWidth.setSelected(state.autoWidth);
        lockWidth.setSelected(state.lockWidth);
        width.setValue(state.width);
        diffWidth.setValue(state.diffWidth);
        previewWidth.setValue(state.previewWidth);
        consoleWidth.setValue(state.consoleWidth);
        untypedWidth.setValue(state.untypedWidth);
        viewportColor.reset(state.viewportColor);
        viewportOpacity.setValue(state.viewportOpacity);
        viewportBorderColor.reset(state.viewportBorderColor);
        viewportBorderOpacity.setValue(state.viewportBorderOpacity);
        viewportBorderWidth.setValue(state.viewportBorderWidth);
        caretLineColor.reset(state.caretLineColor);
        selectionColor.reset(state.selectionColor);
        markerLabelColor.reset(state.markerLabelColor);
        declarationLabelColor.reset(state.declarationLabelColor);
        clickTiming.setSelectedItem(state.clickTiming);
        clickByMousePosition.setSelected(state.clickByMousePosition);
        scrollOnly.setSelected(state.scrollOnly);
        showPreview.setSelected(state.showPreview);
        previewWheel.setSelected(state.previewWheel);
        previewLineNumbers.setSelected(state.previewLineNumbers);
        hoverDelayMs.setValue(state.hoverDelayMs);
        hoverOnly.setSelected(state.hoverOnly);
        showQuickHide.setSelected(state.showQuickHide);
        showRenderedMarkdown.setSelected(state.showRenderedMarkdown);
        markdownSyncSource.setSelected(state.markdownSyncSource);
        showMain.setSelected(state.showMain);
        showDiff.setSelected(state.showDiff);
        showPreviewEditors.setSelected(state.showPreviewEditors);
        showConsole.setSelected(state.showConsole);
        emptyConsole.setSelected(state.emptyConsole);
        showUntyped.setSelected(state.showUntyped);
        emptyUntyped.setSelected(state.emptyUntyped);
        minLines.setValue(state.minLines);
        maxLines.setValue(state.maxLines);
        outOfRangeEmpty.setSelected(state.outOfRangeEmpty);
        excludedExtensions.setText(state.excludedExtensions);
        debounceMs.setValue(state.debounceMs);
        showDiagnostics.setSelected(state.showDiagnostics);
        showErrors.setSelected(state.showErrors);
        showWarnings.setSelected(state.showWarnings);
        showErrors.setEnabled(state.showDiagnostics);
        showWarnings.setEnabled(state.showDiagnostics);
        fullLineDiagnostics.setSelected(state.fullLineDiagnostics);
        showVcs.setSelected(state.showVcs);
        showBookmarks.setSelected(state.showBookmarks);
        showSearch.setSelected(state.showSearch);
        fullLineMarkup.setSelected(state.fullLineMarkup);
        showMarkers.setSelected(state.showMarkers);
        markerWords.setText(state.markerWords);
        markerRegex.setText(state.markerRegex);
        showMethods.setSelected(state.showMethods);
        methodNames.setText(state.methodNames);
        methodAnnotations.setText(state.methodAnnotations);
        markerFontSize.setValue(state.markerFontSize);
        // Setting an unchanged model value does not clear invalid text in its editor.
        for (JSpinner spinner : spinners) {
            ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().setValue(spinner.getValue());
        }
    }

    @Override
    public void disposeUIResources() {
        // Fields are rebuilt by createComponent; dropping the root releases the whole Swing tree.
        component = null;
        spinners.clear();
    }
}
