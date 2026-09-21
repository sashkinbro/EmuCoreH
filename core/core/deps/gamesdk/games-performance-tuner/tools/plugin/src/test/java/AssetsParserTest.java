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

import Utils.Assets.AssetsParser;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AssetsParserTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testParserValid() {
        AssetsParser assetsParser = new AssetsParser(folder.getRoot());
        try {
            folder.newFile("dev_tuningfork.proto");
            folder.newFile("tuningfork_settings.bin");
            folder.newFile("dev_tuningfork_fidelityparams_1.bin");
            folder.newFile("dev_tuningfork_fidelityparams_2.bin");
            assetsParser.parseFiles();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Assert.assertTrue(assetsParser.getDevTuningForkFile().isPresent());
        Assert.assertTrue(assetsParser.getTuningForkSettings().isPresent());
        Assert.assertTrue(assetsParser.getDevFidelityParamFiles().isPresent());
        Assert.assertEquals(assetsParser.getDevFidelityParamFiles().get().size(), 2);
    }

    @Test
    public void testParserNoFiles() {
        AssetsParser assetsParser = new AssetsParser(folder.getRoot());
        Assert.assertFalse(assetsParser.getDevTuningForkFile().isPresent());
        Assert.assertFalse(assetsParser.getTuningForkSettings().isPresent());
        Assert.assertFalse(assetsParser.getDevFidelityParamFiles().isPresent());
    }

    @Test
    public void testParserInvalidFilesName() {
        AssetsParser assetsParser = new AssetsParser(folder.getRoot());
        try {
            folder.newFile("dev_Tuning_Fork.proto");
            folder.newFile("settings_tuningfork.bin");
            folder.newFile("dev_tuningfork_fidelityparams_25.bin");
            folder.newFile("dev_tuningfork_2.bin");
            assetsParser.parseFiles();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Assert.assertFalse(assetsParser.getDevTuningForkFile().isPresent());
        Assert.assertFalse(assetsParser.getTuningForkSettings().isPresent());
        Assert.assertFalse(assetsParser.getDevFidelityParamFiles().isPresent());
    }
}
