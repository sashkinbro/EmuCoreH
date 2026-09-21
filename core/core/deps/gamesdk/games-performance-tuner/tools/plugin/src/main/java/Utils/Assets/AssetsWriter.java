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

package Utils.Assets;

import static Files.FolderConfig.DEV_TUNINGFORK_DESCRIPTOR;
import static Files.FolderConfig.DEV_TUNINGFORK_PROTO;
import static Files.FolderConfig.TUNINGFORK_SETTINGS_BINARY;

import Model.EnumDataModel;
import Model.MessageDataModel;
import Model.QualityDataModel;
import Utils.Proto.CompilationException;
import Utils.Proto.ProtoCompiler;
import com.google.tuningfork.Tuningfork.Settings;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.apache.commons.io.FileUtils;

public class AssetsWriter {

  private final String assetsDirectory;
  private static final String AUTO_GENERATED_PROTO =
      "// This file is auto generated -- DO NOT EDIT\n";
  private static final String AUTO_GENERATED_TEXT =
      "# This file is auto generated -- DO NOT EDIT\n";

  public AssetsWriter(String assetsDirectory) {
    this.assetsDirectory = assetsDirectory;
  }

  public boolean saveDevTuningForkProto(
      List<EnumDataModel> enums,
      MessageDataModel annotationMessage,
      MessageDataModel fidelityMessage) {
    File file = new File(assetsDirectory, DEV_TUNINGFORK_PROTO);
    try (FileWriter fileWriter = new FileWriter(file)) {
      fileWriter.write(AUTO_GENERATED_PROTO);
      fileWriter.write("syntax = \"proto3\";\n\n");
      fileWriter.write("package com.google.tuningfork;\n\n");
      for (EnumDataModel enumDataModel : enums) {
        fileWriter.write(enumDataModel.toString());
      }
      fileWriter.write(annotationMessage.toString());
      fileWriter.write(fidelityMessage.toString());
      fileWriter.write(AUTO_GENERATED_PROTO);
    } catch (IOException e) {
      e.printStackTrace();
    }
    File descriptorFile = new File(assetsDirectory, DEV_TUNINGFORK_DESCRIPTOR);
    try {
      ProtoCompiler.getInstance().compile(file, Optional.of(descriptorFile));
      return true;
    } catch (IOException | CompilationException e) {
      e.printStackTrace();
    }
    return false;
  }

  public boolean saveDevFidelityParams(ProtoCompiler compiler,
      List<QualityDataModel> qualityDataModel) {
    return IntStream.range(0, qualityDataModel.size())
        .filter(i -> {
          try {
            saveDevFidelityParams(compiler,
                qualityDataModel.get(i), i + 1);
          } catch (IOException | CompilationException e) {
            e.printStackTrace();
          }
          return true;
        })
        .count()
        == qualityDataModel.size();
  }

  public void saveDevFidelityParams(ProtoCompiler compiler,
      QualityDataModel qualityDataModel, int fileNumber) throws IOException, CompilationException {
    File devTuningfork = new File(assetsDirectory, DEV_TUNINGFORK_PROTO);
    String filePath = assetsDirectory + "/dev_tuningfork_fidelityparams_" + fileNumber + ".bin";
    compiler.encodeFromTextprotoFile("com.google.tuningfork.FidelityParams", devTuningfork,
        qualityDataModel.toString(),
        filePath,
        Optional.empty());
  }

  public boolean saveInstrumentationSettings(
      Settings settings) {
    File file = new File(assetsDirectory, TUNINGFORK_SETTINGS_BINARY);
    try {
      FileUtils.writeByteArrayToFile(file, settings.toByteArray());
      return true;
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }
}
