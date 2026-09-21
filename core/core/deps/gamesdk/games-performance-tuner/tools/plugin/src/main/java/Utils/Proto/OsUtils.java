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

public final class OsUtils {

  private static OS os = null;

  public static OS getOS() {
    if (os == null) {
      String operSys = System.getProperty("os.name").toLowerCase();
      if (operSys.contains("win")) {
        os = OS.WINDOWS;
      } else if (operSys.contains("nix") || operSys.contains("nux") || operSys.contains("aix")) {
        os = OS.LINUX;
      } else if (operSys.contains("mac")) {
        os = OS.MAC;
      } else {
        os = OS.OTHER;
      }
    }
    return os;
  }

  public enum OS {
    WINDOWS("win", "protoc.exe"),
    LINUX("linux-x86", "protoc"),
    MAC("mac", "protoc"),
    OTHER("other", "");
    private final String osName;
    private final String executableProtoFileName;

    OS(String osName, String executableProtoFileName) {
      this.osName = osName;
      this.executableProtoFileName = executableProtoFileName;
    }

    public String getOsName() {
      return osName;
    }

    public String getExecutableProtoFileName() {
      return executableProtoFileName;
    }
  }
}
