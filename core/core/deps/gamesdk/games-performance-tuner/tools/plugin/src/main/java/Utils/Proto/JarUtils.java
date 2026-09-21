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

import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.commons.io.FileUtils;

public class JarUtils {

  private JarUtils() {
  }

  public static File copyProtobufToTemporaryFolder(String jarPath, String folderPathRelativeToJar)
      throws IOException {
    File temporaryFolder = Files.createTempDir();
    try (JarFile jarFile = new JarFile(jarPath)) {
      Enumeration<JarEntry> jarEntries = jarFile.entries();
      while (jarEntries.hasMoreElements()) {
        JarEntry jarEntry = jarEntries.nextElement();
        String jarEntryName = jarEntry.getName();
        // Copy operating system specific proto compiler along with license
        if (jarEntryName.startsWith(folderPathRelativeToJar) || jarEntryName.endsWith("LICENSE")) {
          File currentFile = new File(temporaryFolder, jarEntryName);
          if (jarEntry.isDirectory()) {
            currentFile.mkdirs();
          } else {
            FileUtils.copyToFile(jarFile.getInputStream(jarEntry), currentFile);
          }
        }
      }
    }
    FileUtils.forceDeleteOnExit(temporaryFolder);
    return temporaryFolder;
  }
}
