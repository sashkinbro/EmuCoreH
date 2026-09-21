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

package Controller.FidelityChanger;

import Model.QualityDataModel;
import Utils.DataModelTransformer;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;

public class FidelityChangerController {

  private final List<QualityDataModel> qualityDataModels;
  private ByteString currentFidelityByteString;

  public FidelityChangerController(List<QualityDataModel> qualityDataModelList) {
    this.qualityDataModels = qualityDataModelList;
  }

  public ByteString getQualityAsByteString(int qualityIndex) {
    return getQualityAsByteString(qualityDataModels.get(qualityIndex));
  }

  public ByteString getQualityAsByteString(QualityDataModel qualityDataModel) {
    FileDescriptor devTuningForkDesc = DataModelTransformer.getDevTuningforkDesc();
    if (devTuningForkDesc == null) {
      return ByteString.EMPTY;
    }
    Descriptor fidelityParamsDesc = devTuningForkDesc.findMessageTypeByName("FidelityParams");
    DynamicMessage.Builder builder = DynamicMessage.newBuilder(fidelityParamsDesc);
    try {
      TextFormat.merge(qualityDataModel.toString(), builder);
    } catch (ParseException e) {
      e.printStackTrace();
      return ByteString.EMPTY;
    }
    return builder.build().toByteString();
  }

  public DefaultMutableTreeNode getQualityAsTree() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    for (int i = 0; i < qualityDataModels.size(); i++) {
      QualityDataModel qualityDataModel = qualityDataModels.get(i);
      String parentText = "Quality " + (i + 1);
      if (currentFidelityByteString != null && getQualityAsByteString(qualityDataModel)
          .equals(currentFidelityByteString)) {
        parentText = parentText + "(Current)";
      }
      DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(parentText);
      for (int j = 0; j < qualityDataModel.getFieldCount(); j++) {
        String nodeText = qualityDataModel.getFieldNames().get(j) + ": " +
            qualityDataModel.getFieldValues().get(j);
        childNode.add(new DefaultMutableTreeNode(nodeText));
      }
      root.add(childNode);
    }
    return root;
  }

  public void setCurrentByteString(ByteString byteString) {
    currentFidelityByteString = byteString;
  }
}
