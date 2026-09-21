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

package Model;

import View.Fidelity.FieldType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class MessageDataModel {

  public enum Type {
    FIDELITY("FidelityParams"),
    ANNOTATION("Annotation");
    private final String messageName;

    Type(String messageName) {
      this.messageName = messageName;
    }

    public String getMessageName() {
      return messageName;
    }
  }

  private List<String> fieldNames;
  private List<String> fieldTypes;
  private Type messageType;
  private List<Optional<EnumDataModel>> enumData;

  public MessageDataModel() {
    fieldNames = new ArrayList<>();
    fieldTypes = new ArrayList<>();
    enumData = new ArrayList<>();
  }

  public MessageDataModel(List<String> fieldNames, List<String> fieldTypes, Type messageType) {
    this.fieldTypes = fieldTypes;
    this.fieldNames = fieldNames;
    this.messageType = messageType;
    enumData = new ArrayList<>();
  }

  public String getType(String fieldName) {
    int fieldNameIndex = fieldNames.indexOf(fieldName);
    return fieldTypes.get(fieldNameIndex);
  }

  public boolean addMultipleFields(List<String> paramNames, List<String> paramValues) {
    for (int i = 0; i < paramNames.size(); i++) {
      if (!addField(paramNames.get(i), paramValues.get(i))) {
        return false;
      }
    }
    return true;
  }

  public void setEnumData(List<EnumDataModel> enumDataModelList) {
    enumData = new ArrayList<>();
    for (String fieldType : fieldTypes) {
      if (fieldType.equals(FieldType.INT32.getName()) || fieldType
          .equals(FieldType.FLOAT.getName())) {
        enumData.add(Optional.empty());
      } else {
        enumData.add(Optional.ofNullable(findEnumByName(fieldType, enumDataModelList)));
      }
    }
  }

  private EnumDataModel findEnumByName(String enumName, List<EnumDataModel> enumDataModels) {
    List<EnumDataModel> possibleEnums = enumDataModels.stream()
        .filter(enumDataModel -> enumDataModel.getName().equals(enumName))
        .collect(Collectors.toList());
    if (possibleEnums.isEmpty()) {
      return null;
    } else {
      return possibleEnums.get(0);
    }
  }

  // TODO(aymanm): Set the method return type to void.
  public boolean addField(String paramName, String paramValue) {
    fieldNames.add(paramName);
    fieldTypes.add(paramValue);
    enumData.add(Optional.empty());
    return true;
  }

  public boolean addField(String paramName, String paramValue,
      EnumDataModel enumDataModel) {
    fieldNames.add(paramName);
    fieldTypes.add(paramValue);
    enumData.add(Optional.of(enumDataModel));
    return true;
  }

  public List<String> getFieldNames() {
    return fieldNames;
  }

  public List<String> getFieldTypes() {
    return fieldTypes;
  }

  public Type getMessageType() {
    return messageType;
  }

  public void removeSetting(int index) {
    fieldNames.remove(index);
    fieldTypes.remove(index);
  }

  // TODO(aymanm): Set the method return type to void.
  public boolean updateName(int index, String name) {
    fieldNames.set(index, name);
    return true;
  }

  public void updateType(int index, String type) {
    fieldTypes.set(index, type);
  }

  public void setMessageType(Type messageType) {
    this.messageType = messageType;
  }

  public Optional<EnumDataModel> getEnumData(int row) {
    return enumData.get(row);
  }

  public void setEnumData(int row, EnumDataModel enumData) {
    this.enumData.set(row, Optional.ofNullable(enumData));
  }

  @Override
  public String toString() {
    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append("message ").append(messageType.getMessageName()).append(" {\n");
    for (int i = 0; i < fieldNames.size(); i++) {
      stringBuilder
          .append(fieldTypes.get(i))
          .append(" ")
          .append(fieldNames.get(i))
          .append(" = ")
          .append(i + 1)
          .append(";\n");
    }
    stringBuilder.append("}\n\n");
    return stringBuilder.toString();
  }

  public int getFieldsCount() {
    return fieldNames.size();
  }
}
