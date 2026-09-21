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

package Controller.Quality;

import Model.EnumDataModel;
import Model.MessageDataModel;
import Model.QualityDataModel;
import Utils.Validation.ValidationTool;
import View.Fidelity.FieldType;
import com.google.common.collect.ImmutableList;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import javax.swing.JTable;

public class QualityTabController {

  private final List<QualityDataModel> qualityDataModels;
  private final MessageDataModel fidelityData;
  private final List<EnumDataModel> enums;
  private final PropertyChangeSupport propertyChangeSupport;
  private int defaultQuality;

  public QualityTabController(List<QualityDataModel> qualityDataModels,
      MessageDataModel fidelityData,
      List<EnumDataModel> enums, Optional<Integer> defaultQuality) {
    this.qualityDataModels = qualityDataModels;
    this.fidelityData = fidelityData;
    this.enums = enums;
    this.propertyChangeSupport = new PropertyChangeSupport(this);
    this.defaultQuality = defaultQuality.map(integer -> integer + 1).orElse(-1);
  }

  public int getDefaultQuality() {
    return defaultQuality;
  }

  public List<QualityDataModel> getQualityDataModels() {
    return qualityDataModels;
  }

  public void setDefaultQuality(JTable table) {
    int selectedColumn = table.getSelectedColumn();
    this.defaultQuality = selectedColumn;
    QualityTableModel tableModel = (QualityTableModel) table.getModel();
    // minus 2 is added because there's 2 constant columns(name, Trend).
    int qualityIndex = selectedColumn - 2;
    tableModel.updateColumnNames();
    setDefaultQuality(qualityIndex);
  }

  public void addInitialQuality(JTable table) {
    QualityTableModel tableModel = (QualityTableModel) table.getModel();
    tableModel.setFidelityNames(fidelityData.getFieldNames());
    List<List<String>> data = new ArrayList<>();
    for (QualityDataModel qualityDataModel : qualityDataModels) {
      data.add(new ArrayList<>());
      data.get(data.size() - 1).addAll(qualityDataModel.getFieldValues());
    }
    tableModel.setInitialData(data);
    tableModel.updateTrend();
  }

  public void addColumn(JTable table) {
    QualityTableModel tableModel = (QualityTableModel) table.getModel();
    tableModel.addColumn();
  }

  public void setDefaultQuality(int value) {
    this.defaultQuality = value;
    propertyChangeSupport.firePropertyChange("changeDefaultQuality", null, value);
  }

  public void addPropertyChangeListener(PropertyChangeListener changeListener) {
    this.propertyChangeSupport.addPropertyChangeListener(changeListener);
  }

  public void removeColumn(JTable table) {
    QualityTableModel tableModel = (QualityTableModel) table.getModel();
    int column = table.getSelectedColumn();
    if (table.isEditing()) {
      table.getCellEditor().stopCellEditing();
    }
    tableModel.removeColumn(column);
  }

  public List<EnumDataModel> getEnums() {
    return enums;
  }

  public void addNewQualityFile() {
    QualityDataModel qualityDataModel = new QualityDataModel();
    for (int i = 0; i < fidelityData.getFieldNames().size(); i++) {
      qualityDataModel.addField(fidelityData.getFieldNames().get(i), getDefaultValueByIndex(i));
    }
    qualityDataModels.add(qualityDataModel);
  }

  public void removeQualityFile(int fileID) {
    qualityDataModels.remove(fileID);
  }

  public void addNewFieldToAllFiles(int row) {
    qualityDataModels.forEach(qualityDataModel -> qualityDataModel.addField("",
        getDefaultValueByIndex(row)));
  }

  public void updateFieldName(final int row, final String newName) {
    qualityDataModels.forEach(qualityDataModel -> qualityDataModel.updateName(row, newName));
  }

  public void updateFieldValue(final int fileID, final int row, final String value) {
    qualityDataModels.get(fileID).updateValue(row, value);
  }

  public void addRow(JTable table) {
    QualityTableModel tableModel = (QualityTableModel) table.getModel();
    tableModel.addRow();
  }

  public OptionalInt getFidelityRowByEnumName(String name) {
    return IntStream.range(0, fidelityData.getFieldTypes().size())
        .filter(i -> fidelityData.getEnumData(i).isPresent() && fidelityData.getEnumData(i).get()
            .getName().equals(name))
        .findFirst();
  }

  public void removeFieldRowData(int row) {
    qualityDataModels.forEach(qualityDataModel -> qualityDataModel.removeSetting(row));
  }

  public void removeRow(JTable table, int row) {
    QualityTableModel tableModel = (QualityTableModel) table.getModel();
    tableModel.removeRow(row);
  }

  public boolean isEnum(int row) {
    return fidelityData.getEnumData(row).isPresent();
  }

  public List<String> getEnumOptionsByIndex(int row) {
    if (isEnum(row)) {
      for (EnumDataModel anEnum : enums) {
        if (anEnum.getName().equals(fidelityData.getFieldTypes().get(row))) {
          return anEnum.getOptions();
        }
      }
    }
    return ImmutableList.of("");
  }

  public boolean isRowValid(int row) {
    return ValidationTool.isRowValid(qualityDataModels, row, getFieldTypeByRow(row));
  }

  public String getNewTrendState(int row) {
    boolean isIncreasing = ValidationTool
        .isIncreasingSingleField(qualityDataModels, fidelityData, row);
    boolean isDecreasing = ValidationTool
        .isDecreasingSingleField(qualityDataModels, fidelityData, row);
    if (!isIncreasing && !isDecreasing) {
      return "none";
    } else if (!isIncreasing) {
      return "decrease";
    } else {
      return "increase";
    }
  }

  public FieldType getFieldTypeByRow(int row) {
    if (fidelityData.getEnumData(row).isPresent()) {
      return FieldType.ENUM;
    } else if (fidelityData.getFieldTypes().get(row).equals(FieldType.INT32.getName())) {
      return FieldType.INT32;
    } else {
      return FieldType.FLOAT;
    }
  }

  public String getDefaultValueByIndex(int index) {
    if (!fidelityData.getEnumData(index).isPresent()) {
      return "0";
    } else {
      return fidelityData.getEnumData(index).get().getOptions().get(0);
    }
  }
}

