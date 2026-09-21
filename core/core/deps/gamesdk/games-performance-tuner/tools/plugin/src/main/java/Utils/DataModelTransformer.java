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

package Utils;

import Model.EnumDataModel;
import Model.MessageDataModel;
import Model.MessageDataModel.Type;
import Model.QualityDataModel;
import Utils.Assets.AssetsFinder;
import Utils.Assets.AssetsParser;
import Utils.Proto.CompilationException;
import Utils.Proto.ProtoCompiler;
import com.google.common.io.Files;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.tuningfork.Tuningfork.Settings;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class DataModelTransformer {

  private final Optional<byte[]> settingsData;
  private final Optional<File> devTuningfork;
  private final Optional<List<File>> qualityFiles;
  private static FileDescriptor devTuningforkDesc;
  private final ProtoCompiler compiler;


  public DataModelTransformer(String projectPath, ProtoCompiler compiler)
      throws IOException, CompilationException {
    File assetsDir = new File(
        AssetsFinder.findAssets(projectPath).getAbsolutePath());
    AssetsParser parser = new AssetsParser(assetsDir);
    parser.parseFiles();
    devTuningfork = parser.getDevTuningForkFile();
    qualityFiles = parser.getDevFidelityParamFiles();
    settingsData = parser.getTuningForkSettings();
    this.compiler = compiler;
    if (devTuningfork.isPresent()) {
      devTuningforkDesc = compiler.compile(devTuningfork.get(), Optional.empty());
    } else {
      devTuningforkDesc = null;
    }
  }

  public static FileDescriptor getDevTuningforkDesc() {
    return devTuningforkDesc;
  }

  public static List<EnumDataModel> getEnums(List<EnumDescriptor> enumDescriptors) {
    return enumDescriptors.stream()
        .map(descriptor -> new EnumDataModel(descriptor.getName(), descriptor.getValues()))
        .collect(Collectors.toList());
  }

  public static Optional<MessageDataModel> transformToAnnotation(Descriptor desc) {
    List<FieldDescriptor> fieldDescriptors = desc.getFields();
    MessageDataModel annotationDataModel = new MessageDataModel();
    annotationDataModel.setMessageType(MessageDataModel.Type.ANNOTATION);

    for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
      if (!annotationDataModel.addField(fieldDescriptor.getName(),
          fieldDescriptor.getEnumType().getName())) {
        return Optional.empty();
      }
    }

    return Optional.of(annotationDataModel);
  }

  public static Optional<QualityDataModel> transformToQuality(DynamicMessage message,
      List<FieldDescriptor> fieldDescriptors) {
    QualityDataModel qualityDataModel = new QualityDataModel();

    for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
      Object fieldValue = message.getField(fieldDescriptor);
      if (fieldValue instanceof EnumValueDescriptor) {
        int index = ((EnumValueDescriptor) fieldValue).getIndex();
        fieldValue = fieldDescriptor.getEnumType().getValues().get(index).getName();
      }

      if (!qualityDataModel.addField(fieldDescriptor.getName(), fieldValue.toString())) {
        return Optional.empty();
      }
    }

    return Optional.of(qualityDataModel);
  }

  public static List<DynamicMessage> convertByteStringToDynamicMessage(List<String> messages,
      FileDescriptor fileDescriptor) {
    Descriptor descriptor = fileDescriptor.findMessageTypeByName("FidelityParams");
    List<DynamicMessage> dynamicMessages = new ArrayList<>();
    messages.forEach(byteMessage -> {
      try {
        dynamicMessages.add(DynamicMessage
            .parseFrom(descriptor, Base64.getDecoder().decode(byteMessage)));
      } catch (InvalidProtocolBufferException e) {
        e.printStackTrace();
      }
    });
    return dynamicMessages;
  }

  public static Optional<MessageDataModel> transformToFidelity(Descriptor desc) {
    List<FieldDescriptor> fieldDescriptors = desc.getFields();
    MessageDataModel fidelityModel = new MessageDataModel();
    fidelityModel.setMessageType(MessageDataModel.Type.FIDELITY);

    for (FieldDescriptor fieldDescriptor : fieldDescriptors) {
      JavaType type = fieldDescriptor.getJavaType();
      String typeToInsert = new String();

      switch (type) {
        case ENUM: {
          typeToInsert = fieldDescriptor.getEnumType().getName();
          break;
        }
        case INT: {
          typeToInsert = "int32";
          break;
        }
        case FLOAT: {
          typeToInsert = "float";
          break;
        }
        default:
          break;
      }

      if (!fidelityModel.addField(fieldDescriptor.getName(), typeToInsert)) {
        return Optional.empty();
      }
    }

    return Optional.of(fidelityModel);
  }

  public MessageDataModel initAnnotationData() {
    if (devTuningforkDesc == null) {
      return new MessageDataModel(new ArrayList<>(), new ArrayList<>(), Type.ANNOTATION);
    }
    Descriptor messageDesc = devTuningforkDesc.findMessageTypeByName("Annotation");
    if (messageDesc == null) {
      return new MessageDataModel(new ArrayList<>(), new ArrayList<>(), Type.ANNOTATION);
    }
    return transformToAnnotation(messageDesc).get();
  }

  public MessageDataModel initFidelityData() {
    if (devTuningforkDesc == null) {
      return new MessageDataModel(new ArrayList<>(), new ArrayList<>(), Type.FIDELITY);
    }
    Descriptor messageDesc = devTuningforkDesc.findMessageTypeByName("FidelityParams");
    if (messageDesc == null) {
      return new MessageDataModel(new ArrayList<>(), new ArrayList<>(), Type.FIDELITY);
    }

    return transformToFidelity(messageDesc).get();
  }

  public List<EnumDataModel> initEnumData() {
    if (devTuningforkDesc == null) {
      return new ArrayList<>();
    }
    List<EnumDescriptor> enumDescriptors = devTuningforkDesc.getEnumTypes();
    return getEnums(enumDescriptors);
  }

  public List<QualityDataModel> initQualityData() throws IOException {
    if (!devTuningfork.isPresent() || devTuningforkDesc == null) {
      return new ArrayList<>();
    }

    Descriptor fidelityDesc = devTuningforkDesc.findMessageTypeByName("FidelityParams");
    List<QualityDataModel> qualityDataModelList = new ArrayList<>();
    if (qualityFiles.isPresent()) {
      List<File> qualityFilesList = qualityFiles.get();
      for (File qualityFile : qualityFilesList) {
        byte[] fileContent = Files.toByteArray(qualityFile);
        DynamicMessage fidelityMessage = compiler.decodeFromBinary(fidelityDesc, fileContent);
        qualityDataModelList
            .add(transformToQuality(fidelityMessage, fidelityDesc.getFields()).get());
      }
    }

    return qualityDataModelList;
  }

  public Settings initProtoSettings() throws InvalidProtocolBufferException {
    if (!settingsData.isPresent()) {
      return Settings.getDefaultInstance();
    }
    return Settings.parseFrom(settingsData.get());
  }
}
