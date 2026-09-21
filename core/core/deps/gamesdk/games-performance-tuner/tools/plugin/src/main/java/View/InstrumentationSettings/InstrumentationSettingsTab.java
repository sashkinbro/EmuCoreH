/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package View.InstrumentationSettings;

import Controller.InstrumentationSettings.InstrumentationSettingsTabController;
import Controller.InstrumentationSettings.InstrumentationSettingsTableModel;
import Utils.Resources.ResourceLoader;
import Utils.UI.UIValidator;
import Utils.Validation.ValidationTool;
import View.Decorator.RoundedCornerBorder;
import View.Decorator.RoundedCornerBorder.BorderType;
import View.Decorator.TableRenderer;
import View.Decorator.TableRenderer.RoundedCornerRenderer;
import View.TabLayout;
import com.google.tuningfork.Tuningfork.Settings.Histogram;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.cellvalidators.TableCellValidator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import java.awt.Dimension;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Hashtable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.TableColumn;
import org.jdesktop.swingx.HorizontalLayout;
import org.jdesktop.swingx.VerticalLayout;
import org.jdesktop.swingx.prompt.PromptSupport;
import org.jdesktop.swingx.prompt.PromptSupport.FocusBehavior;

public class InstrumentationSettingsTab extends TabLayout implements PropertyChangeListener {

  private final static ResourceLoader RESOURCE_LOADER = ResourceLoader.getInstance();
  private final static float LOWEST_RECOMMENDED_BUCKET = (float) (1.00 / 60 * 1000);
  private final static float HIGHEST_RECOMMENDED_BUCKET = (float) (1.00 / 30 * 1000);
  private final JBLabel instrumentationLabel = new JBLabel("Instrumentation Settings");
  private JPanel decoratorPanel;

  private JRadioButton radioButtonTimeBased;
  private JRadioButton radioButtonIntervalBased;
  private ButtonGroup radioButtonGroup;
  private final Disposable disposable;
  private final InstrumentationSettingsTabController controller;
  private JTextField apiKey;
  private JBTable instrumentationTable;
  private JSlider intervalSlider;
  private JLabel sliderValueLabel;
  private final JBLabel informationLabel = new JBLabel(
      "<html> Choose your preferred settings for game analysis. <br>" +
          "Histograms:</html>");
  private JTextField baseUrl;
  private JPanel radioButtonsPanel;

  public InstrumentationSettingsTab(InstrumentationSettingsTabController controller,
      Disposable disposable) {
    this.controller = controller;
    this.disposable = disposable;
    initVariables();
    initComponents();
    addComponents();
    loadData();
    initValidators();
  }

  private void initValidators() {
    UIValidator.createTextValidator(disposable, apiKey, () -> {
      if (apiKey.getText().isEmpty()) {
        return new ValidationInfo(RESOURCE_LOADER.get("field_empty_error"));
      }
      return null;
    });
    UIValidator.createTextValidator(disposable, baseUrl, () -> {
      if (baseUrl.getText().isEmpty()) {
        return new ValidationInfo(RESOURCE_LOADER.get("field_empty_error"));
      }
      return null;
    });
    UIValidator.createTableValidator(disposable, instrumentationTable, () -> {
      int histogramsCount = controller.getDataModel().getHistogramsCount();
      if (histogramsCount == 0) {
        return new ValidationInfo(RESOURCE_LOADER.get("empty_table_error"));
      }
      for (int i = 0; i < histogramsCount; i++) {
        Histogram histogram = controller.getDataModel().getHistograms(i);
        if (histogram.getBucketMin() > histogram.getBucketMax()) {
          return new ValidationInfo(
              String.format(RESOURCE_LOADER.get("invalid_min_max_buckets"), i + 1));
        }
      }
      for (int i = 0; i < histogramsCount; i++) {
        Histogram histogram = controller.getDataModel().getHistograms(i);
        if (!histogramCoversRecommendedSettings(histogram)) {
          return new ValidationInfo(
              String.format(RESOURCE_LOADER.get("unrecommended_histogram_warning"), i + 1))
              .asWarning();
        }
      }
      return null;
    });
    UIValidator.createSliderValidator(disposable, intervalSlider, () -> {
      int value = intervalSlider.getValue();
      if (value == 0) {
        return new ValidationInfo(RESOURCE_LOADER.get("zero_slider_error"));
      } else if (value < getSliderMinimumRecommendedValue()) {
        return new ValidationInfo(RESOURCE_LOADER.get("unrecommended_slider_warning")).asWarning();
      }
      return null;
    });
    UIValidator.createSimpleValidation(disposable, radioButtonsPanel, () -> {
      if (radioButtonGroup.getSelection() == null) {
        return new ValidationInfo(RESOURCE_LOADER.get("aggregation_strategy_not_chosen_error"));
      }
      return null;
    });
    // Fire UI Validator to show warning if they exist.
    UIValidator.isComponentValid(intervalSlider);
    UIValidator.isComponentValid(instrumentationTable);
  }

