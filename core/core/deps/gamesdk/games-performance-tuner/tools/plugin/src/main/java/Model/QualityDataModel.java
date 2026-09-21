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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class QualityDataModel {

  private List<String> fieldNames;
  /*
   * For int32/float values, insert in fieldValues value.toString().
   * For Controller.Enum values, insert <name_of_value_that_is_set>.toString().
   */
  private List<String> fieldValues;

  public QualityDataModel() {
    fieldNames = new ArrayList<>();
    fieldValues = new ArrayList<>();
  }

  public QualityDataModel(List<String> fieldNames, List<String> fieldValues) {
    this.fieldNames = fieldNames;
    this.fieldValues = fieldValues;
  }

  public boolean addField(String paramName, String value) {
    fieldNames.add(paramName);
    fieldValues.add(value);
    return true;
  }

  public List<String> getFieldNames() {
    return fieldNames;
  }

  public List<String> getFieldValues() {
    return fieldValues;
  }

  public void removeSetting(int index) {
    fieldNames.remove(index);
    fieldValues.remove(index);
  }

  public boolean updateName(int index, String name) {
    fieldNames.set(index, name);
    return true;
  }

  public void updateValue(int index, String value) {
    fieldValues.set(index, value);
  }

  public int getFieldCount() {
    return fieldNames.size();
  }

  @Override
  public String toString() {
    StringBuilder stringBuilder = new StringBuilder();
    for (int i = 0; i < fieldNames.size(); i++) {
      stringBuilder.append(fieldNames.get(i)).append(": ").append(fieldValues.get(i)).append("\n");
    }
    return stringBuilder.toString();
  }

  @Override
  public boolean equals(Object objectToCompare) {
    if (objectToCompare instanceof QualityDataModel) {
      QualityDataModel qualityDataModel = (QualityDataModel) objectToCompare;
      if (this.fieldNames.size() != qualityDataModel.fieldNames.size()) {
        return false;
      }

      for (int i = 0; i < getFieldCount(); i++) {
        if (!fieldNames.get(i).equals(qualityDataModel.fieldNames.get(i))) {
          return false;
        }
      }

      if (this.fieldValues.size() != qualityDataModel.fieldValues.size()) {
        return false;
      }

      for (int i = 0; i < getFieldCount(); i++) {
        if (!fieldValues.get(i).equals(qualityDataModel.fieldValues.get(i))) {
          return false;
        }
      }
      return true;
    }
    return false;
  }
}
