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
package Controller.Annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.table.AbstractTableModel;

public class AnnotationTableModel extends AbstractTableModel {

  private final String[] columnNames = {"Type", "Name"};
  private List<String[]> data;
  private final AnnotationTabController annotationTabController;

  public AnnotationTableModel(AnnotationTabController annotationTabController) {
    this.data = new ArrayList<>();
    this.annotationTabController = annotationTabController;
  }

  public void setData(List<String[]> data) {
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
    return data.get(rowIndex)[columnIndex];
  }

  @Override
  public String getColumnName(int column) {
    return columnNames[column];
  }

  public void addRow(String[] row) {
    data.add(row);
    annotationTabController.addEnumField();
    fireTableRowsInserted(getRowCount() - 1, getRowCount());
  }

  @Override
  public boolean isCellEditable(int row, int column) {
    return true;
  }

  @Override
  public void setValueAt(Object value, int row, int column) {
    if (column == 0) {
      if (value == null) {
        data.get(row)[column] = "";
        annotationTabController.setEnumFieldType(row, "");
      } else {
        data.get(row)[column] = value.toString();
        annotationTabController.setEnumFieldType(row, value.toString());
      }
    } else if (column == 1) {
      if (value == null) {
        data.get(row)[column] = "";
        annotationTabController.setEnumFieldName(row, "");
      } else {
        data.get(row)[column] = value.toString();
        annotationTabController.setEnumFieldName(row, value.toString());
      }
    }
    fireTableCellUpdated(row, column);
  }

  public void removeRow(int row) {
    data.remove(row);
    annotationTabController.removeEnumField(row);
    fireTableDataChanged();
  }
}