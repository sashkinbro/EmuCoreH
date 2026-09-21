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

package View.Decorator;

import com.intellij.openapi.ui.cellvalidators.TableCellValidator;
import java.awt.Component;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

public class TableRenderer {

  public static final class RoundedCornerRenderer extends DefaultTableCellRenderer {

    @Override
    public Component getTableCellRendererComponent(JTable jTable, Object value,
        boolean isSelected, boolean hasFocus, int row, int column) {
      JComponent component = (JComponent) super.getTableCellRendererComponent(
          jTable, value, isSelected, hasFocus, row, column);
      component.setBorder(new RoundedCornerBorder());
      setForeground(isSelected ? jTable.getSelectionForeground() : jTable.getForeground());
      setBackground(isSelected ? jTable.getSelectionBackground() : jTable.getBackground());
      return component;
    }
  }

  public static final class ComboBoxRenderer
      extends JComboBox<String> implements TableCellRenderer {

    @Override
    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      removeAllItems();
      addItem(value.toString());
      return this;
    }
  }

  // Unstable API is used for validation. Use with cautious.
  @SuppressWarnings("UnstableApiUsage")
  public static TableCellRenderer getRendererTextBoxWithValidation(
      TableCellRenderer tableCellRenderer,
      TableCellValidator tableCellValidator) {
    return new ValidationTextFieldRendererWrapper(tableCellRenderer)
        .withCellValidator(tableCellValidator);
  }

  public static TableCellRenderer getRendererComboBoxWithValidation(
      TableCellRenderer tableCellRenderer,
      TableCellValidator tableCellValidator) {
    return new ValidatingComboBoxTableCellWrapper(tableCellRenderer)
        .withCellValidator(tableCellValidator);
  }
}
