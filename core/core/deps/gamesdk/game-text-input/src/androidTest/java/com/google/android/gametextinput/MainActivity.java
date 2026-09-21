/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License.
 */
package com.google.androidgamesdk.gametextinput.test;

import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
  InputEnabledTextView inputEnabledTextView;
  TextView displayedText;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    EdgeToEdge.enable(this);
    setContentView(R.layout.activity_main);
    applyInsets();
    inputEnabledTextView = (InputEnabledTextView) findViewById(R.id.input_enabled_text_view);
    assert (inputEnabledTextView != null);

    displayedText = (TextView) findViewById(R.id.displayed_text);
    assert (displayedText != null);

    inputEnabledTextView.createInputConnection(InputType.TYPE_CLASS_TEXT, this);
  }

  public void setDisplayedText(String text, int selectionStart, int selectionEnd) {
    SpannableString str = new SpannableString(text);

    if (selectionStart != selectionEnd) {
      str.setSpan(new BackgroundColorSpan(Color.YELLOW), selectionStart, selectionEnd, 0);
    }
    displayedText.setText(str);
  }

  private void applyInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
      Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
      return insets;
    });
  }
}
