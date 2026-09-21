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
package View.Fidelity;

import org.apache.commons.lang.builder.ToStringBuilder;

public final class FidelityTableData {

  private String fieldEnumName, fieldParamName;
  private FieldType fieldType;

  public FidelityTableData() {
  }

  public FidelityTableData(FieldType fieldType, String fieldEnumName, String fieldName) {
    this.fieldType = fieldType;
    this.fieldEnumName = fieldEnumName;
    this.fieldParamName = fieldName;
  }

  public String getFieldEnumName() {
    return fieldEnumName;
  }

  public void setFieldEnumName(String fieldEnumName) {
    this.fieldEnumName = fieldEnumName;
  }

  public String getFieldParamName() {
    return fieldParamName;
  }

  public void setFieldParamName(String fieldParamName) {
    this.fieldParamName = fieldParamName;
  }

  public FieldType getFieldType() {
    return fieldType;
  }

  public void setFieldType(FieldType fieldType) {
    this.fieldType = fieldType;
  }

  @Override
  public String toString() {
    return new ToStringBuilder(this)
        .append("fieldType", fieldType.getName())
        .append("fieldEnumName", fieldEnumName)
        .append("fieldName", fieldParamName)
        .toString();
  }
}
