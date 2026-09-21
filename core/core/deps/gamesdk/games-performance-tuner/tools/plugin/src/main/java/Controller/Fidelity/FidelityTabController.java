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

import Model.EnumDataModel;
import Model.MessageDataModel;
import View.Fidelity.FidelityTableData;
import View.Fidelity.FieldType;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.swing.JTable;

public class FidelityTabController {

  private MessageDataModel fidelityDataModel;
  private final List<EnumDataModel> enums;
  private static final String FIELD_NAME_PATTERN = "[a-zA-Z_]+$";
  private final PropertyChangeSupport propertyChangeSupport;

  public FidelityTabController(MessageDataModel fidelityDataModel, List<EnumDataModel> enums) {
    super();
    this.fidelityDataModel = fidelityDataModel;
    this.enums = enums;
    this.propertyChangeSupport = new PropertyChangeSupport(this);
  }

  public void addPropertyChangeListener(
      PropertyChangeListener propertyChangeListener) {
    propertyChangeSupport
        .addPropertyChangeListener(propertyChangeListener);
  }

  public void addInitialFidelity(JTable table) {
    List<String> fieldNames = fidelityDataModel.getFieldNames();
    List<String> fieldValues = fidelityDataModel.getFieldTypes();
    FidelityTableModel model = (FidelityTableModel) table.getModel();
    ArrayList<FidelityTableData> data = new ArrayList<>();
    for (int i = 0; i < fieldNames.size(); i++) {
      if (fieldValues.get(i).equals("int32")) {
        data.add(new FidelityTableData(FieldType.INT32, "", fieldNames.get(i)));
      } else if (fieldValues.get(i).equals("float")) {
        data.add(new FidelityTableData(FieldType.FLOAT, "", fieldNames.get(i)));
      } else {
        data.add(new FidelityTableData(FieldType.ENUM, fieldValues.get(i), fieldNames.get(i)));
      }
    }
    model.setData(data);
  }

  public void addFidelityField(String name, String type) {
    fidelityDataModel.addField(name, type);
    propertyChangeSupport.firePropertyChange("addField", null, name);
  }

  public void removeFidelityField(int index) {
    fidelityDataModel.removeSetting(index);
    propertyChangeSupport.firePropertyChange("removeField", null, index);
  }

  public void updateName(int index, String newName) {
    propertyChangeSupport
        .firePropertyChange("nameChange", fidelityDataModel.getFieldNames().get(index),
            new Object[]{index, newName});
    fidelityDataModel.updateName(index, newName);
  }

  public void updateEnum(int index, EnumDataModel enumDataModel) {
    Optional<EnumDataModel> oldType = fidelityDataModel.getEnumData(index);
    fidelityDataModel.setEnumData(index, enumDataModel);
    if ((oldType.isPresent() ^ fidelityDataModel.getEnumData(index).isPresent())
        || (oldType.isPresent() && !oldType.get().equals(enumDataModel))) {
      propertyChangeSupport
          .firePropertyChange("typeChange", null, index);
    }
  }

  public EnumDataModel findEnumByName(String name) {
    return enums.stream().filter(enumDataModel -> enumDataModel.getName().equals(name))
        .findFirst().get();
  }

  public void updateType(int index, String type) {
    fidelityDataModel.updateType(index, type);
  }

  public MessageDataModel getFidelityData() {
    return fidelityDataModel;
  }

  public void addRowAction(JTable jtable) {
    FidelityTableModel model = (FidelityTableModel) jtable.getModel();
    model.addRow(new FidelityTableData(FieldType.INT32, "", ""));
  }

  public void removeRowAction(JTable jtable) {
    FidelityTableModel model = (FidelityTableModel) jtable.getModel();
    int row = jtable.getSelectedRow();
    if (jtable.getCellEditor() != null) {
      jtable.getCellEditor().stopCellEditing();
    }
    model.removeRow(row);
  }

  public boolean hasEnums() {
    return !enums.isEmpty();
  }

  public List<String> getEnumNames() {
    return enums.stream().map(EnumDataModel::getName)
        .collect(Collectors.toList());
  }

  public List<EnumDataModel> getEnums() {
    return enums;
  }
}
