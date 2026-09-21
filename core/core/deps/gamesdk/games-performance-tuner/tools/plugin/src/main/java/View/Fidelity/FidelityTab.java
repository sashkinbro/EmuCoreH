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
package View.Fidelity;

import static Utils.UI.UIValidator.ILLEGAL_TEXT_PATTERN;

import Controller.Fidelity.FidelityTabController;
import Controller.Fidelity.FidelityTableModel;
import Model.EnumDataModel;
import Utils.Resources.ResourceLoader;
import Utils.UI.UIValidator;
import View.Decorator.TableRenderer;
import View.Fidelity.FidelityTableDecorators.FidelityFieldComboBox;
import View.TabLayout;
import View.TableComboBox;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.ui.cellvalidators.TableCellValidator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import org.jdesktop.swingx.VerticalLayout;

public class FidelityTab extends TabLayout implements PropertyChangeListener {

  private final ResourceLoader resourceLoader = ResourceLoader.getInstance();
  private final JBLabel fidelityLabel = new JBLabel(resourceLoader.get("fidelity_settings"));
  private final JBLabel informationLabel = new JBLabel(resourceLoader.get("fidelity_info"));
  FidelityTabController fidelityTabController;
  private JBTable fidelityTable;
  private JPanel fidelityDecoratorPanel;

  private final Disposable disposable;
  private final static FidelityFieldComboBox TABLE_FIELD_CELL_RENDERER = new FidelityFieldComboBox(
      new TableComboBox<>(),
      new FieldType[]{FieldType.INT32, FieldType.FLOAT, FieldType.ENUM});
  private final static FidelityFieldComboBox TABLE_FIELD_CELL_EDITOR = new FidelityFieldComboBox(
      new TableComboBox<>(),
      new FieldType[]{FieldType.INT32, FieldType.FLOAT, FieldType.ENUM});

  public FidelityTab(FidelityTabController controller, Disposable disposable) {
    this.fidelityTabController = controller;
    this.disposable = disposable;
    initVariables();
    initComponents();
    initValidators();
  }

  public FidelityTabController getFidelityTabController() {
    return fidelityTabController;
  }

  private void initVariables() {
    fidelityTable =
        new JBTable() {
          @Override
          public TableCellRenderer getCellRenderer(int row, int column) {
            if (column == 1) {
              FidelityTableData currentData =
                  (FidelityTableData) this.getModel().getValueAt(row, column);
              return getCellRendererByValue(currentData);
            } else {
              return super.getCellRenderer(row, column);
            }
          }

          @Override
          public TableCellEditor getCellEditor(int row, int column) {
            if (column == 1) {
              FidelityTableData currentData =
                  (FidelityTableData) this.getModel().getValueAt(row, column);
              return getCellEditorByValue(currentData);
            } else {
              return super.getCellEditor(row, column);
            }
          }
        };
    fidelityDecoratorPanel =
        ToolbarDecorator.createDecorator(fidelityTable)
            .setAddAction(it -> fidelityTabController.addRowAction(fidelityTable))
            .setRemoveAction(it -> fidelityTabController.removeRowAction(fidelityTable))
            .setMinimumSize(getMinimumSize())
            .setPreferredSize(getPreferredSize())
            .createPanel();

    fidelityLabel.setFont(TabLayout.getMainFont());
    informationLabel.setFont(TabLayout.getSecondaryFont());
  }

  private void initComponents() {
    this.setLayout(new VerticalLayout());
    setSize();
    fidelityLabel.setFont(TabLayout.getMainFont());
    informationLabel.setFont(TabLayout.getSecondaryFont());
    this.add(fidelityLabel);
    this.add(Box.createVerticalStrut(10));
    this.add(informationLabel);

    FidelityTableModel model = new FidelityTableModel(fidelityTabController);
    fidelityTable.setModel(model);
    fidelityTabController.addInitialFidelity(fidelityTable);

    TableColumn enumColumn = fidelityTable.getColumnModel().getColumn(1);
    enumColumn.setCellEditor(new FidelityTableDecorators.TextBoxEditor());
    enumColumn.setCellRenderer(new FidelityTableDecorators.TextBoxRenderer());
    TableColumn typeColumn = fidelityTable.getColumnModel().getColumn(0);
    typeColumn.setMinWidth(150);
    typeColumn.setMaxWidth(300);
    typeColumn.setCellEditor(TABLE_FIELD_CELL_EDITOR);
    typeColumn.setCellRenderer(TABLE_FIELD_CELL_RENDERER);

    setDecoratorPanelSize(fidelityDecoratorPanel);
    setTableSettings(fidelityTable);
    this.add(fidelityDecoratorPanel);
    this.add(Box.createVerticalStrut(10));
  }