  public int getSliderMinimumRecommendedValue() {
    if (radioButtonTimeBased.isSelected()) {
      // Anything less than 40 seconds is not recommended.
      return 4;
    } else if (radioButtonIntervalBased.isSelected()) {
      // TODO(@Mohanad): Change the minimum recommended value for ticks
      return 2400;
    } else {
      // Minimum value just to avoid any warning/errors without selecting aggregation strategy.
      return 0;
    }
  }

  public boolean isViewValid() {
    return UIValidator.isComponentValid(apiKey) & UIValidator.isComponentValid(baseUrl)
        & UIValidator.isComponentValid(instrumentationTable)
        & UIValidator.isTableCellsValid(instrumentationTable)
        & UIValidator.isComponentValid(intervalSlider)
        & UIValidator.isComponentValid(radioButtonsPanel);
  }

  private boolean histogramCoversRecommendedSettings(Histogram histogram) {
    return histogram.getBucketMin() < HIGHEST_RECOMMENDED_BUCKET
        && histogram.getBucketMax() > LOWEST_RECOMMENDED_BUCKET;
  }

  private void loadData() {
    controller.setInitialData(instrumentationTable);
    controller.setAggregation(radioButtonTimeBased, radioButtonIntervalBased, intervalSlider);
    controller.setBaseUrlTextBox(baseUrl);
    controller.setApiKeyTextBox(apiKey);
    sliderValueLabel.setText(getSliderValue());
  }

  private void addComponents() {
    this.add(instrumentationLabel);
    this.add(Box.createVerticalStrut(10));
    this.add(informationLabel);
    this.add(decoratorPanel);
    this.add(Box.createVerticalStrut(10));
    this.add(baseUrl);
    this.add(Box.createVerticalStrut(5));
    this.add(apiKey);
    this.add(Box.createVerticalStrut(14));
    JPanel aggregationPanel = new JPanel(new VerticalLayout());
    aggregationPanel.setBorder(BorderFactory.createTitledBorder("Aggregation Strategy"));
    radioButtonsPanel = new JPanel(new HorizontalLayout());
    radioButtonsPanel.add(radioButtonTimeBased);
    radioButtonsPanel.add(Box.createHorizontalStrut(20));
    radioButtonsPanel.add(radioButtonIntervalBased);
    aggregationPanel.add(radioButtonsPanel);
    JPanel sliderPanel = new JPanel(new HorizontalLayout(0));
    sliderPanel.add(intervalSlider);
    // This panel was made just to align slider with the label on horizontal level
    JPanel temporaryPanel = new JPanel(new VerticalLayout());
    temporaryPanel.add(Box.createVerticalStrut(5));
    temporaryPanel.add(sliderValueLabel);
    sliderPanel.add(temporaryPanel);
    aggregationPanel.add(sliderPanel);
    this.add(aggregationPanel);
  }

