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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import Utils.Proto.CompilationException;
import Utils.Proto.ProtoCompiler;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ProtoCompilerTest {
    private static final File PROTOC_BINARY = ProtocBinary.get();
    private final ProtoCompiler compiler = ProtoCompiler.getInstance(PROTOC_BINARY);

    @Rule
    // Override default behavior to allow overwriting files.
    public TemporaryFolder tempFolder = new TemporaryFolder() {
        @Override
        public File newFile(String filename) {
            return new File(getRoot(), filename);
        }
    };

    private final TestDataHelper helper = new TestDataHelper(tempFolder);

    @Test
    public void compileValid() throws Exception {
        File file = helper.getFile("compile_valid.proto");

        FileDescriptor fDesc = compiler.compile(file, Optional.empty());

        Descriptor messageDesc = fDesc.findMessageTypeByName("Message");
        Descriptor anotherDesc = fDesc.findMessageTypeByName("AnotherMessage");
        assertThat(messageDesc).isNotNull();
        assertThat(anotherDesc).isNotNull();
    }

    @Test
    public void compareDescriptors() throws Exception {
        File file = helper.getFile("compile_valid.proto");
        File outFile = new File(tempFolder.getRoot(), "compile_valid.descriptor");

        FileDescriptor stdoutDescriptor = compiler.compile(file, Optional.of(outFile));

        Descriptor messageDesc = stdoutDescriptor.findMessageTypeByName("Message");
        Descriptor anotherDesc = stdoutDescriptor.findMessageTypeByName("AnotherMessage");
        FileDescriptorSet fileSet = FileDescriptorSet.parseFrom(Files.toByteArray(outFile));
        FileDescriptor outFileDescriptor = FileDescriptor.buildFrom(
            Iterables.getOnlyElement(fileSet.getFileList()), new FileDescriptor[] {});

        assertThat(messageDesc).isNotNull();
        assertThat(anotherDesc).isNotNull();
        assertThat(outFile).isNotNull();
        assertThat(stdoutDescriptor.toProto()).isEqualTo(outFileDescriptor.toProto());
    }

    @Test
    public void compileInvalid() throws Exception {
        File file = helper.getFile("compile_invalid.proto");
        CompilationException expected = assertThrows(
            CompilationException.class, () -> compiler.compile(file, Optional.empty()));

        assertThat(expected).hasMessageThat().isEqualTo(
            "Descriptor for [compile_invalid.proto] does not exist.");
    }

    @Test
    public void compileWithDeps() throws Exception {
        File file = helper.getFile("compile_with_deps.proto");
        CompilationException expected = assertThrows(
            CompilationException.class, () -> compiler.compile(file, Optional.empty()));

        assertThat(expected).hasMessageThat().isEqualTo(
            "Descriptor for [compile_with_deps.proto] does not exist.");
    }

    @Test
    public void compileDevTuningfork() throws Exception {
        File file = helper.getFile("dev_tuningfork.proto");

        FileDescriptor fDesc = compiler.compile(file, Optional.empty());

        Descriptor annotation = fDesc.findMessageTypeByName("Annotation");
        Descriptor fidelityParams = fDesc.findMessageTypeByName("FidelityParams");
        assertThat(annotation).isNotNull();
        assertThat(fidelityParams).isNotNull();
    }

    @Test
    public void encodeAndDecode() throws Exception {
        File protoFile = helper.getFile("dev_tuningfork.proto");
        protoFile.setExecutable(true);
        FileDescriptor fileDescriptor = compiler.compile(protoFile, Optional.empty());
        Descriptor fidelityDescriptor = fileDescriptor.findMessageTypeByName("FidelityParams");
        File originalTextFile = helper.getFile("dev_tuningfork_fidelityparams_1.txt");
        String originalTextString = String.join("\n", Files.readLines(originalTextFile, UTF_8));
        Optional<File> errorFile = Optional.of(tempFolder.newFile("errors.txt"));
        String root = tempFolder.getRoot().getAbsolutePath();
        compiler.encodeFromTextprotoFile(fidelityDescriptor.getFullName(), protoFile,
            originalTextString, root + "/dev_tuningfork_fidelityparams_1.bin", errorFile);
        byte[] error = Files.toByteArray(errorFile.get());
        System.out.println(new String(error, UTF_8));
        assertThat(error).isEqualTo(new byte[0]);
        byte[] dataFile =
            Files.toByteArray(new File(root + "/dev_tuningfork_fidelityparams_1.bin"));
        DynamicMessage fidelityMessage = compiler.decodeFromBinary(fidelityDescriptor, dataFile);
        assertThat(error).isEqualTo(new byte[0]);
        assertThat(originalTextString).isEqualTo(fidelityMessage.toString().trim());
    }

    @Test
    public void runEchoCommand() throws Exception {
        String expected = "Hello world";
        ProcessBuilder builder = new ProcessBuilder(Arrays.asList("echo", expected));
        String result = new String(compiler.runCommand(builder), UTF_8);
        assertThat(result).startsWith(expected);
    }
}
