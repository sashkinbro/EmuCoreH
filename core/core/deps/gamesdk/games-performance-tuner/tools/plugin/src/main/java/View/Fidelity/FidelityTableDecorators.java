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

import View.Decorator.RoundedCornerBorder;
import View.TableComboBox;
import View.TableComboBox.BaseComboBoxTableCell;
import com.intellij.util.ui.UIUtil;
import java.awt.Component;
import java.util.List;
import javax.swing.AbstractCellEditor;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;

public class FidelityTableDecorators {

  public static final class FidelityFieldComboBox extends BaseComboBoxTableCell {

    TableComboBox<FieldType> tableComboBox;
    FieldType[] fieldTypes;

    public FidelityFieldComboBox(TableComboBox<FieldType> tableComboBox, FieldType[] fieldTypes) {
      super(tableComboBox);
      this.tableComboBox = tableComboBox;
      this.fieldTypes = fieldTypes;
      // Used to update the UI.
      tableComboBox.addItemListener(itemEvent -> fireEditingStopped());
    }

    private void refreshComboBoxItems() {
      tableComboBox.removeAllItems();
      for (FieldType fieldType : fieldTypes) {
        tableComboBox.addItem(fieldType);
      }
    }

    @Override
    public Component getTableCellEditorComponent(
        JTable table, Object value, boolean isSelected, int row, int column) {
      super.getTableCellEditorComponent(table, value, isSelected, row, column);
      refreshComboBoxItems();
      FidelityTableData feed = (FidelityTableData) value;
      tableComboBox.setSelectedItem(feed.getFieldType());
      return tableComboBox;
    }

    @Override
    public Object getCellEditorValue() {
      return tableComboBox.getSelectedItem();
    }

    @Override
    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
      refreshComboBoxItems();
      tableComboBox.setSelectedItem(((FidelityTableData) value).getFieldType());
      return tableComboBox;
    }
  }

  public static class JPanelDecorator extends AbstractCellEditor
      implements TableCellEditor, TableCellRenderer {

    FidelityTablePanel fidelityTablePanel;
    List<String> enums;

    public JPanelDecorator(List<String> enumsTemp) {
      this.enums = enumsTemp;
      fidelityTablePanel = new FidelityTablePanel();
      fidelityTablePanel.setBackground(UIUtil.getTableBackground());
    }

    @Override
    public Component getTableCellEditorComponent(
        JTable table, Object value, boolean isSelected, int row, int column) {
      FidelityTableData fidelityTableData = (FidelityTableData) value;
      fidelityTablePanel.updateData(fidelityTableData);
      fidelityTablePanel.setComboBoxChoices(enums);
      fidelityTablePanel.getEnumTypes().setSelectedItem(fidelityTableData.getFieldEnumName());
      fidelityTablePanel.getTextFieldPanel().setBackground(UIUtil.getTableGridColor());
      fidelityTablePanel.getTextFieldPanel().getTextField()
          .setBackground(UIUtil.getTableGridColor());
      return fidelityTablePanel;
    }

    @Override
    public Object getCellEditorValue() {
      return fidelityTablePanel.getFidelityData();
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      FidelityTableData fidelityTableData = (FidelityTableData) value;
      fidelityTablePanel.updateData(fidelityTableData);
      fidelityTablePanel.setComboBoxChoices(enums);
      if (enums.contains(fidelityTableData.getFieldEnumName())) {
        fidelityTablePanel.getEnumTypes().setSelectedItem(fidelityTableData.getFieldEnumName());
      } else {
        fidelityTablePanel.getEnumTypes().setSelectedIndex(-1);
      }
      return fidelityTablePanel;
    }
  }

  public static final class TextBoxRenderer implements TableCellRenderer {

    JTextField textField;

    public TextBoxRenderer() {
      textField = new JTextField();
      textField.setBorder(new RoundedCornerBorder());
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      FidelityTableData feed = (FidelityTableData) value;
      textField.setText(feed.getFieldParamName());
      textField.setBackground(table.getBackground());
      return textField;
    }
  }

  public static final class TextBoxEditor extends AbstractCellEditor implements TableCellEditor {

    JTextField textField;

    public TextBoxEditor() {
      textField = new JTextField();
      textField.setBorder(new RoundedCornerBorder());
    }

    @Override
    public Component getTableCellEditorComponent(
        JTable table, Object value, boolean isSelected, int row, int column) {
      FidelityTableData feed = (FidelityTableData) value;
      textField.setText(feed.getFieldParamName());
      textField.setBackground(UIUtil.getTableGridColor());
      return textField;
    }

    @Override
    public Object getCellEditorValue() {
      return textField.getText();
    }
  }
}
