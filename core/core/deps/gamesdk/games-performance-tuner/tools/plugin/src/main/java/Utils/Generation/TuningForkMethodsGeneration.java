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

package Utils.Generation;

import Model.MessageDataModel;
import Model.MessageDataModel.Type;
import View.Fidelity.FieldType;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.jetbrains.cidr.lang.OCLanguage;
import com.jetbrains.cidr.lang.psi.OCCppLinkageSpecification;
import com.jetbrains.cidr.lang.psi.OCFile;
import java.util.List;
import java.util.Optional;

public class TuningForkMethodsGeneration {

  private final OCFile psiFile;
  private final Project project;
  private final CppFileUtils cppFileUtils;

  public TuningForkMethodsGeneration(PsiFile psiFile, Project project) {
    this.psiFile = (OCFile) psiFile;
    this.project = project;
    this.cppFileUtils = new CppFileUtils(psiFile);
  }

  public void generateImports() {
    cppFileUtils.fixImports(cppFileUtils.getAllReferenceElementsInsideMethods(
        ImmutableList.of("SetFidelityParams", "SetAnnotations")));
  }

  public void generateTuningForkCode(MessageDataModel annotationData,
      MessageDataModel fidelityData) {
    PsiFile temporaryPsiFile = PsiFileFactory.getInstance(project)
        .createFileFromText(OCLanguage.getInstance(), generateCode(annotationData, fidelityData));
    for (PsiElement psiElement : temporaryPsiFile.getChildren()) {
      Optional<PsiElement> addedElement = cppFileUtils.findElement(psiElement);
      // Methods already exist(Was auto generated). just update them
      if (addedElement.isPresent()) {
        if (psiElement instanceof OCCppLinkageSpecification) {
          Optional<PsiElement> methodDefinition = cppFileUtils.findMethodAsChildren(psiElement);
          methodDefinition.ifPresent(method -> addedElement.get().replace(method));
        } else {
          addedElement.get().replace(psiElement);
        }
      } else {
        CodeStyleManager.getInstance(project).reformat(psiElement);
        this.psiFile.add(psiElement);
      }
    }

  }

  private String generateAnnotationMessage(MessageDataModel messageDataModel) {
    if (messageDataModel.getMessageType().equals(Type.ANNOTATION)) {
      String messageName = messageDataModel.getMessageType().getMessageName();
      List<String> fieldTypes = messageDataModel.getFieldTypes();
      List<String> fieldNames = messageDataModel.getFieldNames();
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append("extern \"C\" void ").append("Set")
          .append(messageName).append("s("); // extern "C" void setAnnotations(
      for (int i = 0; i < fieldNames.size(); i++) {
        stringBuilder.append("com::google::tuningfork::").append(fieldTypes.get(i))
            .append(" ").append(fieldNames.get(i));
        if (i + 1 < fieldNames.size()) {
          stringBuilder.append(", ");
        }
      }
      stringBuilder.append(")").append("\n{\n").append("com::google::tuningfork::")
          .append(messageName).append(" ").append(messageName.toLowerCase()).append(";")
          .append("\n");
      for (int i = 0; i < fieldTypes.size(); i++) {
        stringBuilder.append(messageName.toLowerCase()).append(".set_")
            .append(fieldNames.get(i)).append("(")
            .append(fieldNames.get(i)).append(");")
            .append("\n");
      }
      stringBuilder.append("auto ser = tuningfork::TuningFork_CProtobufSerialization_Alloc(")
          .append(messageName.toLowerCase()).append(");\n")
          .append("if (TuningFork_setCurrentAnnotation(&ser)!=TUNINGFORK_ERROR_OK) {\n")
          .append("// Failed to set annotation\n").append("}\n")
          .append("TuningFork_CProtobufSerialization_free(&ser);\n").append("}\n");
      return stringBuilder.toString();
    } else {
      return "";
    }
  }

  private String getFieldName(int row, MessageDataModel fidelityMessage) {
    if (fidelityMessage.getFieldTypes().get(row).equals(FieldType.INT32.getName())) {
      return "int";
    } else if (fidelityMessage.getFieldTypes().get(row).equals(FieldType.FLOAT.getName())) {
      return "float";
    } else {
      return "com::google::tuningfork::" + fidelityMessage.getFieldTypes().get(row);
    }
  }

  private String generateFidelityMessage(MessageDataModel messageDataModel) {
    if (messageDataModel.getMessageType().equals(Type.FIDELITY)) {
      String messageName = messageDataModel.getMessageType().getMessageName();
      List<String> fieldTypes = messageDataModel.getFieldTypes();
      List<String> fieldNames = messageDataModel.getFieldNames();
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append("extern \"C\" void ").append("Set")
          .append(messageName).append("("); // extern "C" void setFidelity(
      for (int i = 0; i < fieldNames.size(); i++) {
        stringBuilder.append(getFieldName(i, messageDataModel))
            .append(" ").append(fieldNames.get(i));
        if (i + 1 < fieldNames.size()) {
          stringBuilder.append(", ");
        }
      }
      stringBuilder.append(")").append("\n{\n").append("com::google::tuningfork::")
          .append(messageName).append(" ")
          .append(messageName.toLowerCase()).append(";").append("\n");
      for (int i = 0; i < fieldTypes.size(); i++) {
        stringBuilder.append(messageName.toLowerCase()).append(".set_")
            .append(fieldNames.get(i)).append("(")
            .append(fieldNames.get(i)).append(");")
            .append("\n");
      }
      stringBuilder.append("auto ser = tuningfork::TuningFork_CProtobufSerialization_Alloc(")
          .append(messageName.toLowerCase()).append(");\n")
          .append("if (TuningFork_setFidelityParameters(&ser) !=TUNINGFORK_ERROR_OK) {\n")
          .append("// Failed to set fidelity\n").append("}\n")
          .append("TuningFork_CProtobufSerialization_free(&ser);\n").append("}\n");
      return stringBuilder.toString();
    } else {
      return "";
    }
  }

  private String generateCode(MessageDataModel annotationMessage,
      MessageDataModel fidelityMessage) {
    return generateAnnotationMessage(annotationMessage)
        + "\n"
        + generateFidelityMessage(fidelityMessage);
  }
}
