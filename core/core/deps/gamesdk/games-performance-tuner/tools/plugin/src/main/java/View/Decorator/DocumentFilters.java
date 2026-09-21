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

package View.Decorator;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

public class DocumentFilters {

  public static class NumberDocumentFilter extends DocumentFilter {

    private boolean isNumber(String string) {
      for (int i = 0; i < string.length(); i++) {
        if (string.charAt(i) != '-' && !Character.isDigit(string.charAt(i))
            && string.charAt(i) != '.') {
          return false;
        }
      }
      return true;
    }

    @Override
    public void insertString(DocumentFilter.FilterBypass fp, int offset, String string,
        AttributeSet asset)
        throws BadLocationException {
      if (isNumber(string)) {
        super.insertString(fp, offset, string, asset);
      }
    }

    @Override
    public void replace(DocumentFilter.FilterBypass fp, int offset, int length, String string,
        AttributeSet asset)
        throws BadLocationException {
      if (isNumber(string)) {
        super.replace(fp, offset, length, string, asset);
      }
    }
  }
}
