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


package Utils.Validation;

import Model.EnumDataModel;
import Model.MessageDataModel;
import Model.QualityDataModel;
import Utils.Resources.ResourceLoader;
import View.Fidelity.FieldType;
import com.intellij.openapi.ui.ValidationInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class ValidationTool {

  private final static ResourceLoader RESOURCE_LOADER = ResourceLoader.getInstance();
  private final static String INTEGER_REGEX = "-?\\d+";
  private final static String NUMBER_REGEX = "-?\\d+(\\.\\d+)?";

  private static ArrayList<String> getQualityForOneField(
      List<QualityDataModel> qualityDataModels, int row) {
    ArrayList<String> qualityForOneField = new ArrayList<>();

    for (QualityDataModel qualityDataModel : qualityDataModels) {
      qualityForOneField.add(qualityDataModel.getFieldValues().get(row));
    }

    return qualityForOneField;
  }

  /*
   * Transforms optionsToBeTransformed (which are the names of the enum options that are
   * set) to a list of value's index in the options list of the enum they belong to.
   */
  private static List<String> getEnumIndexes(List<String> optionsToBeTransformed,
      List<QualityDataModel> qualityDataModels, List<String> currentOptions) {
    return IntStream
        .range(0, qualityDataModels.size())
        .mapToObj(settingsNumber -> IntStream
            .range(0, currentOptions.size())
            .filter(optionsIndex -> currentOptions.get(optionsIndex)
                .equals(optionsToBeTransformed.get(settingsNumber))).boxed()
            .collect(Collectors.toList())
            .get(0)
            .toString())
        .collect(Collectors.toList());
  }

  private static ArrayList<String> transformEnumToIndexSingleRow(
      List<QualityDataModel> qualityDataModels,
      ArrayList<String> qualityParams, MessageDataModel fidelityMessage,
      int row) {
    ArrayList<String> transformed = qualityParams;

    if (fidelityMessage.getEnumData(row).isPresent()) {
      EnumDataModel currentEnum = fidelityMessage.getEnumData(row).get();
      transformed = (ArrayList<String>) getEnumIndexes(qualityParams,
          qualityDataModels,
          currentEnum.getOptions());
    }

    return transformed;
  }

  public static ValidationInfo getIntegerValueValidationInfo(String strValue) {
    if (strValue.isEmpty()) {
      return new ValidationInfo(RESOURCE_LOADER.get("field_empty_error"));
    }
    if (!strValue.matches(INTEGER_REGEX)) {
      return new ValidationInfo(
          String.format(RESOURCE_LOADER.get("invalid_integer_error"), strValue));
    }
    return null;
  }

  public static boolean isInteger(String strValue) {
    return getIntegerValueValidationInfo(strValue) == null;
  }

  public static boolean isDecimal(String strValue) {
    return getFloatValueValidationInfo(strValue) == null;
  }

  public static ValidationInfo getFloatValueValidationInfo(String strValue) {
    if (strValue.isEmpty()) {
      return new ValidationInfo(RESOURCE_LOADER.get("field_empty_error"));
    }
    if (!strValue.matches(NUMBER_REGEX)) {
      return new ValidationInfo(
          String.format(RESOURCE_LOADER.get("invalid_number_error"), strValue));
    }
    return null;
  }

  public static boolean isRowValid(List<QualityDataModel> qualityDataModels, int row,
      FieldType fieldType) {
    ArrayList<String> qualityParams = getQualityForOneField(qualityDataModels, row);
    if (fieldType.equals(FieldType.INT32)) {
      return IntStream.range(0, qualityDataModels.size())
          .allMatch(i -> getIntegerValueValidationInfo(qualityParams.get(i)) == null);
    } else if (fieldType.equals(FieldType.FLOAT)) {
      return IntStream.range(0, qualityDataModels.size())
          .allMatch(i -> getFloatValueValidationInfo(qualityParams.get(i)) == null);
    } else {
      return IntStream.range(0, qualityDataModels.size())
          .noneMatch(i -> qualityParams.get(i).isEmpty());
    }
  }

  public static boolean isIncreasingSingleField(List<QualityDataModel> qualityDataModels,
      MessageDataModel fidelityMessage, int row) {
    ArrayList<String> qualityParams = getQualityForOneField(qualityDataModels, row);
    ArrayList<String> transformedQualityParams = transformEnumToIndexSingleRow(qualityDataModels,
        qualityParams, fidelityMessage, row);

    return IntStream.range(0, qualityDataModels.size() - 1)
        .allMatch(setting -> Float.parseFloat(transformedQualityParams.get(setting))
            <= Float
            .parseFloat(transformedQualityParams.get(setting + 1)));
  }

  public static boolean isDecreasingSingleField(List<QualityDataModel> qualityDataModels,
      MessageDataModel fidelityMessage, int row) {
    ArrayList<String> qualityParams = getQualityForOneField(qualityDataModels, row);
    ArrayList<String> transformedQualityParams = transformEnumToIndexSingleRow(qualityDataModels,
        qualityParams, fidelityMessage, row);

    return IntStream.range(0, qualityDataModels.size() - 1)
        .allMatch(setting -> Float.parseFloat(transformedQualityParams.get(setting))
            >= Float
            .parseFloat(transformedQualityParams.get(setting + 1)));
  }
}