  private void initVariables() {
    instrumentationTable = new JBTable();
    radioButtonGroup = new ButtonGroup();
    radioButtonTimeBased = new RadioButtonTransparentToolTip("Time Based");
    radioButtonIntervalBased = new RadioButtonTransparentToolTip("Tick Based");
    intervalSlider = new JSlider(JSlider.HORIZONTAL);
    sliderValueLabel = new JLabel();
    baseUrl = new JTextField();
    baseUrl.setBorder(new RoundedCornerBorder(BorderType.NORMAL));
    PromptSupport.setPrompt("Base Url", baseUrl);
    PromptSupport.setFocusBehavior(FocusBehavior.HIDE_PROMPT, baseUrl);
    apiKey = new JTextField();
    apiKey.setBorder(new RoundedCornerBorder(BorderType.NORMAL));
    PromptSupport.setPrompt("Api Key", apiKey);
    PromptSupport.setFocusBehavior(FocusBehavior.HIDE_PROMPT, apiKey);
    decoratorPanel =
        ToolbarDecorator.createDecorator(instrumentationTable)
            .setAddAction(it -> controller.addRowAction(instrumentationTable))
            .setRemoveAction(it -> controller.removeRowAction(instrumentationTable))
            .createPanel();
  }

  private void initComponents() {
    this.setLayout(new VerticalLayout());
    setSize();
    instrumentationLabel.setFont(getMainFont());
    informationLabel.setFont(getSecondaryFont());
    InstrumentationSettingsTableModel model = new InstrumentationSettingsTableModel(
        controller);
    instrumentationTable.setModel(model);
    setDecoratorPanelSize(decoratorPanel);
    setTableSettings(instrumentationTable);
    radioButtonGroup.add(radioButtonIntervalBased);
    radioButtonGroup.add(radioButtonTimeBased);
    radioButtonTimeBased.addItemListener(new TimeRadioButtonsChange());
    radioButtonIntervalBased.addItemListener(new TickRadioButtonsChange());
    initIntegerTextField(instrumentationTable, 0, true);
    initNumberTextField(instrumentationTable, 1);
    initNumberTextField(instrumentationTable, 2);
    initIntegerTextField(instrumentationTable, 3, false);
    decoratorPanel.setMinimumSize(new Dimension(300, 120));
    decoratorPanel.setPreferredSize(new Dimension(600, 180));
    intervalSlider.setPreferredSize(new Dimension(500, 50));
    intervalSlider
        .addChangeListener(e -> sliderValueLabel.setText(getSliderValue()));
    sliderValueLabel.setVerticalAlignment(JLabel.TOP);
  }

  public InstrumentationSettingsTabController getInstrumentationSettingsTabController() {
    return controller;
  }

  private String getSliderValue() {
    if (radioButtonTimeBased.isSelected()) {
      return intervalSlider.getValue() * 10 + " Seconds";
    } else if (radioButtonIntervalBased.isSelected()) {
      return intervalSlider.getValue() + " Ticks";
    } else {
      return "";
    }
  }

  public void saveSettings() {
    controller.setApiKey(apiKey.getText());
    controller.setBaseUrl(baseUrl.getText());
    controller.setMaxInstrumentationKeys(10);
    if (radioButtonTimeBased.isSelected()) {
      int timeInMillisecond = intervalSlider.getValue() * 10 * 1000;
      controller.setUploadInterval(timeInMillisecond);
      controller.setAggregationMethod(radioButtonTimeBased.getText());
    } else if (radioButtonIntervalBased.isSelected()) {
      controller.setUploadInterval(intervalSlider.getValue());
      controller.setAggregationMethod(radioButtonIntervalBased.getText());
    }
    controller.setEnumData();
    instrumentationTable.clearSelection();
  }

  public void initIntegerTextField(JTable table, int col, boolean canBeZero) {
    TableColumn column = table.getColumnModel().getColumn(col);
    column.setCellEditor(getIntegerTextFieldModel());
    if (!canBeZero) {
      column
          .setCellRenderer(
              TableRenderer.getRendererTextBoxWithValidation(new RoundedCornerRenderer(),
                  new IntegerTextFieldValidator()));
    } else {
      column
          .setCellRenderer(
              TableRenderer.getRendererTextBoxWithValidation(new RoundedCornerRenderer(),
                  new NonNegativeTextFieldValidator()));
    }
  }

  public void initNumberTextField(JTable table, int col) {
    TableColumn column = table.getColumnModel().getColumn(col);
    column.setCellEditor(getIntegerTextFieldModel());
    column
        .setCellRenderer(TableRenderer.getRendererTextBoxWithValidation(new RoundedCornerRenderer(),
            new NumberTextFieldValidator()));
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    if (evt.getPropertyName().equals("changeDefaultQuality")) {
      controller.setDefaultQuality((int) evt.getNewValue());
    }
  }

