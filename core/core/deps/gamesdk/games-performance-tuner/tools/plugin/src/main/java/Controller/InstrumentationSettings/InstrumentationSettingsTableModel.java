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

package Controller.InstrumentationSettings;

import Utils.Validation.ValidationTool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.table.AbstractTableModel;


public class InstrumentationSettingsTableModel extends AbstractTableModel {

  private static final String[] COLUMN_NAMES = {
      "Instrumentation ID",
      "Minimum Bucket Size",
      "Maximum Bucket Size",
      "Number Of Buckets"
  };
  private List<String[]> data;
  private final InstrumentationSettingsTabController controller;

  public InstrumentationSettingsTableModel(InstrumentationSettingsTabController controller) {
    data = new ArrayList<>();
    this.controller = controller;
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
    return COLUMN_NAMES.length;
  }

  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    return data.get(rowIndex)[columnIndex];
  }

  @Override
  public String getColumnName(int column) {
    return COLUMN_NAMES[column];
  }

  public void addRow() {
    data.add(new String[]{"0", "0", "0", "0"});
    controller.addNewHistogram();
    fireTableRowsInserted(getRowCount() - 1, getRowCount());
  }

  @Override
  public boolean isCellEditable(int row, int column) {
    return true;
  }

  public List<String> getColumnNames() {
    return Arrays.asList(COLUMN_NAMES);
  }

  @Override
  public void setValueAt(Object value, int row, int column) {
    data.get(row)[column] = value.toString();
    String strValue = value.toString();
    if (column == 0 && ValidationTool.isInteger(strValue)) {
      controller.setHistogramInstrumentID(row, strValue);
    } else if (column == 1 && ValidationTool.isDecimal(strValue)) {
      controller.setHistogramMinimumBucketSize(row, strValue);
    } else if (column == 2 && ValidationTool.isDecimal(strValue)) {
      controller.setHistogramMaximumBucketSize(row, strValue);
    } else if (column == 3 && ValidationTool.isInteger(strValue)) {
      controller.setHistogramNumberOfBuckets(row, strValue);
    }
    fireTableCellUpdated(row, column);
  }

  public void removeRow(int row) {
    data.remove(row);
    controller.removeHistogram(row);
    fireTableDataChanged();
  }
}