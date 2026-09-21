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

package Utils.Proto;

import Utils.Proto.OsUtils.OS;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.intellij.util.PathUtil;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.logging.Logger;

public final class ProtoCompiler {

  private static final Logger LOGGER = Logger.getLogger(ProtoCompiler.class.getName());
  private static final String PROTO_FOLDER_PATH_RELATIVE_TO_JAR =
      "protobuf/" + OsUtils.getOS().getOsName();
  private static final String PROTO_BINARY_PATH_RELATIVE_TO_JAR =
      PROTO_FOLDER_PATH_RELATIVE_TO_JAR + "/bin/" + OsUtils.getOS().getExecutableProtoFileName();
  private static ProtoCompiler protoCompiler;
  private final FileDescriptor[] emptyDeps = new FileDescriptor[0];
  private File protocBinary;

  private ProtoCompiler() {
    try {
      File temporaryFolderRoot =
          JarUtils.copyProtobufToTemporaryFolder(
              PathUtil.getJarPathForClass(ProtoCompiler.class), PROTO_FOLDER_PATH_RELATIVE_TO_JAR);
      protocBinary =
          new File(temporaryFolderRoot.getAbsolutePath(), PROTO_BINARY_PATH_RELATIVE_TO_JAR);
      protocBinary.setExecutable(true);
      if (OsUtils.getOS().equals(OS.MAC)) {
        File protocScript = new File(temporaryFolderRoot.getAbsolutePath(),
            PROTO_FOLDER_PATH_RELATIVE_TO_JAR + "/bin/.libs/protoc");
        protocScript.setExecutable(true);
      }
      LOGGER.info("Loaded protocol buffer successfully");
    } catch (IOException e) {
      e.printStackTrace();
      LOGGER.severe("Unable to load protocol buffer compiler");
    }
  }

  private ProtoCompiler(File protoFile) {
    protocBinary = protoFile;
  }

  public static synchronized ProtoCompiler getInstance() {
    if (protoCompiler == null) {
      protoCompiler = new ProtoCompiler();
    }
    return protoCompiler;
  }

  public static synchronized ProtoCompiler getInstance(File protocBinary) {
    if (protoCompiler == null) {
      protoCompiler = new ProtoCompiler(protocBinary);
    }
    return protoCompiler;
  }

  public FileDescriptor compile(File file, Optional<File> outFile)
      throws IOException, CompilationException {
    Preconditions.checkNotNull(file, "file");
    FileDescriptor descriptor = buildAndRunCompilerProcess(file, outFile);
    return descriptor;
  }

  public byte[] runCommand(ProcessBuilder processBuilder) throws IOException, CompilationException {
    Process process = processBuilder.start();
    try {
      process.waitFor();
    } catch (InterruptedException e) {
      throw new CompilationException("Process was interrupted", e);
    }
    InputStream stdin = process.getInputStream();
    byte[] result = new byte[stdin.available()];
    stdin.read(result);
    return result;
  }

  private FileDescriptor buildAndRunCompilerProcess(File file, Optional<File> outFile)
      throws IOException, CompilationException {
    File tempOutFile = File.createTempFile("temp", "txt");
    ImmutableList<String> commandLine = createCommandLine(file, tempOutFile);
    runCommand(new ProcessBuilder(commandLine));
    byte[] result = Files.toByteArray(tempOutFile);
    tempOutFile.delete();

    try {
      FileDescriptorSet fileSet = FileDescriptorSet.parseFrom(result);
      if (outFile.isPresent()) {
        Files.write(fileSet.toByteArray(), outFile.get());
      }
      for (FileDescriptorProto descProto : fileSet.getFileList()) {
        if (descProto.getName().equals(file.getName())) {
          return FileDescriptor.buildFrom(descProto, emptyDeps);
        }
      }
    } catch (DescriptorValidationException | InvalidProtocolBufferException e) {
      throw new IllegalStateException(e);
    }
    throw new CompilationException(
        String.format("Descriptor for [%s] does not exist.", file.getName()));
  }

  /* Decode textproto message from text(textprotoFile) into binary(binFile) */
  public File encodeFromTextprotoFile(
      String message,
      File protoFile,
      String textprotoString,
      String binaryPath,
      Optional<File> errorFile)
      throws IOException, CompilationException {
    ImmutableList<String> command = encodeCommandLine(message, protoFile);

    File binFile = new File(binaryPath);
    File temporaryTextProtoFile = File.createTempFile("textproto", "");
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(temporaryTextProtoFile))) {
      writer.write(textprotoString);
    }
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectInput(temporaryTextProtoFile).redirectOutput(binFile);

    if (errorFile.isPresent()) {
      builder.redirectError(errorFile.get());
    }
    runCommand(builder);
    temporaryTextProtoFile.delete();
    return binFile;
  }

  /* decode a binary array of message into a DynamicMessage */
  public DynamicMessage decodeFromBinary(Descriptor messageDescriptor, byte[] byteData)
      throws InvalidProtocolBufferException {
    return DynamicMessage.parseFrom(messageDescriptor, byteData);
  }

  private ImmutableList<String> createCommandLine(File file, File outFile) {
    return ImmutableList.of(
        protocBinary.getAbsolutePath(),
        "-o",
        outFile.getAbsolutePath(),
        "-I",
        file.getName() + "=" + file.getAbsolutePath(), // That should be one line
        file.getAbsolutePath());
  }

  private ImmutableList<String> encodeCommandLine(String message, File protoFile) {
    return ImmutableList.of(
        protocBinary.getAbsolutePath(),
        "--encode=" + message,
        "-I",
        protoFile.getName() + "=" + protoFile.getAbsolutePath(), // That should be one line
        protoFile.getAbsolutePath());
  }
}
