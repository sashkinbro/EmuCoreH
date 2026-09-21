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

package Controller.Enum;

import Model.EnumDataModel;
import com.intellij.ui.table.JBTable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.table.DefaultTableModel;

public abstract class EnumController {

  public enum ChangeType {
    ADD,
    REMOVE,
    EDIT,
    EDIT_OPTIONS,
  }

  private final List<EnumDataModel> enums;

  public EnumController(List<EnumDataModel> enums) {
    this.enums = enums;
  }

  public final List<EnumDataModel> getEnums() {
    return enums;
  }

  public final boolean hasEnums() {
    return !getEnums().isEmpty();
  }

  public final List<String> getEnumsNames() {
    return enums.stream().map(EnumDataModel::getName).collect(Collectors.toList());
  }

  public final boolean addEnum(String name, List<String> options) {
    EnumDataModel enumDataModel = new EnumDataModel(name, (ArrayList<String>) options);
    if (enums.contains(enumDataModel)) {
      return false;
    }
    enums.add(enumDataModel);
    onEnumTableChanged(ChangeType.ADD, new String[]{name});
    return true;
  }

  public final void removeEnum(int row) {
    onEnumTableChanged(ChangeType.REMOVE, new String[]{enums.get(row).getName()});
    enums.remove(row);
  }

  public final boolean editEnum(int index, String name, ArrayList<String> options) {
    boolean conflictingEnumNames =
        enums.stream().map(EnumDataModel::getName).anyMatch(enumName -> enumName.equals(name));
    if (!name.equals(enums.get(index).getName()) && conflictingEnumNames) {
      return false;
    }
    EnumDataModel enumDataModel = new EnumDataModel(name, options);
    String oldName = enums.get(index).getName();
    List<String> oldOptions = enums.get(index).getOptions();
    enums.set(index, enumDataModel);
    onEnumTableChanged(ChangeType.EDIT, new Object[]{oldName, enumDataModel});
    if (!oldOptions.equals(options)) {
      onEnumTableChanged(ChangeType.EDIT_OPTIONS, new Object[]{oldOptions, options, name});
    }
    return true;
  }

  public final void addEnumsToModel(DefaultTableModel tableModel) {
    for (EnumDataModel enumDataModel : enums) {
      tableModel.addRow(new Object[]{enumDataModel.getName()});
    }
  }

  public final void addEnumToTable(JBTable table) {
    DefaultTableModel model = (DefaultTableModel) table.getModel();
    model.addRow(new Object[]{enums.get(enums.size() - 1).getName()});
  }

  public final void removeEnumFromTable(JBTable table, int row) {
    DefaultTableModel model = (DefaultTableModel) table.getModel();
    model.removeRow(row);
  }

  public final void editEnumInTable(JBTable table, int row) {
    DefaultTableModel model = (DefaultTableModel) table.getModel();
    model.setValueAt(enums.get(row).getName(), row, 0);
  }

  public abstract void onEnumTableChanged(ChangeType changeType, Object[] changeList);
}
