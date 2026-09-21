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

package View.Annotation;

import Model.EnumDataModel;
import View.TableComboBox;
import View.TableComboBox.BaseComboBoxTableCell;
import java.awt.Component;
import java.util.List;
import javax.swing.JTable;

public class AnnotationDecorator {

  public static class EnumComboBoxDecorator extends BaseComboBoxTableCell {

    final List<EnumDataModel> enums;
    final TableComboBox<String> tableComboBox;

    public EnumComboBoxDecorator(TableComboBox<String> tableComboBox,
        List<EnumDataModel> enumsTemp) {
      super(tableComboBox);
      this.enums = enumsTemp;
      this.tableComboBox = tableComboBox;
    }


    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
        int row, int column) {
      super.getTableCellEditorComponent(table, value, isSelected, row, column);
      String strValue = value.toString();
      setComboBoxChoices(enums);
      tableComboBox.setSelectedItem(strValue);
      tableComboBox
          .setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
      tableComboBox
          .setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
      return tableComboBox;
    }

    private void setComboBoxChoices(List<EnumDataModel> choices) {
      tableComboBox.removeAllItems();
      for (EnumDataModel enumDataModel : choices) {
        tableComboBox.addItem(enumDataModel.getName());
      }
    }

    @Override
    public Object getCellEditorValue() {
      return tableComboBox.getSelectedIndex() == -1 ? ""
          : tableComboBox.getSelectedItem().toString();
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
      String strValue = value.toString();
      setComboBoxChoices(enums);
      if (enums.stream()
          .anyMatch(enumDataModel -> enumDataModel.getName().equals(strValue))) {
        tableComboBox.setSelectedItem(strValue);
      } else {
        tableComboBox.setSelectedIndex(-1);
      }
      tableComboBox
          .setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
      tableComboBox
          .setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
      return tableComboBox;
    }
  }
}
