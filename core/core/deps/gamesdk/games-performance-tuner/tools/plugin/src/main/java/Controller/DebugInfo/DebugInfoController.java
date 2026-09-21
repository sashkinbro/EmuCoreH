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

package Controller.DebugInfo;

import Model.QualityDataModel;
import Utils.DataModelTransformer;
import View.Decorator.LabelScrollPane;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;

public class DebugInfoController {

  public DebugInfoController() {
  }

  public void setDebugInfo(final LabelScrollPane pane, String descriptor) {
    String[] lines = descriptor.split("\n");
    SwingUtilities.invokeLater(() -> {
      pane.removeAll();
      for (String line : lines) {
        pane.addText(line);
      }
      SwingUtilities.updateComponentTreeUI(pane.getPanel());
    });
  }


  public List<QualityDataModel> convertByteStringToModel(List<String> messages,
      FileDescriptor fileDescriptor) {
    List<QualityDataModel> qualityDataModels = new ArrayList<>();
    List<DynamicMessage> dynamicMessages = DataModelTransformer
        .convertByteStringToDynamicMessage(messages, fileDescriptor);
    List<FieldDescriptor> fieldDescriptors = fileDescriptor.findMessageTypeByName("FidelityParams")
        .getFields();
    dynamicMessages.forEach(message -> {
      QualityDataModel qualityDataModel = new QualityDataModel();
      for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
        Object fieldValue = message.getField(fieldDescriptor);
        if (fieldValue instanceof EnumValueDescriptor) {
          int index = ((EnumValueDescriptor) fieldValue).getIndex();
          fieldValue = fieldDescriptor.getEnumType().getValues().get(index).getName();
        }
        qualityDataModel.addField(fieldDescriptor.getName(), fieldValue.toString());
      }
      qualityDataModels.add(qualityDataModel);
    });
    return qualityDataModels;
  }

  public DefaultMutableTreeNode getQualityAsTree(List<QualityDataModel> qualityDataModels) {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    for (int i = 0; i < qualityDataModels.size(); i++) {
      QualityDataModel qualityDataModel = qualityDataModels.get(i);
      String parentText = "Quality " + (i + 1);
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
}
