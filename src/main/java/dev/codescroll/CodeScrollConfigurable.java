package dev.codescroll;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
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
        enabled = addCheckbox(form, "Enable minimap", "Show the Code Scroll minimap globally.");
        leftAligned = addCheckbox(form, "Align left", "Place the minimap on the left side of the editor.");
        fit = addCheckbox(form, "Fit to editor height", "Scale the document to fit the available minimap height.");
        syntaxColors = addCheckbox(form, "Syntax colors", "Use syntax highlighting colors in the minimap.");
        showMarkers = addCheckbox(form, "Show markers", "Highlight occurrences of the configured marker words.");
        showMethods = addCheckbox(form, "Show methods", "Show method locations in the minimap.");
        showPreview = addCheckbox(form, "Show hover preview", "Preview source code when hovering over the minimap.");
        scrollOnly = addCheckbox(form, "Scroll only", "Navigate by scrolling without moving the text caret.");
        showMain = addCheckbox(form, "Main editors", "Show the minimap in regular file editors.");
        showDiff = addCheckbox(form, "Diff editors", "Show the minimap in diff views.");
        showPreviewEditors = addCheckbox(form, "Preview editors", "Show the minimap in preview editor views.");
        showConsole = addCheckbox(form, "Console editors", "Show the minimap in console output views.");
        showUntyped = addCheckbox(form, "Untyped editors", "Show the minimap in editors without a recognized file type.");
        hideScrollbar = addCheckbox(form, "Hide editor scrollbar", "Hide the editor's normal vertical scrollbar.");
        autoWidth = addCheckbox(form, "Automatic width", "Reduce minimap width in narrow and split editors.");
        lockWidth = addCheckbox(form, "Lock width", "Keep the minimap width fixed instead of allowing resizing.");
        width = addSpinner(form, "Width (px)", 110, 40, 300, "Minimap width: 40 to 300 pixels.");
        pixelsPerLine = addSpinner(form, "Pixels per line", 2, 1, 8,
                "Line height when not fitting the document: 1 to 8 pixels.");
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
        markerWords = addTextField(form, "Marker words",
                "Comma-separated words to highlight, for example TODO,FIXME,MARK,region.");

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
                || hasUncommittedNumber(width)
                || hasUncommittedNumber(pixelsPerLine)
                || hasUncommittedNumber(minLines)
                || hasUncommittedNumber(maxLines)
                || hasUncommittedNumber(debounceMs);
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
            for (JSpinner spinner : new JSpinner[]{width, pixelsPerLine, minLines, maxLines, debounceMs}) {
                String text = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().getText();
                int value = Integer.parseInt(text);
                SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
                if (value < ((Number) model.getMinimum()).intValue()
                        || value > ((Number) model.getMaximum()).intValue()) {
                    throw new IllegalArgumentException("Number is outside its allowed range.");
                }
                spinner.commitEdit();
            }
        } catch (ParseException | IllegalArgumentException exception) {
            throw new ConfigurationException("Enter whole numbers within the displayed ranges.");
        }
        CodeScrollSettings.State state = readForm();
        state.validate();
        CodeScrollSettings.getInstance().loadState(state);
        CodeScrollManager.getInstance().refreshAll();
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
        // Setting an unchanged model value does not clear invalid text in its editor.
        for (JSpinner spinner : new JSpinner[]{width, pixelsPerLine, minLines, maxLines, debounceMs}) {
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
    }
}