  private static final class IntegerTextFieldValidator implements TableCellValidator {

    @Override
    public ValidationInfo validate(Object o, int i, int i1) {
      String str = o.toString();
      ValidationInfo validationInfo = ValidationTool.getIntegerValueValidationInfo(str);
      if (validationInfo != null) {
        return validationInfo;
      }
      BigInteger bigInteger = new BigInteger(str);
      // A zero or negative integer
      if (bigInteger.compareTo(BigInteger.ZERO) <= 0) {
        return new ValidationInfo(String.format(RESOURCE_LOADER.get("less_than_zero_error"), str));
      }
      return null;
    }
  }

  private static final class NonNegativeTextFieldValidator implements TableCellValidator {

    @Override
    public ValidationInfo validate(Object o, int i, int i1) {
      String str = o.toString();
      ValidationInfo validationInfo = ValidationTool.getIntegerValueValidationInfo(str);
      if (validationInfo != null) {
        return validationInfo;
      }
      BigInteger bigInteger = new BigInteger(str);
      // A zero or negative integer
      if (bigInteger.compareTo(BigInteger.ZERO) < 0) {
        return new ValidationInfo(String.format(RESOURCE_LOADER.get("non_negative_error"), str));
      }
      return null;
    }
  }

  private static final class NumberTextFieldValidator implements TableCellValidator {

    @Override
    public ValidationInfo validate(Object o, int i, int i1) {
      String str = o.toString();
      ValidationInfo validationInfo = ValidationTool.getFloatValueValidationInfo(str);
      if (validationInfo != null) {
        return validationInfo;
      }
      BigDecimal bigDecimal = new BigDecimal(str);
      // A zero or negative integer
      if (bigDecimal.compareTo(BigDecimal.ZERO) < 0) {
        return new ValidationInfo(String.format(RESOURCE_LOADER.get("less_than_zero_error"), str));
      }
      return null;
    }
  }

  private static final class RadioButtonTransparentToolTip extends JRadioButton {

    public RadioButtonTransparentToolTip(String name) {
      super(name);
    }

    @Override
    public String getToolTipText() {
      return ((JComponent) getParent()).getToolTipText();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
      return ((JComponent) getParent()).getToolTipText(event);
    }
  }

  private final class TimeRadioButtonsChange implements ItemListener {

    @Override
    public void itemStateChanged(ItemEvent e) {
      if (e.getStateChange() == ItemEvent.SELECTED) {
        intervalSlider.setMajorTickSpacing(6);
        intervalSlider.setMinorTickSpacing(1);
        intervalSlider.setMinimum(0);
        intervalSlider.setMaximum(60);
        intervalSlider.setPaintTicks(true);
        intervalSlider.setPaintLabels(true);
        intervalSlider.setSnapToTicks(true);
        intervalSlider.setValue(30);
        Hashtable<Integer, JLabel> labelTable = new Hashtable<>();
        labelTable.put(0, new JLabel("0"));
        labelTable.put(30, new JLabel("5 Minutes"));
        labelTable.put(60, new JLabel("10 Minutes"));
        intervalSlider.setLabelTable(labelTable);
        UIValidator.isComponentValid(radioButtonsPanel);
      }
    }
  }

  private final class TickRadioButtonsChange implements ItemListener {

    @Override
    public void itemStateChanged(ItemEvent e) {
      if (e.getStateChange() == ItemEvent.SELECTED) {
        intervalSlider.setMajorTickSpacing(1000);
        intervalSlider.setMinorTickSpacing(100);
        intervalSlider.setMinimum(0);
        intervalSlider.setMaximum(6000);
        intervalSlider.setPaintTicks(true);
        intervalSlider.setPaintLabels(true);
        intervalSlider.setSnapToTicks(true);
        intervalSlider.setValue(3600);
        Hashtable<Integer, JLabel> labelTable = new Hashtable<>();
        labelTable.put(0, new JLabel("0"));
        labelTable.put(3000, new JLabel("3000 Tick"));
        labelTable.put(6000, new JLabel("6000 Tick"));
        intervalSlider.setLabelTable(labelTable);
        UIValidator.isComponentValid(radioButtonsPanel);
      }
    }
  }
}