  private TableCellRenderer getCellRendererByValue(FidelityTableData data) {
    if (data.getFieldType().equals(FieldType.ENUM)) {
      return
          new FidelityCellPanelValidationRendererWrapper(
              new FidelityTableDecorators.JPanelDecorator(fidelityTabController.getEnumNames()))
              .withCellValidator(new FidelityTextCellValidation());
    }
    return TableRenderer
        .getRendererTextBoxWithValidation(new FidelityTableDecorators.TextBoxRenderer(),
            new FidelityTextCellValidation());
  }

  private TableCellEditor getCellEditorByValue(FidelityTableData data) {
    if (data.getFieldType().equals(FieldType.ENUM)) {
      return new FidelityTableDecorators.JPanelDecorator(fidelityTabController.getEnumNames());
    }
    return new FidelityTableDecorators.TextBoxEditor();
  }

  public void saveSettings() {
    fidelityTable.clearSelection();
  }

  public boolean isViewValid() {
    return UIValidator.isTableCellsValidDeep(fidelityTable) &
        UIValidator.isComponentValid(fidelityTable);
  }

  private void initValidators() {
    UIValidator.createTableValidator(disposable, fidelityTable, () -> {
      List<String> fieldNames = fidelityTabController.getFidelityData().getFieldNames();
      boolean isNamesDuplicate =
          fieldNames.stream().anyMatch(option -> Collections.frequency(fieldNames, option) > 1);
      if (isNamesDuplicate) {
        return new ValidationInfo(resourceLoader.get("repeated_fields_error"), fidelityTable);
      }
      if (fieldNames.isEmpty()) {
        return new ValidationInfo(resourceLoader.get("empty_table_error"));
      }
      return null;
    });
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    if (evt.getPropertyName().equals("editEnum")) {
      String oldName = evt.getOldValue().toString();
      EnumDataModel newEnum = (EnumDataModel) evt.getNewValue();
      String newName = newEnum.getName();
      FidelityTableModel fidelityTableModel = (FidelityTableModel) fidelityTable.getModel();
      for (int i = 0; i < fidelityTableModel.getRowCount(); i++) {
        FidelityTableData rowData = (FidelityTableData) fidelityTableModel.getValueAt(i, 0);
        if (rowData.getFieldEnumName().equals(oldName)) {
          rowData.setFieldEnumName(newName);
          fidelityTabController.updateType(i, newName);
          fidelityTabController.updateEnum(i, newEnum);
        }
      }
    } else if (evt.getPropertyName().equals("deleteEnum")) {
      String name = evt.getOldValue().toString();
      FidelityTableModel fidelityTableModel = (FidelityTableModel) fidelityTable.getModel();
      for (int i = 0; i < fidelityTableModel.getRowCount(); i++) {
        FidelityTableData rowData = (FidelityTableData) fidelityTableModel.getValueAt(i, 0);
        if (rowData.getFieldEnumName().equals(name)) {
          rowData.setFieldType(FieldType.INT32);
          fidelityTabController.updateType(i, FieldType.INT32.getName());
          fidelityTabController.updateEnum(i, null);
        }
      }
    }
  }

  @SuppressWarnings("UnstableApiUsage")
  private static class FidelityTextCellValidation implements TableCellValidator {

    private final ResourceLoader resourceLoader = ResourceLoader.getInstance();

    @Override
    public ValidationInfo validate(Object value, int row, int column) {
      FidelityTableData data1 = (FidelityTableData) value;
      String strVal = data1.getFieldParamName();
      if (strVal.isEmpty()) {
        return new ValidationInfo(resourceLoader.get("field_empty_error"));
      }
      if (Pattern.compile(ILLEGAL_TEXT_PATTERN).matcher(strVal).find()) {
        return new ValidationInfo(
            String.format(resourceLoader.get("field_illegal_character_error"), strVal));
      }
      return null;
    }
  }

}
