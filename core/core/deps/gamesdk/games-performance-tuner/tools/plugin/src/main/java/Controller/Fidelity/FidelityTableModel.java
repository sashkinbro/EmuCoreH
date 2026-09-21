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

package Controller.Fidelity;

import Utils.Resources.ResourceLoader;
import View.Fidelity.FidelityTableData;
import View.Fidelity.FieldType;
import com.intellij.openapi.ui.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.table.AbstractTableModel;

public class FidelityTableModel extends AbstractTableModel {

  private final String[] columnNames = {"Type", "Parameter name"};
  private List<FidelityTableData> data;
  private FidelityTabController controller;
  private final ResourceLoader resourceLoader = ResourceLoader.getInstance();
  public FidelityTableModel(FidelityTabController controller) {
    this.controller = controller;
    data = new ArrayList<>();
  }

  public void setData(List<FidelityTableData> data) {
    this.data = data;
  }

  @Override
  public int getRowCount() {
    return data.size();
  }

  @Override
  public int getColumnCount() {
    return columnNames.length;
  }

  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    return data.get(rowIndex);
  }

  @Override
  public String getColumnName(int column) {
    return columnNames[column];
  }

  public void addRow(FidelityTableData row) {
    data.add(row);
    controller.addFidelityField("", row.getFieldType().getName());
    fireTableRowsInserted(getRowCount() - 1, getRowCount());
  }

  @Override
  public boolean isCellEditable(int row, int column) {
    return true;
  }

  @Override
  public void setValueAt(Object value, int row, int column) {
    if (column == 0) {
      FieldType fieldType = (FieldType) value;
      if (fieldType.equals(FieldType.INT32) || fieldType.equals(FieldType.FLOAT)) {
        controller.updateType(row, fieldType.getName());
        controller.updateEnum(row, null);
      } else {
        if (controller.hasEnums()) {
          controller.updateType(row, controller.getEnumNames().get(0));
          controller.updateEnum(row, controller.getEnums().get(0));
          data.get(row).setFieldEnumName(controller.getEnumNames().get(0));
        } else {
          Messages.showErrorDialog(resourceLoader.get("need_to_add_enum_error"),
              resourceLoader.get("unable_to_choose_enum_title"));
          return;
        }
      }
      data.get(row).setFieldType((FieldType) value);
    } else if (column == 1) {
      if (data.get(row).getFieldType().equals(FieldType.ENUM)) {
        FidelityTableData fidelityTableData = (FidelityTableData) value;
        data.get(row).setFieldEnumName(fidelityTableData.getFieldEnumName());
        data.get(row).setFieldParamName(fidelityTableData.getFieldParamName());
        controller.updateName(row, fidelityTableData.getFieldParamName());
        controller.updateEnum(row, controller.findEnumByName(fidelityTableData.getFieldEnumName()));
        controller.updateType(row, fidelityTableData.getFieldEnumName());
      } else {
        data.get(row).setFieldParamName(value.toString());
        controller.updateName(row, value.toString());
      }
    }
    fireTableCellUpdated(row, column);
  }

  public void removeRow(int row) {
    data.remove(row);
    controller.removeFidelityField(row);
    fireTableDataChanged();
  }

  public List<String> getFidelityFieldValues() {
    List<String> enumNames = new ArrayList<>();
    for (FidelityTableData row : data) {
      if (row.getFieldType().equals(FieldType.ENUM)) {
        enumNames.add(row.getFieldEnumName());
      } else if (row.getFieldType().equals(FieldType.INT32)) {
        enumNames.add("int32");
      } else {
        enumNames.add("float");
      }
    }
    return enumNames;
  }

  public List<String> getFidelityParamNames() {
    return data.stream().map(FidelityTableData::getFieldParamName).collect(Collectors.toList());
  }
}
