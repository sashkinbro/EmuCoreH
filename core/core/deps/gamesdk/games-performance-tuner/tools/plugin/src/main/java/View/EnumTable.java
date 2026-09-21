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

package View;

import static View.TabLayout.setTableSettings;

import Controller.Enum.EnumController;
import Utils.Resources.ResourceLoader;
import View.Dialog.EnumDialogWrapper;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import java.awt.Dimension;
import javax.swing.JPanel;
import javax.swing.table.DefaultTableModel;
import org.jdesktop.swingx.HorizontalLayout;

public class EnumTable extends JPanel {

  private final JBTable enumTable;
  private final JPanel enumDecoratePanel;
  private final EnumController controller;
  private final static int TABLE_WIDTH = 300;
  private final static int TABLE_HEIGHT = 200;

  public EnumTable(EnumController controller) {
    this.setLayout(new HorizontalLayout());
    DefaultTableModel tableModel = new DefaultTableModel() {
      @Override
      public void removeRow(int row) {
        dataVector.remove(row);
        fireTableDataChanged();
      }

      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
    tableModel.setColumnIdentifiers(
        new Object[]{ResourceLoader.getInstance().get("table_column_options")});
    controller.addEnumsToModel(tableModel);
    this.enumTable = new JBTable(tableModel);
    this.controller = controller;
    enumDecoratePanel = ToolbarDecorator.createDecorator(enumTable)
        .setAddAction(it -> addEnum())
        .setRemoveAction(it -> removeEnum())
        .setEditAction(it -> editEnum())
        .createPanel();
    setTableSettings(enumTable);
    enumDecoratePanel.setPreferredSize(new Dimension(TABLE_WIDTH, TABLE_HEIGHT));
    enumDecoratePanel.setMinimumSize(new Dimension(TABLE_WIDTH, TABLE_HEIGHT));
    this.add(enumDecoratePanel);
  }

  private void addEnum() {
    EnumDialogWrapper enumDialogWrapper = new EnumDialogWrapper(controller);
    if (enumDialogWrapper.showAndGet()) {
      controller.addEnumToTable(enumTable);
    }
  }

  private void removeEnum() {
    int row = enumTable.getSelectedRow();
    controller.removeEnum(row);
    controller.removeEnumFromTable(enumTable, row);
  }

  private void editEnum() {
    int selectedRow = enumTable.getSelectedRow();
    EnumDialogWrapper enumDialogWrapper = new EnumDialogWrapper(
        controller, selectedRow, controller.getEnums().get(selectedRow));
    if (enumDialogWrapper.showAndGet()) {
      controller.editEnumInTable(enumTable, selectedRow);
    }
  }
}
